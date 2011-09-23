package se.scalablesolutions.akka.mobile.util

import java.util.Map

import se.scalablesolutions.akka.remote.RemoteServer
import se.scalablesolutions.akka.remote.RemoteServerPipelineFactory
import se.scalablesolutions.akka.actor.ActorRef

import org.jboss.netty.channel.group.ChannelGroup

/* 
 * This class is suposed to create a Netty pipeline factory to be used by the Remote Server. Clients
 * could extend this class, allowing then to create pipeline factories (and therefore pipelines) meeting
 * specific needs. It's mandatory, however, that this pipeline factories are subclasses of the
 * RemoteServerPipelineFactory class.
 */
trait PipelineFactoryCreator {
  def createPipelineFactory(name: String,
      openChannels: ChannelGroup,
      loader: Option[ClassLoader],
      actors: Map[String, ActorRef],
      typedActors: Map[String, AnyRef],
      server: RemoteServer): RemoteServerPipelineFactory
}

object DefaultPipelineFactoryCreator extends PipelineFactoryCreator {
  def createPipelineFactory(name: String,
      openChannels: ChannelGroup,
      loader: Option[ClassLoader],
      actors: Map[String, ActorRef],
      typedActors: Map[String, AnyRef],
      server: RemoteServer): RemoteServerPipelineFactory = {
  
    new RemoteServerPipelineFactory(name, openChannels, loader, actors, typedActors, server)
  }
}

