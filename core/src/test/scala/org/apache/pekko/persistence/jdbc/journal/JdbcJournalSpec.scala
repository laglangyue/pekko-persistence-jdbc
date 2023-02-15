/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.jdbc.journal

import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.pekko.persistence.CapabilityFlag
import org.apache.pekko.persistence.journal.JournalSpec
import org.apache.pekko.persistence.jdbc.config.JournalConfig
import org.apache.pekko.persistence.jdbc.db.SlickExtension
import org.apache.pekko.persistence.jdbc.testkit.internal.{ H2, SchemaType }
import org.apache.pekko.persistence.jdbc.util.{ ClasspathResources, DropCreate }
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

abstract class JdbcJournalSpec(config: Config, schemaType: SchemaType)
    extends JournalSpec(config)
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures
    with ClasspathResources
    with DropCreate {
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = true

  implicit val pc: PatienceConfig = PatienceConfig(timeout = 10.seconds)

  implicit lazy val ec = system.dispatcher

  lazy val cfg = system.settings.config.getConfig("jdbc-journal")

  lazy val journalConfig = new JournalConfig(cfg)

  lazy val db = SlickExtension(system).database(cfg).database

  protected override def supportsSerialization: CapabilityFlag = newDao
  protected override def supportsMetadata: CapabilityFlag = newDao

  override def beforeAll(): Unit = {
    dropAndCreate(schemaType)
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    db.close()
    super.afterAll()
  }
}

class H2JournalSpec extends JdbcJournalSpec(ConfigFactory.load("h2-application.conf"), H2)
class H2JournalSpecSharedDb extends JdbcJournalSpec(ConfigFactory.load("h2-shared-db-application.conf"), H2)