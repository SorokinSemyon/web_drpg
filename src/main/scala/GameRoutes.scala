package drpg

import akka.actor.ActorRef
import akka.http.scaladsl.model.{Multipart, StatusCodes}
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.Sink
import db.RoleInfoQueryRepository.getRoleInfoByRoleId
import db.RoleQueryRepository.{getRoleById, getRolesByScenario}
import db.{Role, RoleUtil, Scenario}
import db.RoleUtil.encodeRole
import db.ScenarioQueryRepository.{getCompletedScenario, getScenarioById}
import drpg.CreateRoutes.htmlContent
import util.Template.{baseHtml, htmlFile, putData, putFor}
import io.circe.syntax._

import scala.util.{Failure, Random, Success}
import drpg.Drpg.system
import drpg.Drpg.system.dispatcher
import session.Auth.myRequiredSession

import java.util.{Calendar, Date}
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.math.{abs, min}

object Game {
  case class GameEntity(actor: ActorRef = null,
                        scenario: Scenario,
                        usersMessages: Map[String, String] = Map.empty,
                        roles: Map[String, (Role, Int)] = Map.empty,
                        round: Int = 1,
                        state: String = "Choose role",
                        timer: (Int, Int) = (0, 0))

  val games: TrieMap[Int, GameEntity] = TrieMap.empty

  def addUserMessage(gameId: Int, who: String, msg:String): Unit = {
    games.get(gameId) match {
      case Some(game) =>
        val newUsersMessages = who.take(1) match {
          case "!" => game.usersMessages.filter { case (name, _) => name != who.drop(1) }
          case _ => game.usersMessages + (who -> msg)
        }
        games.update(gameId, game.copy(usersMessages = newUsersMessages))
      case None => ()
    }
  }

  def seqToOption[T](s: Seq[Option[T]]): Option[Seq[T]] = {
    @tailrec
    def seqToOptionHelper(s: Seq[Option[T]], acc: Seq[T] = Seq[T]()): Option[Seq[T]] = {
      s match {
        case Some(head) :: Nil => Option(head +: acc)
        case Some(head) :: tail => seqToOptionHelper(tail, head +: acc)
        case _ => None
      }
    }
    seqToOptionHelper(s)
  }

  def saveRoles(gameId: Int) =
    games.get(gameId) match {
      case Some(game) =>
        Future.sequence(
          game.usersMessages.map { case (who, roleId) =>
            getRoleById(roleId.toInt).map(roleOpt => roleOpt.map(role => (who, role)))
          }.toSeq
        )
          .map(seqToOption)
          .map {
            case Some(data) =>
              val roles = data.toMap.map { case (who -> role) => who -> (role, 0)}
              games.update(gameId, game.copy(roles = roles, usersMessages = Map.empty, state="Read"))
            case None => ()
          }
      case None => Future(())
    }

  def voting(gameId: Int): Unit =
    games.get(gameId) match {
      case Some(game) =>
        if (game.usersMessages.isEmpty) {
          ()
        } else {
          val voteMap = game.usersMessages.values.groupBy(identity).mapValues(_.size).toSeq
          val newRoles = game.roles.map { case who -> v =>
            val (role, curVotes) = v
            val votes = voteMap
              .find { case (roleId, _) => roleId.toInt == role.id.get }
              .map { case (_, votes) => votes }
              .getOrElse(0)

            who -> (role, curVotes + votes)
          }
          games.update(gameId, game.copy(state = "Read", roles = newRoles, usersMessages = Map.empty, round = game.round + 1))
          println("sas")
        }
      case None => ()
    }

  def setTimer(gameId: Int, minutes: Int, seconds: Int): Option[(Int, Int)] =
    games.get(gameId) match {
      case Some(game) if game.state == "Read" =>
        println("sis")
        games.update(gameId, game.copy(timer = ((minutes + game.scenario.roundDuration) % 60, seconds), state = "Discuss", usersMessages = Map.empty))
        Some((minutes + game.scenario.roundDuration) % 60, seconds)
      case Some(game) =>
        Some(game.timer)
      case None => None
    }

  def stopDiscussion(gameId: Int): Unit = {
    games.get(gameId) match {
      case Some(game) => games.update(gameId, game.copy(state = "Voting", usersMessages = Map.empty))
      case None => ()
    }
  }



  @tailrec
  def nextId(): Int = {
    val id = abs(Random.nextInt())
    games.contains(id) match {
      case true  => nextId()
      case false => id
    }
  }

  def createGame(scenario: Scenario): Int = {
    val gameId = nextId()
    games.addOne(gameId, GameEntity(scenario = scenario))
    gameId
  }
}

object GameRoutes {
  val chooseScenario: Route = path("choose_scenario") {
      onComplete(getCompletedScenario) {
        case Failure(exception) => complete(StatusCodes.InternalServerError)
        case Success(scenarios) => htmlContent(putFor(
          template = putFor(
            template = baseHtml("game/choose_scenario.html", "Выбор сценария"),
            label = "scenarioDescription",
            seqData = scenarios.map(s => Map("description" -> s.description, "id" -> s.id.get.toString))
          ),
          label    = "scenarioName",
          seqData  = scenarios.map(s => Map("name" -> s.name, "id" -> s.id.get.toString))
        ))
      }
  }

  val startGame: Route = path("start_game") {
    entity(as[Multipart.FormData]) { data =>
      val dataToMap = Sink.foldAsync[Map[String, String], BodyPart](Map.empty) { (acc, part) =>
        Unmarshal(part.entity).to[String].map { value =>
          acc + (part.name -> value)
        }
      }

      onComplete( for {
        map <- data.parts.runWith(dataToMap)
        scenarioId = map.get("scenarioId")
        scenario <- getScenarioById(scenarioId.getOrElse("0").toInt)
      } yield scenario)
      {
        case Failure(_) => complete(StatusCodes.InternalServerError)
        case Success(scenarioOpt) => scenarioOpt match {
          case Some(scenario) => redirect(s"/game/${Game.createGame(scenario)}", StatusCodes.SeeOther)
          case None => redirect("/choose_scenario", StatusCodes.SeeOther)
        }
      }
    }
  }

  val gameRoute: Route = path("game" / IntNumber) { gameId =>
    Game.games.get(gameId) match {
      case Some(game) =>
        onComplete(getRolesByScenario(game.scenario.id.get))
        {
          case Failure(_) => complete(StatusCodes.InternalServerError)
          case Success(roles) => htmlContent(putFor(
            template = putFor(
              template = putData(
                template = baseHtml("game/game.html", game.scenario.name),
                data     = Map("gameId" -> gameId.toString, "numberOfRoles" -> game.scenario.numberOfRoles.toString)
              ),
              label = "roleDescription",
              seqData = roles.map(RoleUtil.toMap)
            ),
            label    = "roleName",
            seqData  = roles.map(r => Map("name" -> r.name, "id" -> r.id.get.toString))
          ))
        }

      case None => complete(StatusCodes.NotFound)
    }
  }

  val saveRoles: Route = path("save_roles" / IntNumber) { gameId =>
    onComplete(Game.saveRoles(gameId)) {
      case Failure(_) => complete(StatusCodes.InternalServerError)
      case Success(_) => complete(StatusCodes.OK)
      }
    }

  val Voting: Route = path("voting" / IntNumber) { gameId =>
    synchronized(Game.voting(gameId))
    complete(StatusCodes.OK)
  }


  val usersMessage: Route = path("users_message" / IntNumber) { gameId =>
    Game.games.get(gameId) match {
      case Some(game) => complete(game.usersMessages.asJson.toString())
      case None       => complete(StatusCodes.NotFound)
    }
  }

  val gameRoles: Route = path("game_roles" / IntNumber) { gameId =>
    Game.games.get(gameId) match {
      case Some(game) => complete(game.roles.asJson.toString())
      case None       => complete(StatusCodes.NotFound)
    }
  }

  val userRoleInfo: Route = path("user_role_info" / IntNumber) { gameId =>
    myRequiredSession { session =>
      ctx =>
        Game.games.get(gameId) match {
          case Some(game) => game.roles.get(session.username) match {
            case Some((role, _)) => getRoleInfoByRoleId(role.id.get).flatMap { roleInfo =>
              ctx.complete(roleInfo.map {
                info => Map("info" -> info.info, "roundNumber" -> info.roundNumber.toString)
              }.asJson.toString)
            }
            case None => ctx.complete(StatusCodes.NotFound)
          }
          case None => ctx.complete(StatusCodes.NotFound)
        }
    }
  }

  val gameRound: Route = path("game_round" / IntNumber) { gameId =>
    Game.games.get(gameId) match {
      case Some(game) => complete(game.round.toString)
      case None => complete(StatusCodes.NotFound)
    }
  }

  val isGameOver: Route = path("is_game_over" / IntNumber) { gameId =>
    Game.games.get(gameId) match {
      case Some(game) if game.round > game.scenario.numberOfRounds => complete("true")
      case Some(_) => complete("false")
      case None => complete(StatusCodes.NotFound)
    }
  }

  val gameState: Route = path("game_state" / IntNumber) { gameId =>
    Game.games.get(gameId) match {
      case Some(game) => complete(game.state)
      case None => complete(StatusCodes.NotFound)
    }
  }

  val gameTimer: Route = path("game_timer" / IntNumber) { gameId =>
    val time = Calendar.getInstance.getTime
    synchronized {
      Game.setTimer(gameId, time.getMinutes, time.getSeconds) match {
        case Some((minutes, seconds)) =>
          complete(s"$minutes, $seconds")
        case None => complete(StatusCodes.NotFound)
      }
    }
  }

  val stopDiscussion: Route = path("stop_discussion"/ IntNumber) { gameId =>
    Game.stopDiscussion(gameId)
    complete(StatusCodes.OK)
  }

  val gameOver: Route = path("game_over" / IntNumber) { gameId =>
    Game.games.get(gameId) match {
      case Some(game) =>
        val votesToLose = game.scenario.votesToLose
        val killerRoleId = game.scenario.killerRoleId
        val msg = game.roles.values.exists { case (role, votes) => killerRoleId.contains(role.id.get) && votes >= votesToLose } match {
          case true => game.scenario.peopleWinMsg
          case false => game.scenario.killerWinMsg
        }
        Game.games.remove(gameId)
        htmlContent(putData(
          template = baseHtml("index.html", "Конец игры"),
          data = Map("content" -> msg)
        ))
      case None => complete(StatusCodes.NotFound)
    }
  }

  val gameRoutes: Route =
    chooseScenario ~
    startGame ~
    gameRoute ~
    usersMessage ~
    saveRoles ~
    gameRoles ~
    userRoleInfo ~
    Voting ~
    isGameOver ~
    gameRound ~
    gameState ~
    gameTimer ~
    stopDiscussion ~
    gameOver
}
