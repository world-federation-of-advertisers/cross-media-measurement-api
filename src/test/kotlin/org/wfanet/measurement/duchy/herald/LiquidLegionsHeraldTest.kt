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

package org.wfanet.measurement.duchy.herald

import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.UseConstructor
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.stub
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.api.v1alpha.GlobalComputation
import org.wfanet.measurement.api.v1alpha.GlobalComputationsGrpcKt.GlobalComputationsCoroutineImplBase
import org.wfanet.measurement.api.v1alpha.GlobalComputationsGrpcKt.GlobalComputationsCoroutineStub
import org.wfanet.measurement.api.v1alpha.MetricRequisition
import org.wfanet.measurement.api.v1alpha.StreamActiveGlobalComputationsResponse
import org.wfanet.measurement.db.duchy.computation.LiquidLegionsSketchAggregationComputationStorageClients
import org.wfanet.measurement.db.duchy.computation.testing.FakeComputationStorage
import org.wfanet.measurement.db.duchy.computation.testing.FakeComputationsBlobDb
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_ADD_NOISE
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.TO_CONFIRM_REQUISITIONS
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage.WAIT_TO_START
import org.wfanet.measurement.internal.duchy.ComputationDetails.RoleInComputation
import org.wfanet.measurement.internal.duchy.ComputationStageDetails
import org.wfanet.measurement.internal.duchy.ComputationStorageServiceGrpcKt
import org.wfanet.measurement.internal.duchy.ToConfirmRequisitionsStageDetails.RequisitionKey
import org.wfanet.measurement.service.internal.duchy.computation.storage.ComputationStorageServiceImpl
import org.wfanet.measurement.service.internal.duchy.computation.storage.newEmptyOutputBlobMetadata
import org.wfanet.measurement.service.internal.duchy.computation.storage.newInputBlobMetadata
import org.wfanet.measurement.service.internal.duchy.computation.storage.toProtocolStage
import org.wfanet.measurement.service.testing.GrpcTestServerRule

@RunWith(JUnit4::class)
internal class LiquidLegionsHeraldTest {

  private val globalComputations: GlobalComputationsCoroutineImplBase =
    mock(useConstructor = UseConstructor.parameterless()) {}
  private val otherDuchyNames = listOf("Bavaria", "Carinthia")
  private val fakeComputationStorage = FakeComputationStorage(otherDuchyNames)

  @get:Rule
  val grpcTestServerRule = GrpcTestServerRule { channel ->
    computationStorageClients = LiquidLegionsSketchAggregationComputationStorageClients(
      ComputationStorageServiceGrpcKt.ComputationStorageServiceCoroutineStub(channel),
      FakeComputationsBlobDb(),
      otherDuchyNames
    )
    listOf(
      globalComputations,
      ComputationStorageServiceImpl(fakeComputationStorage)
    )
  }

  private lateinit var computationStorageClients:
    LiquidLegionsSketchAggregationComputationStorageClients

  private val stub: GlobalComputationsCoroutineStub by lazy {
    GlobalComputationsCoroutineStub(grpcTestServerRule.channel)
  }

  private lateinit var herald: LiquidLegionsHerald
  @Before
  fun initHerald() {
    herald = LiquidLegionsHerald(computationStorageClients, stub)
  }

  @Test
  fun `syncStatuses on empty stream retains same computaiton token`() = runBlocking {
    mockStreamActiveComputationsToReturn(/* No items in stream. */)
    assertThat(herald.syncStatuses("TOKEN_OF_LAST_ITEM")).isEqualTo("TOKEN_OF_LAST_ITEM")
    assertThat(fakeComputationStorage).isEmpty()
  }

  @Test
  fun `syncStatuses creates new computations`() = runBlocking<Unit> {
    val confirmingKnown = ComputationAtKingdom(454647484950L, GlobalComputation.State.CONFIRMING)
    val newComputationsRequisitions = listOf(
      "alice/a/1234",
      "bob/bb/abc",
      "caroline/ccc/234567"
    )
    val confirmingUnknown =
      ComputationAtKingdom(321L, GlobalComputation.State.CONFIRMING, newComputationsRequisitions)
    mockStreamActiveComputationsToReturn(confirmingKnown, confirmingUnknown)

    fakeComputationStorage.addComputation(
      id = confirmingKnown.id,
      stage = TO_CONFIRM_REQUISITIONS.toProtocolStage(),
      role = RoleInComputation.PRIMARY,
      blobs = listOf(newInputBlobMetadata(0L, "input-blob"), newEmptyOutputBlobMetadata(1L))
    )

    assertThat(herald.syncStatuses(EMPTY_TOKEN)).isEqualTo(confirmingUnknown.continuationToken)
    assertThat(
      fakeComputationStorage
        .mapValues { (_, fakeComputation) -> fakeComputation.computationStage }
    ).containsExactly(
      confirmingKnown.id, TO_CONFIRM_REQUISITIONS.toProtocolStage(),
      confirmingUnknown.id, TO_CONFIRM_REQUISITIONS.toProtocolStage()
    )

    assertThat(fakeComputationStorage[confirmingUnknown.id]?.stageSpecificDetails)
      .isEqualTo(
        ComputationStageDetails.newBuilder().apply {
          toConfirmRequisitionsStageDetailsBuilder.apply {
            addKeys(requisitionKey("alice", "a", "1234"))
            addKeys(requisitionKey("bob", "bb", "abc"))
            addKeys(requisitionKey("caroline", "ccc", "234567"))
          }
        }.build()
      )
  }

  @Test
  fun `syncStatuses starts somputaitons in wait_to_start`() = runBlocking<Unit> {
    val waitingToStart = ComputationAtKingdom(42314125676756L, GlobalComputation.State.RUNNING)
    val addingNoise = ComputationAtKingdom(231313L, GlobalComputation.State.RUNNING)
    mockStreamActiveComputationsToReturn(waitingToStart, addingNoise)

    fakeComputationStorage.addComputation(
      id = waitingToStart.id,
      stage = WAIT_TO_START.toProtocolStage(),
      role = RoleInComputation.SECONDARY,
      blobs = listOf(
        newInputBlobMetadata(0L, "local-copy-of-sketches")
      )
    )

    fakeComputationStorage.addComputation(
      id = addingNoise.id,
      stage = TO_ADD_NOISE.toProtocolStage(),
      role = RoleInComputation.PRIMARY,
      blobs = listOf(
        newInputBlobMetadata(0L, "inputs-to-add-noise"),
        newEmptyOutputBlobMetadata(1L)
      )
    )

    assertThat(herald.syncStatuses(EMPTY_TOKEN)).isEqualTo(addingNoise.continuationToken)
    assertThat(
      fakeComputationStorage
        .mapValues { (_, fakeComputation) -> fakeComputation.computationStage }
    ).containsExactly(
      waitingToStart.id, TO_ADD_NOISE.toProtocolStage(),
      addingNoise.id, TO_ADD_NOISE.toProtocolStage()
    )
  }

  private fun mockStreamActiveComputationsToReturn(vararg computations: ComputationAtKingdom) =
    globalComputations.stub {
      onBlocking {streamActiveGlobalComputations(any()) }
        .thenReturn(computations.toList().map { it.streamedResponse }.asFlow())
      }

  companion object {
    const val EMPTY_TOKEN = ""
  }
}

/** Simple representation of a computation at the kingdom for testing. */
data class ComputationAtKingdom(
  val id: Long,
  val stateAtKingdom: GlobalComputation.State,
  val requisitionResourceKeys: List<String> = listOf()
) {
  private fun parseResourceKey(stringKey: String): MetricRequisition.Key {
    val (provider, campaign, requisition) = stringKey.split("/")
    return MetricRequisition.Key.newBuilder().apply {
      dataProviderId = provider
      campaignId = campaign
      metricRequisitionId = requisition
    }.build()
  }

  val continuationToken = "token_for_$id"
  val streamedResponse: StreamActiveGlobalComputationsResponse =
    StreamActiveGlobalComputationsResponse.newBuilder().apply {
      globalComputationBuilder.apply {
        keyBuilder.apply { globalComputationId = id.toString() }
        state = stateAtKingdom
        addAllMetricRequisitions(requisitionResourceKeys.map { parseResourceKey(it) })
      }
      continuationToken = this@ComputationAtKingdom.continuationToken
    }.build()
}

private fun requisitionKey(provider: String, campaign: String, requisition: String) =
  RequisitionKey.newBuilder().apply {
    dataProviderId = provider
    campaignId = campaign
    metricRequisitionId = requisition
  }.build()