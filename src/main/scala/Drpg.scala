package drpg

import Chat.chatRoute
import CreateRoutes.createRoutes
import GameRoutes.gameRoutes
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{Directives, Route}
import session.Auth
import com.typesafe.scalalogging.StrictLogging

import scala.io.StdIn

object Drpg extends App with StrictLogging {
  db.createTable

  implicit val system: ActorSystem = ActorSystem()
  import system.dispatcher

  val routes: Route = Directives.concat(Auth.authRoutes, chatRoute, createRoutes, gameRoutes)

  val bindingFuture = Http().newServerAt("localhost", 8080).bind(routes)

  logger.info("Server started, press enter to stop. Visit http://localhost:8080 to see the demo.")
  StdIn.readLine()


  bindingFuture
    .flatMap(_.unbind())
    .onComplete { _ =>
      system.terminate()
      println("Server stopped")
    }
}
