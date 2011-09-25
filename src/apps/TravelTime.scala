package apps

import se.scalablesolutions.akka.mobile.actor.MobileActor
import se.scalablesolutions.akka.mobile.theater.TheaterNode
import se.scalablesolutions.akka.mobile.theater.LocalTheater
import se.scalablesolutions.akka.mobile.util.messages._
import se.scalablesolutions.akka.mobile.Mobile
import se.scalablesolutions.akka.mobile.util.ClusterConfiguration

import scala.io.Source

case object StartTravel

class TravelTimeMeasurer(nBytes: Int, itinerary: List[TheaterNode]) extends MobileActor {
  private var beforeTimestamp: Long = _

  private val load = new Array[Byte](nBytes)
  private var notVisited = itinerary

  def receive = {
    case StartTravel =>
      beforeTimestamp = System.currentTimeMillis()
      val nextNode = notVisited.head
      notVisited = notVisited.tail
      self ! MoveTo(nextNode.hostname, nextNode.port)

    case any =>
      println("Recebi: " + any)
  }

  override def beforeMigration() {
  }

  override def afterMigration() {
    println("Passando por " + LocalTheater.node.format)
    if (notVisited.length > 0) {
      val nextNode = notVisited.head
      notVisited = notVisited.tail
      self ! MoveTo(nextNode.hostname, nextNode.port)
    } else {
      val afterTimestamp = System.currentTimeMillis()
      val elapsed = afterTimestamp - beforeTimestamp
      println("Viagem concluída em " + elapsed + " ms")
    }
  }
}

object TravelTime {
  def main(args: Array[String]) {
    Mobile.startTheater("node_1")
    val nBytes = args(0).toInt

    val itinerary: List[TheaterNode] =
      (for {
        line <- Source.fromFile(args(1)).getLines
        node = ClusterConfiguration.nodes.get(line).get.node
      } yield node).toList

    val actor = Mobile.spawn(new TravelTimeMeasurer(nBytes, itinerary ::: List(LocalTheater.node))).here
    actor ! StartTravel

  }
}