package se.scalablesolutions.akka.mobile.theater

import se.scalablesolutions.akka.mobile.actor.MobileActorRef
import se.scalablesolutions.akka.mobile.actor.MobileActor
import se.scalablesolutions.akka.mobile.actor.DetachedActor
import se.scalablesolutions.akka.mobile.serialization.MobileSerialization
import se.scalablesolutions.akka.mobile.serialization.DefaultActorFormat
import se.scalablesolutions.akka.mobile.util.PipelineFactoryCreator
import se.scalablesolutions.akka.mobile.util.DefaultLogger
import se.scalablesolutions.akka.mobile.util.ClusterConfiguration
import se.scalablesolutions.akka.mobile.Mobile
import se.scalablesolutions.akka.mobile.util.messages._
import se.scalablesolutions.akka.mobile.theater.protocol.TheaterProtocol
import se.scalablesolutions.akka.mobile.theater.protocol.AgentProtobufProtocol
import se.scalablesolutions.akka.mobile.theater.protocol.AgentProtocol
import se.scalablesolutions.akka.mobile.theater.protocol.NettyTheaterProtocol
import se.scalablesolutions.akka.mobile.theater.profiler._
import se.scalablesolutions.akka.mobile.nameservice.NameService
import se.scalablesolutions.akka.mobile.nameservice.DistributedNameService
import se.scalablesolutions.akka.mobile.tools.mobtrack.MobTrackGUI

import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.actor.RemoteActorSerialization
import se.scalablesolutions.akka.remote.RemoteServer
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._
import se.scalablesolutions.akka.remote.MessageSerializer
import se.scalablesolutions.akka.config.Config
import se.scalablesolutions.akka.util.Logging

import java.util.concurrent.ConcurrentHashMap
import collection.JavaConversions._

object LocalTheater extends Theater

private[mobile] class Theater extends Logging {

  // Table of mobile actors running within this theater
  private val mobileActors = new ConcurrentHashMap[String, MobileActorRef]

  // Internal remote server (from Akka)
  private val server = new RemoteServer
  // Pipeline factory creator for the remote server
  private var _pipelineFactoryCreator: PipelineFactoryCreator = new TheaterPipelineFactoryCreator(mobileActors, this)

  // The theater protocol is specified in the configuration file
  private val protocol: TheaterProtocol = loadTheaterProtocol()

  private var _isRunning = false
  def isRunning = _isRunning

  // Description of the node (name, node, which services are running)
  private var _description: TheaterDescription = _
  def description = _description

  // The address of this Theater
  def node: TheaterNode = description.node

  // The profiler is used to register the actors communication
  private var _profiler: Option[Profiler] = None
  def profiler = _profiler

  // Mobile actor tracking system
  private var mobTrack = false
  private var mobTrackNode: Option[TheaterNode] = None

  /**
   * METHODS
   */

  // Starts theater from node name (in the configuration file)
  def start(nodeName: String): Boolean = ClusterConfiguration.nodes.get(nodeName) match {
    case Some(description) => start(description)

    case None =>
      log.warning("There is no description for a node with name '%s' in the configuration file", nodeName)
      false
  }

  // Starts theater from specific node address
  def start(node: TheaterNode): Boolean = start(node.hostname, node.port)

  // Starts theater from specific node address
  def start(hostname: String, port: Int): Boolean = {
    val nodeDescription = ClusterConfiguration.nodes.values.filter {
      desc =>
        desc.node.hostname == hostname && desc.node.port == port
    }

    if (nodeDescription.size == 0) {
      log.warning("There is no description for a node at %s in the configuration file.", TheaterNode(hostname, port).format)
      false
    } else {
      // 'nodeDescription' is an Iterable[TheaterDescription], so we take the first (and hopefully only) element
      start(nodeDescription.head)
    }
  }

  private def start(description: TheaterDescription): Boolean = {
    _description = description

    val profilingLabel = if (description.profiling) "Enabled" else "Disabled"
    val nameServerLabel = if (description.hasNameServer) "Enabled" else "Disabled"
    log.info("Starting theater at %s with...\n" +
      "\t name: %s\n" +
      "\t name server: %s\n" +
      "\t profiling: %s", node.format, description.name, nameServerLabel, profilingLabel)

    server.setPipelineFactoryCreator(_pipelineFactoryCreator)
    server.start(node.hostname, node.port)

    protocol.init(this)
    NameService.init(description.hasNameServer)

    if (description.profiling)
      _profiler = Some(new Profiler(this.node))

    if (Config.config.getString("cluster.mob-track.node").isDefined) {
      configureMobTrack()
    }

    _isRunning = server.isRunning
    // Returns true if the theater has been started properly
    _isRunning
  }

  def shutdown(): Unit = {
    log.info("Shutting down theater at %s.", node.format)

    mobileActors.values.foreach { ref =>
      NameService.remove(ref.uuid)
      ref.stop
    }
    mobileActors.clear

    // TODO
    protocol.stop()
    profiler.foreach(_.stop())
    NameService.stop

    server.shutdown
    _isRunning = false
  }

  // Register a mobile actor in this theater
  def register(actor: MobileActorRef, fromMigration: Boolean = false): Unit = {
    if (_isRunning && actor != null) {
      mobileActors.put(actor.uuid, actor)

      // Registering in the name server
      NameService.put(actor.uuid, this.node)

      if (!fromMigration) {
        if (mobTrack) {
          sendTo(mobTrackNode.get, MobTrackArrive(actor.uuid, this.node))
        }
      }

      log.debug("Registering actor with UUID [%s] in theater at %s.", actor.uuid, node.format)
    }
  }

  // Unregister a mobile actor from this theater
  def unregister(actor: MobileActorRef, afterMigration: Boolean = false): Unit = {
    if (_isRunning && actor != null) {
      if (!afterMigration) {
        NameService.remove(actor.uuid)
        if (mobTrack) {
          sendTo(mobTrackNode.get, MobTrackDepart(actor.uuid, this.node))
        }
      }
      mobileActors.remove(actor.uuid)

      log.debug("Unregistering actor with UUID [%s] from theater at %s.", actor.uuid, node.format)
    }
  }

  /**
   * Registers an agent within this theater. Agents are regular actors (not mobile) that run in the theater's
   * internal remote server.
   */
  def registerAgent(name: String, agent: ActorRef): Unit = {
    server.register(name, agent)
  }

  /* Unregisters an agent */
  def unregisterAgent(name: String): Unit = {
    server.unregister(name)
  }

  def isLocal(hostname: String, port: Int): Boolean =
    (LocalTheater.node.hostname == hostname && LocalTheater.node.port == port)

  /**
   * Incoming message handling methods
   */

  // Handles messages received from a remote theater to some mobile actor that supposedly is in
  // this theater.
  private[theater] def handleMobileActorRequest(request: RemoteRequestProtocol): Unit = {
    val uuid = request.getActorInfo.getUuid
    val message = MessageSerializer.deserialize(request.getMessage)

    DefaultLogger.debug("Mensagem recebida para ator [UUID %s]: %s", uuid, message)

    val sender = findMessageSender(request)

    mobileActors.get(uuid) match {
      case actor: MobileActorRef =>
        //        DefaultLogger.debug("Actor with UUID [%s] running at theater. Sending: %s", uuid, message)
        actor.!(message)(sender)

      // The actor is not here. Possibly it has been migrated to some other theater.
      case null =>
        val refOpt = ReferenceManagement.get(uuid)
        if (refOpt.isDefined) {
          val msg = message match {
            case MobileActorMessage(hostname, port, _msg) =>
              val senderNode = TheaterNode(hostname, port)
              sendTo(senderNode, ActorNewLocationNotification(uuid, refOpt.get.node.hostname, refOpt.get.node.port))
              _msg

            case anyOtherMsg => anyOtherMsg
          }
          //          DefaultLogger.debug("Actor with UUID [%s] *NOT* running at theater. " +
          //            "Running at %s (remoto: %s). Sending: %s", uuid, refOpt.get.node.format, refOpt.get.isLocal, message)
          refOpt.get.!(msg)(sender)
        } else {
          log.debug("Actor with UUID [%s] not found at theater %s.", uuid, node.format)
          //          DefaultLogger.debug("Actor with UUID [%s] not found at theater %s.", uuid, node.format)
          handleActorNotFound(request)
        }
    }
  }

  // Tries to find out who is the message sender. 
  private def findMessageSender(request: RemoteRequestProtocol): Option[ActorRef] = {
    return None
    if (request.hasSender) {
      val sender = request.getSender
      MobileActorRef(sender.getUuid) match {
        // Sender is a mobile actor
        case Some(actorRef) => Some(actorRef)
        // Sender is not a mobile actor, construct proxy as usual (from Akka)
        case None => Some(RemoteActorSerialization.fromProtobufToRemoteActorRef(sender, None))
      }
    } else None
  }

  /**
   * Handles the case where the actor was not found at this theater. It possibly has been migrated
   * to some other theater. First this theater will try to find the actor in some other node in the
   * cluster, using the name service. In case of success, it will redirect the message to the actor
   * and then notify the sender theater about the address change
   */
  private def handleActorNotFound(request: RemoteRequestProtocol): Unit = {
    val uuid = request.getActorInfo.getUuid
    //    DefaultLogger.debug("Ator [UUID %s] não encontrado no teatro", uuid)
    NameService.get(uuid) match {
      case Some(node) =>
        log.debug("Actor with UUID [%s] found at %s. The message will be redirected to it.", uuid, node.format)
        //        DefaultLogger.debug("Ator [UUID %s] encontrado em %s. Redirecionando: %s",
        //          uuid, node.format, MessageSerializer.deserialize(request.getMessage))

        val actorRef = MobileActorRef(uuid, node.hostname, node.port)
        val (senderNode, message) = MessageSerializer.deserialize(request.getMessage) match {
          case MobileActorMessage(senderHostname, senderPort, msg) => (Some(TheaterNode(senderHostname, senderPort)), msg)
          case msg => (None, msg)
        }

        actorRef.!(message)(findMessageSender(request))

        // Notifying the sender theater about the address change
        if (senderNode.isDefined) {
          log.debug("Notifying the sender of the message at %s the new location of the actor.",
            senderNode.get.format)
          //          DefaultLogger.debug("Notifying the sender of the message at %s the new location of the actor.",
          //            senderNode.get.format)

          sendTo(senderNode.get, ActorNewLocationNotification(uuid, node.hostname, node.port))
        }

      case None =>
        log.debug("The actor with UUID [%s] was not found in the cluster.", uuid)
        //        DefaultLogger.debug("Ator [UUID %s] não encontrado.", uuid)
        ()
    }
  }

  /*
   * Migration methods
   */

  // Called by the reference, initiates an actor migration
  private[mobile] def migrate(actor: MobileActorRef, destination: TheaterNode): Unit = {
    if (mobileActors.get(actor.uuid) == null) {
      throw new RuntimeException("Actor not registered in this theater, can't go on with migration")
    }

    log.info("Theater at %s received a request to migrate actor with UUID [%s] to theater at %s.",
      node.format, actor.uuid, destination.format)

    val actorBytes = actor.startMigration()
    sendTo(destination, MovingActor(actorBytes))
  }

  private[theater] def migrateGroup(actorsBytes: Array[Array[Byte]], destination: TheaterNode, nextTo: Option[String]): Unit = {
    log.info("Theater at %s performing migration of %d actors to theater at %s.",
      node.format, actorsBytes.size, destination.format)

    sendTo(destination, MovingGroup(actorsBytes, nextTo))
  }

  /*
   * Inter-theater protocol methods
   *
   * The following methods deal with messages exchanged exclusively among theaters. These messages
   * are used to perform actions like migration, remote actors spawn, specific notifications, etc.
   */

  // This method processes all inter-theater messages received. The method will
  // be called by the theater protocol implementation.
  private[theater] def processMessage(message: TheaterMessage): Unit = message match {

    // Serialized actor arriving
    case MovingActor(bytes) =>
      val ref = receiveActor(bytes, message.sender)
      // Notifying the sender that the actor is now registered in this theater
      sendTo(message.sender, MobileActorsRegistered(Array(ref.uuid)))

    // Notification that a migration is completed (common message for single and group migration)
    case MobileActorsRegistered(uuids) =>
      completeMigration(uuids, message.sender)

    // Serialized group of co-located actors arriving
    case MovingGroup(actorsBytes, nextTo) =>
      val refs = receiveGroup(actorsBytes, message.sender, nextTo)
      sendTo(message.sender, MobileActorsRegistered(refs.map(_.uuid)))

    // Request to start a mobile actor of the specified class in this theater
    case StartMobileActorRequest(requestId, className) =>

      val ref = startActorByClassName(className, requestId.toString)
      //      sendTo(message.sender, StartMobileActorReply(requestId, ref.uuid))
      sendTo(message.sender, MobileActorsRegistered(Array(ref.uuid)))

    // Request to start 'number' co-located actors of the specified class in this theater
    case StartColocatedActorsRequest(requestId, className, number, nextTo) => {
      val uuids = startColocatedActorsByClassName(className, number, nextTo, requestId.toString)
      //      sendTo(message.sender, StartColocatedActorsReply(requestId, uuids))
      sendTo(message.sender, MobileActorsRegistered(uuids))
    }

    // Notification that an actor migrated and this theater has an outdated reference
    case ActorNewLocationNotification(uuid, newHostname, newPort) =>
      log.debug("Theater at %s received a notification that actor with UUID [%s] has migrated " +
        "to %s.", node.format, uuid, TheaterNode(newHostname, newPort).format)

      val reference = ReferenceManagement.get(uuid)
      if (reference.isDefined) {
        reference.get.updateRemoteAddress(TheaterNode(newHostname, newPort))
      }

    case MobTrackMigrate(uuid, from, to) =>
      MobTrackGUI.migrate(uuid, from, to)

    case MobTrackArrive(uuid, node) =>
      MobTrackGUI.arrive(uuid, node)

    case MobTrackDepart(uuid, node) =>
      MobTrackGUI.depart(uuid, node)

    case trash =>
      log.debug("Theater at %s received an unknown message: %s. Discarding it.", node.format, trash)
  }

  /**
   * Starts a local actor by its class name in this theater. This method will be called whenever
   * an actor is spawned somewhere using its class name, and the system decides that it should be run
   * in this theater.
   *
   * @param className the class name of the actor that should be spawned
   *
   * @return the reference of the new actor started
   */
  private def startActorByClassName(className: String, existingUuid: String): MobileActorRef = {
    log.debug("Starting an actor of class %s at theater %s", className, node.format)
    //    DefaultLogger.debug("Starting actor with UUID [%s]", existingUuid)
    val mobileRef = MobileActorRef(Class.forName(className).asInstanceOf[Class[_ <: MobileActor]], existingUuid, false)
    mobileRef.start
    mobileRef
  }

  private def startColocatedActorsByClassName(className: String, number: Int, nextTo: Option[String], existingUuid: String): Array[String] = {
    log.debug("Starting %d colocated actors of class %s in theater %s.", number, className, node.format)

    val groupId: String =
      if (nextTo.isDefined) {
        val ref = mobileActors.get(nextTo.get)
        if (ref != null && ref.groupId.isDefined) ref.groupId.get
        else if (ref != null) {
          val newId = GroupManagement.newGroupId
          ref.groupId = Some(newId)
          newId
        } else
          GroupManagement.newGroupId // actor not found in the theater, just generate a new group ID
      } else
        GroupManagement.newGroupId

    val uuids = new Array[String](number)
    for (i <- 0 to (number - 1)) {
      val ref = startActorByClassName(className, existingUuid + "_" + i)
      ref.groupId = Some(groupId)
      uuids(i) = ref.uuid
    }
    uuids
  }

  // Complete the actor migration in the origin theater
  private def completeMigration(uuids: Array[String], destination: TheaterNode): Unit = {
    var notifyGroupManagement = true

    uuids.foreach { uuid =>
      log.debug("Completing the migration process of actor with UUID [%s]", uuid)
      profiler.foreach(_.remove(uuid))
      val actor = mobileActors.get(uuid)
      if (actor != null) {
        if (notifyGroupManagement && actor.groupId.isDefined) {
          GroupManagement.completeMigration(actor.groupId.get)
          notifyGroupManagement = false
        }
        actor.completeMigration(destination)
      }
    }
  }

  // TODO o afterMigration() deveria ocorrer antes do ator começar a processar msgs
  // Instantiates an actor migrating from another theater, starts and registers it.
  private def receiveActor(bytes: Array[Byte], sender: TheaterNode, fromGroupMigration: Boolean = false): MobileActorRef = {
    log.debug("Theater at %s just received a migrating actor from %s.",
      node.format, sender.format)
    //    DefaultLogger.debug("Theater at %s just received a migrating actor from %s.",
    //      node.format, sender.format)

    val mobileRef = MobileSerialization.mobileFromBinary(bytes)(DefaultActorFormat)
    mobileRef.afterMigration()
    if (!fromGroupMigration && mobileRef.groupId.isDefined) {
      GroupManagement.insert(mobileRef, mobileRef.groupId.get)
    }

    if (mobTrack) {
      sendTo(mobTrackNode.get, MobTrackMigrate(mobileRef.uuid, sender, this.node))
    }

    mobileRef
  }

  private def receiveGroup(actorsBytes: Array[Array[Byte]], sender: TheaterNode, nextTo: Option[String]): Array[MobileActorRef] = {
    val refs = for {
      bytes <- actorsBytes
      ref = receiveActor(bytes, sender, true)
    } yield ref

    // This will be used unless there is a 'nextTo' parameter and that actor has a groupId value set.
    lazy val newGroupId = refs(0).groupId.getOrElse(GroupManagement.newGroupId)
    val groupId: String =
      if (nextTo.isDefined) {
        val ref = mobileActors.get(nextTo.get)
        if (ref != null && ref.groupId.isDefined) ref.groupId.get
        else if (ref != null) {
          ref.groupId = Some(newGroupId)
          newGroupId
        } else
          newGroupId // actor not found in the theater, just generate a new group ID
      } else
        newGroupId

    refs.foreach(_.groupId = Some(groupId))
    refs
  }

  /**
   * Protocol specific methods
   */

  // Loads the type of the inter-theater protocol
  private def loadTheaterProtocol(): TheaterProtocol = {
    lazy val defaultProtocol = new NettyTheaterProtocol
    try {
      ClusterConfiguration.instanceOf[TheaterProtocol, AgentProtocol]("cluster.theater-protocol.class")
    } catch {
      case cce: ClassCastException =>
        val classname = Config.config.getString("cluster.theater-protocol.class", "")
        log.warning("The class [%s] does not extend the TheaterProtocol abstract class. Using the default protocol [%s] instead.",
          classname, defaultProtocol.getClass.getName)
        defaultProtocol
    }
  }

  // Sends a message over the wire to some other theater
  private[theater] def sendTo(node: TheaterNode, message: TheaterMessage): Unit = {
    protocol.sendTo(node, message)
  }

  /*
   * Configuration of the mobile actors tracking system
   */
  private def configureMobTrack(): Unit = {
    val nodeName = Config.config.getString("cluster.mob-track.node").get
    val hostname = Config.config.getString("cluster." + nodeName + ".hostname")
    val port = Config.config.getInt("cluster." + nodeName + ".port")

    (hostname, port) match {
      case (Some(_hostname), Some(_port)) =>
        mobTrack = true
        mobTrackNode = Some(TheaterNode(_hostname, _port))
        log.debug("MobTrack is activated and running at node %s", mobTrackNode.get.format)
      case _ =>
        log.debug("MobTrack is not running.")
        ()
    }
  }

  /**
   * SETTERS AND GETTERS
   */

  def pipelineFactoryCreator = _pipelineFactoryCreator

  def pipelineFactoryCreator_=(creator: PipelineFactoryCreator): Unit = {
    if (!_isRunning) {
      _pipelineFactoryCreator = creator
    }
  }

}

