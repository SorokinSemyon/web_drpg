package session

import drpg.Drpg.system.dispatcher
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Directive1, Route}
import com.softwaremill.session.CsrfDirectives._
import com.softwaremill.session.CsrfOptions._
import com.softwaremill.session.SessionDirectives._
import com.softwaremill.session.SessionOptions._
import com.softwaremill.session._
import com.typesafe.scalalogging.StrictLogging
import drpg.CreateRoutes.htmlContent
import util.Template.{baseHtml, htmlFile}

object Auth extends StrictLogging {

  val sessionConfig: SessionConfig = SessionConfig.default(
    "c05ll3lesrinf39t7mc5h6un6r0c69lgfno69dsak3vabeqamouq4328cuaekros401ajdpkh60rrtpd8ro24rbuqmgtnd1ebag6ljnb65i8a55d482ok7o0nch0bfbe")
  implicit val sessionManager = new SessionManager[MyScalaSession](sessionConfig)
  implicit val refreshTokenStorage = new InMemoryRefreshTokenStorage[MyScalaSession] {
    def log(msg: String) = logger.info(msg)
  }

  def mySetSession(v: MyScalaSession): Directive0 = setSession(refreshable, usingCookies, v)

  val myRequiredSession: Directive1[MyScalaSession] = requiredSession(refreshable, usingCookies)
  val myInvalidateSession: Directive0 = invalidateSession(refreshable, usingCookies)

  val authRoutes: Route =
    path("") {
      redirect("/site/auth.html", Found)
    } ~
      hmacTokenCsrfProtection(checkHeader) {
        pathPrefix("api") {
          path("do_login") {
            post {
              entity(as[String]) { body =>
                logger.info(s"Logging in $body")

                mySetSession(MyScalaSession(body)) {
                  setNewCsrfToken(checkHeader) { ctx =>
                    ctx.complete("ok")
                  }
                }
              }
            }
          } ~
            // This should be protected and accessible only when logged in
            path("do_logout") {
              post {
                myRequiredSession { session =>
                  myInvalidateSession { ctx =>
                    logger.info(s"Logging out $session")
                    ctx.complete("ok")
                  }
                }
              }
            } ~
            // This should be protected and accessible only when logged in
            path("current_login") {
              get {
                myRequiredSession { session => ctx =>
                  logger.info("Current session: " + session)
                  ctx.complete(session.username)
                }
              }
            }
        } ~
          pathPrefix("site") {
            getFromResourceDirectory("")
          }
      }
}
