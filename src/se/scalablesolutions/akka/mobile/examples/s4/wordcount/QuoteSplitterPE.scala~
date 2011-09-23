package se.scalablesolutions.akka.mobile.examples.s4.wordcount

import se.scalablesolutions.akka.mobile.examples.s4.ProcessingElement

import scala.collection.mutable.HashMap

class QuoteSplitterPE extends ProcessingElement[QuoteEvent] {
  def process(event: QuoteEvent) = {
    val quote = event.attribute.toLowerCase
    val words = quote.split(' ')
    val wordsMap = new HashMap[String, Int]
   
    words.foreach(
      word => wordsMap.get(word) match {
		case Some(num) => wordsMap.put(word, num + 1)
		case None => wordsMap.put(word, 1)
      }
    )

    for ((word, count) <- wordsMap.toList) {
      emit(WordEvent(word, count))
    }
  }
}
