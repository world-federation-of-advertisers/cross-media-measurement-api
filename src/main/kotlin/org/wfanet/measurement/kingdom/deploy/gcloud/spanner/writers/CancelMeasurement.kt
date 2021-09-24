// Copyright 2021 The Cross-Media Measurement Authors
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

package org.wfanet.measurement.kingdom.deploy.gcloud.spanner.writers

import org.wfanet.measurement.common.identity.ExternalId
import org.wfanet.measurement.internal.kingdom.Measurement
import org.wfanet.measurement.internal.kingdom.copy
import org.wfanet.measurement.kingdom.deploy.gcloud.spanner.common.KingdomInternalException
import org.wfanet.measurement.kingdom.deploy.gcloud.spanner.readers.MeasurementReader

/**
 * Cancels a [Measurement], transitioning its state to [Measurement.State.CANCELLED].
 *
 * Throws a [KingdomInternalException] on [execute] with the following codes/conditions:
 * * [KingdomInternalException.Code.MEASUREMENT_NOT_FOUND]
 * * [KingdomInternalException.Code.MEASUREMENT_STATE_ILLEGAL]
 */
class CancelMeasurement(
  private val externalMeasurementConsumerId: ExternalId,
  private val externalMeasurementId: ExternalId
) : SpannerWriter<Measurement, Measurement>() {
  override suspend fun TransactionScope.runTransaction(): Measurement {
    val (measurementConsumerId, measurementId, measurement) =
      MeasurementReader(Measurement.View.DEFAULT)
        .readByExternalIds(transactionContext, externalMeasurementConsumerId, externalMeasurementId)
        ?: throw KingdomInternalException(KingdomInternalException.Code.MEASUREMENT_NOT_FOUND) {
          "Measurement with external MeasurementConsumer ID $externalMeasurementConsumerId and " +
            "external Measurement ID $externalMeasurementId not found"
        }

    when (val state = measurement.state) {
      Measurement.State.PENDING_REQUISITION_PARAMS,
      Measurement.State.PENDING_REQUISITION_FULFILLMENT,
      Measurement.State.PENDING_PARTICIPANT_CONFIRMATION,
      Measurement.State.PENDING_COMPUTATION -> {}
      Measurement.State.SUCCEEDED,
      Measurement.State.FAILED,
      Measurement.State.CANCELLED,
      Measurement.State.STATE_UNSPECIFIED,
      Measurement.State.UNRECOGNIZED -> {
        throw KingdomInternalException(KingdomInternalException.Code.MEASUREMENT_STATE_ILLEGAL) {
          "Unexpected Measurement state $state (${state.number})"
        }
      }
    }

    updateMeasurementState(measurementConsumerId, measurementId, Measurement.State.CANCELLED)

    return measurement.copy { this.state = Measurement.State.CANCELLED }
  }

  override fun ResultScope<Measurement>.buildResult(): Measurement {
    return checkNotNull(this.transactionResult).copy { updateTime = commitTimestamp.toProto() }
  }
}