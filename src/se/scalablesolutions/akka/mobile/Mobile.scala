package se.scalablesolutions.akka.mobile

import se.scalablesolutions.akka.mobile.algorithm.DistributionAlgorithm
import se.scalablesolutions.akka.mobile.algorithm.RoundRobinAlgorithm
import se.scalablesolutions.akka.mobile.actor.MobileActor
import se.scalablesolutions.akka.mobile.actor.MobileActorRef
import se.scalablesolutions.akka.mobile.theater.LocalTheater
import se.scalablesolutions.akka.mobile.theater.TheaterNode
import se.scalablesolutions.akka.mobile.theater.RemoteSpawnHelper
import se.scalablesolutions.akka.mobile.theater.GroupManagement
import se.scalablesolutions.akka.mobile.util.ClusterConfiguration
import se.scalablesolutions.akka.mobile.util.DefaultLogger

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.config.Config

import java.net.InetAddress

object Mobile extends Logging {

  // Implicit conversion to make it easier to spawn co-located actors from factories (non-default
  // constructor)
  implicit def fromByNameToFunctionLiteral(byName: => MobileActor): () => MobileActor = { () => byName }

  private lazy val algorithm: DistributionAlgorithm = {
    lazy val defaultAlgorithm = new RoundRobinAlgorithm
    try {
      ClusterConfiguration.instanceOf[DistributionAlgorithm, RoundRobinAlgorithm]("cluster.distribution-algorithm")
    } catch {
      case cce: ClassCastException =>
        val classname = Config.config.getString("cluster.distribution-algorithm", "")
        log.warning("The class [%s] does not extend the DistributionAlgorithm trait. Using the default algorithm [%s] instead.",
          classname, defaultAlgorithm.getClass.getName)
        defaultAlgorithm
    }
  }

  /**
   * Infrastructure API - Allows users to instantiate mobile actors in two ways:
   *
   *   Launch: Creates the actor in some node chosen by the infrastrucuture, using some algorithm defined
   *   in the configuration file (e.g. round-robin). Usage:
   *     val ref1  = Mobile.launch[MyActor]
   *     val ref2  = Mobile.launch(new MyActor(state))
   *     val refs1 = Mobile.launch[MyActor](3) // 3 co-located actors
   *     val refs2 = Mobile.launch(new MyActor(state1), new MyActor(state2)) // co-located actors
   *
   *   Spawn: Expects a specifier to tell the infrastructure where to put the actor. This specifier can be
   *   'here', 'at' or 'nextTo' (only for co-located actors). Usage:
   *
   *     val ref1  = Mobile.spawn[MyActor] here
   *     val ref2  = Mobile.spawn(new MyActor(state)) at TheaterNode(hostname, port)
   *     val refs1 = Mobile.spawn[MyActor](3) nextTo (ref2)
   *     val refs2 = Mobile.spawn(new MyActor(state1), new MyActor(state2)) at (refs1(0).node)
   */

  /**
   * Launches the actor(s) in some node chosen by the distribution algorithm
   */
  def launch[T <: MobileActor: Manifest]: MobileActorRef = {
    val clazz = manifest[T].erasure.asInstanceOf[Class[_ <: MobileActor]]
    spawn(Left(clazz))
  }

  def launch(factory: => MobileActor): MobileActorRef = {
    spawn(Right(() => factory))
  }

  // Co-located
  def launch[T <: MobileActor: Manifest](number: Int): List[MobileActorRef] = {
    val clazz = manifest[T].erasure.asInstanceOf[Class[_ <: MobileActor]]
    spawnColocated(Left(clazz, number), None)
  }

  // Co-located
  def launch(factory1: () => MobileActor, factory2: () => MobileActor, factories: (() => MobileActor)*): List[MobileActorRef] = {
    spawnColocated(Right(factory1 :: factory2 :: factories.toList), None)
  }

  /**
   * Spawns the actor(s) in the specified node
   */

  def spawn[T <: MobileActor: Manifest] = {
    val clazz = manifest[T].erasure.asInstanceOf[Class[_ <: MobileActor]]
    new SpawnOps(Left(clazz))
  }

  def spawn(factory: => MobileActor) = {
    new SpawnOps(Right(() => factory))
  }

  // Co-located
  def spawn[T <: MobileActor: Manifest](number: Int) = {
    val clazz = manifest[T].erasure.asInstanceOf[Class[_ <: MobileActor]]
    new ColocateOps(Left(clazz, number))
  }

  // Co-located
  def spawn(factory1: () => MobileActor, factory2: () => MobileActor, factories: (() => MobileActor)*) = {
    new ColocateOps(Right(factory1 :: factory2 :: factories.toList))
  }

  /**
   * Private helper methods and inner classes
   */

  // Specifiers for the 'spawn' method (for individual actors)
  private[Mobile] class SpawnOps(constructor: Either[Class[_ <: MobileActor], () => MobileActor]) {
    // def nextTo(ref: MobileActorRef): List[MobileActorRef] = {
    //   spawnColocated(constructor, Some(ref.node), Some(ref))
    // }

    def at(node: TheaterNode): MobileActorRef = {
      spawn(constructor, Some(node))
    }

    def here: MobileActorRef = {
      spawn(constructor, Some(LocalTheater.node))
    }
  }

  // Methods that actually spawns the actor
  private def spawn(
    constructor: Either[Class[_ <: MobileActor], () => MobileActor],
    where: Option[TheaterNode] = None): MobileActorRef = {

    if (!LocalTheater.isRunning) throw new RuntimeException("You must start a local theater before creating mobile " +
      "actors. See Mobile.startTheater(..) methods.")

    val node: TheaterNode = where.getOrElse(algorithm.chooseTheater)

    if (node.isLocal) {
      val mobileRef = constructor match {
        case Left(clazz) => MobileActorRef(clazz)

        case Right(factory) => MobileActorRef(factory())
      }
      mobileRef.start
      //      DefaultLogger.debug("### [%s]", mobileRef.uuid)
      mobileRef
    } else {
      val ref = RemoteSpawnHelper.spawnActor(constructor, node)
      //      DefaultLogger.debug("### [%s]", ref.uuid)
      ref
    }
  }

  // Specifiers for the 'spawn' method (for co-located actors)
  private[Mobile] class ColocateOps(constructor: Either[Tuple2[Class[_ <: MobileActor], Int], Seq[() => MobileActor]]) {
    def nextTo(ref: MobileActorRef): List[MobileActorRef] = {
      spawnColocated(constructor, Some(ref.node), Some(ref))
    }

    def at(node: TheaterNode): List[MobileActorRef] = {
      spawnColocated(constructor, Some(node))
    }

    def here: List[MobileActorRef] = {
      spawnColocated(constructor, Some(LocalTheater.node))
    }
  }

  // Method that actually spawns co-located actors
  private def spawnColocated(
    constructor: Either[Tuple2[Class[_ <: MobileActor], Int], Seq[() => MobileActor]],
    where: Option[TheaterNode] = None,
    nextTo: Option[MobileActorRef] = None): List[MobileActorRef] = {

    if (!LocalTheater.isRunning) throw new RuntimeException("You must start a local theater before creating mobile " +
      "actors. See Mobile.startTheater(..) methods.")

    val node: TheaterNode = where.getOrElse(algorithm.chooseTheater)
    log.debug("Spawing colocated actors at %s", node.format)
    if (node.isLocal) {
      val mobileRefs: Seq[MobileActorRef] = constructor match {
        case Left((clazz, n)) =>
          for (i <- 1 to n) yield MobileActorRef(clazz)

        case Right(factories) =>
          for (factory <- factories) yield MobileActorRef(factory())
      }
      val groupId =
        if (nextTo.isDefined && nextTo.get.groupId.isDefined)
          nextTo.get.groupId.get
        else if (nextTo.isDefined) {
          val newId = GroupManagement.newGroupId
          nextTo.get.groupId = Some(newId)
          newId
        } else
          GroupManagement.newGroupId
      log.debug("Setting groupId for spawned actors: %s", groupId)
      mobileRefs.foreach { ref =>
        ref.groupId = Some(groupId)
        ref.start
      }
      mobileRefs.toList
    } else RemoteSpawnHelper.spawnColocatedActors(constructor, node, nextTo)
  }

  /**
   * Methods for starting a local theater in this node
   */
  def startTheater(nodeName: String): Boolean = LocalTheater.start(nodeName)

  def startTheater(node: TheaterNode): Boolean = startTheater(node.hostname, node.port)

  def startTheater(hostname: String, port: Int): Boolean = LocalTheater.start(hostname, port)

  // In this case, the system will try to guess which node should run, based on the machine's hostname
  def startTheater(): Boolean = {
    val localHostname = InetAddress.getLocalHost.getHostName
    val iterable = ClusterConfiguration.nodes.values.filter {
      description =>
        description.node.hostname == localHostname
    } map {
      description =>
        description.name
    }
    if (iterable.size > 0) {
      LocalTheater.start(iterable.head)
    } else {
      log.warning("Impossible to figure it out which node is supposed to run on this machine. Please use one of the following:\n" +
        "\t Mobile.startTheater(nodeName: String)\n" +
        "\t Mobile.startTheater(hostname: String, port: Int)")

      false
    }
  }
}

