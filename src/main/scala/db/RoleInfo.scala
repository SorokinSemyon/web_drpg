package db

import slick.jdbc.H2Profile.api._
import slick.lifted.{ForeignKeyQuery, ProvenShape}

import scala.concurrent.Future
import drpg.Drpg.system.dispatcher
import io.circe.Encoder

case class RoleInfo(id: Option[Int] = None,
                roleId: Int,
                roundNumber: Int,
                info: String)

object RoleInfoUtil {
  def fromMap(map: Map[String, String]): RoleInfo = RoleInfo(
    None,
    map("roleId").toInt,
    map("roundNumber").toInt,
    map("info")
  )

  def toMap(roleInfo: RoleInfo): Map[String, String] = Map(
    "id" -> roleInfo.id.get.toString,
    "roleId" -> roleInfo.roleId.toString,
    "roundNumber" -> roleInfo.roundNumber.toString,
    "info" -> roleInfo.info
  )
}

class RoleInfoTable(tag: Tag) extends Table[RoleInfo](tag, "role_info") {
  def id: Rep[Int] = column("id", O.PrimaryKey, O.AutoInc)

  def roleId: Rep[Int] = column("roleId")

  def roundNumber: Rep[Int] = column("roundNumber")

  def info: Rep[String] = column("info")

  def role: ForeignKeyQuery[RoleTable, Role] =
    foreignKey("role", roleId, RoleQueryRepository.allRole)(_.id)

  override def * : ProvenShape[RoleInfo] = (
    id.?,
    roleId,
    roundNumber,
    info,
    ).mapTo[RoleInfo]
}
object RoleInfoQueryRepository {
  val allRoleInfo = TableQuery[RoleInfoTable]

  def roleInfoByRoleIdQuery(roleId: Int): Query[RoleInfoTable, RoleInfo, Seq] = allRoleInfo.filter(_.roleId === roleId)

  def getRoleInfoByRoleId(roleId: Int): Future[Seq[RoleInfo]] = db.run(
    roleInfoByRoleIdQuery(roleId).result
  )

  def addRoleInfo(roleId: Int, info: String): Future[Int] = getRoleInfoByRoleId(roleId).flatMap { roleInfo =>
    db.run(allRoleInfo += RoleInfo(None, roleId, roleInfo.size + 1, info))
  }

  def getInfo(roleId: Int, roundNumber: Int): Future[Option[String]] = db.run(
    roleInfoByRoleIdQuery(roleId)
      .filter(_.roundNumber === roundNumber)
      .map(_.info)
      .result
      .headOption
  )

  def countRoleInfoByRoleIds(roleIds: Seq[Int]): Future[Int] = db.run(
    allRoleInfo
      .filter(_.roleId.inSet(roleIds))
      .result
  ).map(result => result.size)
}
