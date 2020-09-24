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

import com.google.cloud.Timestamp
import com.google.cloud.spanner.Statement
import com.google.cloud.spanner.Struct
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.extensions.proto.ProtoTruth.assertThat
import kotlin.test.assertFails
import org.junit.Before
import org.junit.Test
import org.wfanet.measurement.common.toInstant
import org.wfanet.measurement.db.gcp.asSequence
import org.wfanet.measurement.db.gcp.toGcpTimestamp
import org.wfanet.measurement.db.gcp.toProtoBytes
import org.wfanet.measurement.db.gcp.toProtoJson
import org.wfanet.measurement.db.kingdom.gcp.testing.KingdomDatabaseTestBase
import org.wfanet.measurement.internal.kingdom.Report.ReportState
import org.wfanet.measurement.internal.kingdom.ReportLogEntry

private const val ADVERTISER_ID = 1L
private const val REPORT_CONFIG_ID = 2L
private const val SCHEDULE_ID = 3L
private const val EXTERNAL_ADVERTISER_ID = 4L
private const val EXTERNAL_REPORT_CONFIG_ID = 5L
private const val EXTERNAL_SCHEDULE_ID = 6L
private const val REPORT_ID = 7L
private const val EXTERNAL_REPORT_ID = 8L

private val REPORT_LOG_ENTRY: ReportLogEntry = ReportLogEntry.newBuilder().apply {
  externalReportId = EXTERNAL_REPORT_ID
  sourceBuilder.advertiserBuilder.externalAdvertiserId = 99999
  reportLogDetailsBuilder.apply {
    advertiserLogDetailsBuilder.apiMethod = "/Foo.Bar"
    reportMessage = "some-report-message"
  }
}.build()

class CreateReportLogEntryTest : KingdomDatabaseTestBase() {
  @Before
  fun populateDatabase() {
    insertAdvertiser(ADVERTISER_ID, EXTERNAL_ADVERTISER_ID)
    insertReportConfig(ADVERTISER_ID, REPORT_CONFIG_ID, EXTERNAL_REPORT_CONFIG_ID)
    insertReportConfigSchedule(ADVERTISER_ID, REPORT_CONFIG_ID, SCHEDULE_ID, EXTERNAL_SCHEDULE_ID)
    insertReport(
      ADVERTISER_ID,
      REPORT_CONFIG_ID,
      SCHEDULE_ID,
      REPORT_ID,
      EXTERNAL_REPORT_ID,
      state = ReportState.IN_PROGRESS
    )
  }

  private fun createReportLogEntry(reportLogEntry: ReportLogEntry): ReportLogEntry {
    return CreateReportLogEntry(reportLogEntry).execute(databaseClient)
  }

  private fun readReportLogEntries(): List<Struct> {
    return databaseClient
      .singleUse()
      .executeQuery(Statement.of("SELECT * FROM ReportLogEntries"))
      .asSequence()
      .toList()
  }

  private fun reportLogEntryStructWithCreateTime(createTime: Timestamp): Struct =
    Struct.newBuilder()
      .set("AdvertiserId").to(ADVERTISER_ID)
      .set("ReportConfigId").to(REPORT_CONFIG_ID)
      .set("ScheduleId").to(SCHEDULE_ID)
      .set("ReportId").to(REPORT_ID)
      .set("CreateTime").to(createTime)
      .set("ReportLogDetails").toProtoBytes(REPORT_LOG_ENTRY.reportLogDetails)
      .set("ReportLogDetailsJson").toProtoJson(REPORT_LOG_ENTRY.reportLogDetails)
      .build()

  @Test
  fun success() {
    val timestampBefore = currentSpannerTimestamp
    val reportLogEntry = createReportLogEntry(REPORT_LOG_ENTRY)
    val timestampAfter = currentSpannerTimestamp

    assertThat(reportLogEntry)
      .comparingExpectedFieldsOnly()
      .isEqualTo(REPORT_LOG_ENTRY)

    val createTime = reportLogEntry.createTime.toInstant()
    assertThat(createTime).isGreaterThan(timestampBefore)
    assertThat(createTime).isLessThan(timestampAfter)

    assertThat(readReportLogEntries())
      .containsExactly(reportLogEntryStructWithCreateTime(createTime.toGcpTimestamp()))
  }

  @Test
  fun `multiple ReportLogEntries`() {
    val reportLogEntry1 = createReportLogEntry(REPORT_LOG_ENTRY)
    val reportLogEntry2 = createReportLogEntry(REPORT_LOG_ENTRY)
    val reportLogEntry3 = createReportLogEntry(REPORT_LOG_ENTRY)
    assertThat(readReportLogEntries())
      .containsExactly(
        reportLogEntryStructWithCreateTime(reportLogEntry1.createTime.toGcpTimestamp()),
        reportLogEntryStructWithCreateTime(reportLogEntry2.createTime.toGcpTimestamp()),
        reportLogEntryStructWithCreateTime(reportLogEntry3.createTime.toGcpTimestamp())
      )
  }

  @Test
  fun `missing Report`() {
    val missingExternalReportId = EXTERNAL_REPORT_ID + 1
    val reportLogEntry =
      REPORT_LOG_ENTRY.toBuilder()
        .setExternalReportId(missingExternalReportId)
        .build()

    assertFails {
      createReportLogEntry(reportLogEntry)
    }
  }
}
