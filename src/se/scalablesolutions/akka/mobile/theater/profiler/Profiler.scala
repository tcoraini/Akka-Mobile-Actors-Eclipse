package se.scalablesolutions.akka.mobile.theater.profiler

import se.scalablesolutions.akka.mobile.theater.TheaterNode
import se.scalablesolutions.akka.mobile.util.messages._

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.config.Config

import scala.collection.mutable.HashMap
import scala.collection.mutable.SynchronizedMap

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

object ResetMode extends Enumeration {
  val MANUAL = Value
  val AUTOMATIC = Value
}

object Profiler {
  // Default value for the minimum amount of messages that an actor should receive from a node before
  // entering in the priority queue. Can be set in the configuration file.
  val QUEUE_THRESHOLD: Int = 5
  
  val DEFAULT_RESET_MODE: ResetMode.Value = ResetMode.MANUAL
  val DEFAULT_RESET_INTERVAL = 60 // In Minutes
}

// TODO tratamento especial para atores co-locados?
class Profiler(val localNode: TheaterNode) extends Logging {
  import Profiler._
  
  // For each actor (UUID), holds how many messages it received from each theater in the cluster
  private val incomingMessages = new HashMap[String, HashMap[TheaterNode, IMRecord]] with 
      SynchronizedMap[String, HashMap[TheaterNode, IMRecord]]

  // 11 is the default initial capacity for the PriorityQueue Java class
  private val priorityQueue = new PriorityBlockingQueue[IMRecord](11, IMRecord.comparator)

  private val queueThreshold = Config.config.getInt("cluster.profiling.queue-threshold", Profiler.QUEUE_THRESHOLD)
  
  private var _resetMode: ResetMode.Value = parseResetModeFromConfigurationFile
  def resetMode: ResetMode.Value = _resetMode
  def resetMode_=(mode: ResetMode.Value) = {
    _resetMode = mode
    if (mode == ResetMode.AUTOMATIC) {
      initializeResetService()
    }
  }  

  private var _resetInterval = Config.config.getInt("cluster.profiling.reset-interval", DEFAULT_RESET_INTERVAL)
  def resetInterval = _resetInterval 
  def resetInterval_=(interval: Int) = { _resetInterval = interval }

  if (_resetMode == ResetMode.AUTOMATIC) {
     initializeResetService()
  }    
  
  private[mobile] def localMessageArrived(uuid: String): Unit = {
    messageArrived(uuid, localNode, false)
  }

  private[mobile] def remoteMessageArrived(uuid: String, message: MobileActorMessage): Unit = {
    messageArrived(uuid, TheaterNode(message.senderHostname, message.senderPort), true)
  }

  private[theater] def stop() {
    resetMode = ResetMode.MANUAL
    this.reset()
  }

  // TODO synchronize correctly this method
  private def messageArrived(uuid: String, from: TheaterNode, usePriorityQueue: Boolean): Unit = {  
    lazy val newMap = new HashMap[TheaterNode, IMRecord] with SynchronizedMap[TheaterNode, IMRecord]
    val innerMap = incomingMessages.getOrElseUpdate(uuid, newMap)
    val imRecord = innerMap.getOrElseUpdate(from, IMRecord(uuid, from))

    imRecord.increment()
    
    if (usePriorityQueue) {
      updatePriorityQueue(imRecord)
    }
    
    if (from == localNode) {
      log.debug("Registering arrival of local message to actor with UUID [%s].", uuid)
    } else {
      log.debug("Registering arrival of remote message to actor with UUID [%s] from node %s.",
		uuid, TheaterNode(from.hostname, from.port).format)
    }
  }

  private def updatePriorityQueue(imRecord: IMRecord): Unit = {
    if (imRecord.count == queueThreshold) {
      priorityQueue.add(imRecord)
    } else if (imRecord.count > queueThreshold) {
      priorityQueue.remove(imRecord)
      priorityQueue.add(imRecord)
    }
  }
  
  private def parseResetModeFromConfigurationFile: ResetMode.Value =
    Config.config.getString("cluster.profiling.reset-mode", "").toUpperCase match {
      case "MANUAL" => ResetMode.MANUAL
      case "AUTOMATIC" => ResetMode.AUTOMATIC
      case _ => { 
	log.warning("Invalid value for profiling reset mode in the configuration file. Using '" + 
		    DEFAULT_RESET_MODE.toString + "' as default.")
	DEFAULT_RESET_MODE
      }
  }
       
  // Removes all the records regarding the actor with UUID 'uuid', from both the Hash Map and 
  // the Priority Queue
  private[mobile] def remove(uuid: String): Unit = {
    incomingMessages.get(uuid).foreach { innerMap =>
      innerMap.values.foreach(record => priorityQueue.remove(record))
      incomingMessages.remove(uuid)
    }
  }

  private def initializeResetService(): Unit = {
    new Thread("Profiler Reset Service") {
      override def run() {
	while(_resetMode == ResetMode.AUTOMATIC) {
	  Thread.sleep(resetInterval * 60 * 1000) // Sleep for 'resetInterval' minutes
	  if (_resetMode == ResetMode.AUTOMATIC) {
	    log.debug("Resetting all profiling data now...")
	    reset()
	  }
	}
      }
    } start()
  }
  
  /**
   * API for obtaining data collected by the profiler
   */

  // Gets the first record in the priority queue, i.e., the actor who has received more messages
  // from remote nodes
  def firstInQueue: Option[IMRecord] = {
    val first = priorityQueue.peek
    if (first != null) Some(first)
    else None
  }

  // Gets the number of local messages the actor with UUID 'uuid' received
  def localMessagesCount(uuid: String): Int = {
    // Just as an exercise, but not very clear, I admit :-)
    incomingMessages.get(uuid).flatMap(innerMap => innerMap.get(localNode)).map(record => record.count).getOrElse(0)

    // Without flat map, this is scary!
    //incomingMessages.get(uuid).map(innerMap => innerMap.get(localNode)).map(option => option.map(record => record.count)).getOrElse(None).getOrElse(0)

    // More readable version
    // incomingMessages.get(uuid) match {
    //   case None => 0
    
    //   case Some(innerMap) => 
    // 	   val record = innerMap.get(localNode)
    // 	   if (record.isDefined) 
    // 	     record.get.count
    // 	   else 0
    // }
  }

  // Gets the complete table of IMRecord's for the actor with UUID 'uuid'. This table contains, for
  // each node, a IMRecord with the number of messages that actor received from that node
  def incomingMessagesRecords(uuid: String): Option[HashMap[TheaterNode, IMRecord]] = incomingMessages.get(uuid)

  /**
   * Resets all data of this Profiler. Useful for keeping only up-to-date information. A better
   * solution, though, would be to continuously erase the "expired" data (based on some time-to-live
   * parameter).
   */
  def reset(): Unit = {
    incomingMessages.clear()
    priorityQueue.clear()
  }
}
