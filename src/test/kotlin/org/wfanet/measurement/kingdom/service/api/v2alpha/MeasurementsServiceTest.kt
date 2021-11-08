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

package org.wfanet.measurement.kingdom.service.api.v2alpha

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.extensions.proto.ProtoTruth.assertThat
import com.google.protobuf.Timestamp
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.UseConstructor
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.wfanet.measurement.api.Version
import org.wfanet.measurement.api.v2alpha.CancelMeasurementRequest
import org.wfanet.measurement.api.v2alpha.DataProviderCertificateKey
import org.wfanet.measurement.api.v2alpha.DataProviderKey
import org.wfanet.measurement.api.v2alpha.GetMeasurementRequest
import org.wfanet.measurement.api.v2alpha.ListMeasurementsRequest
import org.wfanet.measurement.api.v2alpha.ListMeasurementsRequestKt.filter
import org.wfanet.measurement.api.v2alpha.Measurement.Failure
import org.wfanet.measurement.api.v2alpha.Measurement.State
import org.wfanet.measurement.api.v2alpha.MeasurementConsumerCertificateKey
import org.wfanet.measurement.api.v2alpha.MeasurementConsumerKey
import org.wfanet.measurement.api.v2alpha.MeasurementKey
import org.wfanet.measurement.api.v2alpha.MeasurementKt.DataProviderEntryKt.value
import org.wfanet.measurement.api.v2alpha.MeasurementKt.dataProviderEntry
import org.wfanet.measurement.api.v2alpha.MeasurementKt.failure
import org.wfanet.measurement.api.v2alpha.MeasurementSpecKt.reachAndFrequency
import org.wfanet.measurement.api.v2alpha.ProtocolConfig
import org.wfanet.measurement.api.v2alpha.ProtocolConfigKt.liquidLegionsV2
import org.wfanet.measurement.api.v2alpha.cancelMeasurementRequest
import org.wfanet.measurement.api.v2alpha.copy
import org.wfanet.measurement.api.v2alpha.createMeasurementRequest
import org.wfanet.measurement.api.v2alpha.differentialPrivacyParams
import org.wfanet.measurement.api.v2alpha.getMeasurementRequest
import org.wfanet.measurement.api.v2alpha.liquidLegionsSketchParams
import org.wfanet.measurement.api.v2alpha.listMeasurementsRequest
import org.wfanet.measurement.api.v2alpha.listMeasurementsResponse
import org.wfanet.measurement.api.v2alpha.measurement
import org.wfanet.measurement.api.v2alpha.measurementSpec
import org.wfanet.measurement.api.v2alpha.protocolConfig
import org.wfanet.measurement.api.v2alpha.signedData
import org.wfanet.measurement.common.base64UrlEncode
import org.wfanet.measurement.common.grpc.testing.GrpcTestServerRule
import org.wfanet.measurement.common.identity.apiIdToExternalId
import org.wfanet.measurement.common.testing.captureFirst
import org.wfanet.measurement.common.testing.verifyProtoArgument
import org.wfanet.measurement.common.toProtoTime
import org.wfanet.measurement.internal.kingdom.DuchyProtocolConfig
import org.wfanet.measurement.internal.kingdom.Measurement as InternalMeasurement
import org.wfanet.measurement.internal.kingdom.Measurement.State as InternalState
import org.wfanet.measurement.internal.kingdom.MeasurementKt as InternalMeasurementKt
import org.wfanet.measurement.internal.kingdom.MeasurementsGrpcKt
import org.wfanet.measurement.internal.kingdom.ProtocolConfig as InternalProtocolConfig
import org.wfanet.measurement.internal.kingdom.ProtocolConfigKt as InternalProtocolConfigKt
import org.wfanet.measurement.internal.kingdom.StreamMeasurementsRequest
import org.wfanet.measurement.internal.kingdom.StreamMeasurementsRequestKt
import org.wfanet.measurement.internal.kingdom.cancelMeasurementRequest as internalCancelMeasurementRequest
import org.wfanet.measurement.internal.kingdom.copy
import org.wfanet.measurement.internal.kingdom.differentialPrivacyParams as internalDifferentialPrivacyParams
import org.wfanet.measurement.internal.kingdom.duchyProtocolConfig
import org.wfanet.measurement.internal.kingdom.getMeasurementRequest as internalGetMeasurementRequest
import org.wfanet.measurement.internal.kingdom.liquidLegionsSketchParams as internalLiquidLegionsSketchParams
import org.wfanet.measurement.internal.kingdom.measurement as internalMeasurement
import org.wfanet.measurement.internal.kingdom.protocolConfig as internalProtocolConfig
import org.wfanet.measurement.internal.kingdom.streamMeasurementsRequest
import org.wfanet.measurement.kingdom.deploy.common.Llv2ProtocolConfig

private const val DEFAULT_LIMIT = 50
private const val DATA_PROVIDERS_NAME = "dataProviders/AAAAAAAAAHs"
private const val DATA_PROVIDERS_CERTIFICATE_NAME =
  "dataProviders/AAAAAAAAAHs/certificates/AAAAAAAAAHs"
private const val MEASUREMENT_NAME = "measurementConsumers/AAAAAAAAAHs/measurements/AAAAAAAAAHs"
private const val MEASUREMENT_CONSUMER_NAME = "measurementConsumers/AAAAAAAAAHs"
private const val MEASUREMENT_CONSUMER_CERTIFICATE_NAME =
  "measurementConsumers/AAAAAAAAAHs/certificates/AAAAAAAAAHs"
private val UPDATE_TIME: Timestamp = Instant.ofEpochSecond(123).toProtoTime()
private val INTERNAL_PROTOCOL_CONFIG = internalProtocolConfig {
  externalProtocolConfigId = "llv2"
  measurementType = InternalProtocolConfig.MeasurementType.REACH_AND_FREQUENCY
  liquidLegionsV2 =
    InternalProtocolConfigKt.liquidLegionsV2 {
      sketchParams =
        internalLiquidLegionsSketchParams {
          decayRate = 1.1
          maxSize = 100
          samplingIndicatorSize = 1000
        }
      dataProviderNoise =
        internalDifferentialPrivacyParams {
          epsilon = 2.1
          delta = 3.3
        }
    }
}
private val PUBLIC_PROTOCOL_CONFIG = protocolConfig {
  name = "protocolConfigs/llv2"
  measurementType = ProtocolConfig.MeasurementType.REACH_AND_FREQUENCY
  liquidLegionsV2 =
    liquidLegionsV2 {
      sketchParams =
        liquidLegionsSketchParams {
          decayRate = 1.1
          maxSize = 100
          samplingIndicatorSize = 1000
        }
      dataProviderNoise =
        differentialPrivacyParams {
          epsilon = 2.1
          delta = 3.3
        }
    }
}
private val DUCHY_PROTOCOL_CONFIG = duchyProtocolConfig {
  liquidLegionsV2 = DuchyProtocolConfig.LiquidLegionsV2.getDefaultInstance()
}
private val MEASUREMENT_SPEC = measurementSpec {
  measurementPublicKey = UPDATE_TIME.toByteString()
  reachAndFrequency =
    reachAndFrequency {
      reachPrivacyParams =
        differentialPrivacyParams {
          epsilon = 1.0
          delta = 1.0
        }
      frequencyPrivacyParams =
        differentialPrivacyParams {
          epsilon = 1.0
          delta = 1.0
        }
    }
}
private val MEASUREMENT = measurement {
  name = MEASUREMENT_NAME
  measurementConsumerCertificate = MEASUREMENT_CONSUMER_CERTIFICATE_NAME
  measurementSpec =
    signedData {
      data = MEASUREMENT_SPEC.toByteString()
      signature = UPDATE_TIME.toByteString()
    }
  serializedDataProviderList = UPDATE_TIME.toByteString()
  dataProviderListSalt = UPDATE_TIME.toByteString()
  dataProviders +=
    dataProviderEntry {
      key = DATA_PROVIDERS_NAME
      value =
        value {
          dataProviderCertificate = DATA_PROVIDERS_CERTIFICATE_NAME
          dataProviderPublicKey =
            signedData {
              data = UPDATE_TIME.toByteString()
              signature = UPDATE_TIME.toByteString()
            }
          encryptedRequisitionSpec = UPDATE_TIME.toByteString()
        }
    }
  protocolConfig = PUBLIC_PROTOCOL_CONFIG
  measurementReferenceId = "ref_id"
  failure =
    failure {
      reason = Failure.Reason.CERTIFICATE_REVOKED
      message = "Measurement Consumer Certificate has been revoked"
    }
}

private val INTERNAL_MEASUREMENT = internalMeasurement {
  externalMeasurementConsumerId =
    apiIdToExternalId(
      MeasurementConsumerCertificateKey.fromName(MEASUREMENT.measurementConsumerCertificate)!!
        .measurementConsumerId
    )
  externalMeasurementId =
    apiIdToExternalId(MeasurementKey.fromName(MEASUREMENT.name)!!.measurementId)
  providedMeasurementId = MEASUREMENT.measurementReferenceId
  externalMeasurementConsumerCertificateId =
    apiIdToExternalId(
      MeasurementConsumerCertificateKey.fromName(MEASUREMENT.measurementConsumerCertificate)!!
        .certificateId
    )
  updateTime = UPDATE_TIME
  dataProviders.putAll(
    MEASUREMENT.dataProvidersList.associateBy(
      { apiIdToExternalId(DataProviderKey.fromName(it.key)!!.dataProviderId) },
      {
        InternalMeasurementKt.dataProviderValue {
          externalDataProviderCertificateId =
            apiIdToExternalId(
              DataProviderCertificateKey.fromName(it.value.dataProviderCertificate)!!.certificateId
            )
          dataProviderPublicKey = it.value.dataProviderPublicKey.data
          dataProviderPublicKeySignature = it.value.dataProviderPublicKey.signature
          encryptedRequisitionSpec = it.value.encryptedRequisitionSpec
        }
      }
    )
  )
  details =
    InternalMeasurementKt.details {
      apiVersion = Version.V2_ALPHA.string
      measurementSpec = MEASUREMENT.measurementSpec.data
      measurementSpecSignature = MEASUREMENT.measurementSpec.signature
      dataProviderList = MEASUREMENT.serializedDataProviderList
      dataProviderListSalt = MEASUREMENT.dataProviderListSalt
      protocolConfig = INTERNAL_PROTOCOL_CONFIG
      duchyProtocolConfig = DUCHY_PROTOCOL_CONFIG
      failure =
        InternalMeasurementKt.failure {
          reason = InternalMeasurement.Failure.Reason.CERTIFICATE_REVOKED
          message = MEASUREMENT.failure.message
        }
    }
}

@RunWith(JUnit4::class)
class MeasurementsServiceTest {
  private val internalMeasurementsMock: MeasurementsGrpcKt.MeasurementsCoroutineImplBase =
    mock(useConstructor = UseConstructor.parameterless()) {
      onBlocking { createMeasurement(any()) }.thenReturn(INTERNAL_MEASUREMENT)
      onBlocking { getMeasurement(any()) }.thenReturn(INTERNAL_MEASUREMENT)
      onBlocking { streamMeasurements(any()) }.thenReturn(flowOf(INTERNAL_MEASUREMENT))
      onBlocking { cancelMeasurement(any()) }.thenReturn(INTERNAL_MEASUREMENT)
    }

  @get:Rule val grpcTestServerRule = GrpcTestServerRule { addService(internalMeasurementsMock) }

  private lateinit var service: MeasurementsService

  @Before
  fun initService() {
    service =
      MeasurementsService(MeasurementsGrpcKt.MeasurementsCoroutineStub(grpcTestServerRule.channel))
  }

  @Test
  fun `getMeasurement returns measurement`() {
    val request = getMeasurementRequest { name = MEASUREMENT_NAME }

    val result = runBlocking { service.getMeasurement(request) }

    val expected = MEASUREMENT

    verifyProtoArgument(
        internalMeasurementsMock,
        MeasurementsGrpcKt.MeasurementsCoroutineImplBase::getMeasurement
      )
      .isEqualTo(
        internalGetMeasurementRequest {
          externalMeasurementConsumerId =
            apiIdToExternalId(MeasurementKey.fromName(MEASUREMENT_NAME)!!.measurementConsumerId)
          externalMeasurementId =
            apiIdToExternalId(MeasurementKey.fromName(MEASUREMENT_NAME)!!.measurementId)
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `getMeasurement throws INVALID_ARGUMENT when resource name is missing`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking { service.getMeasurement(GetMeasurementRequest.getDefaultInstance()) }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description)
      .isEqualTo("Resource name is either unspecified or invalid")
  }

  @Test
  fun `createMeasurement returns measurement with resource name set`() {
    val request = createMeasurementRequest { measurement = MEASUREMENT }

    val result = runBlocking { service.createMeasurement(request) }

    val expected = MEASUREMENT

    verifyProtoArgument(
        internalMeasurementsMock,
        MeasurementsGrpcKt.MeasurementsCoroutineImplBase::createMeasurement
      )
      .isEqualTo(
        INTERNAL_MEASUREMENT.copy {
          clearUpdateTime()
          clearExternalMeasurementId()
          details = details.copy { clearFailure() }
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `createMeasurement throws INVALID_ARGUMENT when certificate resource name is missing`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest {
              measurement = MEASUREMENT.copy { clearMeasurementConsumerCertificate() }
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description)
      .isEqualTo("Measurement Consumer Certificate resource name is either unspecified or invalid")
  }

  @Test
  fun `createMeasurement throws INVALID_ARGUMENT when measurement spec is missing`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest { measurement = MEASUREMENT.copy { clearMeasurementSpec() } }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Measurement spec is unspecified")
  }

  @Test
  fun `createMeasurement throws INVALID_ARGUMENT when measurement public key is missing`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest {
              measurement =
                MEASUREMENT.copy {
                  measurementSpec =
                    signedData {
                      data = MEASUREMENT_SPEC.copy { clearMeasurementPublicKey() }.toByteString()
                      signature = UPDATE_TIME.toByteString()
                    }
                }
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Measurement public key is unspecified")
  }

  @Test
  fun `createMeasurement throws INVALID_ARGUMENT when reach privacy params are missing`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest {
              measurement =
                MEASUREMENT.copy {
                  measurementSpec =
                    signedData {
                      data =
                        MEASUREMENT_SPEC
                          .copy {
                            clearReachAndFrequency()
                            reachAndFrequency =
                              reachAndFrequency {
                                frequencyPrivacyParams =
                                  differentialPrivacyParams {
                                    epsilon = 1.0
                                    delta = 1.0
                                  }
                              }
                          }
                          .toByteString()
                      signature = UPDATE_TIME.toByteString()
                    }
                }
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Reach privacy params are unspecified")
  }

  @Test
  fun `createMeasurement throws INVALID_ARGUMENT when frequency privacy params are missing`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest {
              measurement =
                MEASUREMENT.copy {
                  measurementSpec =
                    signedData {
                      data =
                        MEASUREMENT_SPEC
                          .copy {
                            clearReachAndFrequency()
                            reachAndFrequency =
                              reachAndFrequency {
                                reachPrivacyParams =
                                  differentialPrivacyParams {
                                    epsilon = 1.0
                                    delta = 1.0
                                  }
                              }
                          }
                          .toByteString()
                      signature = UPDATE_TIME.toByteString()
                    }
                }
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Frequency privacy params are unspecified")
  }

  @Test
  fun `createMeasurement throws INVALID_ARGUMENT when measurement type is missing`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest {
              measurement =
                MEASUREMENT.copy {
                  measurementSpec =
                    signedData {
                      data = MEASUREMENT_SPEC.copy { clearMeasurementType() }.toByteString()
                      signature = UPDATE_TIME.toByteString()
                    }
                }
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Measurement type is unspecified")
  }

  @Test
  fun `createMeasurement throws INVALID_ARGUMENT when serialized data provider list is missing`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest {
              measurement = MEASUREMENT.copy { clearSerializedDataProviderList() }
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description)
      .isEqualTo("Serialized Data Provider list is unspecified")
  }

  @Test
  fun `createMeasurement throws INVALID_ARGUMENT when data provider list salt is missing`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest {
              measurement = MEASUREMENT.copy { clearDataProviderListSalt() }
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Data Provider list salt is unspecified")
  }

  @Test
  fun `createMeasurement throws INVALID_ARGUMENT when Data Providers is missing`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest { measurement = MEASUREMENT.copy { dataProviders.clear() } }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Data Providers list is empty")
  }

  @Test
  fun `createMeasurement throws INVALID_ARGUMENT when Data Providers Entry is missing key`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest {
              measurement =
                MEASUREMENT.copy {
                  dataProviders.clear()
                  dataProviders += dataProviderEntry { key = "" }
                }
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description)
      .isEqualTo("Data Provider resource name is either unspecified or invalid")
  }

  @Test
  fun `createMeasurement throws error when Data Providers Entry value is missing cert name`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest {
              measurement =
                MEASUREMENT.copy {
                  dataProviders.clear()
                  dataProviders += dataProviderEntry { key = DATA_PROVIDERS_NAME }
                }
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description)
      .isEqualTo("Data Provider certificate resource name is either unspecified or invalid")
  }

  @Test
  fun `createMeasurement throws error when Data Providers Entry value is missing public key`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest {
              measurement =
                MEASUREMENT.copy {
                  dataProviders.clear()
                  dataProviders +=
                    dataProviderEntry {
                      key = DATA_PROVIDERS_NAME
                      value = value { dataProviderCertificate = DATA_PROVIDERS_CERTIFICATE_NAME }
                    }
                }
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Data Provider public key is unspecified")
  }

  @Test
  fun `createMeasurement throws error when Data Providers Entry value is missing spec`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest {
              measurement =
                MEASUREMENT.copy {
                  dataProviders.clear()
                  dataProviders +=
                    dataProviderEntry {
                      key = DATA_PROVIDERS_NAME
                      value =
                        value {
                          dataProviderCertificate = DATA_PROVIDERS_CERTIFICATE_NAME
                          dataProviderPublicKey =
                            signedData {
                              data = UPDATE_TIME.toByteString()
                              signature = UPDATE_TIME.toByteString()
                            }
                        }
                    }
                }
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Encrypted Requisition spec is unspecified")
  }

  @Test
  fun `listMeasurements with page token uses filter with timestamp from page token`() {
    val request = listMeasurementsRequest {
      parent = MEASUREMENT_CONSUMER_NAME
      pageToken = UPDATE_TIME.toByteArray().base64UrlEncode()
      filter =
        filter {
          states +=
            listOf(
              State.FAILED,
              State.SUCCEEDED,
              State.AWAITING_REQUISITION_FULFILLMENT,
              State.COMPUTING,
              State.CANCELLED
            )
        }
    }

    val result = runBlocking { service.listMeasurements(request) }

    val expected = listMeasurementsResponse {
      measurement += MEASUREMENT
      nextPageToken = UPDATE_TIME.toByteArray().base64UrlEncode()
    }

    val streamMeasurementsRequest =
      captureFirst<StreamMeasurementsRequest> {
        verify(internalMeasurementsMock).streamMeasurements(capture())
      }

    assertThat(streamMeasurementsRequest)
      .ignoringRepeatedFieldOrder()
      .isEqualTo(
        streamMeasurementsRequest {
          limit = DEFAULT_LIMIT
          filter =
            StreamMeasurementsRequestKt.filter {
              externalMeasurementConsumerId =
                apiIdToExternalId(
                  MeasurementConsumerKey.fromName(MEASUREMENT_CONSUMER_NAME)!!.measurementConsumerId
                )
              updatedAfter = UPDATE_TIME
              states +=
                listOf(
                  InternalState.FAILED,
                  InternalState.CANCELLED,
                  InternalState.PENDING_PARTICIPANT_CONFIRMATION,
                  InternalState.PENDING_COMPUTATION,
                  InternalState.SUCCEEDED,
                  InternalState.PENDING_REQUISITION_PARAMS,
                  InternalState.PENDING_REQUISITION_FULFILLMENT
                )
            }
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `listMeasurements throws INVALID_ARGUMENT when parent is missing`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking { service.listMeasurements(ListMeasurementsRequest.getDefaultInstance()) }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description)
      .isEqualTo("Resource name is either unspecified or invalid")
  }

  @Test
  fun `listMeasurements throws INVALID_ARGUMENT when pageSize is less than 0`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.listMeasurements(
            listMeasurementsRequest {
              parent = MEASUREMENT_CONSUMER_NAME
              pageSize = -1
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Page size cannot be less than 0")
  }

  @Test
  fun `cancelMeasurement returns measurement`() {
    val request = cancelMeasurementRequest { name = MEASUREMENT_NAME }

    val result = runBlocking { service.cancelMeasurement(request) }

    val expected = MEASUREMENT

    verifyProtoArgument(
        internalMeasurementsMock,
        MeasurementsGrpcKt.MeasurementsCoroutineImplBase::cancelMeasurement
      )
      .isEqualTo(
        internalCancelMeasurementRequest {
          externalMeasurementConsumerId =
            apiIdToExternalId(MeasurementKey.fromName(MEASUREMENT_NAME)!!.measurementConsumerId)
          externalMeasurementId =
            apiIdToExternalId(MeasurementKey.fromName(MEASUREMENT_NAME)!!.measurementId)
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `cancelMeasurement throws INVALID_ARGUMENT when resource name is missing`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking { service.cancelMeasurement(CancelMeasurementRequest.getDefaultInstance()) }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description)
      .isEqualTo("Resource name is either unspecified or invalid")
  }

  @Test
  fun `createMeasurement throws INVALID_ARGUMENT when dataProviderList has duplicated keys`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        runBlocking {
          service.createMeasurement(
            createMeasurementRequest {
              measurement = MEASUREMENT.copy { dataProviders += dataProviders }
            }
          )
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).contains("Duplicated keys")
  }

  companion object {
    @BeforeClass
    @JvmStatic
    fun initConfig() {
      Llv2ProtocolConfig.setForTest(
        INTERNAL_PROTOCOL_CONFIG.liquidLegionsV2,
        DUCHY_PROTOCOL_CONFIG.liquidLegionsV2
      )
    }
  }
}
