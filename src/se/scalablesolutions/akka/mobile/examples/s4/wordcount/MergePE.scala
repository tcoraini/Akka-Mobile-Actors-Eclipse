package se.scalablesolutions.akka.mobile.examples.s4.wordcount

import se.scalablesolutions.akka.mobile.examples.s4.ProcessingElement

import java.io.FileWriter
import scala.collection.mutable.HashMap

@serializable class MergePE(val eventPrototype: PartialTopKEvent) extends ProcessingElement[PartialTopKEvent] with Runnable {
  val words = new HashMap[String, Int]

  private var writer: FileWriter = _
  
  override def init = {
    super.init
    writer = new FileWriter(WordCounter.partialResultsFile)
    // Starts this as a new Thread
    new Thread(this).start()
  }

  def process(event: PartialTopKEvent) = {
    val listOfWords = event.attribute
    listOfWords.foreach(wordTuple => words.put(wordTuple._1, wordTuple._2))
  }

  override def run(): Unit = {
    while (isRunning) {
      Thread.sleep(WordCounter.mergePEInterval)

      if (!words.isEmpty) {
	var listOfWords = words.toList
	// Sorts the list of entries in descending order related to the count (second member of the tuple)
	listOfWords = listOfWords.sortWith((wordTupleA, wordTupleB) => wordTupleA._2 > wordTupleB._2)
	// Take first K elements
	listOfWords = listOfWords.take(WordCounter.K)
	// Print the top K words
	printPartialResult(listOfWords)
      }
    }
    writer.close()
  }

  private def printPartialResult(listOfWords: List[Tuple2[String, Int]]): Unit = {
    writer.write("-" * 100)
    writer.write("\nDate: " + new java.util.Date + "\n")
    writer.write("Number of words: " + listOfWords.size + "\n\n")
    for ((word, count) <- listOfWords) {
      writer.write(word + ": " + count + "\n")
    }
    writer.write("-" * 100)
    writer.write("\n\n")
    writer.flush()
  }
}
