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

package org.wfanet.measurement.kingdom.deploy.gcloud.spanner

import io.grpc.Status
import org.wfanet.measurement.common.grpc.failGrpc
import org.wfanet.measurement.common.grpc.grpcRequire
import org.wfanet.measurement.common.identity.ExternalId
import org.wfanet.measurement.common.identity.IdGenerator
import org.wfanet.measurement.gcloud.spanner.AsyncDatabaseClient
import org.wfanet.measurement.internal.kingdom.GetMeasurementConsumerRequest
import org.wfanet.measurement.internal.kingdom.MeasurementConsumer
import org.wfanet.measurement.internal.kingdom.MeasurementConsumersGrpcKt.MeasurementConsumersCoroutineImplBase
import org.wfanet.measurement.kingdom.deploy.gcloud.spanner.readers.MeasurementConsumerReader
import org.wfanet.measurement.kingdom.deploy.gcloud.spanner.writers.CreateMeasurementConsumer

class SpannerMeasurementConsumersService(
  private val idGenerator: IdGenerator,
  private val client: AsyncDatabaseClient
) : MeasurementConsumersCoroutineImplBase() {
  override suspend fun createMeasurementConsumer(
    request: MeasurementConsumer
  ): MeasurementConsumer {
    grpcRequire(
      request.details.apiVersion.isNotEmpty() &&
        !request.details.publicKey.isEmpty &&
        !request.details.publicKeySignature.isEmpty
    ) { "Details field of MeasurementConsumer is missing fields." }
    return CreateMeasurementConsumer(request).execute(client, idGenerator)
  }
  override suspend fun getMeasurementConsumer(
    request: GetMeasurementConsumerRequest
  ): MeasurementConsumer {
    return MeasurementConsumerReader()
      .readByExternalMeasurementConsumerId(
        client.singleUse(),
        ExternalId(request.externalMeasurementConsumerId)
      )
      ?.measurementConsumer
      ?: failGrpc(Status.NOT_FOUND) { "MeasurementConsumer not found" }
  }
}