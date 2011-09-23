package se.scalablesolutions.akka.mobile.nameservice

import se.scalablesolutions.akka.mobile.theater.TheaterNode
import se.scalablesolutions.akka.mobile.util.ClusterConfiguration
import se.scalablesolutions.akka.mobile.util.messages._

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.config.Config

object DistributedNameService extends Logging {

  private val nodes: Array[TheaterNode] = findNameServiceNodes

  private val numberOfNodes = nodes.size

  private val hashFunction: HashFunction = {
    lazy val defaultHashFunction = new DefaultHashFunction
    try {
      ClusterConfiguration.instanceOf[HashFunction, DefaultHashFunction]("cluster.name-service.hash-function")
    } catch {
      case cce: ClassCastException =>
        val classname = Config.config.getString("cluster.name-service.hash-function", "")
        log.warning("The class [%s] does not extend the HashFunction trait. Using the default hash function [%s] instead.",
          classname, defaultHashFunction.getClass.getName)
        defaultHashFunction
    }
  }

  private def hash(key: String): Int = hashFunction.hash(key, numberOfNodes)

  private def findNameServiceNodes: Array[TheaterNode] = {
    val nodesWithNameService = ClusterConfiguration.nodes.values.filter {
      nodeInfo =>
        nodeInfo.hasNameServer
    } map {
      nodeInfo =>
        nodeInfo.node
    }

    nodesWithNameService.toArray
  }

  private def nameServerFor(uuid: String): TheaterNode = {
    nodes(hash(uuid))
  }
}

class DistributedNameService extends NameService {
  import DistributedNameService._

  private var initialized = false

  private var runningServer: Boolean = _

  def init(runServer: Boolean): Unit = {
    if (!initialized) {
      runningServer = runServer
      // In case there is a local name server, we start a NameServiceAgent
      if (runServer) NameServiceAgent.startLocalAgent()
      initialized = true
    }
  }

  def stop(): Unit = {
    if (runningServer) NameServiceAgent.stop()
  }

  def put(uuid: String, node: TheaterNode): Unit = {
    val nameServer = nameServerFor(uuid)
    val agent = NameServiceAgent.agentFor(nameServer)
    agent !! ActorRegistrationRequest(uuid, node.hostname, node.port)
  }

  def get(uuid: String): Option[TheaterNode] = {
    val nameServer = nameServerFor(uuid)
    val agent = NameServiceAgent.agentFor(nameServer)
    (agent !! ActorLocationRequest(uuid)) match {
      case Some(ActorLocationResponse(hostname, port)) =>
        Some(TheaterNode(hostname, port))

      case _ => None
    }
  }

  def remove(uuid: String): Unit = {
    val nameServer = nameServerFor(uuid)
    val agent = NameServiceAgent.agentFor(nameServer)
    agent !! ActorUnregistrationRequest(uuid)
  }

  override def toString = "Distributed Name Service"
}

