package se.scalablesolutions.akka.mobile.actor

import se.scalablesolutions.akka.util.Logging

import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.actor.ScalaActorRef

import se.scalablesolutions.akka.mobile.theater.LocalTheater
import se.scalablesolutions.akka.mobile.theater.TheaterNode

trait InnerReference extends ActorRef with ScalaActorRef with Logging {

  protected[actor] var outerRef: MobileActorRef = _

  private[actor] val _lock = new Object

  def isMigrating =
    if (outerRef != null) outerRef.isMigrating
    else false

  protected lazy val holder = new MessageHolder

  /**
   * Abstract methods, to be overriden by subclasses
   */
  protected[actor] def isLocal: Boolean

  protected[actor] def node: TheaterNode

  /**
   * Should be overriden only by local references.
   *
   * The group ID field only makes sense for local actors, it would be too hard to keep
   * track of group IDs for remote actors
   */
  def groupId: Option[String] = None
  protected[mobile] def groupId_=(id: Option[String]) {}

  def stopLocal() = stop()

}
