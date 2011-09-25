package apps

import se.scalablesolutions.akka.mobile.Mobile
import se.scalablesolutions.akka.mobile.actor.MobileActor
import se.scalablesolutions.akka.mobile.actor.MobileActorRef
import se.scalablesolutions.akka.mobile.theater.LocalTheater
import se.scalablesolutions.akka.mobile.util.DefaultLogger
import se.scalablesolutions.akka.mobile.util.Logger
import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.mobile.actor.DetachedActor

trait ArmstrongMessage
case class Next(uuid: String) extends ArmstrongMessage
case class Start(maxRounds: Int) extends ArmstrongMessage
case object Token extends ArmstrongMessage

class ArmstrongActor extends MobileActor {
  import Armstrong._

  private var nextUuid: Option[String] = None
  private var next: MobileActorRef = null
  private var first = false
  private var before: Long = _
  private var rounds = 0
  private var maxRounds: Int = _
  private var heldMessage: Option[ArmstrongMessage] = None

  private var tries = 0
  private var maxTries = 5

  private def actorId: String = "[UUID " + self.uuid + "] "

  def receive = {
    case Next(uuid) if (next == null) =>
      //      logger.debug("%s NEXT recebido", actorId)
      nextUuid = Some(uuid)
      var nextOpt = MobileActorRef(uuid)
      while (!nextOpt.isDefined && tries < maxTries) {
        logger.debug("%s Tentativa %s de achar ator com UUID [%s]", actorId, tries, uuid)
        Thread.sleep(100)
        nextOpt = MobileActorRef(uuid)
        tries = tries + 1
      }

      //      logger.debug("%s Executando -- NEXT: [UUID %s] - %s", actorId, uuid, nextOpt)
      if (!nextOpt.isDefined) {
        logger.info("%s Impossível achar NEXT [UUID %s]", actorId, uuid)
        System.exit(1)
      }
      next = nextOpt.get
      logger.debug("%s --> [UUID %s]", actorId, uuid)
      if (heldMessage.isDefined) {
        self ! heldMessage.get
        heldMessage = None
        //        logger.debug("%s recebeu NEXT, repassando TOKEN", actorId)
      }
    //      logger.debug("%s Executando -- NEXT: [UUID %s]", actorId, uuid)

    case Start(max) if (next == null) =>
      heldMessage = Some(Start(max))
    //      logger.info("%s START recebido antes de NEXT", actorId)
    //      System.exit(3)

    case Start(max) =>
      logger.info("%s START recebido", actorId)
      first = true
      before = System.currentTimeMillis
      maxRounds = max
      rounds = 1
      //      logger.debug("TOKEN saindo para [UUID %s] em %s [local: %s  -  detached: %s]",
      //        next.uuid, next.node.format, next.isLocal, next.innerRef.isInstanceOf[DetachedActor]);
      next ! Token

    case Token if (next == null) =>
      heldMessage = Some(Token)
      logger.debug("%s guardando TOKEN", actorId)
    //      logger.info("%s TOKEN recebido antes de NEXT", actorId)
    //      System.exit(2)

    case Token if (next != null) =>
      logger.debug("%s TOKEN [%s - %s %s]", actorId, next.uuid, next.isLocal, next.node.format)
      //      logger.debug("TOKEN chegando em %s", actorId)
      if (first) {
        var after = System.currentTimeMillis
        var time = after - before
        logger.info("%s Time elapsed: %s ms", actorId, time)
        before = System.currentTimeMillis
        rounds = rounds + 1

        if (rounds <= maxRounds) {
          //          logger.debug("* * TOKEN saindo para [UUID %s] em %s [local: %s  -  detached: %s]",
          //            next.uuid, next.node.format, next.isLocal, next.innerRef.isInstanceOf[DetachedActor])
          next ! Token
        } else {
          logger.info("%s Terminado", actorId)
        }
      } else {
        //        logger.debug("TOKEN saindo para [UUID %s] em %s [local: %s  -  detached: %s]",
        //          next.uuid, next.node.format, next.isLocal, next.innerRef.isInstanceOf[DetachedActor])
        next ! Token
      }

    case any =>
      val logger = new Logger("logs/mobile-actors/" + self.uuid + ".log")
      logger.debug("Mensagem desconhecida recebida: %s", any)
  }

  override def beforeMigration() {
    logger.debug("%s Antes da migração", actorId)
    next = null
    tries = 0
  }

  override def afterMigration() {
    logger.debug("%s Depois da migração", actorId)
    //    if (next == null) {
    nextUuid.foreach { uuid => self ! Next(uuid) }
    // 	var nextOpt = MobileActorRef(uuid)
    //     while (!nextOpt.isDefined && tries < maxTries) {
    // 	  Thread.sleep(1000)
    // 	  nextOpt = MobileActorRef(uuid)
    // 	  tries = tries + 1
    // 	}

    //     next = nextOpt.getOrElse(throw new IllegalArgumentException("UUID invalido: " + uuid))
    //     logger.debug("%s Executando -- NEXT: [UUID %s]", actorId, uuid)
    //   }
    // }
  }
}

object Armstrong extends Logging {
  import Mobile._

  private val NUMBER_OF_ACTORS = 100
  private val MAX_ROUNDS = 3
  private val BATCH_SIZE = 1

  lazy val logger = DefaultLogger // new Logger("logs/mobile-actors/mobile-actors.log")

  class Configuration() {
    var theaterName: String = _
    var start: Boolean = false
    var numActors: Int = NUMBER_OF_ACTORS
    var maxRounds: Int = MAX_ROUNDS
    var batchSize: Int = BATCH_SIZE
  }

  def main(args: Array[String]) {
    val arguments: List[String] =
      if (args.length == 1) args(0).split(' ').toList
      else args.toList
    val config = configure(arguments)

    val number = config.numActors
    val maxRounds = config.maxRounds
    val batchSize = config.batchSize

    if (config.theaterName != null && config.theaterName.trim.size > 0) {
      Mobile.startTheater(config.theaterName)
      logger.info("Iniciando um teatro")
    }

    if (config.start) {
      logger.info("Executando o desafio de Armstrong com: \n" +
        "\tNúmero de atores: %s\n" +
        "\tNúmero de rodadas: %s\n" +
        "\tTamanho de grupos de atores co-locados: %s",
        number, maxRounds, batchSize)

      val launchActors: Int => (MobileActorRef, MobileActorRef) = launchActorsFromClass
      //      val launchActors: Int => (MobileActorRef, MobileActorRef) = launchActorsWithConstructor

      var remaining = number
      var (first, previous) = launchActors(batchSize)
      remaining = remaining - batchSize
      while (remaining > 0) {
        if (remaining % 10000 == 0) {
          println((number - remaining))
        }
        var (head, last) = launchActors(batchSize)
        //        logger.debug("Enviando Next(%s) para [UUID %s]", head.uuid, previous.uuid)
        previous ! Next(head.uuid)
        previous = last
        remaining = remaining - batchSize
      }
      previous ! Next(first.uuid)

      logger.info("Esperando...")
      //      System.in.read()
      //            Thread.sleep(10000)
      logger.info("Começando...")
      first ! Start(maxRounds)
    }
  }

  private def launchActorsWithConstructor(number: Int): (MobileActorRef, MobileActorRef) = {
    if (number == 1) {
      val actor = Mobile.launch(new ArmstrongActor)
      (actor, actor)
    } else {
      // TODO generalizar
      val actors = Mobile.launch(new ArmstrongActor, new ArmstrongActor)
      val last = chainActors(actors)
      (actors.head, last)
    }
  }

  private def launchActorsFromClass(number: Int): (MobileActorRef, MobileActorRef) = {
    if (number == 1) {
      val actor = Mobile.launch[ArmstrongActor]
      (actor, actor)
    } else {
      val actors = Mobile.launch[ArmstrongActor](number)
      val last = chainActors(actors)
      (actors.head, last)
    }
  }

  def configure(args: List[String], _config: Option[Configuration] = None): Configuration = {
    val config = _config.getOrElse(new Configuration)
    val remainingArgs: List[String] = args match {
      case "-t" :: theaterName :: tail =>
        config.theaterName = theaterName
        tail
      case "-start" :: tail =>
        config.start = true
        tail
      case "-n" :: nActors :: tail =>
        config.numActors = nActors.toInt
        tail
      case "-r" :: maxRounds :: tail =>
        config.maxRounds = maxRounds.toInt
        tail
      case "-b" :: batchSize :: tail =>
        config.batchSize = batchSize.toInt
        tail
      case _ :: tail =>
        tail
      case Nil =>
        Nil
    }
    if (remainingArgs == Nil)
      config
    else
      configure(remainingArgs, Some(config))
  }

  def chainActors(actors: List[MobileActorRef]): MobileActorRef = actors match {
    case first :: second :: tail =>
      first ! Next(second.uuid)
      chainActors(second :: tail)

    case last :: Nil =>
      last

    case Nil => null
  }
}
