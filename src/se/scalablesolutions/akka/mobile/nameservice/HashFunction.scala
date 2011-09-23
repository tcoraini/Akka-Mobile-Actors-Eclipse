package se.scalablesolutions.akka.mobile.nameservice

trait HashFunction {
  def hash(key: String, nodes: Int): Int
}

class DefaultHashFunction extends HashFunction {

  def hash(key: String, nodes: Int): Int = {
    // We expect UUID's to be of type Long converted to String
    val result = 
      try {
        key.toLong
      } catch {
        case nfe: NumberFormatException =>
          var hash = 5381L
          for (c <- key)
            hash = ((hash << 5) + hash) + c; // hash * 33 + c
          hash abs
      }

    (result % nodes) toInt
  }
}
    

