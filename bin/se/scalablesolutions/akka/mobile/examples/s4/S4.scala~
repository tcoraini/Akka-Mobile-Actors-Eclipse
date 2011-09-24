package se.scalablesolutions.akka.mobile.examples.s4

import se.scalablesolutions.akka.mobile.Mobile
import se.scalablesolutions.akka.mobile.actor.MobileActorRef

import java.util.concurrent.ConcurrentHashMap

object S4 {
  
  /* TODO private */ val pesFactories = new ConcurrentHashMap[Class[_ <: Event[_, _]], Class[_ <: ProcessingElement[_]]]
  /* TODO private */ val runningPEs = new ConcurrentHashMap[String, MobileActorRef]

  def registerPE[P <: ProcessingElement[_] : Manifest, E <: Event[_, _] : Manifest] = {
    val peClass = manifest[P].erasure.asInstanceOf[Class[_ <: ProcessingElement[_]]]
    val eventClass = manifest[E].erasure.asInstanceOf[Class[_ <: Event[_, _]]]

    pesFactories.put(eventClass, peClass)
  }
  
  def dispatch(event: Event[_, _]) = {
    val eventName = event.uniqueName
    
    MobileActorRef(eventName) match {
      case Some(pe) =>
	pe ! event
      
      case None =>
	val newPE = createPE(event)
	newPE ! event
    }
  }

  private def createPE(event: Event[_, _]): MobileActorRef = {
    val peClass = pesFactories.get(event.getClass)
    if (peClass == null) {
      throw new RuntimeException("There is no Processing Element registered for events of type '" + event.getClass + "'.")
    }
    
//    val constructor = peClass.getConstructor(event.getClass)
//    val pe = constructor.newInstance(event)
    
    val ref = Mobile.spawn(peClass.newInstance())
    ref ! StartPEFor(event)
    ref
  }

}
