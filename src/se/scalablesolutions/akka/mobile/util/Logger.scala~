package tests

import se.scalablesolutions.akka.mobile.theater.LocalTheater
import se.scalablesolutions.akka.actor.Actor

import java.util.Date
import java.text.SimpleDateFormat
import java.io.OutputStream
import java.io.FileOutputStream

class Logger(filename: String) {

  private val dateFormat = new SimpleDateFormat("[dd/MM/yyyy - HH:mm:ss.SSS]")
  private val console = System.out
  private val loggerActor = Actor.actorOf(new FileLoggerActor(filename))

  loggerActor.start()
  
  def debug(message: String, args: Any*) = {
    var result = message + "\n"
    for (arg <- args) {
      result = result.replaceFirst("%s", arg.toString)
    }

    val formatted = format(result)
    
    /*outputStream.write(formatted.getBytes)
    outputStream.flush()*/
    loggerActor ! Log(formatted)
  }
  
  def info(message: String, args: Any*) = {
    var result = message + "\n"
    for (arg <- args) {
      result = result.replaceFirst("%s", arg.toString)
    }

    val formatted = format(result)

    console.print(formatted)
    loggerActor ! Log(formatted)
/*    outputStream.write(formatted.getBytes)
    outputStream.flush()*/
  }

  private def format(message: String): String = {
    dateAndTime + " " + 
//    threadName  + " " +
    currentNode + " - " +
    message
  }
    
  private def currentNode: String = {
    try {
      LocalTheater.node.format
    } catch {
      case e:Exception => ""
    }
  }
  
  private def threadName: String = "[" + Thread.currentThread.getName + "]"

  private def dateAndTime: String = dateFormat.format(new Date)
}

case class Log(message: String)
class FileLoggerActor(filename: String) extends Actor {
  def receive = {
    case Log(message: String) =>
      val out = new FileOutputStream(filename, true)
      out.write(message.getBytes)
      out.flush()
      out.close()
  }
}
