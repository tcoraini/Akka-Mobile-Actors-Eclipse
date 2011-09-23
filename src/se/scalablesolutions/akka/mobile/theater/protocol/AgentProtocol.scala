package se.scalablesolutions.akka.mobile.theater.protocol

import se.scalablesolutions.akka.mobile.util.messages._

import se.scalablesolutions.akka.mobile.theater.Theater
import se.scalablesolutions.akka.mobile.theater.TheaterNode

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.remote.RemoteClient

import scala.collection.mutable.HashMap

class AgentProtocol extends TheaterProtocol {
  
  private lazy val agents = new HashMap[TheaterNode, ActorRef]
  
  private lazy val localAgent = Actor.actor {
    case message: TheaterMessage => 
      theater.processMessage(message)
    
    case message => 
      println("\n\nMESSAGE RECEIVED BY THEATER AGENT UNKNOWN: " + message + "\n\n")
  }
  private lazy val agentName = "theaterAgent@" + theater.node.hostname + ":" + theater.node.port  

  override def init(theater: Theater): Unit = {
    super.init(theater)

    theater.registerAgent(agentName, localAgent)
    agents += theater.node -> localAgent
  }

  def sendTo(node: TheaterNode, message: TheaterMessage): Unit = {
    try {
      agentFor(node) ! message
    } catch {
      case e: IllegalStateException =>
        //TODO Gambiarra monstro
        (new Thread() {
          override def run(): Unit = {
            agentFor(node) ! message
          }
        }).start()
    }
  }

  override def stop(): Unit = {
    theater.unregisterAgent(agentName)
    localAgent.stop()
    agents.clear
  }
    
  private def agentFor(node: TheaterNode): ActorRef = agents.get(node) match {
    case Some(agent) => agent
      
    case None => 
      val agentName = "theaterAgent@" + node.hostname + ":" + node.port
      val newAgent = RemoteClient.actorFor(agentName, node.hostname, node.port)
      agents += node -> newAgent
      newAgent
  }
}
