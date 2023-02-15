/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.jdbc

import org.apache.pekko.annotation.InternalApi
import org.apache.pekko.persistence.PersistentRepr
import org.apache.pekko.persistence.jdbc.state.DurableStateTables
import org.apache.pekko.persistence.jdbc.journal.dao.JournalTables.JournalPekkoSerializationRow
import org.apache.pekko.serialization.{ Serialization, Serializers }

import scala.util.{ Success, Try }

/**
 * INTERNAL API
 */
@InternalApi
object PekkoSerialization {

  case class PekkoSerialized(serId: Int, serManifest: String, payload: Array[Byte])

  def serialize(serialization: Serialization, payload: Any): Try[PekkoSerialized] = {
    val p2 = payload.asInstanceOf[AnyRef]
    val serializer = serialization.findSerializerFor(p2)
    val serManifest = Serializers.manifestFor(serializer, p2)
    val serialized = serialization.serialize(p2)
    serialized.map(payload => PekkoSerialized(serializer.identifier, serManifest, payload))
  }

  def fromRow(serialization: Serialization)(row: JournalPekkoSerializationRow): Try[(PersistentRepr, Long)] = {
    serialization.deserialize(row.eventPayload, row.eventSerId, row.eventSerManifest).flatMap { payload =>
      val metadata = for {
        mPayload <- row.metaPayload
        mSerId <- row.metaSerId
      } yield (mPayload, mSerId)

      val repr = PersistentRepr(
        payload,
        row.sequenceNumber,
        row.persistenceId,
        row.adapterManifest,
        row.deleted,
        sender = null,
        writerUuid = row.writer)

      // This means that failure to deserialize the meta will fail the read, I think this is the correct to do
      for {
        withMeta <- metadata match {
          case None => Success(repr)
          case Some((payload, id)) =>
            serialization.deserialize(payload, id, row.metaSerManifest.getOrElse("")).map { meta =>
              repr.withMetadata(meta)
            }
        }
      } yield (withMeta.withTimestamp(row.writeTimestamp), row.ordering)
    }
  }

  def fromDurableStateRow(serialization: Serialization)(row: DurableStateTables.DurableStateRow): Try[AnyRef] = {
    serialization.deserialize(row.statePayload, row.stateSerId, row.stateSerManifest.getOrElse(""))
  }
}