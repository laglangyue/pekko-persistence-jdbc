/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pekko.persistence.jdbc.integration

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.persistence.jdbc.state.scaladsl.JdbcDurableStateSpec
import pekko.persistence.jdbc.testkit.internal.{ Oracle, SchemaType, SqlServer }

class OracleScalaJdbcDurableStateStoreQueryTest
    extends JdbcDurableStateSpec(ConfigFactory.load("oracle-shared-db-application.conf"), Oracle) {
  implicit lazy val system: ActorSystem =
    ActorSystem("JdbcDurableStateSpec", config.withFallback(customSerializers))

  override private[jdbc] def dropAndCreate(schemaType: SchemaType): Unit = {
    super.dropAndCreate(schemaType)
    withStatement(stmt =>
      stmt.executeUpdate(s"""BEGIN "RESET__${durableStateConfig.columnNames.globalOffset}"; END; """))
  }
}

class SqlServerScalaJdbcDurableStateStoreQueryTest
    extends JdbcDurableStateSpec(ConfigFactory.load("sqlserver-shared-db-application.conf"), SqlServer) {
  implicit lazy val system: ActorSystem =
    ActorSystem("JdbcDurableStateSpec", config.withFallback(customSerializers))

  override private[jdbc] def dropAndCreate(schemaType: SchemaType): Unit = {
    super.dropAndCreate(schemaType)
    withStatement(stmt =>
      stmt.executeUpdate(s"""ALTER SEQUENCE ${durableStateConfig.columnNames.globalOffset} RESTART WITH 1"""))
  }
}
