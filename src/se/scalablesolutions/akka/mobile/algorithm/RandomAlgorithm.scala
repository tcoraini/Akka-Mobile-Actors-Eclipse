package se.scalablesolutions.akka.mobile.algorithm

import se.scalablesolutions.akka.mobile.util.ClusterConfiguration
import se.scalablesolutions.akka.mobile.theater.TheaterNode

import scala.util.Random

class RandomAlgorithm extends DistributionAlgorithm {
  
  private val indexedTheaters = ClusterConfiguration.nodes.toIndexedSeq

  private val numberOfTheaters = indexedTheaters.size

  def chooseTheater: TheaterNode = {
    val index = Random.nextInt(numberOfTheaters)

    // indexedTheaters(index) is a Tuple2[String, TheaterDescription]
    val theaterDescription = indexedTheaters(index)._2

    theaterDescription.node
  }
}
