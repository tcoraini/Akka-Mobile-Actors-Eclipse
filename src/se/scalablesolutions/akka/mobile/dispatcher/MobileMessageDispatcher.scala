package se.scalablesolutions.akka.mobile.dispatcher

import se.scalablesolutions.akka.dispatch.MessageDispatcher
import se.scalablesolutions.akka.dispatch.MessageInvocation

trait MobileMessageDispatcher extends MessageDispatcher {
  def dispatchWithPriority(invocation: MessageInvocation)
}  
