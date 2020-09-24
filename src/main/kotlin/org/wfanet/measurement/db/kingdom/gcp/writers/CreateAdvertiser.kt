// Copyright 2020 The Measurement System Authors
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

package org.wfanet.measurement.db.kingdom.gcp.writers

import com.google.cloud.spanner.Mutation
import org.wfanet.measurement.common.ExternalId
import org.wfanet.measurement.db.gcp.bufferTo
import org.wfanet.measurement.internal.kingdom.Advertiser

class CreateAdvertiser : SpannerWriter<ExternalId, Advertiser>() {
  override suspend fun TransactionScope.runTransaction(): ExternalId {
    val internalId = idGenerator.generateInternalId()
    val externalId = idGenerator.generateExternalId()
    Mutation.newInsertBuilder("Advertisers")
      .set("AdvertiserId").to(internalId.value)
      .set("ExternalAdvertiserId").to(externalId.value)
      .set("AdvertiserDetails").to("")
      .set("AdvertiserDetailsJson").to("")
      .build()
      .bufferTo(transactionContext)
    return externalId
  }

  override fun ResultScope<ExternalId>.buildResult(): Advertiser {
    val externalAdvertiserId = checkNotNull(transactionResult).value
    return Advertiser.newBuilder().setExternalAdvertiserId(externalAdvertiserId).build()
  }
}
