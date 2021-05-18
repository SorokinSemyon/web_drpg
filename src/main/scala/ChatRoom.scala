package drpg

import akka.actor._

object ChatRoom {
  case object Join
  case class ChatMessage(message: String)
}

class ChatRoom(roomId: Int) extends Actor {
  import ChatRoom._
  var users: Set[ActorRef] = Set.empty

  override def receive: Receive = {
    case Join =>
      users += sender()
      context.watch(sender())

    case Terminated(user) =>
      users -= user

    case msg: ChatMessage =>
      users.foreach(_ ! msg)
  }
}
