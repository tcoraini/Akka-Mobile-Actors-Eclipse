package apps

import se.scalablesolutions.akka.mobile.Mobile
import se.scalablesolutions.akka.mobile.theater.LocalTheater
import se.scalablesolutions.akka.mobile.util.DefaultLogger

object StartTheater {
  def main(args: Array[String]) {
    if (args.length == 0) {
      Mobile.startTheater("node_2")
    } else {
      Mobile.startTheater(args(0))
    }
    DefaultLogger.info("Teatro iniciado em %s", LocalTheater.node.format)
  }
}