package se.scalablesolutions.akka.mobile.examples.s4.wordcount

import se.scalablesolutions.akka.mobile.examples.s4.ProcessingElement

import scala.collection.mutable.HashMap

@serializable class SortPE(val eventPrototype: UpdateCountEvent) extends ProcessingElement[UpdateCountEvent] with Runnable {
  private val words = new HashMap[String, Int]
  
  override def init = {
    super.init
    // Starts this as a new Thread
    new Thread(this).start()
  }

  def process(event: UpdateCountEvent) = {
    val word = event.attribute._1
    val count = event.attribute._2

    words.get(word) match {
      case Some(cnt) => words.put(word, cnt + count)
      case None => words.put(word, count)
    }
  }

  override def run(): Unit = {
    while (isRunning) {
      Thread.sleep(WordCounter.sortPEInterval)
      
      if (!words.isEmpty) {
	var listOfWords = words.toList
	// Sorts the list of entries in descending order related to the count (second member of the tuple)
	listOfWords = listOfWords.sortWith((wordTupleA, wordTupleB) => wordTupleA._2 > wordTupleB._2)
	// Take first K elements
	listOfWords = listOfWords.take(WordCounter.K)
	// Emit the resulting list
	emit(PartialTopKEvent(WordCounter.mergePEKey, listOfWords))
      }
    }
  }
}
