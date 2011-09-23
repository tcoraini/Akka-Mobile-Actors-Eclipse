package se.scalablesolutions.akka.mobile.examples.s4.wordcount

import se.scalablesolutions.akka.mobile.examples.s4.Event

case class QuoteEvent (val attribute: String) extends Event[Null, String] {
  val key: Null = null
}
