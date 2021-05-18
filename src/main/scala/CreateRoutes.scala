package drpg

import util.Template._
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Multipart, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Route, StandardRoute}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.Sink
import db.RoleInfoQueryRepository.{addRoleInfo, getRoleInfoByRoleId}
import db.RoleQueryRepository.{addRole, getRoleById, getRolesByScenario, getScenarioByRoleId}
import db.{Role, RoleInfo, RoleInfoUtil, RoleUtil, ScenarioUtil}
import db.ScenarioQueryRepository.{addScenario, getCompletedScenario, getLastId, getScenarioById}
import drpg.Drpg.system
import drpg.Drpg.system.dispatcher

import scala.concurrent.Future
import scala.io.Source
import scala.util.{Failure, Success}

object CreateRoutes {

  val htmlContent: String => StandardRoute =
    html => complete(HttpEntity( ContentTypes.`text/html(UTF-8)`, html))

  val createScenario: Route = path("create_scenario") {
    htmlContent(baseHtml("create/create_scenario.html", "Создать сценарий"))
  }

  val saveScenario: Route = path("save_scenario") {
    entity(as[Multipart.FormData]) { data =>
      val dataToMap = Sink.foldAsync[Map[String, String], BodyPart](Map.empty) { (acc, part) =>
          Unmarshal(part.entity).to[String].map { value =>
            acc + (part.name -> value)
          }
        }

      onComplete(
        data.parts.runWith(dataToMap).flatMap { map =>
          addScenario(ScenarioUtil.fromMap(map))
          getLastId
        }.flatMap {
          case Some(lastId) => Future.successful(redirect(s"/roles/$lastId", StatusCodes.SeeOther))
          case None => Future.failed(new RuntimeException)
        }
      )
      {
        case Failure(exception) => complete(exception)
        case Success(redirect) => redirect
      }
    }
  }

  val Roles: Route = path("roles" / IntNumber) { scenarioId =>
      onComplete(
        getRolesByScenario(scenarioId).map { roles =>
          (roles, roles.map(role => RoleUtil.toMap(role)))
        }.flatMap { case (roles, seqData) =>
          getScenarioById(scenarioId).map {
            case Some(scenario) =>
              putIf(
                template  = putFor(
                  template = putData(baseHtml("create/roles.html", s"Роли"), Map("scenarioName" -> scenario.name)),
                  label    = "role",
                  seqData  = seqData
                ),
                label     = "empty",
                data      = Map("scenarioId" -> scenarioId.toString),
                condition = roles.size < scenario.numberOfRoles
              )
            case None => "Такого сценария нет"
          }
        }
      )
      {
        case Failure(exception) => complete(exception)
        case Success(html) => htmlContent(html)

      }
    }

  val createRole: Route = path("create_role" / IntNumber) { scenarioId =>
    onComplete(getScenarioById(scenarioId)) {
      case Failure(_) =>complete(StatusCodes.InternalServerError)
      case Success(scenarioOpt) => scenarioOpt match {
        case Some(scenario) =>
          htmlContent(
            putData(
              template = baseHtml("create/create_role.html", s"Создать персонажа"),
              data     = Map("scenarioName" -> scenario.name)
            )
          )
        case None => complete(StatusCodes.NotFound)
      }
    }
  }

  val saveRole: Route = path("save_role" / Segment) { scenarioId =>
    entity(as[Multipart.FormData]) { data =>
      val dataToMap = Sink.foldAsync[Map[String, String], BodyPart](Map.empty) { (acc, part) =>
        Unmarshal(part.entity).to[String].map { value =>
          acc + (part.name -> value)
        }
      }

      onComplete(
        data.parts.runWith(dataToMap).flatMap { map =>
          addRole(RoleUtil.fromMap(map + ("scenarioId" -> scenarioId)))
        }
      ) {
        case Failure(exception) => complete(exception)
        case Success(_) => redirect(s"/roles/$scenarioId", StatusCodes.SeeOther)
      }
    }
  }

  val roleInfo: Route = path("role_info" / IntNumber) { roleId =>
    onComplete( for {
      role <- getRoleById(roleId)
      scenario <- getScenarioByRoleId(roleId)
      roleInfo <- getRoleInfoByRoleId(roleId)
    } yield (role, scenario, roleInfo)) {
      case Failure(_) => complete(StatusCodes.InternalServerError)
      case Success((Some(role), Some(scenario), roleInfo)) =>
        val seqData = roleInfo.map(info => RoleInfoUtil.toMap(info))
        htmlContent(
          putData(
            template = putIf(
              template  = putFor(baseHtml("create/role_info.html", "Карточки истории"), "role_info", seqData),
              label     = "empty",
              data      = Map("roleId" -> roleId.toString),
              condition = roleInfo.size < scenario.numberOfRounds
            ),
            data = Map(
              "roleName" -> role.name,
              "scenarioName" -> scenario.name,
            )
          )
        )
    }
  }

  val createRoleInfo: Route = path("create_info" / IntNumber) { roleId =>
    onComplete( for {
        role <- getRoleById(roleId)
        scenario <- getScenarioByRoleId(roleId)
        roleInfo <- getRoleInfoByRoleId(roleId)
      } yield (role, scenario, roleInfo))
    {
      case Failure(_) => complete(StatusCodes.InternalServerError)
      case Success((Some(role), Some(scenario), roleInfo)) => htmlContent(
        putData(
          template = baseHtml("create/create_role_info.html", "Создать карточку истории"),
          data     = Map(
            "roleName" -> role.name,
            "scenarioName" -> scenario.name,
            "lastRoleInfo" -> s"${roleInfo.size + 1}"
          )
        )
      )
    }
  }

  val saveRoleInfo: Route = path("save_role_info" / IntNumber ) { roleId =>
    entity(as[Multipart.FormData]) { data =>
      val dataToMap = Sink.foldAsync[Map[String, String], BodyPart](Map.empty) { (acc, part) =>
        Unmarshal(part.entity).to[String].map { value =>
          acc + (part.name -> value)
        }
      }

      onComplete(
        data.parts.runWith(dataToMap).flatMap { map =>
          addRoleInfo(roleId, map("info"))
        }
      ) {
        case Failure(exception) => complete(exception)
        case Success(_) => redirect(s"/role_info/$roleId", StatusCodes.SeeOther)
      }
    }
  }

  val indexRoute: Route = path("index"){
    htmlContent(putData(
      template = baseHtml("index.html", "Привет"),
      data = Map("content" -> Source.fromResource("hello.txt").getLines.mkString("\n"))
    ))
  }

  val createRoutes: Route = createScenario ~
    saveScenario ~
    Roles ~
    createRole ~
    saveRole ~
    roleInfo ~
    createRoleInfo ~
    saveRoleInfo ~
    indexRoute
}
