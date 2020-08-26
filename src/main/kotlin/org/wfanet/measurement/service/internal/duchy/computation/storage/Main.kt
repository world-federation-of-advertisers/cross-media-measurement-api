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

package org.wfanet.measurement.service.internal.duchy.computation.storage

import org.wfanet.measurement.common.CommonServer
import org.wfanet.measurement.common.DuchyOrder
import org.wfanet.measurement.common.commandLineMain
import org.wfanet.measurement.crypto.DuchyPublicKeys
import org.wfanet.measurement.crypto.toDuchyOrder
import org.wfanet.measurement.db.duchy.computation.ComputationsRelationalDb
import org.wfanet.measurement.db.duchy.computation.LiquidLegionsSketchAggregationProtocol
import org.wfanet.measurement.db.duchy.computation.ProtocolStageEnumHelper
import org.wfanet.measurement.db.duchy.computation.ReadOnlyComputationsRelationalDb
import org.wfanet.measurement.db.duchy.computation.SingleProtocolDatabase
import org.wfanet.measurement.db.duchy.computation.gcp.ComputationMutations
import org.wfanet.measurement.db.duchy.computation.gcp.GcpSpannerComputationsDb
import org.wfanet.measurement.db.duchy.computation.gcp.GcpSpannerReadOnlyComputationsRelationalDb
import org.wfanet.measurement.db.gcp.SpannerFromFlags
import org.wfanet.measurement.duchy.CommonDuchyFlags
import org.wfanet.measurement.internal.duchy.ComputationStage
import org.wfanet.measurement.internal.duchy.ComputationStageDetails
import org.wfanet.measurement.internal.duchy.ComputationTypeEnum.ComputationType
import picocli.CommandLine

class GcpSingleProtocolDatabase(
  private val reader: GcpSpannerReadOnlyComputationsRelationalDb,
  private val writer: GcpSpannerComputationsDb<ComputationStage, ComputationStageDetails>,
  private val protocolStageEnumHelper: ProtocolStageEnumHelper<ComputationStage>,
  override val computationType: ComputationType
) : SingleProtocolDatabase,
  ReadOnlyComputationsRelationalDb by reader,
  ComputationsRelationalDb<ComputationStage, ComputationStageDetails> by writer,
  ProtocolStageEnumHelper<ComputationStage> by protocolStageEnumHelper

/** Creates a new Liquid Legions based spanner database client. */
fun newLiquidLegionsProtocolGapDatabaseClient(
  spanner: SpannerFromFlags,
  duchyOrder: DuchyOrder,
  duchyName: String
): GcpSingleProtocolDatabase =
  GcpSingleProtocolDatabase(
    reader = GcpSpannerReadOnlyComputationsRelationalDb(
      databaseClient = spanner.databaseClient,
      computationStagesHelper = LiquidLegionsSketchAggregationProtocol.ComputationStages
    ),
    writer = GcpSpannerComputationsDb(
      databaseClient = spanner.databaseClient,
      duchyName = duchyName,
      duchyOrder = duchyOrder,
      computationMutations = ComputationMutations(
        LiquidLegionsSketchAggregationProtocol.ComputationStages,
        LiquidLegionsSketchAggregationProtocol.ComputationStages.Details(listOf())
      )
    ),
    protocolStageEnumHelper = LiquidLegionsSketchAggregationProtocol.ComputationStages,
    computationType = ComputationType.LIQUID_LEGIONS_SKETCH_AGGREGATION_V1
  )

private class Flags {
  @CommandLine.Mixin
  lateinit var server: CommonServer.Flags
    private set

  @CommandLine.Mixin
  lateinit var duchy: CommonDuchyFlags
    private set

  @CommandLine.Mixin
  lateinit var duchyPublicKeys: DuchyPublicKeys.Flags
    private set

  @CommandLine.Mixin
  lateinit var spanner: SpannerFromFlags.Flags
    private set
}

@CommandLine.Command(
  name = "gcp_computation_storage_server",
  mixinStandardHelpOptions = true,
  showDefaultValues = true
)
private fun run(@CommandLine.Mixin flags: Flags) {
  val duchyName = flags.duchy.duchyName
  val duchyPublicKeyMap = DuchyPublicKeys.fromFlags(flags.duchyPublicKeys).latest
  require(duchyPublicKeyMap.containsKey(duchyName)) {
    "Public key not specified for Duchy $duchyName"
  }

  // Currently this server only accommodates the Liquid Legions protocol running on a GCP
  // instance. For a new cloud platform there would need to be an instance of
  // [SingleProtocolDatabase] which can interact with the database offerings of that cloud.
  // For a new protocol there would need to be implementations of [ProtocolStageEnumHelper]
  // for the new computation protocol.
  val gcpDatabaseClient = newLiquidLegionsProtocolGapDatabaseClient(
    spanner = SpannerFromFlags(flags.spanner),
    duchyOrder = duchyPublicKeyMap.toDuchyOrder(),
    duchyName = duchyName
  )
  CommonServer.fromFlags(
    flags.server,
    "GcpComputationStorageServer",
    ComputationStorageServiceImpl(gcpDatabaseClient)
  ).start().blockUntilShutdown()
}

fun main(args: Array<String>) = commandLineMain(::run, args)
