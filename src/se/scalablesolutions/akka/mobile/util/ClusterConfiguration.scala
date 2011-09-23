package se.scalablesolutions.akka.mobile.util

import se.scalablesolutions.akka.mobile.theater.TheaterNode
import se.scalablesolutions.akka.mobile.theater.TheaterDescription

import scala.collection.immutable.HashMap

import se.scalablesolutions.akka.config.Config.config

import se.scalablesolutions.akka.util.Logging

object ClusterConfiguration extends Logging {
  lazy val nodeNames: Seq[String] = config.getList("cluster.nodes")

  lazy val nodes = loadConfiguration()

  lazy val numberOfNodes = nodes.size

  def loadConfiguration(): HashMap[String, TheaterDescription] = {
    log.debug("Reading the cluster description from configuration file") 

    var _nodes = new HashMap[String, TheaterDescription]
    
    for {
      nodeName <- nodeNames
      label = "cluster." + nodeName
      
      hostname = config.getString(label + ".hostname")
      if (hostname.isDefined)
      port = config.getInt(label + ".port")
      if (port.isDefined)
      hasNameServer = config.getBool(label + ".name-server", false)
      profiling = config.getBool(label + ".profiling", false)
      
      theaterNode = TheaterNode(hostname.get, port.get)
      nodeInfo = TheaterDescription(nodeName, theaterNode, profiling, hasNameServer)
    } _nodes = _nodes + ((nodeName, nodeInfo)) 
    
    _nodes
  }

  def instanceOf[T <: AnyRef, D <: T : Manifest](key: String): T = {
    lazy val defaultClass: T = {
      val clazz = manifest[D].erasure.asInstanceOf[Class[_ <: T]]
      clazz.newInstance.asInstanceOf[T]
    }
    lazy val defaultClassName = defaultClass.getClass.getName

    config.getString(key) match {
      case Some(classname) => {
	try {
          val instance = Class.forName(classname).newInstance.asInstanceOf[T]
          log.info("Instance of [%s] loaded for the property [%s].", classname, key)
          instance
	} catch {
          case cnfe: ClassNotFoundException =>
            log.warning("The class [%s] could not be found. Using the default class [%s] instead.", classname, defaultClassName)
            defaultClass
          
	  // TODO this is not thrown, apparently because of the parameterized types...Any way to resolve it?
          case cce: ClassCastException =>
            log.warning("The class [%s] does not extend the expected type. Using the default class [%s] instead.", classname, defaultClassName)
            defaultClass

	  case e: Exception =>
	    log.warning("The class [%s] could not be instantiated. Maybe it is a Trait or it does not have a nullary constructor " + 
			"(without arguments). Using the default class [%s] instead.", classname, defaultClassName)
	    defaultClass
	}
      }

      case None =>
	log.warning("Could not find the property [%s] in the configuration file. Using the default class [%s] instead.", key, defaultClassName)
	defaultClass
    }
  }
}
