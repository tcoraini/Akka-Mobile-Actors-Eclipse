package se.scalablesolutions.akka.mobile.theater

import se.scalablesolutions.akka.mobile.util.ClusterConfiguration

case class TheaterNode(hostname: String, port: Int) {
  def isLocal: Boolean = LocalTheater.isLocal(hostname, port)

  def format = "[" + hostname + ":" + port + "]"
}

object TheaterNode {
  def apply(nodeName: String): TheaterNode = ClusterConfiguration.nodes.get(nodeName).getOrElse {
    throw new IllegalArgumentException("There is no node called '" + nodeName + "' in the cluster.")
  }
  
  // Defining some implicit conversions, just for convenience
  // From TheaterNode to Tuple2 (hostname, port)
  implicit def theaterNodeToTuple2(node: TheaterNode): Tuple2[String, Int] = {
    (node.hostname, node.port)
  }

  // From TheaterDescription to TheaterNode
  implicit def theaterDescriptionToTheaterNode(description: TheaterDescription): TheaterNode = {
    description.node
  }

}

