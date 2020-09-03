package org.wfanet.measurement.db.kingdom.gcp.writers

import com.google.cloud.spanner.Mutation
import com.google.cloud.spanner.Value
import org.wfanet.measurement.common.ExternalId
import org.wfanet.measurement.db.gcp.bufferTo
import org.wfanet.measurement.db.gcp.toProtoBytes
import org.wfanet.measurement.db.gcp.toProtoJson
import org.wfanet.measurement.db.kingdom.gcp.readers.ReportReader
import org.wfanet.measurement.internal.kingdom.ReportLogEntry

class CreateReportLogEntry(
  private val reportLogEntry: ReportLogEntry
) : SpannerWriter<Unit, ReportLogEntry>() {

  override suspend fun TransactionScope.runTransaction() {
    val externalId = ExternalId(reportLogEntry.externalReportId)
    val reportReadResult = ReportReader().readExternalId(transactionContext, externalId)
    reportLogEntry
      .toInsertMutation(reportReadResult)
      .bufferTo(transactionContext)
  }

  override fun ResultScope<Unit>.buildResult(): ReportLogEntry {
    return reportLogEntry.toBuilder().apply {
      createTime = commitTimestamp.toProto()
    }.build()
  }
}

private fun ReportLogEntry.toInsertMutation(reportReadResult: ReportReader.Result): Mutation =
  Mutation.newInsertBuilder("ReportLogEntries")
    .set("AdvertiserId").to(reportReadResult.advertiserId)
    .set("ReportConfigId").to(reportReadResult.reportConfigId)
    .set("ScheduleId").to(reportReadResult.scheduleId)
    .set("ReportId").to(reportReadResult.reportId)
    .set("CreateTime").to(Value.COMMIT_TIMESTAMP)
    .set("ReportLogDetails").toProtoBytes(reportLogDetails)
    .set("ReportLogDetailsJson").toProtoJson(reportLogDetails)
    .build()