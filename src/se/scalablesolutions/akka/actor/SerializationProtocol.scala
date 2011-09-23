/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.config.{AllForOneStrategy, OneForOneStrategy, FaultHandlingStrategy}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.stm.global._
import se.scalablesolutions.akka.stm.TransactionManagement._
import se.scalablesolutions.akka.stm.TransactionManagement
import se.scalablesolutions.akka.dispatch.MessageInvocation
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._
import se.scalablesolutions.akka.remote.{RemoteServer, RemoteRequestProtocolIdFactory, MessageSerializer}
import se.scalablesolutions.akka.serialization.Serializer

import com.google.protobuf.ByteString

/**
 * Type class definition for Actor Serialization
 */
trait FromBinary[T <: Actor] {
  def fromBinary(bytes: Array[Byte], act: T): T
}

trait ToBinary[T <: Actor] {
  def toBinary(t: T): Array[Byte]
}

// client needs to implement Format[] for the respective actor
trait Format[T <: Actor] extends FromBinary[T] with ToBinary[T]

/**
 * A default implementation for a stateless actor
 *
 * Create a Format object with the client actor as the implementation of the type class
 *
 * <pre>
 * object BinaryFormatMyStatelessActor {
 *   implicit object MyStatelessActorFormat extends StatelessActorFormat[MyStatelessActor]
 * }
 * </pre>
 */
trait StatelessActorFormat[T <: Actor] extends Format[T] {
  def fromBinary(bytes: Array[Byte], act: T) = act
  def toBinary(ac: T) = Array.empty[Byte]
}

/**
 * A default implementation of the type class for a Format that specifies a serializer
 *
 * Create a Format object with the client actor as the implementation of the type class and
 * a serializer object
 *
 * <pre>
 * object BinaryFormatMyJavaSerializableActor {
 *   implicit object MyJavaSerializableActorFormat extends SerializerBasedActorFormat[MyJavaSerializableActor] {
 *     val serializer = Serializer.Java
 *   }
 * }
 * </pre>
 */
trait SerializerBasedActorFormat[T <: Actor] extends Format[T] {
  val serializer: Serializer
  def fromBinary(bytes: Array[Byte], act: T) = serializer.fromBinary(bytes, Some(act.self.actorClass)).asInstanceOf[T]
  def toBinary(ac: T) = serializer.toBinary(ac)
}

/**
 * Module for local actor serialization
 */
object ActorSerialization {

  def fromBinary[T <: Actor](bytes: Array[Byte])(implicit format: Format[T]): ActorRef =
    fromBinaryToLocalActorRef(bytes, format)

  //def toBinary[T <: Actor](a: ActorRef)(implicit format: Format[T]): Array[Byte] =
  //  toSerializedActorRefProtocol(a, format).toByteArray
  
  def toBinary[T <: Actor](a: ActorRef, serializeMailBox: Boolean = true)(implicit format: Format[T]): Array[Byte] =
    toSerializedActorRefProtocol(a, format, serializeMailBox).toByteArray

  // wrapper for implicits to be used by Java
  def fromBinaryJ[T <: Actor](bytes: Array[Byte], format: Format[T]): ActorRef =
    fromBinary(bytes)(format)

  // wrapper for implicits to be used by Java
  //def toBinaryJ[T <: Actor](a: ActorRef, format: Format[T]): Array[Byte] =
  //  toBinary(a)(format)

  def toBinaryJ[T <: Actor](a: ActorRef, format: Format[T], srlMailBox: Boolean = true): Array[Byte] =
    toBinary(a, srlMailBox)(format) 

  private def toSerializedActorRefProtocol[T <: Actor](
    actorRef: ActorRef, format: Format[T], serializeMailBox: Boolean = true): SerializedActorRefProtocol = {
    //actorRef: ActorRef, format: Format[T]): SerializedActorRefProtocol = {
    val lifeCycleProtocol: Option[LifeCycleProtocol] = {
      def setScope(builder: LifeCycleProtocol.Builder, scope: Scope) = scope match {
        case Permanent => builder.setLifeCycle(LifeCycleType.PERMANENT)
        case Temporary => builder.setLifeCycle(LifeCycleType.TEMPORARY)
      }
      val builder = LifeCycleProtocol.newBuilder
      actorRef.lifeCycle match {
        case Some(LifeCycle(scope)) =>
          setScope(builder, scope)
          Some(builder.build)
        case None => None
      }
    }

    val originalAddress = AddressProtocol.newBuilder
      .setHostname(actorRef.homeAddress.getHostName)
      .setPort(actorRef.homeAddress.getPort)
      .build

    val builder = SerializedActorRefProtocol.newBuilder
      .setUuid(actorRef.uuid)
      .setId(actorRef.id)
      .setActorClassname(actorRef.actorClass.getName)
      .setOriginalAddress(originalAddress)
      .setIsTransactor(actorRef.isTransactor)
      .setTimeout(actorRef.timeout)

    if (serializeMailBox == true) {
      val messages = 
        actorRef.mailbox match {
          case q: java.util.Queue[MessageInvocation] =>
            val l = new scala.collection.mutable.ListBuffer[MessageInvocation]
            val it = q.iterator
            while (it.hasNext == true) l += it.next
            l
        }
      
      val requestProtocols = 
        messages.map(m =>
          RemoteActorSerialization.createRemoteRequestProtocolBuilder(
            actorRef,
            m.message,
            false,
            actorRef.getSender).build)
  
      requestProtocols.foreach(rp => builder.addMessages(rp))
    }

    actorRef.receiveTimeout.foreach(builder.setReceiveTimeout(_))
    builder.setActorInstance(ByteString.copyFrom(format.toBinary(actorRef.actor.asInstanceOf[T])))
    lifeCycleProtocol.foreach(builder.setLifeCycle(_))
    actorRef.supervisor.foreach(s => builder.setSupervisor(RemoteActorSerialization.toRemoteActorRefProtocol(s)))
    // FIXME: how to serialize the hotswap PartialFunction ??
    //hotswap.foreach(builder.setHotswapStack(_))
    builder.build
  }

  private def fromBinaryToLocalActorRef[T <: Actor](bytes: Array[Byte], format: Format[T]): ActorRef =
    fromProtobufToLocalActorRef(SerializedActorRefProtocol.newBuilder.mergeFrom(bytes).build, format, None)

  private def fromProtobufToLocalActorRef[T <: Actor](
    protocol: SerializedActorRefProtocol, format: Format[T], loader: Option[ClassLoader]): ActorRef = {
    Actor.log.debug("Deserializing SerializedActorRefProtocol to LocalActorRef:\n" + protocol)

    val serializer =
      if (format.isInstanceOf[SerializerBasedActorFormat[_]])
        Some(format.asInstanceOf[SerializerBasedActorFormat[_]].serializer)
      else None

    val lifeCycle =
      if (protocol.hasLifeCycle) {
        val lifeCycleProtocol = protocol.getLifeCycle
        Some(if (lifeCycleProtocol.getLifeCycle == LifeCycleType.PERMANENT) LifeCycle(Permanent)
             else if (lifeCycleProtocol.getLifeCycle == LifeCycleType.TEMPORARY) LifeCycle(Temporary)
             else throw new IllegalActorStateException("LifeCycle type is not valid: " + lifeCycleProtocol.getLifeCycle))
      } else None

    val supervisor =
      if (protocol.hasSupervisor)
        Some(RemoteActorSerialization.fromProtobufToRemoteActorRef(protocol.getSupervisor, loader))
      else None

    val hotswap =
      if (serializer.isDefined && protocol.hasHotswapStack) Some(serializer.get
        .fromBinary(protocol.getHotswapStack.toByteArray, Some(classOf[PartialFunction[Any, Unit]]))
        .asInstanceOf[PartialFunction[Any, Unit]])
      else None

    val ar = new LocalActorRef(
      protocol.getUuid,
      protocol.getId,
      protocol.getActorClassname,
      protocol.getActorInstance.toByteArray,
      protocol.getOriginalAddress.getHostname,
      protocol.getOriginalAddress.getPort,
      if (protocol.hasIsTransactor) protocol.getIsTransactor else false,
      if (protocol.hasTimeout) protocol.getTimeout else Actor.TIMEOUT,
      if (protocol.hasReceiveTimeout) Some(protocol.getReceiveTimeout) else None,
      lifeCycle,
      supervisor,
      hotswap,
      loader.getOrElse(getClass.getClassLoader), // TODO: should we fall back to getClass.getClassLoader?
      protocol.getMessagesList.toArray.toList.asInstanceOf[List[RemoteRequestProtocol]], 
      format)

    if (format.isInstanceOf[SerializerBasedActorFormat[_]] == false)
      format.fromBinary(protocol.getActorInstance.toByteArray, ar.actor.asInstanceOf[T])
    ar
  }
}

object RemoteActorSerialization {
  /**
   * Deserializes a byte array (Array[Byte]) into an RemoteActorRef instance.
   */
  def fromBinaryToRemoteActorRef(bytes: Array[Byte]): ActorRef =
    fromProtobufToRemoteActorRef(RemoteActorRefProtocol.newBuilder.mergeFrom(bytes).build, None)

    /**
     * Deserializes a byte array (Array[Byte]) into an RemoteActorRef instance.
     */
  def fromBinaryToRemoteActorRef(bytes: Array[Byte], loader: ClassLoader): ActorRef =
    fromProtobufToRemoteActorRef(RemoteActorRefProtocol.newBuilder.mergeFrom(bytes).build, Some(loader))

  /**
   * Deserializes a RemoteActorRefProtocol Protocol Buffers (protobuf) Message into an RemoteActorRef instance.
   */
  private[akka] def fromProtobufToRemoteActorRef(protocol: RemoteActorRefProtocol, loader: Option[ClassLoader]): ActorRef = {
    Actor.log.debug("Deserializing RemoteActorRefProtocol to RemoteActorRef:\n" + protocol)
    RemoteActorRef(
      protocol.getUuid,
      protocol.getActorClassname,
      protocol.getHomeAddress.getHostname,
      protocol.getHomeAddress.getPort,
      protocol.getTimeout,
      loader)
  }

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteActorRefProtocol(ar: ActorRef): RemoteActorRefProtocol = {
    import ar._
    val host = homeAddress.getHostName
    val port = homeAddress.getPort

    if (!registeredInRemoteNodeDuringSerialization) {
      Actor.log.debug("Register serialized Actor [%s] as remote @ [%s:%s]", actorClass.getName, host, port)
      RemoteServer.getOrCreateServer(homeAddress)
      RemoteServer.registerActor(homeAddress, uuid, ar)
      registeredInRemoteNodeDuringSerialization = true
    }

    RemoteActorRefProtocol.newBuilder
      .setUuid(uuid)
      .setActorClassname(actorClass.getName)
      .setHomeAddress(AddressProtocol.newBuilder.setHostname(host).setPort(port).build)
      .setTimeout(timeout)
      .build
  }

  def createRemoteRequestProtocolBuilder(actorRef: ActorRef, message: Any, isOneWay: Boolean, senderOption: Option[ActorRef]):
    RemoteRequestProtocol.Builder = {
    import actorRef._

    val actorInfo = ActorInfoProtocol.newBuilder
        .setUuid(uuid)
        .setTarget(actorClassName)
        .setTimeout(timeout)
        .setActorType(ActorType.SCALA_ACTOR)
        .build

    val request = RemoteRequestProtocol.newBuilder
        .setId(RemoteRequestProtocolIdFactory.nextId)
        .setMessage(MessageSerializer.serialize(message))
        .setActorInfo(actorInfo)
        .setIsOneWay(isOneWay)

    val id = registerSupervisorAsRemoteActor
    if (id.isDefined) request.setSupervisorUuid(id.get)

    senderOption.foreach { sender =>
      RemoteServer.getOrCreateServer(sender.homeAddress).register(sender.uuid, sender)
      request.setSender(toRemoteActorRefProtocol(sender))
    }
    request
  }
}
