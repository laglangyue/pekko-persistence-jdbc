/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.jdbc.state.scaladsl

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.Try

import slick.jdbc.{ JdbcBackend, JdbcProfile }
import org.apache.pekko.{ Done, NotUsed }
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.pattern.ask
import org.apache.pekko.persistence.state.scaladsl.{ DurableStateUpdateStore, GetObjectResult }
import org.apache.pekko.persistence.jdbc.PekkoSerialization
import org.apache.pekko.persistence.jdbc.state.DurableStateQueries
import org.apache.pekko.persistence.jdbc.config.DurableStateTableConfiguration
import org.apache.pekko.persistence.jdbc.state.{ DurableStateTables, OffsetSyntax }
import org.apache.pekko.persistence.query.{ DurableStateChange, Offset }
import org.apache.pekko.persistence.query.scaladsl.DurableStateStoreQuery
import org.apache.pekko.persistence.jdbc.journal.dao.FlowControl
import org.apache.pekko.serialization.Serialization
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.util.Timeout
import DurableStateSequenceActor._
import OffsetSyntax._
import org.apache.pekko.annotation.ApiMayChange
import org.apache.pekko.persistence.query.UpdatedDurableState

object JdbcDurableStateStore {
  val Identifier = "jdbc-durable-state-store"
}

/**
 * API may change
 */
@ApiMayChange
class JdbcDurableStateStore[A](
    db: JdbcBackend#Database,
    val profile: JdbcProfile,
    durableStateConfig: DurableStateTableConfiguration,
    serialization: Serialization)(implicit val system: ExtendedActorSystem)
    extends DurableStateUpdateStore[A]
    with DurableStateStoreQuery[A] {
  import FlowControl._
  import profile.api._

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat: Materializer = SystemMaterializer(system).materializer

  lazy val queries = new DurableStateQueries(profile, durableStateConfig)

  // Started lazily to prevent the actor for querying the db if no changesByTag queries are used
  private[jdbc] lazy val stateSequenceActor = system.systemActorOf(
    DurableStateSequenceActor.props(this, durableStateConfig.stateSequenceConfig),
    s"pekko-persistence-jdbc-durable-state-sequence-actor")

  def getObject(persistenceId: String): Future[GetObjectResult[A]] = {
    db.run(queries.selectFromDbByPersistenceId(persistenceId).result).map { rows =>
      rows.headOption match {
        case Some(row) =>
          GetObjectResult(
            PekkoSerialization.fromDurableStateRow(serialization)(row).toOption.asInstanceOf[Option[A]],
            row.revision)

        case None =>
          GetObjectResult(None, 0)
      }
    }
  }

  def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): Future[Done] = {
    require(revision > 0)
    val row =
      PekkoSerialization.serialize(serialization, value).map { serialized =>
        DurableStateTables.DurableStateRow(
          0, // insert 0 for autoinc columns
          persistenceId,
          revision,
          serialized.payload,
          Option(tag).filter(_.trim.nonEmpty),
          serialized.serId,
          Option(serialized.serManifest).filter(_.trim.nonEmpty),
          System.currentTimeMillis)
      }

    Future
      .fromTry(row)
      .flatMap { r =>
        val action = if (revision == 1) insertDurableState(r) else updateDurableState(r)
        db.run(action)
      }
      .map { rowsAffected =>
        if (rowsAffected == 0)
          throw new IllegalStateException(
            s"Incorrect revision number [$revision] provided: It has to be 1 more than the value existing in the database for persistenceId [$persistenceId]")
        else Done
      }
  }

  def deleteObject(persistenceId: String): Future[Done] =
    db.run(queries.deleteFromDb(persistenceId).map(_ => Done))

  def deleteObject(persistenceId: String, revision: Long): Future[Done] =
    db.run(queries.deleteFromDb(persistenceId).map(_ => Done))

  def currentChanges(tag: String, offset: Offset): Source[DurableStateChange[A], NotUsed] = {
    Source
      .futureSource(maxStateStoreOffset().map { maxOrderingInDb =>
        changesByTag(tag, offset.value, terminateAfterOffset = Some(maxOrderingInDb))
      })
      .mapMaterializedValue(_ => NotUsed)
  }

  def changes(tag: String, offset: Offset): Source[DurableStateChange[A], NotUsed] =
    changesByTag(tag, offset.value, terminateAfterOffset = None)

  private def currentChangesByTag(
      tag: String,
      from: Long,
      batchSize: Long,
      queryUntil: MaxGlobalOffset): Source[DurableStateChange[A], NotUsed] = {
    if (queryUntil.maxOffset < from) Source.empty
    else changesByTagFromDb(tag, from, queryUntil.maxOffset, batchSize).mapAsync(1)(Future.fromTry)
  }

  private def changesByTagFromDb(
      tag: String,
      offset: Long,
      maxOffset: Long,
      batchSize: Long): Source[Try[DurableStateChange[A]], NotUsed] = {
    Source
      .fromPublisher(db.stream(queries.changesByTag((tag, offset, maxOffset, batchSize)).result))
      .map(toDurableStateChange)
  }

  private[jdbc] def changesByTag(
      tag: String,
      offset: Long,
      terminateAfterOffset: Option[Long]): Source[DurableStateChange[A], NotUsed] = {

    val batchSize = durableStateConfig.batchSize
    val startingOffsets = List.empty[Long]
    implicit val askTimeout: Timeout = Timeout(durableStateConfig.stateSequenceConfig.askTimeout)

    Source
      .unfoldAsync[(Long, FlowControl, List[Long]), Seq[DurableStateChange[A]]]((offset, Continue, startingOffsets)) {
        case (from, control, s) =>
          def retrieveNextBatch() = {
            for {
              queryUntil <- stateSequenceActor.ask(GetMaxGlobalOffset).mapTo[MaxGlobalOffset]
              xs <- currentChangesByTag(tag, from, batchSize, queryUntil).runWith(Sink.seq)
            } yield {

              val hasMoreEvents = xs.size == batchSize
              val nextControl: FlowControl =
                terminateAfterOffset match {
                  // we may stop if target is behind queryUntil and we don't have more events to fetch
                  case Some(target) if !hasMoreEvents && target <= queryUntil.maxOffset => Stop

                  // We may also stop if we have found an event with an offset >= target
                  case Some(target) if xs.exists(_.offset.value >= target) => Stop

                  // otherwise, disregarding if Some or None, we must decide how to continue
                  case _ =>
                    if (hasMoreEvents) Continue
                    else ContinueDelayed
                }
              val nextStartingOffset = if (xs.isEmpty) {
                math.max(from.value, queryUntil.maxOffset)
              } else {
                // Continue querying from the largest offset
                xs.map(_.offset.value).max
              }
              Some(((nextStartingOffset, nextControl, s :+ nextStartingOffset), xs))
            }
          }

          control match {
            case Stop     => Future.successful(None)
            case Continue => retrieveNextBatch()
            case ContinueDelayed =>
              org.apache.pekko.pattern.after(durableStateConfig.refreshInterval, system.scheduler)(retrieveNextBatch())
          }
      }
      .mapConcat(identity)
  }

  private[jdbc] def maxStateStoreOffset(): Future[Long] =
    db.run(queries.maxOffsetQuery.result)

  private[jdbc] def stateStoreStateInfo(offset: Long, limit: Long): Source[(String, Long, Long), NotUsed] =
    Source.fromPublisher(db.stream(queries.stateStoreStateQuery((offset, limit)).result))

  private def toDurableStateChange(row: DurableStateTables.DurableStateRow): Try[DurableStateChange[A]] = {
    PekkoSerialization
      .fromDurableStateRow(serialization)(row)
      .map(payload =>
        new UpdatedDurableState(
          row.persistenceId,
          row.revision,
          payload.asInstanceOf[A],
          Offset.sequence(row.globalOffset),
          row.stateTimestamp))
  }

  private def updateDurableState(row: DurableStateTables.DurableStateRow) = {
    import queries._

    for {
      s <- getSequenceNextValueExpr()
      u <- updateDbWithDurableState(row, s.head)
    } yield u
  }

  private def insertDurableState(row: DurableStateTables.DurableStateRow) = {
    import queries._

    for {
      s <- getSequenceNextValueExpr()
      u <- insertDbWithDurableState(row, s.head)
    } yield u
  }

  def deleteAllFromDb() = db.run(queries.deleteAllFromDb())
}