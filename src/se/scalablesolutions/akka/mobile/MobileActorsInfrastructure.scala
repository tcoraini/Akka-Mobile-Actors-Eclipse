package se.scalablesolutions.akka.mobile

import se.scalablesolutions.akka.mobile.util.ClusterConfiguration
import se.scalablesolutions.akka.mobile.theater.LocalTheater

import se.scalablesolutions.akka.util.Logging

import java.net.InetAddress

object MobileActorsInfrastructure extends Logging {
  
  def main(args: Array[String]) {
    var hostname: Option[String] = None
    var port: Option[Int] = None
    var nodeName: Option[String] = None
    
    for (i <- 0 to args.size - 2) {
      if (args(i) == "-h")
	hostname = Some(args(i + 1))
      else if (args(i) == "-p")
	port = Some(args(i + 1).toInt)
      else if (args(i) == "-n")
	nodeName = Some(args(i + 1))
    }

    if (hostname.isDefined && port.isDefined)
      LocalTheater.start(hostname.get, port.get)
    else if (nodeName.isDefined)
      LocalTheater.start(nodeName.get)
    else {
      // Try to guess based on hostname
      val localHostname = InetAddress.getLocalHost.getHostName
      val iterable = ClusterConfiguration.nodes.values.filter {
	description => description.node.hostname == localHostname
      } map {
	description => description.name
      }
      if (iterable.size > 0) {
	LocalTheater.start(iterable.head)
      } else {
	log.warning("Impossible to figure it out which node is supposed to run on this machine. Please use one of the following:\n" +
		    "\t -h HOSTNAME -p PORT\n" +
		    "\t -n NODE_NAME")
      }
    }
  }
}
