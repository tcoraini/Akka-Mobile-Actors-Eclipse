package se.scalablesolutions.akka.mobile.actor

import se.scalablesolutions.akka.mobile.theater.Theater
import se.scalablesolutions.akka.mobile.util.messages._
import se.scalablesolutions.akka.mobile.util.UUID
import se.scalablesolutions.akka.actor.Actor

import java.net.InetSocketAddress

@serializable 
trait MobileActor extends Actor {

  private var _groupId: Option[String] = None
  private[actor] def groupId_=(newId: Option[String]) = { _groupId = newId }
  def groupId = _groupId

  self.uuid = UUID.newUuid.toString

  override private[akka] def apply(msg: Any): Unit = {
    if (specialBehavior.isDefinedAt(msg)) specialBehavior(msg)
    else super.apply(msg)
  }

  private val specialBehavior: Receive = {
    case MoveTo(hostname, port) =>
      outerRef.foreach(_.moveTo(hostname, port))

    case MoveGroupTo(hostname, port, nextTo) =>
      outerRef.foreach(_.moveGroupTo(hostname, port, nextTo))

    case PrepareToMigrate =>
      outerRef.foreach(_.prepareToMigrate())
  }

  private def outerRef: Option[MobileActorRef] = self match {
    case inner: InnerReference => inner.outerRef match {
      case null => None
      case ref => Some(ref)
    }
    
    case _ => None
  }

  /**
   * Callbacks
   */
    
  def beforeMigration() {}

  def afterMigration() {}
}
