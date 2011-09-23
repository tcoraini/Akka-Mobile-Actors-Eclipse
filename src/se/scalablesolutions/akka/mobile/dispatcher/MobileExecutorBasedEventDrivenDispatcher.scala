package se.scalablesolutions.akka.mobile.dispatcher

import java.util.Deque
import java.util.concurrent.LinkedBlockingDeque

import se.scalablesolutions.akka.actor.{ ActorRef, IllegalActorStateException }
import se.scalablesolutions.akka.dispatch.{ MessageInvocation, Dispatchers, ThreadPoolBuilder, MessageDispatcher }

import se.scalablesolutions.akka.mobile.actor.LocalMobileActor
import se.scalablesolutions.akka.mobile.util.messages._

class MobileExecutorBasedEventDrivenDispatcher(
  _name: String,
  throughput: Int = Dispatchers.THROUGHPUT,
  capacity: Int = Dispatchers.MAILBOX_CAPACITY) extends MobileMessageDispatcher with ThreadPoolBuilder {

  mailboxCapacity = capacity

  @volatile private var active: Boolean = false

  val name = "mobile:event-driven:dispatcher:" + _name
  init

  def dispatch(invocation: MessageInvocation) = dispatch(invocation, false)
  // invocation.message match {
  //   case moveTo: MoveTo => dispatch(invocation, true)

  //   case _ => dispatch(invocation, false)
  //}

  def dispatchWithPriority(invocation: MessageInvocation) = dispatch(invocation, true)

  private def dispatch(invocation: MessageInvocation, priority: Boolean): Unit = invocation.receiver match {
    case receiver: LocalMobileActor => {
      if (priority)
        getMailbox(receiver).addFirst(invocation)
      else
        getMailbox(receiver).add(invocation)

      dispatch(receiver)
    }

    case _ => throw new RuntimeException("This dispatcher can only dispatch messages for mobile actors")
  }

  /**
   * @return the mailbox associated with the actor
   */
  private def getMailbox(receiver: ActorRef) = receiver.mailbox.asInstanceOf[Deque[MessageInvocation]]

  override def mailboxSize(actorRef: ActorRef) = getMailbox(actorRef).size

  override def register(actorRef: ActorRef) = {
    if (actorRef.mailbox eq null) {
      if (mailboxCapacity <= 0) actorRef.mailbox = new LinkedBlockingDeque[MessageInvocation]
      else actorRef.mailbox = new LinkedBlockingDeque[MessageInvocation](mailboxCapacity)
    }
    super.register(actorRef)
  }

  def dispatch(receiver: LocalMobileActor): Unit = if (active) {
    executor.execute(new Runnable() {
      def run = {
        var lockAcquiredOnce = false
        var finishedBeforeMailboxEmpty = false
        val lock = receiver.dispatcherLock
        val mailbox = getMailbox(receiver)
        // this do-while loop is required to prevent missing new messages between the end of the inner while
        // loop and releasing the lock
        do {
          if (lock.tryLock) {
            // Only dispatch if we got the lock. Otherwise another thread is already dispatching.
            lockAcquiredOnce = true
            try {
              finishedBeforeMailboxEmpty = processMailbox(receiver)
            } finally {
              lock.unlock
              if (finishedBeforeMailboxEmpty) dispatch(receiver)
            }
          }
        } while ((lockAcquiredOnce && !finishedBeforeMailboxEmpty && !mailbox.isEmpty && !receiver.isMigrating))
      }
    })
  } else {
    log.warning("%s is shut down,\n\tignoring the rest of the messages in the mailbox of\n\t%s", toString, receiver)
  }

  /**
   * Process the messages in the mailbox of the given actor.
   *
   * @return true if the processing finished before the mailbox was empty, due to the throughput constraint
   */
  def processMailbox(receiver: LocalMobileActor): Boolean = {
    var processedMessages = 0
    val mailbox = getMailbox(receiver)

    // Don't dispatch if the actor is migrating, the messages should stay in the mailbox to be
    // serialized
    if (receiver.isMigrating) return false

    var messageInvocation = mailbox.poll
    while (messageInvocation != null) {
      messageInvocation.invoke
      processedMessages += 1
      // if the actor is migrating (which means the last message processed was a MoveTo message),
      // we stop processing the messages
      if (receiver.isMigrating) return false
      // check if we simply continue with other messages, or reached the throughput limit
      if (throughput <= 0 || processedMessages < throughput) messageInvocation = mailbox.poll
      else {
        messageInvocation = null
        return !mailbox.isEmpty
      }
    }
    false
  }

  def start = if (!active) {
    log.debug("Starting up %s\n\twith throughput [%d]", toString, throughput)
    active = true
  }

  def shutdown = if (active) {
    log.debug("Shutting down %s", toString)
    executor.shutdownNow
    active = false
    references.clear
  }

  def ensureNotActive(): Unit = if (active) throw new IllegalActorStateException(
    "Can't build a new thread pool for a dispatcher that is already up and running")

  override def toString = "MobileExecutorBasedEventDrivenDispatcher[" + name + "]"

  private[mobile] def init = withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity.buildThreadPool
}
