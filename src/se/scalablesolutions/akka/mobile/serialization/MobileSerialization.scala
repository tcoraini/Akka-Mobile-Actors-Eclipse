package se.scalablesolutions.akka.mobile.serialization

import se.scalablesolutions.akka.mobile.actor.MobileActorRef
import se.scalablesolutions.akka.mobile.actor.LocalMobileActor

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.LocalActorRef
import se.scalablesolutions.akka.actor.Format
import se.scalablesolutions.akka.actor.SerializerBasedActorFormat
import se.scalablesolutions.akka.actor.IllegalActorStateException
import se.scalablesolutions.akka.actor.RemoteActorSerialization

import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._
import se.scalablesolutions.akka.config.ScalaConfig._

object MobileSerialization {
  
  /* Adapted from SerializationProtocol just to construct a MobileActorRef */
  def mobileFromBinary[T <: Actor](bytes: Array[Byte])(implicit format: Format[T]): MobileActorRef =
    MobileActorRef(fromBinaryToMobileActorRef(bytes, format))

  private def fromBinaryToMobileActorRef[T <: Actor](bytes: Array[Byte], format: Format[T]): LocalMobileActor =
    fromProtobufToMobileActorRef(SerializedActorRefProtocol.newBuilder.mergeFrom(bytes).build, format, None)

  private def fromProtobufToMobileActorRef[T <: Actor](
    protocol: SerializedActorRefProtocol, format: Format[T], loader: Option[ClassLoader]): LocalMobileActor = {
    Actor.log.debug("Deserializing SerializedActorRefProtocol to MobileActorRef:\n" + protocol)

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
      format) with LocalMobileActor

    if (format.isInstanceOf[SerializerBasedActorFormat[_]] == false)
      format.fromBinary(protocol.getActorInstance.toByteArray, ar.actor.asInstanceOf[T])
    ar
  }
}
