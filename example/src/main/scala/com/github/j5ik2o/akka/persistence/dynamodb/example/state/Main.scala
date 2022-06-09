package com.github.j5ik2o.akka.persistence.dynamodb.example.state

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior }
import akka.util.Timeout
import com.github.j5ik2o.akka.persistence.dynamodb.example.CounterProtocol
import com.typesafe.config.ConfigFactory

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.{ Duration, DurationInt }
import scala.util.Try
import com.typesafe.config.Config

object Main extends App {
  trait Message

  case class AdaptedResponse(id: UUID, n: Try[Int]) extends Message

  def apply(): Behavior[Message] = Behaviors.setup[Message] { ctx =>
    implicit val timeout: Timeout = 3.seconds

    val id         = UUID.randomUUID()
    val counterRef = ctx.spawnAnonymous(Counter(id))

    counterRef ! CounterProtocol.Increment
    counterRef ! CounterProtocol.Increment
    counterRef ! CounterProtocol.Increment

    ctx.ask(counterRef, CounterProtocol.GetValue) { n =>
      AdaptedResponse(id, n)
    }

    def stopped: Behavior[Message] = {
      Behaviors.setup { ctx =>
        val counterRef = ctx.spawnAnonymous(Counter(id))
        counterRef ! CounterProtocol.Increment
        ctx.ask(counterRef, CounterProtocol.GetValue) { n =>
          AdaptedResponse(id, n)
        }

        Behaviors.receiveMessage { case AdaptedResponse(id, n) =>
          ctx.log.info(s"id = $id, count = $n")
          Behaviors.stopped
        }
      }
    }

    Behaviors.receiveMessage { case AdaptedResponse(id, n) =>
      ctx.log.info(s"id = $id, count = $n")
      ctx.stop(counterRef)
      stopped
    }

  }

  val config: Config               = ConfigFactory.load()
  val system: ActorSystem[Message] = ActorSystem(apply(), "main", config)

  Await.result(system.whenTerminated, Duration.Inf)

}