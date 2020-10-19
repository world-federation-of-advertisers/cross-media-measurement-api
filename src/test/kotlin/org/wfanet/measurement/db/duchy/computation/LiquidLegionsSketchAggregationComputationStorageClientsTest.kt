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

package org.wfanet.measurement.db.duchy.computation

import com.google.common.truth.extensions.proto.ProtoTruth
import com.google.protobuf.ByteString
import java.time.Instant
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.common.byteStringOf
import org.wfanet.measurement.common.crypto.ElGamalPublicKey
import org.wfanet.measurement.common.grpc.testing.GrpcTestServerRule
import org.wfanet.measurement.common.testing.TestClockWithNamedInstants
import org.wfanet.measurement.common.withPadding
import org.wfanet.measurement.db.duchy.computation.testing.FakeLiquidLegionsComputationDb
import org.wfanet.measurement.duchy.DuchyPublicKeyMap
import org.wfanet.measurement.duchy.toProtocolStage
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.COMPLETED
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_ADD_NOISE
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_APPEND_SKETCHES_AND_ADD_NOISE
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_BLIND_POSITIONS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_BLIND_POSITIONS_AND_JOIN_REGISTERS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_CONFIRM_REQUISITIONS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_DECRYPT_FLAG_COUNTS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_DECRYPT_FLAG_COUNTS_AND_COMPUTE_METRICS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.WAIT_CONCATENATED
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.WAIT_FLAG_COUNTS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.WAIT_SKETCHES
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.WAIT_TO_START
import org.wfanet.measurement.internal.duchy.ClaimWorkRequest
import org.wfanet.measurement.internal.duchy.ComputationBlobDependency
import org.wfanet.measurement.internal.duchy.ComputationDetails.CompletedReason
import org.wfanet.measurement.internal.duchy.ComputationDetails.RoleInComputation
import org.wfanet.measurement.internal.duchy.ComputationStageBlobMetadata
import org.wfanet.measurement.internal.duchy.ComputationStageDetails
import org.wfanet.measurement.internal.duchy.ComputationStorageServiceGrpcKt.ComputationStorageServiceCoroutineStub
import org.wfanet.measurement.internal.duchy.ComputationTypeEnum.ComputationType
import org.wfanet.measurement.internal.duchy.CreateComputationRequest
import org.wfanet.measurement.internal.duchy.EnqueueComputationRequest
import org.wfanet.measurement.internal.duchy.FinishComputationRequest
import org.wfanet.measurement.internal.duchy.RecordOutputBlobPathRequest
import org.wfanet.measurement.service.internal.duchy.computation.storage.ComputationStorageServiceImpl
import org.wfanet.measurement.service.internal.duchy.computation.storage.newEmptyOutputBlobMetadata
import org.wfanet.measurement.service.internal.duchy.computation.storage.toGetTokenRequest
import org.wfanet.measurement.storage.StorageClient
import org.wfanet.measurement.system.v1alpha.GlobalComputationsGrpcKt.GlobalComputationsCoroutineStub

private const val ELLIPTIC_CURVE_ID = 415 // prime256v1
private val EL_GAMAL_GENERATOR = byteStringOf(
  0x03, 0x6B, 0x17, 0xD1, 0xF2, 0xE1, 0x2C, 0x42, 0x47, 0xF8, 0xBC, 0xE6, 0xE5, 0x63, 0xA4, 0x40,
  0xF2, 0x77, 0x03, 0x7D, 0x81, 0x2D, 0xEB, 0x33, 0xA0, 0xF4, 0xA1, 0x39, 0x45, 0xD8, 0x98, 0xC2,
  0x96
)
private const val ID_WHERE_ALSACE_IS_NOT_PRIMARY = "123"
private const val ID_WHERE_ALSACE_IS_PRIMARY = "456"
private const val ALSACE = "Alsace"
private const val BAVARIA = "Bavaria"
private const val CARINTHIA = "Carinthia"
private val DUCHIES = listOf(ALSACE, BAVARIA, CARINTHIA)

@RunWith(JUnit4::class)
class LiquidLegionsSketchAggregationComputationStorageClientsTest {
  private val fakeDatabase = FakeLiquidLegionsComputationDb()

  @get:Rule
  val grpcTestServerRule = GrpcTestServerRule {
    globalComputationClient = GlobalComputationsCoroutineStub(channel)
    addService(ComputationStorageServiceImpl(fakeDatabase, globalComputationClient, "DUCHY 1"))
  }

  private lateinit var globalComputationClient: GlobalComputationsCoroutineStub

  private val dummyStorageClient = object : StorageClient {
    override val defaultBufferSizeBytes: Int
      get() {
        throw NotImplementedError("Unused by test")
      }

    override suspend fun createBlob(
      blobKey: String,
      content: Flow<ByteString>
    ): StorageClient.Blob {
      throw NotImplementedError("Unused by test")
    }

    override fun getBlob(blobKey: String): StorageClient.Blob? {
      throw NotImplementedError("Unused by test")
    }
  }

  @Test
  fun runProtocolAtNonPrimaryWorker() = runBlocking {
    val testClock = TestClockWithNamedInstants(Instant.ofEpochMilli(100L))
    val computation = SingleLiquidLegionsComputation(
      LiquidLegionsSketchAggregationComputationStorageClients(
        ComputationStorageServiceCoroutineStub(
          channel = grpcTestServerRule.channel
        ),
        storageClient = dummyStorageClient,
        otherDuchies = publicKeysMap.keys.minus(ALSACE).toList()
      ),
      ID_WHERE_ALSACE_IS_NOT_PRIMARY,
      testClock
    )
    val fakeRpcService = computation.FakeRpcService()
    computation.enqueue()
    computation.claimWorkFor("mill-1")
    computation.writeOutputs(TO_CONFIRM_REQUISITIONS)
    computation.runWaitStage(WAIT_TO_START, numOfOutput = 0)
    computation.start()

    computation.claimWorkFor("mill-2")
    computation.writeOutputs(TO_ADD_NOISE)
    computation.runWaitStage(WAIT_CONCATENATED)

    fakeRpcService.receiveConcatenatedSketchGrpc()

    computation.claimWorkFor("mill-3")
    computation.writeOutputs(TO_BLIND_POSITIONS)
    computation.runWaitStage(WAIT_FLAG_COUNTS)

    fakeRpcService.receiveFlagCountsGrpc()

    computation.claimWorkFor("mill-4")
    computation.writeOutputs(TO_DECRYPT_FLAG_COUNTS)
    computation.end(reason = CompletedReason.SUCCEEDED)
  }

  @Test
  fun runProtocolAtPrimaryWorker() = runBlocking {
    val testClock = TestClockWithNamedInstants(Instant.ofEpochMilli(100L))
    val computation = SingleLiquidLegionsComputation(
      LiquidLegionsSketchAggregationComputationStorageClients(
        ComputationStorageServiceCoroutineStub(
          channel = grpcTestServerRule.channel
        ),
        storageClient = dummyStorageClient,
        otherDuchies = publicKeysMap.keys.minus(ALSACE).toList()
      ),
      ID_WHERE_ALSACE_IS_PRIMARY,
      testClock
    )
    val fakeRpcService = computation.FakeRpcService()

    computation.enqueue()
    computation.claimWorkFor("mill-1")
    computation.writeOutputs(TO_CONFIRM_REQUISITIONS)
    computation.waitForSketches(
      LiquidLegionsSketchAggregationProtocol.EnumStages.Details(DUCHIES.subList(1, 3)).detailsFor(
        WAIT_SKETCHES
      )
    )
    fakeRpcService.receiveSketch(BAVARIA)
    fakeRpcService.receiveSketch(CARINTHIA)

    computation.claimWorkFor("mill-2")
    computation.writeOutputs(TO_APPEND_SKETCHES_AND_ADD_NOISE)
    computation.runWaitStage(WAIT_CONCATENATED)

    fakeRpcService.receiveConcatenatedSketchGrpc()

    computation.claimWorkFor("mill-3")
    computation.writeOutputs(TO_BLIND_POSITIONS_AND_JOIN_REGISTERS)
    computation.runWaitStage(WAIT_FLAG_COUNTS)
    fakeRpcService.receiveFlagCountsGrpc()

    computation.claimWorkFor("mill-4")
    computation.writeOutputs(TO_DECRYPT_FLAG_COUNTS_AND_COMPUTE_METRICS)
    computation.end(reason = CompletedReason.SUCCEEDED)
  }

  companion object {
    private val publicKeysMap: DuchyPublicKeyMap =
      DUCHIES.mapIndexed { idx, name ->
        name to ElGamalPublicKey(
          ELLIPTIC_CURVE_ID,
          EL_GAMAL_GENERATOR,
          byteStringOf(idx).withPadding(ElGamalPublicKey.ELEMENT_SIZE)
        )
      }.toMap()
  }
}

/** Data about a single step of a computation. .*/
data class ComputationStep(
  val token: org.wfanet.measurement.internal.duchy.ComputationToken,
  val inputs: List<ComputationStageBlobMetadata>,
  val outputs: List<ComputationStageBlobMetadata>
)

/**
 * Encapsulates a computation and the operations to run on it for the MPC.
 *
 * The computation is inserted when the object is created.
 *
 * Because it is a view of a single computation, the computation token is saved after each
 * operation.
 */
class SingleLiquidLegionsComputation(
  private val storageClients: LiquidLegionsSketchAggregationComputationStorageClients,
  globalId: String,
  private val testClock: TestClockWithNamedInstants
) {

  private var token: org.wfanet.measurement.internal.duchy.ComputationToken = runBlocking {
    storageClients.computationStorageClient.createComputation(
      CreateComputationRequest.newBuilder().apply {
        globalComputationId = globalId
        computationType = ComputationType.LIQUID_LEGIONS_SKETCH_AGGREGATION_V1
      }.build()
    ).token
  }
  val localId by lazy { token.localComputationId }

  suspend fun writeOutputs(stage: LiquidLegionsSketchAggregationStage) {
    assertEquals(stage.toProtocolStage(), token.computationStage)
    testClock.tickSeconds(
      "${token.computationStage.liquidLegionsSketchAggregation}_$token.attempt_outputs"
    )
    token.blobsList.filter { it.dependencyType == ComputationBlobDependency.OUTPUT }
      .forEach {
        token =
          storageClients.computationStorageClient.recordOutputBlobPath(
            RecordOutputBlobPathRequest.newBuilder()
              .setToken(token)
              .setOutputBlobId(it.blobId)
              .setBlobPath("unused_output_${it.blobId}")
              .build()
          ).token
      }
  }

  /** Runs an operation and checks the returned token from the operation matches the expected. */
  private fun assertTokenChangesTo(
    expected: org.wfanet.measurement.internal.duchy.ComputationToken,
    run: suspend (ComputationStep) -> org.wfanet.measurement.internal.duchy.ComputationToken
  ) = runBlocking {
    testClock.tickSeconds("${expected.computationStage}_$expected.attempt")
    // Some stages use the inputs to their predecessor as inputs it itself. If the inputs are needed
    // they will be fetched.
    val inputsToCurrentStage = token.blobsList.ofType(ComputationBlobDependency.INPUT)
    val outputsToCurrentStage = token.blobsList.ofType(ComputationBlobDependency.OUTPUT)
    val result = run(ComputationStep(token, inputsToCurrentStage, outputsToCurrentStage))
    ProtoTruth.assertThat(result)
      .isEqualTo(expected.toBuilder().setVersion(token.version + 1).build())
    token = result
  }

  /** Add computation to work queue and verify that it has no owner. */
  suspend fun enqueue() {
    assertTokenChangesTo(token.toBuilder().setAttempt(0).build()) {
      storageClients.computationStorageClient.enqueueComputation(
        EnqueueComputationRequest.newBuilder().setToken(token).build()
      )
      storageClients.computationStorageClient
        .getComputationToken(token.globalComputationId.toGetTokenRequest())
        .token
    }
  }

  /** Get computation from work queue and verify it is owned by the [workerId]. */
  suspend fun claimWorkFor(workerId: String) {
    assertTokenChangesTo(token.toBuilder().setAttempt(1).build()) {
      val claimed = storageClients.computationStorageClient.claimWork(
        ClaimWorkRequest.newBuilder()
          .setOwner(workerId)
          .setComputationType(ComputationType.LIQUID_LEGIONS_SKETCH_AGGREGATION_V1)
          .build()
      )
      ProtoTruth.assertThat(claimed).isNotEqualToDefaultInstance()
      claimed.token
    }
  }

  inner class FakeRpcService {
    /**
     * Fake receiving a sketch from a [sender], if all sketches have been received
     * set stage to [TO_BLIND_POSITIONS_AND_JOIN_REGISTERS].
     */
    suspend fun receiveSketch(sender: String) {
      val stageDetails = token.stageSpecificDetails.waitSketchStageDetails

      val blobId = checkNotNull(stageDetails.externalDuchyLocalBlobIdMap[sender])
      val path = "unused_${sender}_$blobId"
      token = storageClients.computationStorageClient.recordOutputBlobPath(
        RecordOutputBlobPathRequest.newBuilder()
          .setToken(token)
          .setOutputBlobId(blobId)
          .setBlobPath(path)
          .build()
      ).token

      val notWritten =
        token.blobsList.count {
          it.dependencyType == ComputationBlobDependency.OUTPUT &&
            it.path.isEmpty()
        }
      if (notWritten == 0) {
        assertTokenChangesTo(
          token.outputBlobsToInputBlobs(keepInputs = true)
            .addEmptyOutputs(1)
            .clearStageSpecificDetails()
            .setComputationStage(
              TO_APPEND_SKETCHES_AND_ADD_NOISE.toProtocolStage()
            ).setAttempt(0).build()
        ) {
          storageClients.transitionComputationToStage(
            it.token,
            it.inputs.paths() + it.outputs.paths(),
            TO_APPEND_SKETCHES_AND_ADD_NOISE
          )
        }
      }
    }

    /** Fakes receiving the concatenated sketch from the incoming duchy. */
    suspend fun receiveConcatenatedSketchGrpc() {
      writeOutputs(WAIT_CONCATENATED)
      val stage =
        if (token.role == RoleInComputation.PRIMARY) TO_BLIND_POSITIONS_AND_JOIN_REGISTERS
        else TO_BLIND_POSITIONS
      assertTokenChangesTo(
        token.outputBlobsToInputBlobs()
          .addBlobs(newEmptyOutputBlobMetadata(1))
          .setComputationStage(stage.toProtocolStage())
          .setAttempt(0)
          .build()
      ) {
        storageClients.transitionComputationToStage(
          it.token,
          it.outputs.paths(),
          stage
        )
      }
    }

    /** Fakes receiving the joined sketch from the incoming duchy. */
    suspend fun receiveFlagCountsGrpc() {
      writeOutputs(WAIT_FLAG_COUNTS)
      val stage =
        if (token.role == RoleInComputation.PRIMARY) TO_DECRYPT_FLAG_COUNTS_AND_COMPUTE_METRICS
        else TO_DECRYPT_FLAG_COUNTS
      assertTokenChangesTo(
        token
          .outputBlobsToInputBlobs()
          .addEmptyOutputs(1)
          .setComputationStage(stage.toProtocolStage()).setAttempt(0).build()
      ) {
        storageClients.transitionComputationToStage(
          it.token,
          it.outputs.paths(),
          stage
        )
      }
    }
  }

  /** Move to a waiting stage and make sure the computation is not in the work queue. */
  suspend fun waitForSketches(details: ComputationStageDetails) {
    assertTokenChangesTo(
      token
        .outputBlobsToInputBlobs()
        .addEmptyOutputs(2)
        .setComputationStage(WAIT_SKETCHES.toProtocolStage())
        .setAttempt(1)
        .setStageSpecificDetails(details)
        .build()
    ) {
      storageClients.transitionComputationToStage(it.token, it.outputs.paths(), WAIT_SKETCHES)
    }
  }

  suspend fun start() {
    assertTokenChangesTo(
      token
        .outputBlobsToInputBlobs(keepInputs = true)
        .addEmptyOutputs(1)
        .setComputationStage(TO_ADD_NOISE.toProtocolStage())
        .setAttempt(0)
        .build()
    ) {
      storageClients.transitionComputationToStage(it.token, it.inputs.paths(), TO_ADD_NOISE)
    }
  }

  /** Move to a waiting stage and make sure the computation is not in the work queue. */
  suspend fun runWaitStage(stage: LiquidLegionsSketchAggregationStage, numOfOutput: Int = 1) {
    assertTokenChangesTo(
      token
        .outputBlobsToInputBlobs()
        .addEmptyOutputs(numOfOutput)
        .setComputationStage(stage.toProtocolStage()).setAttempt(1)
        .build()
    ) {
      storageClients.transitionComputationToStage(it.token, it.outputs.paths(), stage)
    }
  }

  suspend fun end(reason: CompletedReason) {
    token = storageClients.computationStorageClient.finishComputation(
      FinishComputationRequest.newBuilder()
        .setToken(token)
        .setEndingComputationStage(COMPLETED.toProtocolStage())
        .setReason(reason)
        .build()
    ).token
  }
}

fun List<ComputationStageBlobMetadata>.paths() = map { it.path }
fun List<ComputationStageBlobMetadata>.ofType(dependencyType: ComputationBlobDependency) =
  filter { it.dependencyType == dependencyType }

fun org.wfanet.measurement.internal.duchy.ComputationToken.outputBlobsToInputBlobs(
  keepInputs: Boolean = false
):
  org.wfanet.measurement.internal.duchy.ComputationToken.Builder {
    return toBuilder().clearBlobs().addAllBlobs(
      blobsList.filter { keepInputs || it.dependencyType == ComputationBlobDependency.OUTPUT }
        .mapIndexed { index, blob ->
          blob.toBuilder()
            .setDependencyType(ComputationBlobDependency.INPUT)
            .setBlobId(index.toLong())
            .build()
        }
    )
  }

fun org.wfanet.measurement.internal.duchy.ComputationToken.Builder.addEmptyOutputs(
  n: Int
): org.wfanet.measurement.internal.duchy.ComputationToken.Builder {
  val currentMaxIndex = blobsCount.toLong()
  (0 until n).forEach {
    addBlobs(newEmptyOutputBlobMetadata(currentMaxIndex + it))
  }
  return this
}
