package se.scalablesolutions.akka.mobile.examples.s4

import se.scalablesolutions.akka.mobile.actor.MobileActor

@serializable abstract class ProcessingElement[T <: Event[_, _] : Manifest] extends MobileActor {
  
  val eventPrototype: T

  private var _isRunning = false
  def isRunning = _isRunning
  
  val eventClass = manifest[T].erasure.asInstanceOf[Class[_ <: Event[_, _]]]

  lazy val key = eventPrototype.key
  
  self.uuid = self.uuid + "@" + eventPrototype.uniqueName

  def receive = {
    case event: T if event.key == key => process(event)
      
    case event: Event[_, _] => log.warning("Processing element received an Event with an incompatible key: " + event)
      
    case msg => log.warning("Processing element received an unknown message: " + msg)
  }

  override def init {
    _isRunning = true
  }

  override def shutdown {
    _isRunning = false
  }
  
  protected def emit(event: Event[_, _]): Unit = {
    S4.dispatch(event)
  }
  
  def process(event: T): Unit
  
}
