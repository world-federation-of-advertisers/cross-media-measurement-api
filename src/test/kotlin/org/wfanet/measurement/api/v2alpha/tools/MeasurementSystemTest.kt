/*
 * Copyright 2022 The Cross-Media Measurement Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wfanet.measurement.api.v2alpha.tools

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.extensions.proto.ProtoTruth.assertThat
import com.google.protobuf.ByteString
import com.google.protobuf.duration
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.ServerServiceDefinition
import io.grpc.netty.NettyServerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Paths
import java.security.Permission
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.any
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.wfanet.measurement.api.AccountConstants
import org.wfanet.measurement.api.ApiKeyConstants
import org.wfanet.measurement.api.v2alpha.Account
import org.wfanet.measurement.api.v2alpha.AccountKt
import org.wfanet.measurement.api.v2alpha.AccountsGrpcKt.AccountsCoroutineImplBase
import org.wfanet.measurement.api.v2alpha.CertificatesGrpcKt
import org.wfanet.measurement.api.v2alpha.CreateMeasurementRequest
import org.wfanet.measurement.api.v2alpha.DataProvidersGrpcKt
import org.wfanet.measurement.api.v2alpha.EncryptionPublicKey
import org.wfanet.measurement.api.v2alpha.GetMeasurementRequest
import org.wfanet.measurement.api.v2alpha.ListMeasurementsRequest
import org.wfanet.measurement.api.v2alpha.Measurement
import org.wfanet.measurement.api.v2alpha.MeasurementConsumersGrpcKt
import org.wfanet.measurement.api.v2alpha.MeasurementKt.ResultKt.frequency
import org.wfanet.measurement.api.v2alpha.MeasurementKt.ResultKt.impression
import org.wfanet.measurement.api.v2alpha.MeasurementKt.ResultKt.reach
import org.wfanet.measurement.api.v2alpha.MeasurementKt.ResultKt.watchDuration
import org.wfanet.measurement.api.v2alpha.MeasurementKt.failure
import org.wfanet.measurement.api.v2alpha.MeasurementKt.result
import org.wfanet.measurement.api.v2alpha.MeasurementKt.resultPair
import org.wfanet.measurement.api.v2alpha.MeasurementSpec
import org.wfanet.measurement.api.v2alpha.MeasurementSpecKt
import org.wfanet.measurement.api.v2alpha.MeasurementsGrpcKt
import org.wfanet.measurement.api.v2alpha.RequisitionSpec
import org.wfanet.measurement.api.v2alpha.RequisitionSpecKt
import org.wfanet.measurement.api.v2alpha.account
import org.wfanet.measurement.api.v2alpha.activateAccountRequest
import org.wfanet.measurement.api.v2alpha.authenticateRequest
import org.wfanet.measurement.api.v2alpha.authenticateResponse
import org.wfanet.measurement.api.v2alpha.certificate
import org.wfanet.measurement.api.v2alpha.dataProvider
import org.wfanet.measurement.api.v2alpha.differentialPrivacyParams
import org.wfanet.measurement.api.v2alpha.encryptionPublicKey
import org.wfanet.measurement.api.v2alpha.getMeasurementRequest
import org.wfanet.measurement.api.v2alpha.listMeasurementsRequest
import org.wfanet.measurement.api.v2alpha.listMeasurementsResponse
import org.wfanet.measurement.api.v2alpha.measurement
import org.wfanet.measurement.api.v2alpha.measurementConsumer
import org.wfanet.measurement.api.v2alpha.requisitionSpec
import org.wfanet.measurement.api.v2alpha.signedData
import org.wfanet.measurement.api.v2alpha.timeInterval
import org.wfanet.measurement.common.crypto.SigningCerts
import org.wfanet.measurement.common.crypto.SigningKeyHandle
import org.wfanet.measurement.common.crypto.readCertificate
import org.wfanet.measurement.common.crypto.readPrivateKey
import org.wfanet.measurement.common.crypto.tink.loadPrivateKey
import org.wfanet.measurement.common.crypto.tink.loadPublicKey
import org.wfanet.measurement.common.getRuntimePath
import org.wfanet.measurement.common.grpc.testing.mockService
import org.wfanet.measurement.common.grpc.toServerTlsContext
import org.wfanet.measurement.common.openid.createRequestUri
import org.wfanet.measurement.common.parseTextProto
import org.wfanet.measurement.common.readByteString
import org.wfanet.measurement.common.testing.captureFirst
import org.wfanet.measurement.common.testing.verifyProtoArgument
import org.wfanet.measurement.common.toProtoTime
import org.wfanet.measurement.consent.client.common.toEncryptionPublicKey
import org.wfanet.measurement.consent.client.dataprovider.decryptRequisitionSpec
import org.wfanet.measurement.consent.client.dataprovider.verifyMeasurementSpec
import org.wfanet.measurement.consent.client.dataprovider.verifyRequisitionSpec
import org.wfanet.measurement.consent.client.duchy.encryptResult
import org.wfanet.measurement.consent.client.duchy.signResult

private val SECRETS_DIR: File =
  getRuntimePath(
      Paths.get(
        "wfa_measurement_system",
        "src",
        "main",
        "k8s",
        "testing",
        "secretfiles",
      )
    )!!
    .toFile()

private val KINGDOM_TLS_CERT: File = SECRETS_DIR.resolve("kingdom_tls.pem")
private val KINGDOM_TLS_KEY: File = SECRETS_DIR.resolve("kingdom_tls.key")
private val KINGDOM_TRUSTED_CERTS: File = SECRETS_DIR.resolve("all_root_certs.pem")

private val CLIENT_TLS_CERT: File = SECRETS_DIR.resolve("mc_tls.pem")
private val CLIENT_TLS_KEY: File = SECRETS_DIR.resolve("mc_tls.key")
private val CLIENT_TRUSTED_CERTS: File = SECRETS_DIR.resolve("kingdom_root.pem")

private val SIOP_KEY: File = SECRETS_DIR.resolve("account1_siop_private.tink")

private val kingdomSigningCerts =
  SigningCerts.fromPemFiles(KINGDOM_TLS_CERT, KINGDOM_TLS_KEY, KINGDOM_TRUSTED_CERTS)

private const val API_KEY = "nR5QPN7ptx"

private const val MEASUREMENT_CONSUMER_NAME = "measurementConsumers/1"
private const val MEASUREMENT_CONSUMER_CERTIFICATE_NAME = "measurementConsumers/1/certificates/1"
private val MEASUREMENT_CONSUMER_CERTIFICATE_DER =
  SECRETS_DIR.resolve("mc_cs_cert.der").readByteString()
private val MEASUREMENT_CONSUMER_CERTIFICATE = readCertificate(MEASUREMENT_CONSUMER_CERTIFICATE_DER)
private val MEASUREMENT_PUBLIC_KEY = SECRETS_DIR.resolve("mc_enc_public.tink").readByteString()

private const val DATA_PROVIDER_NAME = "dataProviders/1"
private const val DATA_PROVIDER_CERTIFICATE_NAME = "dataProviders/1/certificates/1"
private val DATA_PROVIDER_PUBLIC_KEY =
  loadPublicKey(SECRETS_DIR.resolve("edp1_enc_public.tink")).toEncryptionPublicKey()
private val DATA_PROVIDER_PRIVATE_KEY_HANDLE =
  loadPrivateKey(SECRETS_DIR.resolve("edp1_enc_private.tink"))

private val MEASUREMENT_CONSUMER = measurementConsumer {
  name = MEASUREMENT_CONSUMER_NAME
  certificateDer = MEASUREMENT_CONSUMER_CERTIFICATE_DER
  certificate = MEASUREMENT_CONSUMER_CERTIFICATE_NAME
  publicKey = signedData { data = MEASUREMENT_PUBLIC_KEY }
}

private val DATA_PROVIDER = dataProvider {
  name = DATA_PROVIDER_NAME
  certificate = DATA_PROVIDER_CERTIFICATE_NAME
  publicKey = signedData { data = DATA_PROVIDER_PUBLIC_KEY.toByteString() }
}

private const val TIME_STRING_1 = "2022-05-22T01:00:00.000Z"
private const val TIME_STRING_2 = "2022-05-24T05:00:00.000Z"
private const val TIME_STRING_3 = "2022-05-22T01:22:32.122Z"
private const val TIME_STRING_4 = "2022-05-23T03:14:55.876Z"
private const val TIME_STRING_5 = "2022-04-22T01:19:42.567Z"
private const val TIME_STRING_6 = "2022-05-22T01:56:12.483Z"

private val AGGREGATOR_CERTIFICATE_DER =
  SECRETS_DIR.resolve("aggregator_cs_cert.der").readByteString()
private val AGGREGATOR_PRIVATE_KEY_DER =
  SECRETS_DIR.resolve("aggregator_cs_private.der").readByteString()
private val AGGREGATOR_SIGNING_KEY: SigningKeyHandle by lazy {
  val consentSignal509Cert = readCertificate(AGGREGATOR_CERTIFICATE_DER)
  SigningKeyHandle(
    consentSignal509Cert,
    readPrivateKey(AGGREGATOR_PRIVATE_KEY_DER, consentSignal509Cert.publicKey.algorithm)
  )
}
private val AGGREGATOR_CERTIFICATE = certificate { x509Der = AGGREGATOR_CERTIFICATE_DER }

private const val MEASUREMENT_NAME = "$MEASUREMENT_CONSUMER_NAME/measurements/100"
private val MEASUREMENT = measurement { name = MEASUREMENT_NAME }
private val SUCCEEDED_MEASUREMENT = measurement {
  name = MEASUREMENT_NAME
  state = Measurement.State.SUCCEEDED

  val measurementPublicKey = encryptionPublicKey {
    format = EncryptionPublicKey.Format.TINK_KEYSET
    data = MEASUREMENT_PUBLIC_KEY
  }
  results += resultPair {
    val result = result { reach = reach { value = 4096 } }
    encryptedResult = getEncryptedResult(result, measurementPublicKey)
    certificate = DATA_PROVIDER_CERTIFICATE_NAME
  }
  results += resultPair {
    val result = result {
      frequency = frequency {
        relativeFrequencyDistribution.put(1, 1.0 / 6)
        relativeFrequencyDistribution.put(2, 3.0 / 6)
        relativeFrequencyDistribution.put(3, 2.0 / 6)
      }
    }
    encryptedResult = getEncryptedResult(result, measurementPublicKey)
    certificate = DATA_PROVIDER_CERTIFICATE_NAME
  }
  results += resultPair {
    val result = result { impression = impression { value = 4096 } }
    encryptedResult = getEncryptedResult(result, measurementPublicKey)
    certificate = DATA_PROVIDER_CERTIFICATE_NAME
  }
  results += resultPair {
    val result = result {
      watchDuration = watchDuration {
        value = duration {
          seconds = 100
          nanos = 99
        }
      }
    }
    encryptedResult = getEncryptedResult(result, measurementPublicKey)
    certificate = DATA_PROVIDER_CERTIFICATE_NAME
  }
}

private val LIST_MEASUREMENT_RESPONSE = listMeasurementsResponse {
  measurement += measurement {
    name = "$MEASUREMENT_CONSUMER_NAME/measurements/101"
    state = Measurement.State.AWAITING_REQUISITION_FULFILLMENT
  }
  measurement += measurement {
    name = "$MEASUREMENT_CONSUMER_NAME/measurements/102"
    state = Measurement.State.SUCCEEDED
  }
  measurement += measurement {
    name = "$MEASUREMENT_CONSUMER_NAME/measurements/102"
    state = Measurement.State.FAILED
    failure = failure {
      reason = Measurement.Failure.Reason.REQUISITION_REFUSED
      message = "Privacy budget exceeded."
    }
  }
}

@RunWith(JUnit4::class)
class MeasurementSystemTest {
  private val accountsServiceMock: AccountsCoroutineImplBase = mockService()
  private val headerInterceptor = HeaderCapturingInterceptor()

  private val measurementConsumersServiceMock:
    MeasurementConsumersGrpcKt.MeasurementConsumersCoroutineImplBase =
    mockService() { onBlocking { getMeasurementConsumer(any()) }.thenReturn(MEASUREMENT_CONSUMER) }
  private val dataProvidersServiceMock: DataProvidersGrpcKt.DataProvidersCoroutineImplBase =
    mockService() { onBlocking { getDataProvider(any()) }.thenReturn(DATA_PROVIDER) }
  private val measurementsServiceMock: MeasurementsGrpcKt.MeasurementsCoroutineImplBase =
    mockService() {
      onBlocking { createMeasurement(any()) }.thenReturn(MEASUREMENT)
      onBlocking { listMeasurements(any()) }.thenReturn(LIST_MEASUREMENT_RESPONSE)
      onBlocking { getMeasurement(any()) }.thenReturn(SUCCEEDED_MEASUREMENT)
    }
  private val certificatesServiceMock: CertificatesGrpcKt.CertificatesCoroutineImplBase =
    mockService() { onBlocking { getCertificate(any()) }.thenReturn(AGGREGATOR_CERTIFICATE) }

  val services: List<ServerServiceDefinition> =
    listOf(
      ServerInterceptors.intercept(accountsServiceMock, headerInterceptor),
      ServerInterceptors.intercept(measurementsServiceMock, headerInterceptor),
      measurementConsumersServiceMock.bindService(),
      dataProvidersServiceMock.bindService(),
      certificatesServiceMock.bindService(),
    )

  private val publicApiServer: Server =
    NettyServerBuilder.forPort(0)
      .sslContext(kingdomSigningCerts.toServerTlsContext())
      .addServices(services)
      .build()

  @Before
  fun startServer() {
    publicApiServer.start()
  }

  @After
  fun shutdownServer() {
    publicApiServer.shutdown()
    publicApiServer.awaitTermination()
  }

  private val commonArgs: Array<String>
    get() =
      arrayOf(
        "--tls-cert-file=$CLIENT_TLS_CERT",
        "--tls-key-file=$CLIENT_TLS_KEY",
        "--cert-collection-file=$CLIENT_TRUSTED_CERTS",
        "--kingdom-public-api-target=localhost:${publicApiServer.port}"
      )

  private fun callCli(args: Array<String>): String {
    return capturingSystemOut { assertExitsWith(0) { MeasurementSystem.main(args) } }
  }

  @Test
  fun `accounts authenticate prints ID token`() {
    val args =
      commonArgs +
        arrayOf("accounts", "authenticate", "--self-issued-openid-provider-key=$SIOP_KEY")
    accountsServiceMock.stub {
      onBlocking { authenticate(any()) }
        .thenReturn(
          authenticateResponse {
            authenticationRequestUri = createRequestUri(1234L, 5678L, "https://example.com", true)
          }
        )
    }

    val output: String = callCli(args)

    verifyProtoArgument(accountsServiceMock, AccountsCoroutineImplBase::authenticate)
      .isEqualTo(authenticateRequest { issuer = MeasurementSystem.SELF_ISSUED_ISSUER })
    assertThat(output).matches("ID Token: [-_a-zA-Z0-9.]+\\s*")
  }

  @Test
  fun `accounts activate prints response`() {
    val accountName = "accounts/KcuXSjfBx9E"
    val idToken = "fake-id-token"
    val activationToken = "vzmtXavLdk4"
    val args =
      commonArgs +
        arrayOf(
          "accounts",
          "activate",
          accountName,
          "--id-token=$idToken",
          "--activation-token=$activationToken"
        )
    val account = account {
      name = accountName
      activationState = Account.ActivationState.ACTIVATED
      openId =
        AccountKt.openIdConnectIdentity {
          issuer = MeasurementSystem.SELF_ISSUED_ISSUER
          subject = "fake-oid-subject"
        }
    }
    accountsServiceMock.stub { onBlocking { activateAccount(any()) }.thenReturn(account) }

    val output: String = callCli(args)

    assertThat(
        headerInterceptor.capturedHeaders.single().get(AccountConstants.ID_TOKEN_METADATA_KEY)
      )
      .isEqualTo(idToken)
    verifyProtoArgument(accountsServiceMock, AccountsCoroutineImplBase::activateAccount)
      .isEqualTo(
        activateAccountRequest {
          name = accountName
          this.activationToken = activationToken
        }
      )
    assertThat(parseTextProto(output.reader(), Account.getDefaultInstance())).isEqualTo(account)
  }

  @Test
  fun `Create command call public api with valid Measurement`() {
    val args =
      commonArgs +
        arrayOf(
          "measurements",
          "--api-key=$API_KEY",
          "create",
          "--impression",
          "--impression-privacy-epsilon=0.015",
          "--impression-privacy-delta=0.0",
          "--max-frequency=1000",
          "--vid-sampling-start=0.1",
          "--vid-sampling-width=0.2",
          "--measurement-consumer=measurementConsumers/777",
          "--private-key-der-file=$SECRETS_DIR/mc_cs_private.der",
          "--measurement-ref-id=9999",
          "--data-provider=dataProviders/1",
          "--event-group=dataProviders/1/eventGroups/1",
          "--event-filter=abcd",
          "--event-start-time=$TIME_STRING_1",
          "--event-end-time=$TIME_STRING_2",
          "--event-group=dataProviders/1/eventGroups/2",
          "--event-start-time=$TIME_STRING_3",
          "--event-end-time=$TIME_STRING_4",
          "--data-provider=dataProviders/2",
          "--event-group=dataProviders/2/eventGroups/1",
          "--event-filter=ijk",
          "--event-start-time=$TIME_STRING_5",
          "--event-end-time=$TIME_STRING_6",
        )

    val output = callCli(args)

    assertThat(output).matches(("Measurement Name: [-_a-zA-Z0-9./]+\\s*"))

    // verify api key
    assertThat(
        headerInterceptor.capturedHeaders
          .single()
          .get(ApiKeyConstants.API_AUTHENTICATION_KEY_METADATA_KEY)
      )
      .isEqualTo(API_KEY)

    val request =
      captureFirst<CreateMeasurementRequest> {
        runBlocking { verify(measurementsServiceMock).createMeasurement(capture()) }
      }
    val measurement = request.measurement
    // measurementSpec matches
    val measurementSpec = MeasurementSpec.parseFrom(measurement.measurementSpec.data)
    assertThat(
        verifyMeasurementSpec(
          measurement.measurementSpec.signature,
          measurementSpec,
          MEASUREMENT_CONSUMER_CERTIFICATE
        )
      )
      .isTrue()
    val nonceHashes = measurement.dataProvidersList.map { it.value.nonceHash }
    assertThat(measurementSpec)
      .comparingExpectedFieldsOnly()
      .isEqualTo(
        org.wfanet.measurement.api.v2alpha.measurementSpec {
          measurementPublicKey = MEASUREMENT_PUBLIC_KEY
          this.nonceHashes += nonceHashes
        }
      )
    assertThat(measurement.measurementReferenceId).isEqualTo("9999")
    // dataProvider1 matches
    val dataProviderEntry1 = measurement.dataProvidersList[0]
    assertThat(dataProviderEntry1.key).isEqualTo("dataProviders/1")
    val signedRequisitionSpec1 =
      decryptRequisitionSpec(
        dataProviderEntry1.value.encryptedRequisitionSpec,
        DATA_PROVIDER_PRIVATE_KEY_HANDLE
      )
    val requisitionSpec1 = RequisitionSpec.parseFrom(signedRequisitionSpec1.data)
    assertThat(
        verifyRequisitionSpec(
          signedRequisitionSpec1.signature,
          requisitionSpec1,
          measurementSpec,
          MEASUREMENT_CONSUMER_CERTIFICATE
        )
      )
      .isTrue()
    assertThat(requisitionSpec1)
      .comparingExpectedFieldsOnly()
      .isEqualTo(
        requisitionSpec {
          measurementPublicKey = MEASUREMENT_PUBLIC_KEY
          eventGroups +=
            RequisitionSpecKt.eventGroupEntry {
              key = "dataProviders/1/eventGroups/1"
              value =
                RequisitionSpecKt.EventGroupEntryKt.value {
                  collectionInterval = timeInterval {
                    startTime = Instant.parse(TIME_STRING_1).toProtoTime()
                    endTime = Instant.parse(TIME_STRING_2).toProtoTime()
                  }
                  filter = RequisitionSpecKt.eventFilter { expression = "abcd" }
                }
            }
          eventGroups +=
            RequisitionSpecKt.eventGroupEntry {
              key = "dataProviders/1/eventGroups/2"
              value =
                RequisitionSpecKt.EventGroupEntryKt.value {
                  collectionInterval = timeInterval {
                    startTime = Instant.parse(TIME_STRING_3).toProtoTime()
                    endTime = Instant.parse(TIME_STRING_4).toProtoTime()
                  }
                }
            }
        }
      )
    // dataProvider2 matches
    val dataProviderEntry2 = measurement.dataProvidersList[1]
    assertThat(dataProviderEntry2.key).isEqualTo("dataProviders/2")
    val signedRequisitionSpec2 =
      decryptRequisitionSpec(
        dataProviderEntry2.value.encryptedRequisitionSpec,
        DATA_PROVIDER_PRIVATE_KEY_HANDLE
      )
    val requisitionSpec2 = RequisitionSpec.parseFrom(signedRequisitionSpec2.data)
    assertThat(
        verifyRequisitionSpec(
          signedRequisitionSpec2.signature,
          requisitionSpec2,
          measurementSpec,
          MEASUREMENT_CONSUMER_CERTIFICATE
        )
      )
      .isTrue()
    assertThat(requisitionSpec2)
      .comparingExpectedFieldsOnly()
      .isEqualTo(
        requisitionSpec {
          measurementPublicKey = MEASUREMENT_PUBLIC_KEY
          eventGroups +=
            RequisitionSpecKt.eventGroupEntry {
              key = "dataProviders/2/eventGroups/1"
              value =
                RequisitionSpecKt.EventGroupEntryKt.value {
                  collectionInterval = timeInterval {
                    startTime = Instant.parse(TIME_STRING_5).toProtoTime()
                    endTime = Instant.parse(TIME_STRING_6).toProtoTime()
                  }
                  filter = RequisitionSpecKt.eventFilter { expression = "ijk" }
                }
            }
        }
      )
  }

  @Test
  fun `Create command call public api with correct REACH_AND_FREQUENCY params`() {
    val args =
      commonArgs +
        arrayOf(
          "measurements",
          "--api-key=$API_KEY",
          "create",
          "--measurement-consumer=measurementConsumers/777",
          "--reach-and-frequency",
          "--reach-privacy-epsilon=0.015",
          "--reach-privacy-delta=0.0",
          "--frequency-privacy-epsilon=0.02",
          "--frequency-privacy-delta=0.0",
          "--vid-sampling-start=0.1",
          "--vid-sampling-width=0.2",
          "--private-key-der-file=$SECRETS_DIR/mc_cs_private.der",
          "--data-provider=dataProviders/1",
          "--event-group=dataProviders/1/eventGroups/1",
          "--event-filter=abcd",
          "--event-start-time=$TIME_STRING_1",
          "--event-end-time=$TIME_STRING_2",
        )
    callCli(args)

    val request =
      captureFirst<CreateMeasurementRequest> {
        runBlocking { verify(measurementsServiceMock).createMeasurement(capture()) }
      }

    val measurement = request.measurement
    val measurementSpec = MeasurementSpec.parseFrom(measurement.measurementSpec.data)
    assertThat(measurementSpec)
      .comparingExpectedFieldsOnly()
      .isEqualTo(
        org.wfanet.measurement.api.v2alpha.measurementSpec {
          reachAndFrequency =
            MeasurementSpecKt.reachAndFrequency {
              reachPrivacyParams = differentialPrivacyParams {
                epsilon = 0.015
                delta = 0.0
              }
              frequencyPrivacyParams = differentialPrivacyParams {
                epsilon = 0.02
                delta = 0.0
              }
            }
          vidSamplingInterval =
            MeasurementSpecKt.vidSamplingInterval {
              start = 0.1f
              width = 0.2f
            }
        }
      )
  }

  @Test
  fun `Create command call public api with correct IMPRESSION params`() {
    val args =
      commonArgs +
        arrayOf(
          "measurements",
          "--api-key=$API_KEY",
          "create",
          "--measurement-consumer=measurementConsumers/777",
          "--impression",
          "--impression-privacy-epsilon=0.015",
          "--impression-privacy-delta=0.0",
          "--max-frequency=1000",
          "--vid-sampling-start=0.1",
          "--vid-sampling-width=0.2",
          "--private-key-der-file=$SECRETS_DIR/mc_cs_private.der",
          "--data-provider=dataProviders/1",
          "--event-group=dataProviders/1/eventGroups/1",
          "--event-filter=abcd",
          "--event-start-time=$TIME_STRING_1",
          "--event-end-time=$TIME_STRING_2"
        )
    callCli(args)

    val request =
      captureFirst<CreateMeasurementRequest> {
        runBlocking { verify(measurementsServiceMock).createMeasurement(capture()) }
      }

    val measurement = request.measurement
    val measurementSpec = MeasurementSpec.parseFrom(measurement.measurementSpec.data)
    assertThat(measurementSpec)
      .comparingExpectedFieldsOnly()
      .isEqualTo(
        org.wfanet.measurement.api.v2alpha.measurementSpec {
          impression =
            MeasurementSpecKt.impression {
              privacyParams = differentialPrivacyParams {
                epsilon = 0.015
                delta = 0.0
              }
              maximumFrequencyPerUser = 1000
            }
          vidSamplingInterval =
            MeasurementSpecKt.vidSamplingInterval {
              start = 0.1f
              width = 0.2f
            }
        }
      )
  }

  @Test
  fun `Create command call public api with correct DURATION params`() {
    val args =
      commonArgs +
        arrayOf(
          "measurements",
          "--api-key=$API_KEY",
          "create",
          "--measurement-consumer=measurementConsumers/777",
          "--duration",
          "--duration-privacy-epsilon=0.015",
          "--duration-privacy-delta=0.0",
          "--max-duration=1000",
          "--vid-sampling-start=0.1",
          "--vid-sampling-width=0.2",
          "--private-key-der-file=$SECRETS_DIR/mc_cs_private.der",
          "--data-provider=dataProviders/1",
          "--event-group=dataProviders/1/eventGroups/1",
          "--event-filter=abcd",
          "--event-start-time=$TIME_STRING_1",
          "--event-end-time=$TIME_STRING_2",
        )
    callCli(args)

    val request =
      captureFirst<CreateMeasurementRequest> {
        runBlocking { verify(measurementsServiceMock).createMeasurement(capture()) }
      }

    val measurement = request.measurement
    val measurementSpec = MeasurementSpec.parseFrom(measurement.measurementSpec.data)
    assertThat(measurementSpec)
      .comparingExpectedFieldsOnly()
      .isEqualTo(
        org.wfanet.measurement.api.v2alpha.measurementSpec {
          duration =
            MeasurementSpecKt.duration {
              privacyParams = differentialPrivacyParams {
                epsilon = 0.015
                delta = 0.0
              }
              maximumWatchDurationPerUser = 1000
            }
          vidSamplingInterval =
            MeasurementSpecKt.vidSamplingInterval {
              start = 0.1f
              width = 0.2f
            }
        }
      )
  }

  @Test
  fun `List command call public api with correct request`() {
    val args =
      commonArgs +
        arrayOf(
          "measurements",
          "--api-key=$API_KEY",
          "list",
          "--measurement-consumer=$MEASUREMENT_CONSUMER_NAME"
        )
    callCli(args)

    // verify api key
    assertThat(
        headerInterceptor.capturedHeaders
          .single()
          .get(ApiKeyConstants.API_AUTHENTICATION_KEY_METADATA_KEY)
      )
      .isEqualTo(API_KEY)

    val request =
      captureFirst<ListMeasurementsRequest> {
        runBlocking { verify(measurementsServiceMock).listMeasurements(capture()) }
      }
    assertThat(request)
      .comparingExpectedFieldsOnly()
      .isEqualTo(listMeasurementsRequest { parent = MEASUREMENT_CONSUMER_NAME })
  }

  @Test
  fun `Get command call public api with correct request`() {
    val args =
      commonArgs +
        arrayOf(
          "measurements",
          "--api-key=$API_KEY",
          "get",
          "--encryption-private-key-file=$SECRETS_DIR/mc_enc_private.tink",
          MEASUREMENT_NAME,
        )

    callCli(args)

    // verify api key
    assertThat(
        headerInterceptor.capturedHeaders
          .single()
          .get(ApiKeyConstants.API_AUTHENTICATION_KEY_METADATA_KEY)
      )
      .isEqualTo(API_KEY)

    val request =
      captureFirst<GetMeasurementRequest> {
        runBlocking { verify(measurementsServiceMock).getMeasurement(capture()) }
      }
    assertThat(request)
      .comparingExpectedFieldsOnly()
      .isEqualTo(getMeasurementRequest { name = MEASUREMENT_NAME })
  }
}

private fun getEncryptedResult(
  result: Measurement.Result,
  publicKey: EncryptionPublicKey
): ByteString {
  val signedResult = signResult(result, AGGREGATOR_SIGNING_KEY)
  return encryptResult(signedResult, publicKey)
}

private inline fun capturingSystemOut(block: () -> Unit): String {
  val originalOut = System.out
  val outputStream = ByteArrayOutputStream()

  System.setOut(PrintStream(outputStream, true))
  try {
    block()
  } finally {
    System.setOut(originalOut)
  }

  return outputStream.toString()
}

private inline fun assertExitsWith(status: Int, block: () -> Unit) {
  val exception: ExitException = assertFailsWith {
    val originalSecurityManager: SecurityManager? = System.getSecurityManager()
    System.setSecurityManager(
      object : SecurityManager() {
        override fun checkPermission(perm: Permission?) {
          // Allow everything.
        }

        override fun checkExit(status: Int) {
          super.checkExit(status)
          throw ExitException(status)
        }
      }
    )

    try {
      block()
    } finally {
      System.setSecurityManager(originalSecurityManager)
    }
  }
  assertThat(exception.status).isEqualTo(status)
}

private class ExitException(val status: Int) : RuntimeException()

private class HeaderCapturingInterceptor : ServerInterceptor {
  override fun <ReqT, RespT> interceptCall(
    call: ServerCall<ReqT, RespT>,
    headers: Metadata,
    next: ServerCallHandler<ReqT, RespT>,
  ): ServerCall.Listener<ReqT> {
    _capturedHeaders.add(headers)
    return next.startCall(call, headers)
  }

  private val _capturedHeaders = mutableListOf<Metadata>()
  val capturedHeaders: List<Metadata>
    get() = _capturedHeaders
}