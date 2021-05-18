package db

import slick.dbio.Effect
import slick.jdbc.H2Profile.api._
import slick.lifted.{ForeignKeyQuery, ProvenShape}

import scala.concurrent.Future
import drpg.Drpg.system.dispatcher

import RoleQueryRepository._

case class Scenario(id: Option[Int] = None,
                    name: String,
                    description: String,
                    numberOfRoles: Int,
                    numberOfRounds: Int,
                    roundDuration: Int,
                    votesToLose: Int,
                    killerRoleId: Option[Int] = None,
                    killerWinMsg: String,
                    peopleWinMsg: String)

object ScenarioUtil {
  def fromMap(map: Map[String, String]): Scenario = Scenario(
    None,
    map("name"),
    map("description"),
    map("numberOfRoles").toInt,
    map("numberOfRounds").toInt,
    map("roundDuration").toInt,
    map("votesToLose").toInt,
    None,
    map("killerWinMsg"),
    map("peopleWinMsg")
  )
}

class ScenarioTable(tag: Tag) extends Table[Scenario](tag, "scenario") {
  def id: Rep[Int] = column("id", O.PrimaryKey, O.AutoInc)

  def name: Rep[String] = column("name")

  def description: Rep[String] = column("description")

  def numberOfRoles: Rep[Int] = column("numberOfRoles")

  def numberOfRounds: Rep[Int] = column("numberOfRounds")

  def roundDuration: Rep[Int] = column("roundDuration")

  def votesToLose: Rep[Int] = column("votesToLose")

  def killerRoleId: Rep[Option[Int]] = column("killerRoleId")

  def killerWinMsg: Rep[String] = column("killerWinMsg")

  def peopleWinMsg: Rep[String] = column("peopleWinMsg")

  def killer: ForeignKeyQuery[RoleTable, Role] =
    foreignKey("killer", killerRoleId, RoleQueryRepository.allRole)(_.id.?)

  override def * : ProvenShape[Scenario] = (
      id.?,
      name,
      description,
      numberOfRoles,
      numberOfRounds,
      roundDuration,
      votesToLose,
      killerRoleId,
      killerWinMsg,
      peopleWinMsg
    ).mapTo[Scenario]
}

object ScenarioQueryRepository {
  val allScenario = TableQuery[ScenarioTable]

  def addScenario(scenario: Scenario): Future[Int] = db.run(
    allScenario += scenario
  )

  def getLastId: Future[Option[Int]] = db.run(
    allScenario
      .sortBy(_.id)
      .result
  ).map(_.last.id)

  def getScenarioById(scenarioId: Int): Future[Option[Scenario]] = db.run(
    allScenario
    .filter(_.id === scenarioId)
    .result
    .headOption
  )

  def isScenarioComplete(scenarioId: Int): Future[Boolean] = getScenarioById(scenarioId).flatMap {
    case Some(scenario) => countRoleInfoByScenario(scenario.id.get).map{ count =>
     count == (scenario.numberOfRounds * scenario.numberOfRoles)
    }
    case None => Future(false)
  }

  def getCompletedScenario: Future[Seq[Scenario]] = db.run(
    allScenario
      .result
  ).flatMap { scenario => Future.sequence(scenario
    .map(_.id.get)
    .map(isScenarioComplete)).map {
    isCompleted => (scenario zip isCompleted)
      .filter { case (_, isCompleted) => isCompleted }
      .map    { case (scenario, _ ) => scenario }
    }
  }
}



