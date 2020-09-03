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

package org.wfanet.measurement.db.kingdom.gcp.readers

import com.google.cloud.spanner.Struct
import org.wfanet.measurement.db.gcp.getProtoEnum
import org.wfanet.measurement.db.gcp.getProtoMessage
import org.wfanet.measurement.internal.kingdom.Report
import org.wfanet.measurement.internal.kingdom.Report.ReportState
import org.wfanet.measurement.internal.kingdom.ReportDetails

/**
 * Reads [Report] protos from Spanner.
 */
class ReportReader : SpannerReader<ReportReader.Result>() {
  data class Result(
    val report: Report,
    val advertiserId: Long,
    val reportConfigId: Long,
    val scheduleId: Long,
    val reportId: Long
  )

  override val baseSql: String =
    """
    SELECT $SELECT_COLUMNS_SQL
    FROM Reports
    JOIN Advertisers USING (AdvertiserId)
    JOIN ReportConfigs USING (AdvertiserId, ReportConfigId)
    JOIN ReportConfigSchedules USING (AdvertiserId, ReportConfigId, ScheduleId)
    """.trimIndent()

  override val externalIdColumn: String = "Reports.ExternalReportId"

  override suspend fun translate(struct: Struct): Result =
    Result(
      buildReport(struct),
      struct.getLong("AdvertiserId"),
      struct.getLong("ReportConfigId"),
      struct.getLong("ScheduleId"),
      struct.getLong("ReportId")
    )

  private fun buildReport(struct: Struct): Report = Report.newBuilder().apply {
    externalAdvertiserId = struct.getLong("ExternalAdvertiserId")
    externalReportConfigId = struct.getLong("ExternalReportConfigId")
    externalScheduleId = struct.getLong("ExternalScheduleId")
    externalReportId = struct.getLong("ExternalReportId")

    createTime = struct.getTimestamp("CreateTime").toProto()
    updateTime = struct.getTimestamp("UpdateTime").toProto()

    windowStartTime = struct.getTimestamp("WindowStartTime").toProto()
    windowEndTime = struct.getTimestamp("WindowEndTime").toProto()
    state = struct.getProtoEnum("State", ReportState::forNumber)

    reportDetails = struct.getProtoMessage("ReportDetails", ReportDetails.parser())
    reportDetailsJson = struct.getString("ReportDetailsJson")
  }.build()

  companion object {
    private val SELECT_COLUMNS = listOf(
      "Reports.AdvertiserId",
      "Reports.ReportConfigId",
      "Reports.ScheduleId",
      "Reports.ReportId",
      "Reports.ExternalReportId",
      "Reports.CreateTime",
      "Reports.UpdateTime",
      "Reports.WindowStartTime",
      "Reports.WindowEndTime",
      "Reports.State",
      "Reports.ReportDetails",
      "Reports.ReportDetailsJson",
      "Advertisers.ExternalAdvertiserId",
      "ReportConfigs.ExternalReportConfigId",
      "ReportConfigSchedules.ExternalScheduleId"
    )

    val SELECT_COLUMNS_SQL = SELECT_COLUMNS.joinToString(", ")
  }
}