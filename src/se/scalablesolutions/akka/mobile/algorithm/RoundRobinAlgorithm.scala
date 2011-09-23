package se.scalablesolutions.akka.mobile.algorithm

import se.scalablesolutions.akka.mobile.util.ClusterConfiguration
import se.scalablesolutions.akka.mobile.theater.TheaterNode

class RoundRobinAlgorithm extends DistributionAlgorithm {
  
  private val indexedNodes = ClusterConfiguration.nodeNames.toIndexedSeq

  private val numberOfTheaters = indexedNodes.size

  private var currentTheater = 0

  def chooseTheater: TheaterNode = {
    // indexedTheaters(currentTheater) is a Tuple2[String, TheaterDescription]
    val nodeName = indexedNodes(currentTheater)
    val theaterDescription = ClusterConfiguration.nodes.get(nodeName).get

    currentTheater = (currentTheater + 1) % numberOfTheaters
    
    theaterDescription.node
  }
}
