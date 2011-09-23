package se.scalablesolutions.akka.mobile.examples.s4

import se.scalablesolutions.akka.mobile.Mobile
import se.scalablesolutions.akka.mobile.actor.MobileActorRef
import se.scalablesolutions.akka.mobile.theater.TheaterNode
import se.scalablesolutions.akka.mobile.theater.LocalTheater

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.remote.RemoteClient

import java.util.concurrent.ConcurrentHashMap

case class PEFor(eventPrototype: Event[_, _])

class S4Agent extends Actor {
  self.timeout = 10000
  
  def receive = {
    case PEFor(eventPrototype) =>
      self.reply(S4.getOrCreatePE(eventPrototype).uuid)
  }
}

object S4 {
  
  val homeNode = TheaterNode("ubuntu-tcoraini", 1810)
  
  val s4agent: ActorRef = {
    if (homeNode.isLocal) {
      val localAgent = Actor.actorOf(new S4Agent)
      LocalTheater.registerAgent("s4agent", localAgent)
      localAgent
    } else {
      RemoteClient.actorFor("s4agent", homeNode.hostname, homeNode.port)
    }
  }

  /* TODO private */ val pesFactories = new ConcurrentHashMap[Class[_ <: Event[_, _]], Class[_ <: ProcessingElement[_]]]
  /* TODO private */ val runningPEs = new ConcurrentHashMap[String, MobileActorRef]

  def registerPE[P <: ProcessingElement[_] : Manifest, E <: Event[_, _] : Manifest] = {
    val peClass = manifest[P].erasure.asInstanceOf[Class[_ <: ProcessingElement[_]]]
    val eventClass = manifest[E].erasure.asInstanceOf[Class[_ <: Event[_, _]]]

    pesFactories.put(eventClass, peClass)
  }
  
  def dispatch(event: Event[_, _]) = {
    val peRef = {
      if (homeNode.isLocal) {
	getOrCreatePE(event)
      } else {
	(s4agent.!!(PEFor(event), 10000)) match {
	  case Some(uuid) if uuid.isInstanceOf[String] => MobileActorRef(uuid.asInstanceOf[String]).get
	  
	  case msg => throw new RuntimeException("Problems in communication with S4 home node at " + homeNode + ".\nMsg received: " + msg)
	}
      }
    }
    peRef ! event
    
/*    val eventName = event.uniqueName
    
    MobileActorRef(eventName) match {
      case Some(pe) =>
	pe ! event
      
      case None =>
	val newPE = createPE(event)
	newPE ! event
    }*/
  }

  def getOrCreatePE(eventPrototype: Event[_, _]): MobileActorRef = {
    var peRef = runningPEs.get(eventPrototype.uniqueName)
    if (peRef == null) {
      peRef = createPE(eventPrototype)
      runningPEs.put(eventPrototype.uniqueName, peRef)
    }
    peRef
  }


  private def createPE(event: Event[_, _]): MobileActorRef = {
    val peClass = pesFactories.get(event.getClass)
    if (peClass == null) {
      throw new RuntimeException("There is no Processing Element registered for events of type '" + event.getClass + "'.")
    }
    
    val constructor = peClass.getConstructor(event.getClass)
    Mobile.spawn(constructor.newInstance(event)) here
    
//    val ref = Mobile.spawn(peClass.newInstance())
//    ref ! StartPEFor(event)
//    ref
  }

}
