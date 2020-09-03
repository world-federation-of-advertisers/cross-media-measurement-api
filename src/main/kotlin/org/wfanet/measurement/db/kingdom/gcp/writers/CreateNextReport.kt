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
import com.google.cloud.spanner.Value
import java.time.Duration
import java.time.Period
import java.time.temporal.TemporalAmount
import org.wfanet.measurement.common.ExternalId
import org.wfanet.measurement.common.toInstant
import org.wfanet.measurement.common.toJson
import org.wfanet.measurement.common.toProtoTime
import org.wfanet.measurement.db.gcp.bufferTo
import org.wfanet.measurement.db.gcp.toGcpTimestamp
import org.wfanet.measurement.db.gcp.toProtoBytes
import org.wfanet.measurement.db.gcp.toProtoEnum
import org.wfanet.measurement.db.gcp.toProtoJson
import org.wfanet.measurement.db.kingdom.gcp.queries.ReadLatestReportByScheduleQuery
import org.wfanet.measurement.db.kingdom.gcp.readers.ScheduleReader
import org.wfanet.measurement.internal.kingdom.Report
import org.wfanet.measurement.internal.kingdom.Report.ReportState
import org.wfanet.measurement.internal.kingdom.ReportConfigSchedule
import org.wfanet.measurement.internal.kingdom.ReportDetails
import org.wfanet.measurement.internal.kingdom.TimePeriod

/**
 * Creates the next [Report] for a [ReportConfigSchedule].
 *
 * It will create a new report if the schedule's nextReportStartTime is in the past, or if there are
 * no reports for this schedule yet.
 */
class CreateNextReport(
  private val externalScheduleId: ExternalId
) : SpannerWriter<Report, Report>() {

  override suspend fun TransactionScope.runTransaction(): Report {
    val scheduleReadResult = ScheduleReader().readExternalId(transactionContext, externalScheduleId)
    if (needsNewReport(scheduleReadResult.schedule)) {
      return createNewReport(scheduleReadResult)
    }
    return ReadLatestReportByScheduleQuery().execute(transactionContext, externalScheduleId)
  }

  override fun ResultScope<Report>.buildResult(): Report {
    return checkNotNull(transactionResult).toBuilder().apply {
      createTime = commitTimestamp.toProto()
      updateTime = commitTimestamp.toProto()
    }.build()
  }

  private fun TransactionScope.needsNewReport(schedule: ReportConfigSchedule): Boolean {
    return schedule.nextReportStartTime.toInstant() < clock.instant() ||
      schedule.nextReportStartTime.toInstant() <= schedule.repetitionSpec.start.toInstant()
  }

  private fun TransactionScope.createNewReport(scheduleReadResult: ScheduleReader.Result): Report {
    val schedule: ReportConfigSchedule = scheduleReadResult.schedule

    val repetitionPeriod = getTemporalAmount(schedule.repetitionSpec.repetitionPeriod)
    val reportDuration = getTemporalAmount(scheduleReadResult.reportConfigDetails.reportDuration)

    val windowStartTime = schedule.nextReportStartTime.toInstant()
    val windowEndTime = windowStartTime.plus(reportDuration)

    val nextNextReportStartTime = windowStartTime.plus(repetitionPeriod)

    Mutation.newUpdateBuilder("ReportConfigSchedules")
      .set("AdvertiserId").to(scheduleReadResult.advertiserId)
      .set("ReportConfigId").to(scheduleReadResult.reportConfigId)
      .set("ScheduleId").to(scheduleReadResult.scheduleId)
      .set("NextReportStartTime").to(nextNextReportStartTime.toGcpTimestamp())
      .build()
      .bufferTo(transactionContext)

    val newExternalReportId = idGenerator.generateExternalId()
    Mutation.newInsertBuilder("Reports")
      .set("AdvertiserId").to(scheduleReadResult.advertiserId)
      .set("ReportConfigId").to(scheduleReadResult.reportConfigId)
      .set("ScheduleId").to(scheduleReadResult.scheduleId)
      .set("ReportId").to(idGenerator.generateInternalId().value)
      .set("ExternalReportId").to(newExternalReportId.value)
      .set("CreateTime").to(Value.COMMIT_TIMESTAMP)
      .set("UpdateTime").to(Value.COMMIT_TIMESTAMP)
      .set("WindowStartTime").to(windowStartTime.toGcpTimestamp())
      .set("WindowEndTime").to(windowEndTime.toGcpTimestamp())
      .set("State").toProtoEnum(ReportState.AWAITING_REQUISITION_CREATION)
      .set("ReportDetails").toProtoBytes(ReportDetails.getDefaultInstance())
      .set("ReportDetailsJson").toProtoJson(ReportDetails.getDefaultInstance())
      .build()
      .bufferTo(transactionContext)

    return Report.newBuilder().apply {
      externalAdvertiserId = scheduleReadResult.schedule.externalAdvertiserId
      externalReportConfigId = scheduleReadResult.schedule.externalReportConfigId
      externalScheduleId = scheduleReadResult.schedule.externalScheduleId
      externalReportId = newExternalReportId.value
      this.windowStartTime = windowStartTime.toProtoTime()
      this.windowEndTime = windowEndTime.toProtoTime()
      state = ReportState.AWAITING_REQUISITION_CREATION
      reportDetails = ReportDetails.getDefaultInstance()
      reportDetailsJson = reportDetails.toJson()
    }.build()
  }

  private fun getTemporalAmount(period: TimePeriod): TemporalAmount {
    return when (period.unit) {
      TimePeriod.Unit.DAY -> Period.ofDays(period.count.toInt())
      TimePeriod.Unit.HOUR -> Duration.ofHours(period.count)
      else -> error("Unsupported time unit: $period")
    }
  }
}