package se.scalablesolutions.akka.mobile.actor

import se.scalablesolutions.akka.mobile.theater.Theater
import se.scalablesolutions.akka.mobile.theater.LocalTheater
import se.scalablesolutions.akka.mobile.theater.TheaterNode
import se.scalablesolutions.akka.mobile.theater.ReferenceManagement
import se.scalablesolutions.akka.mobile.theater.GroupManagement
import se.scalablesolutions.akka.mobile.serialization.DefaultActorFormat
import se.scalablesolutions.akka.mobile.nameservice.NameService
import se.scalablesolutions.akka.mobile.Mobile
import se.scalablesolutions.akka.mobile.util.DefaultLogger
import se.scalablesolutions.akka.mobile.util.Logger

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.actor.LocalActorRef
import se.scalablesolutions.akka.actor.RemoteActorRef
import se.scalablesolutions.akka.actor.ScalaActorRef
import se.scalablesolutions.akka.actor.ActorSerialization

import se.scalablesolutions.akka.remote.RemoteClient

import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.stm.TransactionConfig
import se.scalablesolutions.akka.util.Logging

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.{ Map => JMap }

object MobileActorRef {

  /**
   * Returns a mobile reference that represents the local reference provided. If a mobile
   * reference already exists for this actor (which would be a proxy for a remote mobile
   * actor), then it is updated with this new reference.
   */
  private[mobile] def apply(reference: InnerReference): MobileActorRef = {
    ReferenceManagement.get(reference.uuid) match {
      case Some(mobileRef) =>
        mobileRef.switchActorRef(reference)
        if (reference.isLocal) {
          LocalTheater.register(mobileRef)
        }
        mobileRef

      case None =>
        register(new MobileActorRef(reference))
    }
  }

  /**
   * Creates a local reference for the mobile actor instantiated with the factory provided.
   */
  private[mobile] def apply(factory: => MobileActor): MobileActorRef = {
    val localRef = new LocalActorRef(() => factory) with LocalMobileActor
    register(new MobileActorRef(localRef))
  }

  /**
   * Creates a local reference for a mobile actor of the class specified.
   */
  private[mobile] def apply(clazz: Class[_ <: MobileActor]): MobileActorRef = {
    val localRef = new LocalActorRef(clazz) with LocalMobileActor
    register(new MobileActorRef(localRef))
  }

  private[mobile] def apply(clazz: Class[_ <: MobileActor], existingUuid: String, detachedProxy: Boolean): MobileActorRef = {
    val localRef =
      if (!detachedProxy) new LocalActorRef(clazz) with LocalMobileActor
      else new LocalActorRef(clazz) with LocalMobileActor with DetachedActor
    localRef.uuid = existingUuid
    register(new MobileActorRef(localRef))
  }

  /**
   * Creates a reference from the UUID of the actor. The reference can be either local or remote.
   */
  def apply(uuid: String): Option[MobileActorRef] = {
    ReferenceManagement.get(uuid) match {
      // Actor with this uuid is local
      case Some(reference) => Some(reference)

      case None => NameService.get(uuid) match {
        case Some(node) => Some(MobileActorRef(uuid, node.hostname, node.port)) // Proxy for remote mobile actor
        case None => None // Actor not found
      }
    }
  }

  /**
   * Creates a remote reference for the actor with the specified UUID running in the Theater at hostname:port.
   */
  private[mobile] def apply(
    uuid: String,
    hostname: String,
    port: Int,
    timeout: Long = Actor.TIMEOUT): MobileActorRef = {

    ReferenceManagement.get(uuid) match {
      case Some(reference) =>
        reference.updateRemoteAddress(TheaterNode(hostname, port))
        reference

      case None =>
        val remoteRef = remoteMobileActor(uuid, hostname, port, timeout)
        register(new MobileActorRef(remoteRef))
    }
  }

  /**
   * Creates a reference which mixes in the RemoteMobileActor trait. It will be used
   * as a proxy for a mobile actor remotely located
   */
  private def remoteMobileActor(
    uuid: String,
    hostname: String,
    port: Int,
    timeout: Long = Actor.TIMEOUT): RemoteMobileActor = {
    new RemoteActorRef(uuid, uuid, hostname, port, timeout, None) with RemoteMobileActor
  }

  /* Registers the mobile reference in the ReferenceManagement */
  private def register(reference: MobileActorRef): MobileActorRef = {
    ReferenceManagement.putIfAbsent(reference.uuid, reference) match {
      case None =>
        if (reference.isLocal) {
          LocalTheater.register(reference)
        }
        reference

      case Some(ref) => ref
    }
  }
}

// TODO voltar innerRef a protected
class MobileActorRef private (var innerRef: InnerReference) extends MethodDelegation with Logging {

  if (!LocalTheater.isRunning)
    throw new RuntimeException("There must be a Local Theater running before you can instantiate mobile actors.")

  val logger = new Logger("logs/mobile-actors/" + uuid + ".log")

  innerRef.outerRef = this

  private var _isMigrating = false

  def isMigrating = _isMigrating

  def groupId: Option[String] = innerRef.groupId
  protected[mobile] def groupId_=(id: Option[String]) = { innerRef.groupId = id }

  def node: TheaterNode = innerRef.node

  def isLocal = innerRef.isLocal

  /**
   * This methods should be called by the MobileActor trait, when the
   * actor receives a migration-related message
   */

  // Individual migration
  private[actor] def moveTo(hostname: String, port: Int): Unit = {
    groupId = None
    LocalTheater.migrate(this, TheaterNode(hostname, port))
  }

  // Group migration (for co-located actors only)
  private[actor] def moveGroupTo(hostname: String, port: Int, nextTo: Option[String]): Unit = {
    if (groupId.isDefined) {
      GroupManagement.startGroupMigration(groupId.get, TheaterNode(hostname, port), nextTo)
    } else {
      // If not in a group, migrate the actor alone
      moveTo(hostname, port)
    }
  }

  // Group migration, used only internally by the infrastructure
  private[actor] def prepareToMigrate(): Unit = {
    if (groupId.isDefined) {
      GroupManagement.readyToMigrate(this)
    }
  }

  /**
   * Changes the actor reference behind this proxy.
   * Returns true if the new actor is local, false otherwise.
   */
  protected def switchActorRef(newRef: InnerReference): Unit = {
    val previousInnerRef = innerRef
    innerRef._lock.synchronized {
      innerRef = newRef
      innerRef.outerRef = this
    }
    previousInnerRef.stopLocal()

    val label = if (innerRef.isLocal) "local" else "remote"
    log.debug("Switching mobile reference for actor with UUID [%s] to a %s reference.", uuid, label)

    logger.debug("Trocando referencia interna de [UUID %s] para uma referencia %s", uuid, label)
  }

  /**
   * This methods is should be called by the local theater, after a migration is initiated.
   * It returns an array of bytes containing the serialized actor
   */
  protected[mobile] def startMigration(): Array[Byte] = {
    if (!isLocal) throw new RuntimeException("The method 'startMigration' should be call only on local actors")

    _isMigrating = true

    // The mailbox won't be serialized if the actor has not been started yet. In this case, there will be
    // no messages in it's mailbox. This was used in a previous form of actor remote spawn, where the actor
    // was no started before being serialized. But still makes sense, and maybe will be used in the future.
    val serializeMailbox =
      if (isRunning) {
        // Sinalizing the start of the migration process
        innerRef.asInstanceOf[LocalMobileActor].beforeMigration()
        true
      } else false

    ActorSerialization.toBinary(innerRef, serializeMailbox)(DefaultActorFormat)
  }

  /**
   * Completes the migration of the actor in the origin theater of that actor.
   */
  protected[mobile] def completeMigration(destination: TheaterNode): Unit = {
    if (isLocal && (isMigrating || innerRef.isInstanceOf[DetachedActor])) {
      // New inner reference (a remote one), will act as a proxy for the actor in its new theater
      val remoteActorRef = MobileActorRef.remoteMobileActor(uuid, destination.hostname, destination.port, innerRef.timeout)

      val previousInnerRef = innerRef
      switchActorRef(remoteActorRef)
      previousInnerRef.asInstanceOf[LocalMobileActor].completeMigration(remoteActorRef)

      _isMigrating = false
    }
  }

  /**
   * Calls the afterMigration() callback in the actor implementation, defined by the user.
   * This method will be called in the destination theater, after the migrating actor arrives.
   */
  protected[mobile] def afterMigration(): Unit = if (isLocal) {
    innerRef.asInstanceOf[LocalMobileActor].afterMigration()
  }

  protected[mobile] def updateRemoteAddress(newAddress: TheaterNode): Unit = {
    log.debug("Updating the remote address for actor with UUID [%s] to theater %s.", uuid, newAddress.format)

    assert(!newAddress.isLocal) // TODO
    val newReference = MobileActorRef.remoteMobileActor(uuid, newAddress.hostname, newAddress.port, timeout)

    switchActorRef(newReference)
  }
}
