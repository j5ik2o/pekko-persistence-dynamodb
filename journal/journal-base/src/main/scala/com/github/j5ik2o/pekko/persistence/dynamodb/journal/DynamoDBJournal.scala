/*
 * Copyright 2017 Dennis Vriend
 * Copyright 2019 Junichi Kato
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.j5ik2o.pekko.persistence.dynamodb.journal

import org.apache.pekko.Done
import org.apache.pekko.actor.{ ActorLogging, ActorSystem }
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.pattern.pipe
import org.apache.pekko.persistence.journal.AsyncWriteJournal
import org.apache.pekko.persistence.{ AtomicWrite, PersistentRepr }
import org.apache.pekko.serialization.{ Serialization, SerializationExtension }
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.aggregate.JournalAggregateStore
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao._
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.JournalHeadWriteRequest
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.serialization.{
  ByteArrayJournalSerializer,
  FlowPersistentReprSerializer
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.write.JournalWritePlan
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ Context, PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ PlugInLifecycleHandler, PlugInLifecycleHandlerFactory }
import com.typesafe.config.Config

import java.util.UUID
import scala.collection.immutable._
import scala.collection.{ immutable, mutable }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

object DynamoDBJournal {

  final case class InPlaceUpdateEvent(persistenceId: String, sequenceNumber: Long, message: AnyRef)

  final case class WriteFinished(pid: String, f: Future[_])

}

final class DynamoDBJournal(config: Config) extends AsyncWriteJournal with ActorLogging {

  import DynamoDBJournal._

  private val id = UUID.randomUUID()

  implicit val system: ActorSystem  = context.system
  implicit val mat: Materializer    = SystemMaterializer(system).materializer
  implicit val _log: LoggingAdapter = log

  log.debug("dynamodb journal plugin: id = {}", id)

  protected val serialization: Serialization = SerializationExtension(system)

  protected val journalPluginConfig: JournalPluginConfig = JournalPluginConfig.fromConfig(config)
  private val pluginContext                              = JournalPluginContext(system, journalPluginConfig)

  import pluginContext._

  private val plugInLifecycleHandlerFactory: PlugInLifecycleHandlerFactory = pluginContext
    .newDynamicAccessor[PlugInLifecycleHandlerFactory]().createThrow(
      journalPluginConfig.plugInLifecycleHandlerFactoryClassName
    )
  private val plugInLifecycleHandler: PlugInLifecycleHandler = plugInLifecycleHandlerFactory.create

  private val serializer: FlowPersistentReprSerializer[JournalRow] =
    new ByteArrayJournalSerializer(serialization, journalPluginConfig.tagSeparator, metricsReporter, traceReporter)

  protected val journalDao: JournalDaoWithUpdates =
    journalPluginConfig.journalRowDriverWrapperClassName match {
      case Some(className) =>
        val wrapper = pluginContext.newDynamicAccessor[JournalRowWriteDriver]().createThrow(className)
        new WriteJournalDaoImpl(
          pluginContext,
          wrapper,
          serializer
        )(pluginExecutor, system)
      case None =>
        new WriteJournalDaoImpl(
          pluginContext,
          journalRowWriteDriver,
          serializer
        )(pluginExecutor, system)
    }

  protected val writeInProgress: mutable.Map[String, Future[_]] = mutable.Map.empty

  private val aggregateStore: JournalAggregateStore =
    JournalAggregateStore.fromJournalDao(
      journalDao,
      persistenceId => journalRowWriteDriver.headSequenceNr(persistenceId),
      journalPluginConfig.headConfig.enabled
    )

  override def asyncWriteMessages(atomicWrites: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    val persistenceId = atomicWrites.head.persistenceId
    val pid           = PersistenceId(persistenceId)
    val context       = Context.newContext(UUID.randomUUID(), pid)
    val newContext    = metricsReporter.fold(context)(_.beforeJournalAsyncWriteMessages(context))

    implicit val ec: ExecutionContext = pluginExecutor

    def execute(implicit ec: ExecutionContext): Future[Vector[Try[Unit]]] = {
      if (journalPluginConfig.headConfig.enabled) executeSequentially(atomicWrites)
      else executeInParallel(atomicWrites)
    }

    /** Serializes every AtomicWrite. A serialization failure is Pekko's own refusal of that one write, so it stays a
      * Failure of that AtomicWrite; every other check of the plugin runs on the rows that were serialized.
      */
    def serializeAll(atomicWrites: immutable.Seq[AtomicWrite])(implicit
        ec: ExecutionContext
    ): Future[Vector[Try[Seq[JournalRow]]]] =
      Future.traverse(serializer.serialize(atomicWrites).toVector)(_.transform(serialized => Success(serialized)))

    /** Writes the AtomicWrites of one message in parallel.
      *
      * The head-less path sends the rows without a JournalHead item, so the JournalHead validation of the plan does not
      * run. Measure the rows here too: a row over 400 KB is a bug in the design of the events, not an error to live
      * with. It fails the whole write before anything is sent, so the actor stops through onPersistFailure and leaves
      * no gap in the sequence numbers.
      */
    def executeInParallel(atomicWrites: immutable.Seq[AtomicWrite])(implicit
        ec: ExecutionContext
    ): Future[Vector[Try[Unit]]] =
      serializeAll(atomicWrites).flatMap { serializedWrites =>
        val firstRowTooLarge = serializedWrites.iterator
          .collect { case Success(rows) =>
            JournalWritePlan.findJournalRowTooLarge(
              rows,
              journalPluginConfig.columnsDefConfig,
              partitionKeyResolver,
              sortKeyResolver
            )
          }.collectFirst { case Some(rejection) => rejection }
        firstRowTooLarge match {
          case Some(rejection) => Future.failed(rejection.toJournalHeadException)
          case None =>
            Future.traverse(serializedWrites) {
              case Success(rows) =>
                journalDao
                  .putMessages(rows).runWith(Sink.head).map(_ => Success(()): Try[Unit]).recoverWith { case ex =>
                    log.error(ex, "occurred error")
                    Future.failed(ex)
                  }
              case Failure(ex) =>
                Future.successful(Failure(ex): Try[Unit])
            }
        }
      }

    /** Writes the AtomicWrites of one message in order, one after the other.
      *
      * The JournalHead condition expects the head to be below the first sequence number of the write, so two writes of
      * the same persistence id must reach DynamoDB in the order the actor produced them. A write that exceeds a limit
      * of the JournalHead path is a bug in the design of the events. Every AtomicWrite is checked before the first one
      * is sent, and one violation fails the whole write: nothing is written, the actor stops through onPersistFailure,
      * and the sequence numbers continue without a gap after the restart.
      */
    def executeSequentially(atomicWrites: immutable.Seq[AtomicWrite])(implicit
        ec: ExecutionContext
    ): Future[Vector[Try[Unit]]] =
      serializeAll(atomicWrites).flatMap { serializedWrites =>
        val firstRejection = serializedWrites.iterator
          .collect { case Success(rows) =>
            JournalHeadWriteRequest.of(
              rows,
              journalPluginConfig.headConfig,
              journalPluginConfig.columnsDefConfig,
              partitionKeyResolver,
              sortKeyResolver
            )
          }.collectFirst { case Left(rejected) => rejected }
        firstRejection match {
          case Some(rejected) => Future.failed(rejected)
          case None =>
            serializedWrites.foldLeft(Future.successful(Vector.empty[Try[Unit]])) { (resultsFuture, serialized) =>
              resultsFuture.flatMap { results =>
                serialized match {
                  case Success(rows) =>
                    journalDao.putMessages(rows).runWith(Sink.head).map(_ => results :+ Success(()))
                  case Failure(ex) =>
                    Future.successful(results :+ Failure(ex))
                }
              }
            }
        }
      }

    val future = traceReporter.fold(execute)(_.traceJournalAsyncWriteMessages(newContext)(execute))

    writeInProgress.put(persistenceId, future)
    future.onComplete { result: Try[Seq[Try[Unit]]] =>
      self ! WriteFinished(persistenceId, future)
      result match {
        case Success(_) =>
          metricsReporter.foreach(_.afterJournalAsyncWriteMessages(newContext))
        case Failure(ex) =>
          metricsReporter.foreach(_.errorJournalAsyncWriteMessages(newContext, ex))
      }
    }
    future
  }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    val pid        = PersistenceId(persistenceId)
    val context    = Context.newContext(UUID.randomUUID(), pid)
    val newContext = metricsReporter.fold(context)(_.beforeJournalAsyncDeleteMessagesTo(context))

    implicit val ec: ExecutionContext = pluginExecutor

    def future = journalDao
      .deleteMessages(PersistenceId(persistenceId), SequenceNumber(toSequenceNr))
      .runWith(Sink.head).map(_ => ())

    val traced = traceReporter.fold(future)(_.traceJournalAsyncDeleteMessagesTo(context)(future))

    traced.onComplete {
      case Success(_) =>
        metricsReporter.foreach(_.afterJournalAsyncDeleteMessagesTo(newContext))
      case Failure(ex) =>
        metricsReporter.foreach(_.errorJournalAsyncDeleteMessagesTo(newContext, ex))
    }
    traced
  }

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
      recoveryCallback: PersistentRepr => Unit
  ): Future[Unit] = {
    implicit val ec: ExecutionContext = pluginExecutor

    val pid        = PersistenceId(persistenceId)
    val context    = Context.newContext(UUID.randomUUID(), pid)
    val newContext = metricsReporter.fold(context)(_.beforeJournalAsyncReplayMessages(context))

    def future = journalDao
      .getMessagesAsPersistentReprWithBatch(
        persistenceId,
        fromSequenceNr,
        toSequenceNr,
        journalPluginConfig.replayBatchSize,
        journalPluginConfig.replayBatchRefreshInterval.map(_ -> system.scheduler)
      )
      .take(max)
      .mapAsync(1)(deserializedRepr => Future.fromTry(deserializedRepr))
      .runForeach(recoveryCallback)
      .map(_ => ())

    val traced = traceReporter.fold(future)(_.traceJournalAsyncReplayMessages(context)(future))

    traced.onComplete {
      case Success(_) =>
        metricsReporter.foreach(_.afterJournalAsyncReplayMessages(newContext))
      case Failure(ex) =>
        metricsReporter.foreach(_.errorJournalAsyncReplayMessages(newContext, ex))
    }
    traced
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    implicit val ec: ExecutionContext = pluginExecutor
    val pid                           = PersistenceId(persistenceId)
    val context                       = Context.newContext(UUID.randomUUID(), pid)
    val newContext = metricsReporter.fold(context)(_.beforeJournalAsyncReadHighestSequenceNr(context))

    def fetchHighestSeqNr(): Future[Long] =
      aggregateStore
        .highestSequenceNr(PersistenceId.apply(persistenceId), SequenceNumber(fromSequenceNr))
        .map(_.highestSequenceNr)
        .runWith(Sink.head)

    def future = writeInProgress.get(persistenceId) match {
      case None    => fetchHighestSeqNr()
      case Some(f) =>
        // we must fetch the highest sequence number after the previous write has completed
        // If the previous write failed then we can ignore this
        f.recover { case _ => () }.flatMap(_ => fetchHighestSeqNr())
    }

    val traced = traceReporter.fold(future)(_.traceJournalAsyncReadHighestSequenceNr(context)(future))

    traced.onComplete {
      case Success(_) =>
        metricsReporter.foreach(_.afterJournalAsyncReadHighestSequenceNr(newContext))
      case Failure(ex) =>
        metricsReporter.foreach(_.errorJournalAsyncReadHighestSequenceNr(newContext, ex))
    }

    traced
  }

  override def preStart(): Unit = {
    super.preStart()
    plugInLifecycleHandler.start()
  }

  override def postStop(): Unit = {
    journalDao.dispose()
    journalRowWriteDriver.dispose()
    writeInProgress.clear()
    plugInLifecycleHandler.stop()
    super.postStop()
  }

  override def receivePluginInternal: Receive = {
    case WriteFinished(persistenceId, _) =>
      writeInProgress.remove(persistenceId)
    case InPlaceUpdateEvent(pid, seq, message) =>
      implicit val ec: ExecutionContext = pluginExecutor
      asyncUpdateEvent(pid, seq, message).pipeTo(sender())
  }

  private def asyncUpdateEvent(persistenceId: String, sequenceNumber: Long, message: AnyRef): Future[Done] = {
    implicit val ec: ExecutionContext = pluginExecutor

    val pid        = PersistenceId(persistenceId)
    val context    = Context.newContext(UUID.randomUUID(), pid)
    val newContext = metricsReporter.fold(context)(_.beforeJournalAsyncUpdateEvent(context))

    def future = {
      val write = PersistentRepr(message, sequenceNumber, persistenceId)
      serializer
        .serialize(write).recoverWith { case cause =>
          Future.failed(
            new IllegalArgumentException(
              s"Failed to serialize ${write.getClass} for update of [$persistenceId] @ [$sequenceNumber]",
              cause
            )
          )
        }.flatMap { serializedRow =>
          // Only the serialization failure is relabelled. A failure of the update itself keeps its type, so the
          // caller can tell the head-mode outcomes (a JournalHeadException) from a DynamoDB failure.
          journalDao.updateMessage(serializedRow).runWith(Sink.ignore)
        }
    }

    val traced = traceReporter.fold(future)(_.traceJournalAsyncUpdateEvent(context)(future))

    traced.onComplete {
      case Success(_) =>
        metricsReporter.foreach(_.afterJournalAsyncUpdateEvent(newContext))
      case Failure(ex) =>
        metricsReporter.foreach(_.errorJournalAsyncUpdateEvent(newContext, ex))
    }

    traced
  }
}
