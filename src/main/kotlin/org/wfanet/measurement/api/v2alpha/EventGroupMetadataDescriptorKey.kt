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

package org.wfanet.measurement.api.v2alpha

import org.wfanet.measurement.common.ResourceNameParser

private val parser =
  ResourceNameParser(
    "dataProviders/{data_provider}/eventGroupMetadataDescriptors/{event_group_metadata_descriptor}"
  )

/** [ResourceKey] of an EventGroupMetadataDescriptor. */
data class EventGroupMetadataDescriptorKey(
  val dataProviderId: String,
  val eventGroupMetadataDescriptorId: String
) : ResourceKey {
  override fun toName(): String {
    return parser.assembleName(
      mapOf(
        IdVariable.DATA_PROVIDER to dataProviderId,
        IdVariable.EVENT_GROUP_METADATA_DESCRIPTOR to eventGroupMetadataDescriptorId
      )
    )
  }

  companion object {
    val defaultValue = EventGroupMetadataDescriptorKey("", "")

    fun fromName(resourceName: String): EventGroupMetadataDescriptorKey? {
      return parser.parseIdVars(resourceName)?.let {
        EventGroupMetadataDescriptorKey(
          it.getValue(IdVariable.DATA_PROVIDER),
          it.getValue(IdVariable.EVENT_GROUP_METADATA_DESCRIPTOR)
        )
      }
    }
  }
}