package se.scalablesolutions.akka.mobile.theater.protocol

import se.scalablesolutions.akka.mobile.theater.Theater
import se.scalablesolutions.akka.mobile.theater.TheaterNode

import se.scalablesolutions.akka.mobile.theater.protocol.protobuf.ProtobufTheaterMessages._
import se.scalablesolutions.akka.mobile.theater.protocol.protobuf.ProtobufTheaterMessages.{TheaterMessageType => MessageType}
import se.scalablesolutions.akka.mobile.util.messages._

import com.google.protobuf.ByteString
import com.google.protobuf.Message

import collection.JavaConversions._

abstract class ProtobufProtocol extends TheaterProtocol {
  
  /**
   * Abstract method, should be overriden by the subclasses.
   * It sends a message in the protobuf format to some node.
   */
  def sendTo(node: TheaterNode, message: TheaterMessageProtocol)
  
  /**
   * Constructs a Protobuf message based on the respective case class message.
   */
  def sendTo(node: TheaterNode, message: TheaterMessage): Unit = {

    // Constructs a protobuf message (of the types defined in TheaterProtocol.proto) based
    // on the TheaterMessage received
    val protobufMessage: Message = message match {

      case MovingActor(bytes) =>
        MovingActorProtocol.newBuilder
            .setActorBytes(ByteString.copyFrom(bytes))
            .build
      
      case MovingGroup(actorsBytes, nextTo) =>
	
	val builder = MovingGroupProtocol.newBuilder
	    .addAllActorsBytes(actorsBytes.map(bytes => ByteString.copyFrom(bytes)).toList)
	nextTo.foreach(uuidStr => builder.setNextTo(uuidStr))
	builder.build

      case MobileActorsRegistered(uuids: Array[String]) =>
        MobileActorsRegisteredProtocol.newBuilder
            .addAllUuids(uuids.toList)
            .build

      case StartMobileActorRequest(requestId, className) => 
        StartActorRequestProtocol.newBuilder
            .setRequestId(requestId)
	    .setClassName(className)
	    .build

      case StartMobileActorReply(requestId, uuid) =>
        StartActorReplyProtocol.newBuilder
            .setRequestId(requestId)
            .setActorUuid(uuid)
            .build
		       
      case StartColocatedActorsRequest(requestId, className, number, nextTo) =>
	val builder = StartColocatedActorsRequestProtocol.newBuilder
	    .setRequestId(requestId)
	    .setClassName(className)
            .setNumber(number)
	nextTo.foreach(uuidStr => builder.setNextTo(uuidStr))
        builder.build

      case StartColocatedActorsReply(requestId, uuids) =>
        StartColocatedActorsReplyProtocol.newBuilder
	    .setRequestId(requestId)
	    .addAllUuids(uuids.toList)
            .build

      case ActorNewLocationNotification(uuid, hostname, port) =>
        ActorNewLocationNotificationProtocol.newBuilder
            .setUuid(uuid)
            .setHostname(hostname)
            .setPort(port)
            .build
    }
    
    // Wraps the message in a TheaterMessageProtocol kind of message, and sends it
    // to node.
    sendTo(node, constructProtobufMessage(protobufMessage))
  } 

  /**
   * Wraps a protobuf message (constructed in the sendTo method above) in a TheaterMessageProtocol
   * message, to be send over the wire to other theater.
   */
  private def constructProtobufMessage(message: Message): TheaterMessageProtocol = {
    val sender = TheaterNodeProtocol.newBuilder
        .setHostname(theater.node.hostname)
        .setPort(theater.node.port)
        .build
    val builder = TheaterMessageProtocol.newBuilder
        .setSender(sender)

    message match {
      case msg: MovingActorProtocol =>
        builder
          .setMessageType(MessageType.MOVING_ACTOR)
          .setMovingActor(msg)
          .build

      case msg: MovingGroupProtocol =>
	builder
	  .setMessageType(MessageType.MOVING_GROUP)
	  .setMovingGroup(msg)
	  .build

      case msg: MobileActorsRegisteredProtocol =>
        builder
          .setMessageType(MessageType.MOBILE_ACTORS_REGISTERED)
          .setMobileActorsRegistered(msg)
          .build

      case msg: StartActorRequestProtocol =>
        builder
          .setMessageType(MessageType.START_ACTOR_REQUEST)
          .setStartActorRequest(msg)
          .build

      case msg: StartActorReplyProtocol =>
        builder
          .setMessageType(MessageType.START_ACTOR_REPLY)
          .setStartActorReply(msg)
          .build

      case msg: StartColocatedActorsRequestProtocol =>
	builder
	  .setMessageType(MessageType.START_COLOCATED_ACTORS_REQUEST)
	  .setStartColocatedActorsRequest(msg)
	  .build

      case msg: StartColocatedActorsReplyProtocol =>
	builder
	  .setMessageType(MessageType.START_COLOCATED_ACTORS_REPLY)
	  .setStartColocatedActorsReply(msg)
	  .build

      case msg: ActorNewLocationNotificationProtocol =>
        builder
          .setMessageType(MessageType.ACTOR_NEW_LOCATION_NOTIFICATION)
          .setActorNewLocationNotification(msg)
          .build
    }
  }
  
  /**
   * Processes a message received from some node, in the protobuf format. It transforms
   * that message in an instance of some TheaterMessage subclass, and passes this to
   * the theater.
   */
  def processMessage(message: TheaterMessageProtocol): Unit = {
    import TheaterMessageType._
    
    val theaterMessage = message.getMessageType match {
      case MOVING_ACTOR =>
        val bytes = message.getMovingActor.getActorBytes.toByteArray
        MovingActor(bytes)

      case MOVING_GROUP =>
	val movingGroup = message.getMovingGroup
	val listOfByteString = movingGroup.getActorsBytesList
	var arrayOfByteString = new Array[ByteString](listOfByteString.size)
	listOfByteString.toArray(arrayOfByteString)
	
	val nextTo: Option[String] = 
	  if (movingGroup.hasNextTo) Some(movingGroup.getNextTo)
	  else None
      
	MovingGroup(arrayOfByteString.map(bs => bs.toByteArray), nextTo)

      case MOBILE_ACTORS_REGISTERED =>
	val uuids = message.getMobileActorsRegistered.getUuidsList
	var uuidsArray = new Array[String](uuids.size)
	uuids.toArray(uuidsArray)
        MobileActorsRegistered(uuidsArray)

      case START_ACTOR_REQUEST =>
        val request = message.getStartActorRequest
	StartMobileActorRequest(request.getRequestId, request.getClassName)

      case START_ACTOR_REPLY =>
        val reply = message.getStartActorReply
        StartMobileActorReply(reply.getRequestId, reply.getActorUuid)

      case START_COLOCATED_ACTORS_REQUEST =>
	val request = message.getStartColocatedActorsRequest
	val nextTo = 
	  if (request.hasNextTo) Some(request.getNextTo)
	  else None
	
	StartColocatedActorsRequest(
	  request.getRequestId, request.getClassName, request.getNumber, nextTo)
      
      case START_COLOCATED_ACTORS_REPLY =>
	val reply = message.getStartColocatedActorsReply
	val uuids = reply.getUuidsList
	var uuidsArray = new Array[String](uuids.size)
	uuids.toArray(uuidsArray)
	StartColocatedActorsReply(reply.getRequestId, uuidsArray)

      case ACTOR_NEW_LOCATION_NOTIFICATION =>
        val notification = message.getActorNewLocationNotification
        ActorNewLocationNotification(notification.getUuid, notification.getHostname, notification.getPort)
    }
    theaterMessage.sender = TheaterNode(message.getSender.getHostname, message.getSender.getPort)
    theater.processMessage(theaterMessage)
  }
}
