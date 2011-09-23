package se.scalablesolutions.akka.mobile.actor

import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.mobile.theater.TheaterNode

case class AttachRefToActor(node: TheaterNode)

/**
 * This trait is supposed to be used with a remote internal reference in the following way:
 *   val remoteRef = new RemoteActorRef with RemoteMobileActor with DetachedActor
 *                             ^                    ^
 *                        Akka actors         Mobile actors
 *
 * When this is used, the reference is said to be "detached", which means it doesn't have the UUID
 * of the actors it represents yet. The reference contains only the actor's address (hostname and port),
 * and a temporary ID. Until this reference is 'attached' to an actor, it will hold the messages it receives
 * locally, using the MessageHolder class functionality.
 *
 * When the reference receives an AttachRefToActor(uuid) messages, it attaches this references to the actor
 * with UUID == 'uuid' in the address it already has. Then, it forwards all held messages to this actor, and
 * starts to behave like a usual remote internal reference.
 */
trait DetachedActor extends LocalMobileActor {

  override def isMigrating = true

  abstract override def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit = message match {
    case AttachRefToActor(node) =>
      outerRef.completeMigration(node)
    
    case _ => super.postMessageToMailbox(message, senderOption)
  }
}
  
