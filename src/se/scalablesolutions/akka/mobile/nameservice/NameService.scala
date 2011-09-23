package se.scalablesolutions.akka.mobile.nameservice

import se.scalablesolutions.akka.mobile.actor.MobileActorRef
import se.scalablesolutions.akka.mobile.theater.TheaterNode
import se.scalablesolutions.akka.mobile.util.ClusterConfiguration

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.config.Config

object NameService extends Logging {
  private var isRunning = false
  
  private var service: NameService = _

  private[mobile] def init(runServer: Boolean) {
    this.service = chooseNameService
    service.init(runServer)
    isRunning = true
  }

  private[mobile] def stop() {
    service.stop()
  }

  private[mobile] def put(actor: MobileActorRef, node: TheaterNode): Unit = {
    put(actor.uuid, node)
  }

  private[mobile] def put(uuid: String, node: TheaterNode): Unit = ifRunning {
    service.put(uuid, node)
  }
    
  def get(actor: MobileActorRef): Option[TheaterNode] = {
    get(actor.uuid)
  }

  def get(uuid: String): Option[TheaterNode] = ifRunning {
    service.get(uuid)
  }

  private[mobile] def remove(actor: MobileActorRef): Unit = {
    remove(actor.uuid)
  }
  
  private[mobile] def remove(uuid: String): Unit = ifRunning {
    service.remove(uuid)
  }

  private def ifRunning[T](execute: => T): T = {
    if (isRunning)
      execute
    else 
      throw new RuntimeException("The name service is not running. You have to call NameService.init() first.")
  }

  // Choose the name service to be used, based on the configuration file
  private def chooseNameService: NameService = {
    lazy val defaultNameService = new DistributedNameService
    try {
      ClusterConfiguration.instanceOf[NameService, DistributedNameService]("cluster.name-service.class")
    } catch {
      case cce: ClassCastException =>
	val classname = Config.config.getString("cluster.name-service.class", "")
        log.warning("The class [%s] does not extend the NameService trait. Using the default name service [%s] instead.", 
                    classname, defaultNameService.getClass.getName)
	defaultNameService
    }
  }
}

trait NameService {
  def init(runServer: Boolean)

  def put(uuid: String, node: TheaterNode)

  def get(uuid: String): Option[TheaterNode] 

  def remove(uuid: String)
  
  def stop()
}
