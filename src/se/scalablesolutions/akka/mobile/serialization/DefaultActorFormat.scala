package se.scalablesolutions.akka.mobile.serialization

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.SerializerBasedActorFormat

import se.scalablesolutions.akka.serialization.Serializer

// Default type class for actor serialization, uses Java serialization
object DefaultActorFormat extends SerializerBasedActorFormat[Actor] {
  val serializer = Serializer.Java
}
