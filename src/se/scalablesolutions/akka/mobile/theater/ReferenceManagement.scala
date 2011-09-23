package se.scalablesolutions.akka.mobile.theater

import se.scalablesolutions.akka.mobile.actor.MobileActorRef
import se.scalablesolutions.akka.mobile.actor.RemoteMobileActor
import se.scalablesolutions.akka.mobile.actor.AttachRefToActor
import se.scalablesolutions.akka.mobile.util.ClusterConfiguration
import se.scalablesolutions.akka.mobile.util.DefaultLogger

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.remote.RemoteClientLifeCycleEvent
import se.scalablesolutions.akka.remote.RemoteClient

import collection.mutable.SynchronizedMap
import collection.mutable.HashMap

object ReferenceManagement {

  private val references = new HashMap[String, MobileActorRef] with SynchronizedMap[String, MobileActorRef]
  private val remoteClients = new HashMap[TheaterNode, ActorRef] with SynchronizedMap[TheaterNode, ActorRef]

  private[mobile] def putIfAbsent(uuid: String, reference: MobileActorRef): Option[MobileActorRef] = this.synchronized {
    references.get(uuid) match {
      case Some(ref) => Some(ref)

      case None =>
        references.put(uuid, reference)
        None
    }
  }

  def get(uuid: String): Option[MobileActorRef] = {
    references.get(uuid)
  }

  private[mobile] def remove(uuid: String): Unit = {
    references.remove(uuid)
  }

  private[mobile] def registerForRemoteClientEvents(reference: RemoteMobileActor, client: RemoteClient): Unit = {
    val listener = remoteClients.get(TheaterNode(client.hostname, client.port)) match {
      case Some(l) =>
        l

      case None =>
        val l = Actor.actorOf[RemoteClientEventsListener].start
        remoteClients.put(TheaterNode(client.hostname, client.port), l)
        client.addListener(l)
        l
    }
    listener ! AddReference(reference)
  }

  private[mobile] def unregisterForRemoteClientEvents(reference: RemoteMobileActor, client: RemoteClient): Unit = {
    remoteClients.get(TheaterNode(client.hostname, client.port)).foreach(_ ! RemoveReference(reference))
  }

  /*
   * Listener for the remote client events
   */
  private case class AddReference(ref: RemoteMobileActor)
  private case class RemoveReference(ref: RemoteMobileActor)
  private class RemoteClientEventsListener extends Actor {
    private var references: List[RemoteMobileActor] = Nil

    def receive = {
      case event: RemoteClientLifeCycleEvent =>
        references.foreach(ref => ref.handleRemoteClientEvent(event))

      case AddReference(ref) =>
        references = ref :: references

      case RemoveReference(ref) =>
        references = references.filter(_ != ref)
    }
  }
}

