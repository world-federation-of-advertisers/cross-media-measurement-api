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

package org.wfanet.measurement.db.kingdom.gcp.queries

import com.google.cloud.spanner.ReadContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.wfanet.measurement.db.gcp.appendClause
import org.wfanet.measurement.db.kingdom.StreamRequisitionsFilter
import org.wfanet.measurement.db.kingdom.gcp.common.StreamRequisitionsFilterSqlConverter
import org.wfanet.measurement.db.kingdom.gcp.common.toSql
import org.wfanet.measurement.db.kingdom.gcp.readers.RequisitionReader
import org.wfanet.measurement.internal.kingdom.Requisition

class StreamRequisitionsQuery {
  /**
   * Streams [Requisition]s matching [filter] from Spanner.
   *
   * @param readContext the context in which to perform Spanner reads
   * @param filter a filter to control which [Requisition]s to return
   * @param limit how many [Requisition]s to return -- if zero, there is no limit
   * @return a [Flow] of [Requisition]s matching the filter ordered by ascending CreateTime
   */
  fun execute(
    readContext: ReadContext,
    filter: StreamRequisitionsFilter,
    limit: Long
  ): Flow<Requisition> {
    val reader = RequisitionReader()

    if (!filter.empty) {
      reader.builder.append("\nWHERE ")
      filter.toSql(reader.builder, StreamRequisitionsFilterSqlConverter)
    }

    reader.builder
      .appendClause("ORDER BY CreateTime ASC")
      .appendClause("LIMIT @limit")
      .bind("limit").to(limit)

    return reader.execute(readContext).map { it.requisition }
  }
}