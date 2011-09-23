package se.scalablesolutions.akka.mobile.actor

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.actor.ScalaActorRef

import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.stm.TransactionConfig

import se.scalablesolutions.akka.config.FaultHandlingStrategy
import se.scalablesolutions.akka.config.ScalaConfig.LifeCycle

import se.scalablesolutions.akka.mobile.util.DefaultLogger

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.{ Map => JMap }

/**
 * TODO's:
 *   - Add some special implementation of some of the following methods in the inner refs. Example:
 *     - makeRemote() -> maybe send a MoveTo message?
 *     - homeAddress() -> address of the theater that hosts the actor
 *     - remoteAddress()
 *     - etc
 *   First check exaclty where and how those methods are used, so nothing is broken.
 */

trait MethodDelegation extends ActorRef with ScalaActorRef {

  protected var innerRef: InnerReference

  /* 
   * ActorRef
   */

  // Implemented methods from ActorRef that depends on attributes of the class
  // Should then forward the call to the actual reference, with the correct attribute values
  override def setReceiveTimeout(timeout: Long) = innerRef.setReceiveTimeout(timeout)
  override def getReceiveTimeout(): Option[Long] = innerRef.receiveTimeout
  override def setTrapExit(exceptions: Array[Class[_ <: Throwable]]) = innerRef.setTrapExit(exceptions)
  override def getTrapExit(): Array[Class[_ <: Throwable]] = innerRef.getTrapExit
  override def setFaultHandler(handler: FaultHandlingStrategy) = innerRef.setFaultHandler(handler)
  override def getFaultHandler(): Option[FaultHandlingStrategy] = innerRef.getFaultHandler
  override def setLifeCycle(lifeCycle: LifeCycle) = innerRef.setLifeCycle(lifeCycle)
  override def getLifeCycle(): Option[LifeCycle] = innerRef.getLifeCycle
  override def setDispatcher(dispatcher: MessageDispatcher) = innerRef.setDispatcher(dispatcher)
  override def getDispatcher(): MessageDispatcher = innerRef.getDispatcher
  override def compareTo(other: ActorRef) = innerRef.compareTo(other)
  override def getUuid() = innerRef.getUuid
  override def uuid = innerRef.uuid
  override def isBeingRestarted: Boolean = innerRef.isBeingRestarted
  override def isRunning: Boolean = innerRef.isRunning
  override def isShutdown: Boolean = innerRef.isShutdown
  override def isDefinedAt(message: Any): Boolean = innerRef.isDefinedAt(message)
  override def homeAddress: InetSocketAddress = innerRef.homeAddress
  override def mailboxSize = innerRef.mailboxSize

  // These methods are used to get and set the 'id' and 'timeout' fields in the actual reference,
  // since this fields are var's that cannot be overriden
  override def getId = innerRef.id
  override def setId(id: String) = { innerRef.id = id }
  override def getTimeout = innerRef.timeout
  override def setTimeout(timeout: Long) = { innerRef.timeout = timeout }

  // Overrided methods from AnyRef
  // Should be overrided to forward to the reference
  override def hashCode: Int = innerRef.hashCode
  override def equals(that: Any): Boolean = innerRef.equals(that)
  override def toString = innerRef.toString

  // Abstract methods from ActorRef
  // Should only forward the call to the actual reference
  def actorClass: Class[_ <: Actor] = innerRef.actorClass
  def actorClassName: String = innerRef.actorClassName
  def dispatcher_=(md: MessageDispatcher): Unit = innerRef.dispatcher_=(md)
  def dispatcher: MessageDispatcher = innerRef.dispatcher
  def makeRemote(hostname: String, port: Int): Unit = innerRef.makeRemote(hostname, port)
  def makeRemote(address: InetSocketAddress): Unit = innerRef.makeRemote(address)
  def makeTransactionRequired: Unit = innerRef.makeTransactionRequired
  def transactionConfig_=(config: TransactionConfig): Unit = innerRef.transactionConfig_=(config)
  def transactionConfig: TransactionConfig = innerRef.transactionConfig
  def homeAddress_=(address: InetSocketAddress): Unit = innerRef.homeAddress_=(address)
  def remoteAddress: Option[InetSocketAddress] = innerRef.remoteAddress
  def start: ActorRef = innerRef.start
  def stop: Unit = innerRef.stop
  def link(reference: ActorRef): Unit = innerRef.link(reference)
  def unlink(reference: ActorRef): Unit = innerRef.unlink(reference)
  def startLink(reference: ActorRef): Unit = innerRef.startLink(reference)
  def startLinkRemote(reference: ActorRef, hostname: String, port: Int): Unit = innerRef.startLinkRemote(reference, hostname, port)
  def spawn(clazz: Class[_ <: Actor]): ActorRef = innerRef.spawn(clazz)
  def spawnRemote(clazz: Class[_ <: Actor], hostname: String, port: Int): ActorRef = innerRef.spawnRemote(clazz, hostname, port)
  def spawnLink(clazz: Class[_ <: Actor]): ActorRef = innerRef.spawnLink(clazz)
  def spawnLinkRemote(clazz: Class[_ <: Actor], hostname: String, port: Int): ActorRef = innerRef.spawnLinkRemote(clazz, hostname, port)
  def supervisor: Option[ActorRef] = innerRef.supervisor

  // Protected methdos from ActorRef
  // These SHOULD NOT be invoked in a MobileActorRef, because there is no way we can forward the call
  // to the actual reference (unless this class goes on the 'akka' package).
  // TODO nao vou deixar no pacote Akka mesmo? nao eh melhor chamar os metodos protected tb?
  def remoteAddress_=(addr: Option[InetSocketAddress]): Unit = unsupported
  def invoke(messageHandle: MessageInvocation): Unit = unsupported
  def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit = unsupported
  def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
    message: Any,
    timeout: Long,
    senderOption: Option[ActorRef],
    senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T] = unsupported
  def actorInstance: AtomicReference[Actor] = unsupported
  def supervisor_=(sup: Option[ActorRef]): Unit = unsupported
  def mailbox: AnyRef = unsupported
  def mailbox_=(value: AnyRef): AnyRef = unsupported
  def handleTrapExit(dead: ActorRef, reason: Throwable): Unit = unsupported
  def restart(reason: Throwable, maxNrOfRetries: Int, withinTimeRange: Int): Unit = unsupported
  def restartLinkedActors(reason: Throwable, maxNrOfRetries: Int, withinTimeRange: Int): Unit = unsupported
  def registerSupervisorAsRemoteActor: Option[String] = unsupported
  def linkedActors: JMap[String, ActorRef] = unsupported
  def linkedActorsAsList: List[ActorRef] = unsupported

  private def unsupported = throw new UnsupportedOperationException("This method cannot be invoked on a MobileActorRef.")

  /* 
   * ActorRefShared
   */

  // Abstract method from ActorRefShared
  def shutdownLinkedActors: Unit = innerRef.shutdownLinkedActors

  /*
   * ScalaActorRef
   */

  // Implemented methods from ScalaActorRef
  // Should forward to the actual reference
  override def sender: Option[ActorRef] = innerRef.sender
  override def senderFuture(): Option[CompletableFuture[Any]] = innerRef.senderFuture

  override def !(message: Any)(implicit sender: Option[ActorRef] = None): Unit = innerRef._lock.synchronized {
    innerRef.!(message)(sender)
  }
  override def !!(message: Any, timeout: Long = this.timeout)(implicit sender: Option[ActorRef] = None): Option[Any] =
    innerRef.!!(message, timeout)(sender)
  override def !!![T](message: Any, timeout: Long = this.timeout)(implicit sender: Option[ActorRef] = None): Future[T] =
    innerRef.!!!(message, timeout)(sender)
  override def forward(message: Any)(implicit sender: Some[ActorRef]) =
    innerRef.forward(message)(sender)

}
