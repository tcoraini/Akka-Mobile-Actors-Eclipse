package se.scalablesolutions.akka.mobile.util.messages

import se.scalablesolutions.akka.mobile.theater.LocalTheater
import se.scalablesolutions.akka.mobile.theater.TheaterNode

/**
 * Migration-related messages
 */
sealed abstract class MigrationMessage
// Message that request an actor to migrate to some node
case class MoveTo(hostname: String, port: Int) extends MigrationMessage

/**
 * Message that request all actors from a group to migrate together to some node
 * Note that the constructor is private to the infrastructure, to protect the 'nextTo' use of the
 * migration.
 * To allow access for users to this message, we define an 'apply' method in the companion object.
 */
case class MoveGroupTo private[mobile] (hostname: String, port: Int, nextTo: Option[String]) extends MigrationMessage
object MoveGroupTo {
  def apply(hostname: String, port: Int) = new MoveGroupTo(hostname, port, None)
}

// Internal message for the group migration process
private[mobile] case object PrepareToMigrate extends MigrationMessage


/**
 * Name Service messages:
 */
case class ActorRegistrationRequest(acturUuid: String, hostname: String, port: Int)
case class ActorUnregistrationRequest(actorUuid: String)

case class ActorLocationRequest(actorUuid: String)
case class ActorLocationResponse(hostname: String, port: Int)
case object ActorNotFound

/**
 * Inter-theater messages
 */
trait TheaterMessage {
  private[mobile] var sender: TheaterNode = LocalTheater.node
}

case class MovingActor(bytes: Array[Byte]) extends TheaterMessage
case class MovingGroup(bytes: Array[Array[Byte]], nextTo: Option[String]) extends TheaterMessage

case class MobileActorsRegistered(uuids: Array[String]) extends TheaterMessage 

case class StartMobileActorRequest(requestId: Long, className: String) extends TheaterMessage
case class StartMobileActorReply(requestId: Long, uuid: String) extends TheaterMessage

case class StartColocatedActorsRequest(requestId: Long, className: String, number: Int, nextTo: Option[String]) extends TheaterMessage
case class StartColocatedActorsReply(requestId: Long, uuids: Array[String]) extends TheaterMessage

case class ActorNewLocationNotification(uuid: String, hostname: String, port: Int) extends TheaterMessage

// Messages for the tracking system
case class MobTrackMigrate(uuid: String, from: TheaterNode, to: TheaterNode) extends TheaterMessage
case class MobTrackArrive(uuid: String, node: TheaterNode) extends TheaterMessage
case class MobTrackDepart(uuid: String, node: TheaterNode) extends TheaterMessage

/**
 * Message that wraps a request for a remote mobile actor
 */
case class MobileActorMessage(senderHostname: String, senderPort: Int, message: Any)

