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

package org.wfanet.measurement.db.kingdom

import kotlinx.coroutines.flow.Flow
import org.wfanet.measurement.common.ExternalId
import org.wfanet.measurement.internal.kingdom.Advertiser
import org.wfanet.measurement.internal.kingdom.Campaign
import org.wfanet.measurement.internal.kingdom.DataProvider
import org.wfanet.measurement.internal.kingdom.Report
import org.wfanet.measurement.internal.kingdom.Report.ReportState
import org.wfanet.measurement.internal.kingdom.ReportConfig
import org.wfanet.measurement.internal.kingdom.ReportConfigSchedule
import org.wfanet.measurement.internal.kingdom.ReportDetails
import org.wfanet.measurement.internal.kingdom.ReportLogEntry
import org.wfanet.measurement.internal.kingdom.Requisition
import org.wfanet.measurement.internal.kingdom.Requisition.RequisitionState
import org.wfanet.measurement.internal.kingdom.RequisitionTemplate

/**
 * Wrapper interface for the Kingdom's relational database.
 */
interface KingdomRelationalDatabase {
  /**
   * Persists a [Requisition] in the database.
   *
   * If an equivalent [Requisition] already exists, this will return that instead.
   *
   * @param requisition the Requisition to save
   * @return the [Requisition] in the database -- old or new
   */
  suspend fun createRequisition(requisition: Requisition): Requisition

  /**
   * Updates the state of a [Requisition] to [RequisitionState.FULFILLED].
   */
  suspend fun fulfillRequisition(externalRequisitionId: ExternalId, duchyId: String): Requisition

  /**
   * Streams [Requisition]s.
   */
  fun streamRequisitions(filter: StreamRequisitionsFilter, limit: Long): Flow<Requisition>

  /** Returns a Report given its external id. */
  suspend fun getReport(externalId: ExternalId): Report

  /**
   * Creates the next [Report] for a [ReportConfigSchedule].
   *
   * If the report start window would be in the future, this does nothing.
   */
  suspend fun createNextReport(externalScheduleId: ExternalId): Report

  /**
   * Updates the state of a [Report].
   */
  suspend fun updateReportState(externalReportId: ExternalId, state: ReportState): Report

  /**
   * Streams [Report]s ordered by ascending update time.
   */
  fun streamReports(filter: StreamReportsFilter, limit: Long): Flow<Report>

  /**
   * Streams [Report]s in state [ReportState.AWAITING_REQUISITION_FULFILLMENT] where all of their
   * [Requisition]s have state [RequisitionState.FULFILLED].
   */
  fun streamReadyReports(limit: Long): Flow<Report>

  /**
   * Associates a [Requisition] and a [Report].
   */
  suspend fun associateRequisitionToReport(
    externalRequisitionId: ExternalId,
    externalReportId: ExternalId
  )

  /** Lists the idealized [RequisitionTemplate]s for a [ReportConfig]. */
  fun listRequisitionTemplates(reportConfigId: ExternalId): Flow<RequisitionTemplate>

  /** Streams [ReportConfigSchedule]s with a nextReportStartTime in the past. */
  fun streamReadySchedules(limit: Long): Flow<ReportConfigSchedule>

  /** Appends a ReportLogEntry to a Report. Returns a copy with all fields filled in. */
  suspend fun addReportLogEntry(reportLogEntry: ReportLogEntry): ReportLogEntry

  /**
   * Confirms that [duchyId] is ready to start work on the Report for [externalReportId].
   *
   * If all duchies are ready, then the Report is transitioned into state [ReportState.IN_PROGRESS].
   *
   * @param externalReportId the Report
   * @param duchyId the stable Duchy identifier
   * @param externalRequisitionIds the [Requisition]s for which this Duchy is providing data
   * @return the modified Report
   * @throws IllegalArgumentException if [externalRequisitionIds] is not exactly what is expected
   **/
  suspend fun confirmDuchyReadiness(
    externalReportId: ExternalId,
    duchyId: String,
    externalRequisitionIds: Set<ExternalId>
  ): Report

  /**
   * Finalizes a [Report].
   *
   * @param externalReportId the Report
   * @param result the end result for the report
   */
  suspend fun finishReport(externalReportId: ExternalId, result: ReportDetails.Result): Report

  /**
   * Registers a Data Provider.
   */
  suspend fun createDataProvider(): DataProvider

  /**
   * Registers an Advertiser.
   */
  suspend fun createAdvertiser(): Advertiser

  /**
   * Registers a Campaign.
   *
   * @param externalDataProviderId the Data Provider providing data for the campaign
   * @param externalAdvertiserId the Advertiser owning of the campaign
   * @param providedCampaignId user-provided, unvalidated name of the campaign (for display in UIs)
   * @return the created [Campaign]
   */
  suspend fun createCampaign(
    externalDataProviderId: ExternalId,
    externalAdvertiserId: ExternalId,
    providedCampaignId: String
  ): Campaign

  /**
   * Creates a [ReportConfig] for an Advertiser.
   *
   * The `externalReportConfigId` in [reportConfig] is ignored and the return value will have a
   * new `externalReportConfigId` populated.
   */
  suspend fun createReportConfig(
    reportConfig: ReportConfig,
    campaigns: List<ExternalId>
  ): ReportConfig

  /**
   * Creates a [ReportConfigSchedule] for a [ReportConfig].
   *
   * The `externalScheduleId` in [schedule] is ignored and the return value will have a new
   * `externalScheduleId` populated.
   */
  suspend fun createSchedule(schedule: ReportConfigSchedule): ReportConfigSchedule
}
