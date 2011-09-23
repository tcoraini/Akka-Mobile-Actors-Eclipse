package se.scalablesolutions.akka.mobile.actor

import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.actor.ScalaActorRef
import se.scalablesolutions.akka.mobile.util.DefaultLogger
import se.scalablesolutions.akka.mobile.util.Logger

import scala.collection.mutable.SynchronizedQueue

case class HeldMessage(message: Any, sender: Option[ActorRef])

class MessageHolder {
  private val lock = new Object

  private lazy val heldMessages = new SynchronizedQueue[HeldMessage]

  def holdMessage(message: Any, senderOption: Option[ActorRef]): Unit = lock.synchronized {
    heldMessages.enqueue(HeldMessage(message, senderOption))
  }

  def processHeldMessages(p: HeldMessage => Unit): Unit = lock.synchronized {
    heldMessages.foreach(p)
    heldMessages.clear()
  }

  def forwardHeldMessages(to: ActorRef): Unit = lock.synchronized {
    while (!heldMessages.isEmpty) {
      val hm = heldMessages.dequeue()
      //      val logger = new Logger("logs/mobile-actors/" + to.uuid + ".log")
      //      logger.debug("Encaminhando mensagem: %s", hm.message)
      to.!(hm.message)(hm.sender)
    }
  }
}

