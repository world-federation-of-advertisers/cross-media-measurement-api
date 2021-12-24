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

package org.wfanet.measurement.kingdom.service.internal.testing

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.extensions.proto.ProtoTruth.assertThat
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.time.Clock
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.common.identity.IdGenerator
import org.wfanet.measurement.common.identity.RandomIdGenerator
import org.wfanet.measurement.internal.kingdom.AccountsGrpcKt.AccountsCoroutineImplBase
import org.wfanet.measurement.internal.kingdom.DataProvidersGrpcKt.DataProvidersCoroutineImplBase
import org.wfanet.measurement.internal.kingdom.EventGroup
import org.wfanet.measurement.internal.kingdom.EventGroupsGrpcKt.EventGroupsCoroutineImplBase
import org.wfanet.measurement.internal.kingdom.GetEventGroupRequest
import org.wfanet.measurement.internal.kingdom.MeasurementConsumersGrpcKt.MeasurementConsumersCoroutineImplBase
import org.wfanet.measurement.internal.kingdom.StreamEventGroupsRequestKt.filter
import org.wfanet.measurement.internal.kingdom.copy
import org.wfanet.measurement.internal.kingdom.eventGroup
import org.wfanet.measurement.internal.kingdom.streamEventGroupsRequest

private const val RANDOM_SEED = 1
private const val EXTERNAL_EVENT_GROUP_ID = 123L
private const val FIXED_EXTERNAL_ID = 6789L
private const val PROVIDED_EVENT_GROUP_ID = "ProvidedEventGroupId"

@RunWith(JUnit4::class)
abstract class EventGroupsServiceTest<T : EventGroupsCoroutineImplBase> {

  private val testClock: Clock = Clock.systemUTC()
  protected val idGenerator = RandomIdGenerator(testClock, Random(RANDOM_SEED))
  private val population = Population(testClock, idGenerator)
  private lateinit var eventGroupsService: T

  protected lateinit var measurementConsumersService: MeasurementConsumersCoroutineImplBase
    private set

  protected lateinit var dataProvidersService: DataProvidersCoroutineImplBase
    private set

  protected lateinit var accountsService: AccountsCoroutineImplBase
    private set

  protected abstract fun newServices(idGenerator: IdGenerator): EventGroupAndHelperServices<T>

  @Before
  fun initServices() {
    val services = newServices(idGenerator)
    eventGroupsService = services.eventGroupsService
    measurementConsumersService = services.measurementConsumersService
    dataProvidersService = services.dataProvidersService
    accountsService = services.accountsService
  }

  @Test
  fun `getEventGroup fails for missing EventGroup`() = runBlocking {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        eventGroupsService.getEventGroup(
          GetEventGroupRequest.newBuilder().setExternalEventGroupId(EXTERNAL_EVENT_GROUP_ID).build()
        )
      }

    assertThat(exception.status.code).isEqualTo(Status.Code.NOT_FOUND)
    assertThat(exception).hasMessageThat().contains("NOT_FOUND: EventGroup not found")
  }

  @Test
  fun `createEventGroup fails for missing data provider`() = runBlocking {
    val externalMeasurementConsumerId =
      population.createMeasurementConsumer(measurementConsumersService, accountsService)
        .externalMeasurementConsumerId

    val eventGroup = eventGroup {
      externalDataProviderId = FIXED_EXTERNAL_ID
      this.externalMeasurementConsumerId = externalMeasurementConsumerId
      providedEventGroupId = PROVIDED_EVENT_GROUP_ID
    }

    val exception =
      assertFailsWith<StatusRuntimeException> { eventGroupsService.createEventGroup(eventGroup) }

    assertThat(exception.status.code).isEqualTo(Status.Code.NOT_FOUND)
    assertThat(exception).hasMessageThat().contains("NOT_FOUND: DataProvider not found")
  }

  @Test
  fun `createEventGroup fails for missing measurement consumer`() = runBlocking {
    val externalDataProviderId =
      population.createDataProvider(dataProvidersService).externalDataProviderId

    val eventGroup = eventGroup {
      this.externalDataProviderId = externalDataProviderId
      externalMeasurementConsumerId = FIXED_EXTERNAL_ID
      providedEventGroupId = PROVIDED_EVENT_GROUP_ID
    }

    val exception =
      assertFailsWith<StatusRuntimeException> { eventGroupsService.createEventGroup(eventGroup) }

    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception)
      .hasMessageThat()
      .contains("INVALID_ARGUMENT: MeasurementConsumer not found")
  }

  @Test
  fun `createEventGroup succeeds`() = runBlocking {
    val externalMeasurementConsumerId =
      population.createMeasurementConsumer(measurementConsumersService, accountsService)
        .externalMeasurementConsumerId

    val externalDataProviderId =
      population.createDataProvider(dataProvidersService).externalDataProviderId

    val eventGroup = eventGroup {
      this.externalDataProviderId = externalDataProviderId
      this.externalMeasurementConsumerId = externalMeasurementConsumerId
      providedEventGroupId = PROVIDED_EVENT_GROUP_ID
    }

    val createdEventGroup = eventGroupsService.createEventGroup(eventGroup)

    assertThat(createdEventGroup)
      .isEqualTo(
        eventGroup
          .toBuilder()
          .also {
            it.externalEventGroupId = createdEventGroup.externalEventGroupId
            it.createTime = createdEventGroup.createTime
          }
          .build()
      )
  }

  @Test
  fun `createEventGroup returns already created eventGroup for the same ProvidedEventGroupId`() =
      runBlocking {
    val externalMeasurementConsumerId =
      population.createMeasurementConsumer(measurementConsumersService, accountsService)
        .externalMeasurementConsumerId

    val externalDataProviderId =
      population.createDataProvider(dataProvidersService).externalDataProviderId

    val eventGroup = eventGroup {
      this.externalDataProviderId = externalDataProviderId
      this.externalMeasurementConsumerId = externalMeasurementConsumerId
      providedEventGroupId = PROVIDED_EVENT_GROUP_ID
    }

    val createdEventGroup = eventGroupsService.createEventGroup(eventGroup)
    val secondCreateEventGroupAttempt = eventGroupsService.createEventGroup(eventGroup)
    assertThat(secondCreateEventGroupAttempt).isEqualTo(createdEventGroup)
  }

  @Test
  fun `createEventGroup creates new eventGroup when called without providedEventGroupId`(): Unit =
      runBlocking {
    val externalMeasurementConsumerId =
      population.createMeasurementConsumer(measurementConsumersService, accountsService)
        .externalMeasurementConsumerId

    val externalDataProviderId =
      population.createDataProvider(dataProvidersService).externalDataProviderId

    val eventGroup = eventGroup {
      this.externalDataProviderId = externalDataProviderId
      this.externalMeasurementConsumerId = externalMeasurementConsumerId
    }

    eventGroupsService.createEventGroup(eventGroup)

    val otherExternalMeasurementConsumerId =
      population.createMeasurementConsumer(measurementConsumersService, accountsService)
        .externalMeasurementConsumerId

    val otherEventGroup =
      eventGroup.copy {
        this.externalDataProviderId = externalDataProviderId
        this.externalMeasurementConsumerId = otherExternalMeasurementConsumerId
      }

    val secondCreateEventGroupAttempt = eventGroupsService.createEventGroup(otherEventGroup)
    assertThat(secondCreateEventGroupAttempt)
      .isEqualTo(
        otherEventGroup.copy {
          externalEventGroupId = secondCreateEventGroupAttempt.externalEventGroupId
          createTime = secondCreateEventGroupAttempt.createTime
        }
      )
  }

  @Test
  fun `getEventGroup succeeds`() = runBlocking {
    val externalMeasurementConsumerId =
      population.createMeasurementConsumer(measurementConsumersService, accountsService)
        .externalMeasurementConsumerId

    val externalDataProviderId =
      population.createDataProvider(dataProvidersService).externalDataProviderId

    val eventGroup = eventGroup {
      this.externalDataProviderId = externalDataProviderId
      this.externalMeasurementConsumerId = externalMeasurementConsumerId
    }

    val createdEventGroup = eventGroupsService.createEventGroup(eventGroup)

    val eventGroupRead =
      eventGroupsService.getEventGroup(
        GetEventGroupRequest.newBuilder()
          .also {
            it.externalDataProviderId = externalDataProviderId
            it.externalEventGroupId = createdEventGroup.externalEventGroupId
          }
          .build()
      )

    assertThat(eventGroupRead).isEqualTo(createdEventGroup)
  }

  @Test
  fun `streamEventGroups returns all eventGroups in order`(): Unit = runBlocking {
    val externalDataProviderId =
      population.createDataProvider(dataProvidersService).externalDataProviderId

    val eventGroup1 =
      eventGroupsService.createEventGroup(
        eventGroup {
          this.externalDataProviderId = externalDataProviderId
          this.externalMeasurementConsumerId =
            population.createMeasurementConsumer(measurementConsumersService, accountsService)
              .externalMeasurementConsumerId
          providedEventGroupId = "eventGroup1"
        }
      )

    val eventGroup2 =
      eventGroupsService.createEventGroup(
        eventGroup {
          this.externalDataProviderId = externalDataProviderId
          this.externalMeasurementConsumerId =
            population.createMeasurementConsumer(measurementConsumersService, accountsService)
              .externalMeasurementConsumerId
          providedEventGroupId = "eventGroup2"
        }
      )

    val eventGroups: List<EventGroup> =
      eventGroupsService
        .streamEventGroups(
          streamEventGroupsRequest {
            filter = filter { this.externalDataProviderId = externalDataProviderId }
          }
        )
        .toList()

    if (eventGroup1.externalEventGroupId < eventGroup2.externalEventGroupId) {
      assertThat(eventGroups)
        .comparingExpectedFieldsOnly()
        .containsExactly(eventGroup1, eventGroup2)
        .inOrder()
    } else {
      assertThat(eventGroups)
        .comparingExpectedFieldsOnly()
        .containsExactly(eventGroup2, eventGroup1)
        .inOrder()
    }
  }

  @Test
  fun `streamEventGroups can get one page at a time`(): Unit = runBlocking {
    val externalDataProviderId =
      population.createDataProvider(dataProvidersService).externalDataProviderId

    val eventGroup1 =
      eventGroupsService.createEventGroup(
        eventGroup {
          this.externalDataProviderId = externalDataProviderId
          this.externalMeasurementConsumerId =
            population.createMeasurementConsumer(measurementConsumersService, accountsService)
              .externalMeasurementConsumerId
          providedEventGroupId = "eventGroup1"
        }
      )

    val eventGroup2 =
      eventGroupsService.createEventGroup(
        eventGroup {
          this.externalDataProviderId = externalDataProviderId
          this.externalMeasurementConsumerId =
            population.createMeasurementConsumer(measurementConsumersService, accountsService)
              .externalMeasurementConsumerId
          providedEventGroupId = "eventGroup2"
        }
      )

    val eventGroups: List<EventGroup> =
      eventGroupsService
        .streamEventGroups(
          streamEventGroupsRequest {
            filter = filter { this.externalDataProviderId = externalDataProviderId }
            limit = 1
          }
        )
        .toList()

    assertThat(eventGroups).containsAnyOf(eventGroup1, eventGroup2)
    assertThat(eventGroups).hasSize(1)

    val eventGroups2: List<EventGroup> =
      eventGroupsService
        .streamEventGroups(
          streamEventGroupsRequest {
            filter =
              filter {
                this.externalDataProviderId = externalDataProviderId
                externalEventGroupIdAfter = eventGroups[0].externalEventGroupId
                externalDataProviderIdAfter = eventGroups[0].externalDataProviderId
              }
            limit = 1
          }
        )
        .toList()

    assertThat(eventGroups2).hasSize(1)
    assertThat(eventGroups2).containsAnyOf(eventGroup1, eventGroup2)
    assertThat(eventGroups2[0].externalEventGroupId)
      .isGreaterThan(eventGroups[0].externalEventGroupId)
  }

  @Test
  fun `streamEventGroups respects limit`(): Unit = runBlocking {
    val externalDataProviderId =
      population.createDataProvider(dataProvidersService).externalDataProviderId

    eventGroupsService.createEventGroup(
      eventGroup {
        this.externalDataProviderId = externalDataProviderId
        this.externalMeasurementConsumerId =
          population.createMeasurementConsumer(measurementConsumersService, accountsService)
            .externalMeasurementConsumerId
        providedEventGroupId = "eventGroup1"
      }
    )

    eventGroupsService.createEventGroup(
      eventGroup {
        this.externalDataProviderId = externalDataProviderId
        this.externalMeasurementConsumerId =
          population.createMeasurementConsumer(measurementConsumersService, accountsService)
            .externalMeasurementConsumerId
        providedEventGroupId = "eventGroup2"
      }
    )

    val eventGroups: List<EventGroup> =
      eventGroupsService
        .streamEventGroups(
          streamEventGroupsRequest {
            filter = filter { this.externalDataProviderId = externalDataProviderId }
            limit = 1
          }
        )
        .toList()

    assertThat(eventGroups).hasSize(1)
  }

  @Test
  fun `streamEventGroups respects externalMeasurementConsumerIds`(): Unit = runBlocking {
    val externalDataProviderId =
      population.createDataProvider(dataProvidersService).externalDataProviderId

    eventGroupsService.createEventGroup(
      eventGroup {
        this.externalDataProviderId = externalDataProviderId
        this.externalMeasurementConsumerId =
          population.createMeasurementConsumer(measurementConsumersService, accountsService)
            .externalMeasurementConsumerId
        providedEventGroupId = "eventGroup1"
      }
    )

    val eventGroup2 =
      eventGroupsService.createEventGroup(
        eventGroup {
          this.externalDataProviderId = externalDataProviderId
          this.externalMeasurementConsumerId =
            population.createMeasurementConsumer(measurementConsumersService, accountsService)
              .externalMeasurementConsumerId
          providedEventGroupId = "eventGroup2"
        }
      )

    val eventGroups: List<EventGroup> =
      eventGroupsService
        .streamEventGroups(
          streamEventGroupsRequest {
            filter =
              filter {
                this.externalDataProviderId = externalDataProviderId
                this.externalMeasurementConsumerIds += eventGroup2.externalMeasurementConsumerId
              }
          }
        )
        .toList()

    assertThat(eventGroups).comparingExpectedFieldsOnly().containsExactly(eventGroup2)
  }
}

data class EventGroupAndHelperServices<T : EventGroupsCoroutineImplBase>(
  val eventGroupsService: T,
  val measurementConsumersService: MeasurementConsumersCoroutineImplBase,
  val dataProvidersService: DataProvidersCoroutineImplBase,
  val accountsService: AccountsCoroutineImplBase
)
