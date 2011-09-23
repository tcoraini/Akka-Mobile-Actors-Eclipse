package apps

import se.scalablesolutions.akka.mobile.actor.MobileActor
import se.scalablesolutions.akka.mobile.util.messages._
import se.scalablesolutions.akka.mobile.util.DefaultLogger
import se.scalablesolutions.akka.mobile.Mobile

import java.util.Date

class MigrationTimeMeasurer extends MobileActor {
  private var beforeTimestamp: Long = _

  def receive = {
    case any =>
      println("Recebi: " + any)
  }

  override def beforeMigration() {
    beforeTimestamp = (new Date).getTime
  }

  override def afterMigration() {
    val afterTimestamp = (new Date).getTime
    val elapsed = afterTimestamp - beforeTimestamp
    DefaultLogger.info("Tempo decorrido da migração: %s ms", elapsed)
  }

}

object MigrationTime {
  def main(args: Array[String]) {
    Mobile.startTheater("node_1")
    val actor = Mobile.spawn[MigrationTimeMeasurer].here
    actor ! MoveTo("node_2", 1810)

    val actor2 = Mobile.spawn[MigrationTimeMeasurer].here
    actor2 ! MoveTo("node_2", 1810)
  }
}