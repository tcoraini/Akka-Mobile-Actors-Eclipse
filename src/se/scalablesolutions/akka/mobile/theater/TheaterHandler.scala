package se.scalablesolutions.akka.mobile.theater

import se.scalablesolutions.akka.mobile.actor.MobileActorRef

import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._

import se.scalablesolutions.akka.util.Logging

import java.util.Map

import org.jboss.netty.channel._

@ChannelHandler.Sharable // TODO eh mesmo?
class TheaterHandler(actors: Map[String, MobileActorRef], theater: Theater) extends SimpleChannelUpstreamHandler with Logging {

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    val message = event.getMessage
    if (message.isInstanceOf[RemoteRequestProtocol]) {
      val request = message.asInstanceOf[RemoteRequestProtocol]
      if (request.getActorInfo.getActorType == ActorType.MOBILE_ACTOR)
        theater.handleMobileActorRequest(request)
      else ctx.sendUpstream(event)
    } else ctx.sendUpstream(event)
  }
}

