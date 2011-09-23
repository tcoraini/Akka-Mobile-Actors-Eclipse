package se.scalablesolutions.akka.mobile.examples.s4.wordcount

import se.scalablesolutions.akka.mobile.examples.s4.Event

case class UpdateCountEvent(val key: Int, val attribute: Tuple2[String, Int]) extends Event[Int, Tuple2[String, Int]]
