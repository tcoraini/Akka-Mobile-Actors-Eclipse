package se.scalablesolutions.akka.mobile.examples.s4.wordcount

import se.scalablesolutions.akka.mobile.examples.s4.Event

case class PartialTopKEvent(val key: Int, attribute: List[Tuple2[String, Int]]) extends Event[Int, List[Tuple2[String, Int]]]
