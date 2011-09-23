package se.scalablesolutions.akka.mobile.examples.s4.wordcount

import se.scalablesolutions.akka.mobile.examples.s4.S4

import scala.io.Source

object WordCounter {
  val numberOfSortPEs = 4 // TODO ser configurável

  val sortPEInterval = 5000 // 5 seconds

  val mergePEInterval = 15000 // 15 seconds

  val K = 100 // Top K words from each SortPE

  val mergePEKey = 1234

  val partialResultsFile = "wc-partial-results.txt"

  def init(): Unit = {
    S4.registerPE[QuoteSplitterPE, QuoteEvent]
    S4.registerPE[WordCountPE, WordEvent]
    S4.registerPE[SortPE, UpdateCountEvent]
    S4.registerPE[MergePE, PartialTopKEvent]
  }

  def count(quote: String): Unit = {
    S4.dispatch(QuoteEvent(quote))
  }

  def countFromFile(filename: String): Unit = {
    for (line <- Source.fromFile(filename).getLines) {
      count(line)
    }
  }
	
}
