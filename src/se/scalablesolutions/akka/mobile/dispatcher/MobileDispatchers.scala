package se.scalablesolutions.akka.mobile.dispatcher

import se.scalablesolutions.akka.actor.ActorRef

object MobileDispatchers {
  
  object globalMobileExecutorBasedEventDrivenDispatcher extends MobileExecutorBasedEventDrivenDispatcher("global") {
    override def register(actor: ActorRef) = {
      if (isShutdown) init
      super.register(actor)
    }
  }

}
