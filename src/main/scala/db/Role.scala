package db

import slick.dbio.Effect
import slick.jdbc.H2Profile.api._
import slick.lifted.{ForeignKeyQuery, ProvenShape}
import ScenarioQueryRepository._
import RoleInfoQueryRepository._

import scala.concurrent.Future
import drpg.Drpg.system.dispatcher
import io.circe.Encoder

case class Role(id: Option[Int] = None,
                name: String,
                description: String,
                scenarioId: Int,
                imageUrl: String)

object RoleUtil {
  implicit val encodeRole: Encoder[Role] =
       Encoder.forProduct4("id", "name", "scenarioId", "imageUrl")((role: Role) =>
         (role.id.get, role.name, role.description, role.imageUrl))

  def fromMap(map: Map[String, String]): Role = Role(
    None,
    map("name"),
    map("description"),
    map("scenarioId").toInt,
    map("imageUrl")
  )

  def toMap(role: Role): Map[String, String] = Map(
    "id" -> role.id.get.toString,
    "name" -> role.name,
    "description" -> role.description,
    "imageUrl" -> role.imageUrl
  )
}

class RoleTable(tag: Tag) extends Table[Role](tag, "role") {
  def id: Rep[Int] = column("id", O.PrimaryKey, O.AutoInc)

  def name: Rep[String] = column("name")

  def description: Rep[String] = column("description")

  def scenarioId: Rep[Int] = column("scenarioId")

  def imageUrl: Rep[String] = column("imageUrl")

  def scenario: ForeignKeyQuery[ScenarioTable, Scenario] =
    foreignKey("scenario", scenarioId, ScenarioQueryRepository.allScenario)(_.id)

  override def * : ProvenShape[Role] = (
    id.?,
    name,
    description,
    scenarioId,
    imageUrl
    ).mapTo[Role]
}

object RoleQueryRepository {
  val allRole = TableQuery[RoleTable]

  def addRole(role: Role): Future[Int] = db.run(allRole += role)

  def getRolesByScenario(scenarioId: Int): Future[Seq[Role]] = db.run(
    allRole
      .filter(_.scenarioId === scenarioId)
      .result
  )

  def getRoleById(roleId: Int): Future[Option[Role]] = db.run(
    allRole
      .filter(_.id === roleId)
      .result
      .headOption
  )

  def getScenarioByRoleId(roleId: Int): Future[Option[Scenario]] = db.run(
    allRole
      .filter(_.id === roleId)
      .map(_.scenarioId)
      .result
      .headOption
  ).flatMap {
      case Some(scenarioId) => getScenarioById(scenarioId)
      case None => Future.failed(new IllegalArgumentException)
    }

  def countRoleInfoByScenario(scenarioId: Int): Future[Int] = getRolesByScenario(scenarioId)
    .flatMap{ roles => countRoleInfoByRoleIds(roles.map(_.id.get))
  }
}


