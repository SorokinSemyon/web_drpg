import db.RoleInfoQueryRepository.allRoleInfo
import db.RoleQueryRepository.allRole
import db.ScenarioQueryRepository.allScenario
import slick.backend.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.Future

package object db {
    val dbConfig = DatabaseConfig.forConfig[JdbcProfile]("postgres")
    val db = dbConfig.db
    import dbConfig.driver.api._

    def createTable: Future[Unit] =
        db.run((allRole.schema ++ allScenario.schema ++ allRoleInfo.schema).createIfNotExists)
}
