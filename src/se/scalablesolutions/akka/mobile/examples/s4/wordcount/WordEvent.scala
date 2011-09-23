package se.scalablesolutions.akka.mobile.examples.s4.wordcount

import se.scalablesolutions.akka.mobile.examples.s4.Event

case class WordEvent(val key: String, val attribute: Int) extends Event[String, Int]
