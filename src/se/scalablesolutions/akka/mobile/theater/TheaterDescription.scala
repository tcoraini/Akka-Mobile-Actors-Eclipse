package se.scalablesolutions.akka.mobile.theater

case class TheaterDescription(
  name: String, 
  node: TheaterNode,
  profiling: Boolean,
  hasNameServer: Boolean)
