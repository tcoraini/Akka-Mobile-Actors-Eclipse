package se.scalablesolutions.akka.mobile.algorithm

import se.scalablesolutions.akka.mobile.theater.TheaterNode

trait DistributionAlgorithm {
  def chooseTheater: TheaterNode
}
    
