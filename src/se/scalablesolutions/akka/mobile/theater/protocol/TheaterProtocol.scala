package se.scalablesolutions.akka.mobile.theater.protocol

import se.scalablesolutions.akka.mobile.theater.Theater
import se.scalablesolutions.akka.mobile.theater.TheaterNode

import se.scalablesolutions.akka.mobile.util.messages._

abstract class TheaterProtocol {
  protected var theater: Theater = _

  def init(theater: Theater) {
    this.theater = theater
  }
  
  def sendTo(node: TheaterNode, message: TheaterMessage)

  def stop() { }
}
