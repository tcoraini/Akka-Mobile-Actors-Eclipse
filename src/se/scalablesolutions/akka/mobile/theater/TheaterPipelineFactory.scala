package se.scalablesolutions.akka.mobile.theater

import se.scalablesolutions.akka.mobile.util.PipelineFactoryCreator
import se.scalablesolutions.akka.mobile.actor.MobileActorRef

import java.util.Map

import se.scalablesolutions.akka.remote.RemoteServer
import se.scalablesolutions.akka.remote.RemoteServerPipelineFactory
import se.scalablesolutions.akka.actor.ActorRef

import org.jboss.netty.channel.group.ChannelGroup
import org.jboss.netty.channel.ChannelPipeline
import org.jboss.netty.channel.StaticChannelPipeline
import org.jboss.netty.channel.ChannelHandler


class TheaterPipelineFactoryCreator(mobileActors: Map[String, MobileActorRef], theater: Theater) extends PipelineFactoryCreator {
  def createPipelineFactory(name: String,
      openChannels: ChannelGroup,
      loader: Option[ClassLoader],
      actors: Map[String, ActorRef],
      typedActors: Map[String, AnyRef],
      server: RemoteServer): RemoteServerPipelineFactory = {

    new TheaterPipelineFactory(name, openChannels, loader, actors, typedActors, server, mobileActors, theater)
  }
}

class TheaterPipelineFactory(
    name: String,
    openChannels: ChannelGroup,
    loader: Option[ClassLoader],
    actors: Map[String, ActorRef],
    typedActors: Map[String, AnyRef],
    server: RemoteServer,
    mobileActors: Map[String, MobileActorRef],
    theater: Theater) 
  extends RemoteServerPipelineFactory(name, openChannels, loader, actors, typedActors, server) {

  // This method will just get the default channel pipeline, from the RemoteServerPipelineFactory, and add
  // a specific handler for the theater purposes, just before the last handler in the chain (the RemoteServerHandler)
  override def getPipeline: ChannelPipeline = {
    val remoteServerPipeline: ChannelPipeline = super.getPipeline
    val numberOfHandlers = remoteServerPipeline.toMap.keySet.size

    val handlers: Array[ChannelHandler] = new Array(numberOfHandlers + 1)
    
    // The first n-1 handlers are the same
    for (i <- 0 to numberOfHandlers - 2)
      handlers(i) = remoteServerPipeline.get(i.toString)

    // Put the specific theater handler in the chain, just before the remote server handler
    handlers(numberOfHandlers - 1) = new TheaterHandler(mobileActors, theater)

    // And finally the remote server handler
    handlers(numberOfHandlers) = remoteServerPipeline.get((numberOfHandlers - 1).toString)
  
    new StaticChannelPipeline(handlers: _*)
  }
}

