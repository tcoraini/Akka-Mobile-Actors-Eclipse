package apps

import se.scalablesolutions.akka.mobile.actor.MobileActor
import se.scalablesolutions.akka.mobile.util.messages._
import se.scalablesolutions.akka.mobile.util.DefaultLogger
import se.scalablesolutions.akka.mobile.util.ClusterConfiguration
import se.scalablesolutions.akka.mobile.Mobile

class MigrationTimeMeasurer(nBytes: Int) extends MobileActor {
  private var beforeTimestamp: Long = _

  private val load = new Array[Byte](nBytes)
  println("Construído com " + nBytes + " bytes.")

  def receive = {
    case any =>
      println("Recebi: " + any)
  }

  override def beforeMigration() {
    println("Iniciando migração com " + load.length + " bytes.")
    beforeTimestamp = System.currentTimeMillis()
  }

  override def afterMigration() {
    val afterTimestamp = System.currentTimeMillis()
    val elapsed = afterTimestamp - beforeTimestamp
    println("Migração concluída com " + load.length + " bytes.");
    println("Tempo total: " + elapsed + " ms");
  }

}

object MigrationTime {
  def main(args: Array[String]) {
    Mobile.startTheater("node_1")
    val nBytes = args(0).toInt

    val rounds: Int =
      if (args.length > 1) args(1).toInt
      else 1

    val destination = ClusterConfiguration.nodes.get("node_2").getOrElse(throw new RuntimeException("Não existe um nó chamado 'node_2'"));
    for (i <- 1 to rounds) {
      val actor = Mobile.spawn(new MigrationTimeMeasurer(nBytes)).here
      actor ! MoveTo(destination.node.hostname, destination.node.port)
    }
  }
}