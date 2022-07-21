// Copyright 2022 The Cross-Media Measurement Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.wfanet.measurement.reporting.deploy.postgres.readers

import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.firstOrNull
import org.wfanet.measurement.common.db.r2dbc.ReadContext
import org.wfanet.measurement.common.db.r2dbc.ResultRow
import org.wfanet.measurement.common.db.r2dbc.boundStatement
import org.wfanet.measurement.internal.reporting.Measurement
import org.wfanet.measurement.internal.reporting.measurement
import org.wfanet.measurement.reporting.service.internal.MeasurementNotFoundException
import org.wfanet.measurement.reporting.service.internal.ReportingInternalException

class MeasurementReader {
  data class Result(val measurement: Measurement)

  private val baseSql: String =
    """
    SELECT
      MeasurementConsumerReferenceId,
      MeasurementReferenceId,
      State,
      Failure,
      Result
    FROM
      Measurements
    """

  fun translate(row: ResultRow): Result = Result(buildMeasurement(row))

  /**
   * Reads a Measurement using reference IDs.
   *
   * Throws a subclass of [ReportingInternalException].
   * @throws [MeasurementNotFoundException] Measurement not found.
   */
  suspend fun readMeasurementByReferenceIds(
    readContext: ReadContext,
    measurementConsumerReferenceId: String,
    measurementReferenceId: String
  ): Result {
    val builder =
      boundStatement(
        baseSql +
          """
        WHERE MeasurementConsumerReferenceId = $1
          AND MeasurementReferenceId = $2
        """
      ) {
        bind("$1", measurementConsumerReferenceId)
        bind("$2", measurementReferenceId)
      }

    return readContext.executeQuery(builder).consume(::translate).firstOrNull()
      ?: throw MeasurementNotFoundException()
  }

  private fun buildMeasurement(row: ResultRow): Measurement {
    return measurement {
      measurementConsumerReferenceId = row["MeasurementConsumerReferenceId"]
      measurementReferenceId = row["MeasurementReferenceId"]
      state = Measurement.State.forNumber(row["State"])
      val failure: ByteString? = row["Failure"]
      if (failure != null) {
        this.failure = Measurement.Failure.parseFrom(failure)
      }
      val result: ByteString? = row["Result"]
      if (result != null) {
        this.result = Measurement.Result.parseFrom(result)
      }
    }
  }
}