package com.github.j5ik2o.pekko.persistence.dynamodb.example.typed

import org.apache.pekko.actor.typed.ActorRef
import com.github.j5ik2o.pekko.persistence.dynamodb.example.CborSerializable

object CounterProtocol {
  sealed trait Command extends CborSerializable

  final case object Increment extends Command
  final case class IncrementBy(value: Int) extends Command
  final case class GetValue(replyTo: ActorRef[Int]) extends Command

}
