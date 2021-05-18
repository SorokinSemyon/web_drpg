package drpg

import akka.NotUsed
import akka.actor._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream._
import akka.stream.scaladsl._
import drpg.Drpg.system

import scala.collection.concurrent.TrieMap

object Chat {
  def getOrCreateRoom(gameId: Int): ActorRef =
    Game.games(gameId) match {
      case game if game.actor == null =>
        val actor = system.actorOf(Props(new ChatRoom(gameId)), s"game$gameId")
        Game.games.update(gameId, game.copy(actor = actor))
        actor
      case game => game.actor
    }

  def newUser(gameId: Int): Flow[Message, Message, NotUsed] = {
    val userActor = system.actorOf(Props(new User(getOrCreateRoom(gameId))))

    val incomingMessages: Sink[Message, NotUsed] =
      Flow[Message].map {
        // transform websocket message to domain message
        case TextMessage.Strict(text) =>
          text.split(": ") match {
            case Array(who, msg) => Game.addUserMessage(gameId, who, msg)
          }
          User.IncomingMessage(text)
      }.to(Sink.actorRef[User.IncomingMessage](userActor, PoisonPill))

    val outgoingMessages: Source[Message, NotUsed] =
      Source.actorRef[User.OutgoingMessage](10, OverflowStrategy.fail)
        .mapMaterializedValue { outActor =>
          // give the user actor a way to send messages out
          userActor ! User.Connected(outActor)
          NotUsed
        }.map(
        // transform domain message to web socket message
        (outMsg: User.OutgoingMessage) => TextMessage(outMsg.text))

    // then combine both to a flow
    Flow.fromSinkAndSource(incomingMessages, outgoingMessages)
  }

  val chatRoute: Route =
    path("wschat" / IntNumber) { roomId =>
      get {
        handleWebSocketMessages(newUser(roomId))
      }
    }
}
