package se.scalablesolutions.akka.mobile.actor

import se.scalablesolutions.akka.mobile.dispatcher.MobileDispatchers

import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.actor.ScalaActorRef
import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.Format
import se.scalablesolutions.akka.actor.ActorSerialization
import se.scalablesolutions.akka.mobile.util.DefaultLogger
import se.scalablesolutions.akka.mobile.util.Logger

import se.scalablesolutions.akka.dispatch.MessageInvocation
import se.scalablesolutions.akka.dispatch.MessageDispatcher
import se.scalablesolutions.akka.dispatch.CompletableFuture
import se.scalablesolutions.akka.dispatch.DefaultCompletableFuture
import se.scalablesolutions.akka.dispatch.ThreadBasedDispatcher
import se.scalablesolutions.akka.dispatch.ExecutorBasedEventDrivenDispatcher
import se.scalablesolutions.akka.dispatch.ExecutorBasedEventDrivenWorkStealingDispatcher

import se.scalablesolutions.akka.stm.TransactionManagement._

import se.scalablesolutions.akka.mobile.theater.LocalTheater
import se.scalablesolutions.akka.mobile.dispatcher.MobileMessageDispatcher
import se.scalablesolutions.akka.mobile.theater.GroupManagement
import se.scalablesolutions.akka.mobile.util.messages._

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * TODO's:
 *   - !! and !!! methods
 */

trait LocalMobileActor extends InnerReference {

  // Check some conditions that must hold for the proper instantiation of the actor
  checkConditions()

  abstract override protected[akka] def actor: MobileActor = super.actor.asInstanceOf[MobileActor]

  abstract override def dispatcher: MobileMessageDispatcher = super.dispatcher.asInstanceOf[MobileMessageDispatcher]

  abstract override def dispatcher_=(md: MessageDispatcher): Unit = md match {
    case mmd: MobileMessageDispatcher => super.dispatcher_=(mmd)

    case _ => throw new RuntimeException("A mobile actor must have a MobileMessageDispatcher instance as its dispatcher.")
  }

  abstract override def start(): ActorRef = {
    // Needed during deserialization
    ensureDispatcherIsMobile()
    super.start()
  }

  // Don't unregister from Name Service, because actor is actually still running (but in
  // a different theater)
  override def stopLocal(): Unit = {
    LocalTheater.unregister(outerRef, true)
    super.stop()
  }

  abstract override def stop(): Unit = {
    LocalTheater.unregister(outerRef, false)
    super.stop()
  }

  abstract override def !(message: Any)(implicit sender: Option[ActorRef] = None): Unit = {
    // All messages received (local and remote) are registered
    val profiler = LocalTheater.profiler
    val msg = message match {
      // Message from remote actor received and forwarded by local theater
      case remoteMsg: MobileActorMessage =>
        profiler.foreach(_.remoteMessageArrived(uuid, remoteMsg))
        remoteMsg.message

      case localMsg =>
        profiler.foreach(_.localMessageArrived(uuid))
        localMsg
    }
    super.!(msg)
  }

  override def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit = {
    if (isMigrating) {
      //      val logger = new Logger("logs/mobile-actors/" + uuid + ".log")
      holder.holdMessage(message, senderOption)
      //      logger.debug("Holding message: %s", message)
    } else {
      //super.postMessageToMailbox(message, senderOption)
      val invocation = new MessageInvocation(this, message, senderOption, None, transactionSet.get)
      if (hasPriority(message))
        dispatcher.asInstanceOf[MobileMessageDispatcher].dispatchWithPriority(invocation)
      else
        invocation.send
    }
  }

  /**
   * These methods will be called by the external reference (MobileActorRef)
   */
  // To be called in the origin theater
  protected[actor] def beforeMigration(): Unit = actor.beforeMigration()

  // To be called in the destination theater
  protected[actor] def afterMigration(): Unit = actor.afterMigration()

  // To be called in the origin theater, after the migration is complete
  protected[actor] def completeMigration(newActor: ActorRef): Unit = holder.forwardHeldMessages(newActor)

  /**
   * InnerReference methods implementation
   */
  override def groupId: Option[String] = actor.groupId

  override protected[mobile] def groupId_=(id: Option[String]) {
    // Removes this actor from the old group id, if it is not None
    groupId.foreach(GroupManagement.remove(outerRef, _))
    // Inserts this actor in the new group id, if it is not None
    id.foreach(GroupManagement.insert(outerRef, _))
    actor.groupId = id
  }

  protected[actor] def isLocal = true

  protected[actor] def node = LocalTheater.node

  /**
   * Private methods
   */
  private def checkConditions(): Unit = {
    if (!actor.isInstanceOf[MobileActor]) {
      throw new RuntimeException("MobileActorRef should be used only with a MobileActor instance.")
    }
  }

  // When a mobile actor is started, we have to ensure it is running with a proper mobile
  // dispatcher.
  private def ensureDispatcherIsMobile(): Unit = super.dispatcher match {
    case mmd: MobileMessageDispatcher => ()

    case _ => dispatcher = MobileDispatchers.globalMobileExecutorBasedEventDrivenDispatcher
  }

  private def hasPriority(message: Any): Boolean = message.isInstanceOf[MigrationMessage]

}
