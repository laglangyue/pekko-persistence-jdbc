/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.jdbc

import org.apache.pekko.persistence.query._

package object query {
  implicit class OffsetOps(val that: Offset) extends AnyVal {
    def value =
      that match {
        case Sequence(offsetValue) => offsetValue
        case NoOffset              => 0L
        case _ =>
          throw new IllegalArgumentException(
            "pekko-persistence-jdbc does not support " + that.getClass.getName + " offsets")
      }
  }
}