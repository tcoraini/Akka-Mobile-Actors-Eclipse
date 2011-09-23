package se.scalablesolutions.akka.mobile.theater.profiler

import se.scalablesolutions.akka.mobile.theater.TheaterNode

import java.util.Comparator

/**
 * IMRecord is a record for incoming messages for each actor. The value 'count' stands for how many
 * messages the actor with UUID 'uuid' received from any actor in the node 'from'.
 */
 
case class IMRecord(uuid: String, from: TheaterNode) {
  private var _count: Int = 0

  def count = _count
  private[mobile] def count_=(value: Int) = this.synchronized { _count = value }

  private[mobile] def increment(): Unit = this.synchronized { _count = _count + 1 }

  private[mobile] def reset = { _count = 0 }

  override def toString = "IMRecord{ UUID: " + uuid + "   FROM: " + from + "   COUNT: " + count + " }"
  
  /**
   * Two IMRecord's are equal if their 'uuid' and 'from' fields are equal, regardless
   * of the 'count' field.
   */
   // TODO isso provavelmente eh desnecessario, o equals do case class acho que ja vai cuidar disso (por algum
   // motivo eu achava que nao)
  // override def equals(that: Any): Boolean = that match {
  //   case imr: IMRecord => 
  //     imr.uuid == this.uuid && imr.from == this.from

  //   case _ => false
  // }

  // override def hashCode = uuid.hashCode * from.hashCode
}

object IMRecord {

  /**
   * This comparator is used for putting IMRecord's instances in a Priority Queue.
   * What is intended here is: the least record is the one with the biggest 'count' value,
   * so it will be in the head of the queue.
   * 
   * Note that the ordering imposed by this comparator *is not consistent with equals* for IMRecord
   * objects. This means that if 'compare(imr1, imr2) == 0' is TRUE, not necessarily 'imr1.equals(imr2)'
   * will be TRUE too. Actually, the 'equals' method in the IMRecord class doesn't even take the 'count'
   * field into account.
   *
   * This is OK for the use in the Priority Queue in the Profiler class, though.
   */
  @serializable class IMRecordComparator extends Comparator[IMRecord] {
    def compare(o1: IMRecord, o2: IMRecord) = o2.count - o1.count
  }

  def comparator = new IMRecordComparator
    
}
