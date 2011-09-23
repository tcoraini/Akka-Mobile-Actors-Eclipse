package se.scalablesolutions.akka.mobile.theater

import se.scalablesolutions.akka.mobile.actor.MobileActorRef
import se.scalablesolutions.akka.mobile.util.messages._
import se.scalablesolutions.akka.mobile.util.UUID
import se.scalablesolutions.akka.mobile.nameservice.NameService
import se.scalablesolutions.akka.config.Config

import collection.mutable.SynchronizedMap
import collection.mutable.HashMap
import collection.mutable.ArrayBuilder
import java.util.Timer
import java.util.TimerTask

object GroupManagement {

  val MIGRATION_TIMEOUT = 5000L
  
  val migrationTimeout = Config.config.getLong("cluster.colocated-actors.migration-timeout", MIGRATION_TIMEOUT)

  private val timer = new Timer("Group Management Timer")

  private val groups = new HashMap[String, List[MobileActorRef]] with SynchronizedMap[String, List[MobileActorRef]]
  /*private*/ val migrationTasks = new HashMap[String, GroupMigrationTask] with SynchronizedMap[String, GroupMigrationTask]
  
  def newGroupId = UUID.newUuid.toString

  private[mobile] def insert(ref: MobileActorRef, groupId: String): Unit = this.synchronized {
    val list = groups.getOrElseUpdate(groupId, Nil)
    // Registering group at the name service
    if (list == Nil) NameService.put(groupId, LocalTheater.node)
    groups.put(groupId, ref :: list)
  }
  
  private[mobile] def remove(ref: MobileActorRef, groupId: String): Unit = this.synchronized {
    groups.get(groupId).foreach { group =>
      val newGroup = group.filter(_ != ref)
      if (newGroup.size > 0) {
	groups.put(groupId, newGroup)
      } else {
	groups.remove(groupId)
	// If every actor has left the group, the group ceases to exist and is removed from the name service
	NameService.remove(groupId)
      }
    }
  }

  def group(groupId: String): Option[List[MobileActorRef]] = groups.get(groupId)
  def numberOfGroups = groups.size
  
  private[mobile] def startGroupMigration(groupId: String, destination: TheaterNode, nextTo: Option[String]): Unit = this.synchronized {
    val group = groups.get(groupId)
    val task = migrationTasks.get(groupId)
    (group, task) match {
      case (Some(group), None) =>
	val task = new GroupMigrationTask(groupId, group.size, destination, nextTo)
	migrationTasks.put(groupId, task)
	group.foreach(actor => actor ! PrepareToMigrate)
	timer.schedule(task, migrationTimeout)

      case _ => ()
    }
  }

  private[mobile] def readyToMigrate(actor: MobileActorRef): Unit = {
    migrationTasks.get(actor.groupId.get).foreach {
      _.actorReady(actor)
    }
  }
  
  private[mobile] def completeMigration(groupId: String) {
    migrationTasks.get(groupId).foreach {
      _.migrationCompleted()
    }
  }

  class GroupMigrationTask(groupId: String, groupSize: Int, destination: TheaterNode, nextTo: Option[String]) extends TimerTask {
    object GroupMigrationStatus extends Enumeration {
      val Waiting, Migrating, Migrated = Value
    }
    import GroupMigrationStatus._
    private var status: GroupMigrationStatus.Value = Waiting

    private var actorsReady = 0

    lazy val builder = ArrayBuilder.make[Array[Byte]]
    private val _lock = new Object
    
    override def run(): Unit = {
      _lock.synchronized { status = Migrating }
      val groupBytes = builder.result
      if (groupBytes.size > 0) 
	LocalTheater.migrateGroup(groupBytes, destination, nextTo)
      else
	migrationCompleted()
      
      groups.remove(groupId)
    }

    private[GroupManagement] def migrationCompleted() {
      status = Migrated
    }

    private[GroupManagement] def actorReady(actor: MobileActorRef) {
      var isLate = false
      _lock.synchronized {
	actorsReady = actorsReady + 1
	if (status == Waiting) builder += (actor.startMigration)
	else isLate = true
      }
      
      if (!isLate) {
	// If all actors are ready, execute the task immediately. Note that 'cancel()' will
	// return true if the task has not yet run.
	if (actorsReady == groupSize && cancel()) {
	  run()
	  migrationTasks.remove(groupId)
	}
      } else {
	currentGroupNode match {
	  case Some(node) if node != LocalTheater.node => LocalTheater.migrate(actor, node)
	  case _ => ()
	}
	if (actorsReady == groupSize) migrationTasks.remove(groupId)
      }
    }
    
    // Returns the current node of the groups identified by 'groupId'
    private def currentGroupNode: Option[TheaterNode] = {
      // If the migration is still going on, return the original destination (the parameter of
      // MoveGroupTo)
      if (status == Migrating) 
	Some(destination)
      else // If the migration is done, return the current theater based on name service
	NameService.get(groupId)
    }
  }
}
