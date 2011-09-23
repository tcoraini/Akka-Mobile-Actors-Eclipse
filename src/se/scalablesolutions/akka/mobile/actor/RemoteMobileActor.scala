package se.scalablesolutions.akka.mobile.actor

import se.scalablesolutions.akka.mobile.theater.LocalTheater
import se.scalablesolutions.akka.mobile.theater.TheaterNode
import se.scalablesolutions.akka.mobile.theater.ReferenceManagement
import se.scalablesolutions.akka.mobile.util.messages._
import se.scalablesolutions.akka.mobile.util.DefaultLogger
import se.scalablesolutions.akka.mobile.util.Logger
import se.scalablesolutions.akka.mobile.nameservice.NameService

import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.actor.ScalaActorRef
import se.scalablesolutions.akka.actor.RemoteActorRef
import se.scalablesolutions.akka.actor.RemoteActorSerialization._

import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._
import se.scalablesolutions.akka.remote.RemoteClientLifeCycleEvent
import se.scalablesolutions.akka.remote.RemoteClientDisconnected

import java.nio.channels.ClosedChannelException

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.Actor._

trait RemoteMobileActor extends InnerReference {
  /*
   * Só funciona pq estamos dentro do pacote Akka. Quando não for o caso, como resolver?
   *
   * 1 - Deixar o mínimo de código necessário para a aplicação dentro do pacote Akka
   * 2 - Reescrever a classe RemoteActorRef toda
   */
  remoteActorRef: RemoteActorRef =>

  override def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit = {
    val newMessage = MobileActorMessage(LocalTheater.node.hostname, LocalTheater.node.port, message)
    val senderIsMobile = senderOption.isDefined && senderOption.get.isInstanceOf[InnerReference]

    // If the sender is a mobile actor, we encode this information (the sender reference) in
    // a different way. Basically, mobile actors can be found only with its UUID, via the
    // name service
    val requestBuilder =
      if (senderIsMobile)
        createRemoteRequestProtocolBuilder(this, newMessage, true, None)
      else
        createRemoteRequestProtocolBuilder(this, newMessage, true, senderOption)

    // Special actor type (MOBILE_ACTOR) so the destination remote server recognizes the
    // message recipient as a mobile actor
    val actorInfo = requestBuilder.getActorInfo.toBuilder
    actorInfo.setActorType(ActorType.MOBILE_ACTOR)
    requestBuilder.setActorInfo(actorInfo.build)

    // All we really need here is the UUID (but the other fields are set as 'required' in 
    // the .proto file
    if (senderIsMobile) {
      val senderRef = senderOption.get.asInstanceOf[InnerReference]
      val senderBuilder = RemoteActorRefProtocol.newBuilder
        .setUuid(senderRef.uuid)
        .setActorClassname(senderRef.actorClass.getName)
        .setHomeAddress(AddressProtocol.newBuilder.setHostname(senderRef.node.hostname).setPort(senderRef.node.port).build)
        .build
      requestBuilder.setSender(senderBuilder)
    }

    val logger = new Logger("logs/mobile-actors/" + uuid + ".log")
    logger.debug("[UUID %s] Mensagem passando por proxy, enviando para [%s:%s]: %s",
      uuid, remoteActorRef.hostname, remoteActorRef.port, newMessage)
    remoteActorRef.remoteClient.send[Any](requestBuilder.build, None)
  }

  abstract override def start: ActorRef = {
    ReferenceManagement.registerForRemoteClientEvents(this, remoteActorRef.remoteClient)
    super.start
  }

  abstract override def stop: Unit = {
    _isRunning = false
    _isShutDown = true
    ReferenceManagement.unregisterForRemoteClientEvents(this, remoteActorRef.remoteClient)
  }

  /**
   * InnerReference methods implementation
   */
  protected[actor] def isLocal = false

  protected[actor] def node = TheaterNode(remoteActorRef.hostname, remoteActorRef.port)

  /**
   * Private methods
   */
  private[mobile] def handleRemoteClientEvent(message: RemoteClientLifeCycleEvent): Unit = message match {
    case rmd: RemoteClientDisconnected => tryToUpdateReference()

    case other => () // Discard it. Should log?
    // log.debug("RemoteActorRef received a notification from its remote client: " + other)
  }

  private def tryToUpdateReference(): Unit = NameService.get(uuid) match {
    case Some(TheaterNode(remoteActorRef.hostname, remoteActorRef.port)) =>
      log.debug("Lost connection to remote node %s. Actor with UUID [%s] was there and did not migrate.",
        TheaterNode(remoteActorRef.hostname, remoteActorRef.port).format, uuid)
      () // TODO: Actor did not migrate. What to do?

    case Some(newAddress) => {
      log.debug("Lost connection to remote node %s. Actor with UUID [%s] was there and migrated to %s. Updating the reference.",
        TheaterNode(remoteActorRef.hostname, remoteActorRef.port).format,
        uuid,
        newAddress.format)
      outerRef.updateRemoteAddress(newAddress)
    }

    case None => ()
  }
}
