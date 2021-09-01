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

package org.wfanet.measurement.loadtest.frontend

import com.google.common.truth.extensions.proto.ProtoTruth.assertThat
import com.google.protobuf.ByteString
import java.nio.file.Paths
import java.time.Duration
import java.util.logging.Logger
import kotlin.random.Random
import kotlinx.coroutines.delay
import org.wfanet.anysketch.AnySketch
import org.wfanet.anysketch.Sketch
import org.wfanet.anysketch.SketchProtos
import org.wfanet.estimation.Estimators
import org.wfanet.estimation.ValueHistogram
import org.wfanet.measurement.api.v2alpha.DataProvider
import org.wfanet.measurement.api.v2alpha.DataProviderKey
import org.wfanet.measurement.api.v2alpha.DataProvidersGrpcKt.DataProvidersCoroutineStub
import org.wfanet.measurement.api.v2alpha.DifferentialPrivacyParams
import org.wfanet.measurement.api.v2alpha.EncryptionPublicKey
import org.wfanet.measurement.api.v2alpha.EventGroup
import org.wfanet.measurement.api.v2alpha.EventGroupKey
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpcKt.EventGroupsCoroutineStub
import org.wfanet.measurement.api.v2alpha.GetDataProviderRequest
import org.wfanet.measurement.api.v2alpha.HybridCipherSuite
import org.wfanet.measurement.api.v2alpha.HybridCipherSuite.DataEncapsulationMechanism
import org.wfanet.measurement.api.v2alpha.HybridCipherSuite.KeyEncapsulationMechanism
import org.wfanet.measurement.api.v2alpha.ListEventGroupsRequestKt
import org.wfanet.measurement.api.v2alpha.ListRequisitionsRequestKt
import org.wfanet.measurement.api.v2alpha.Measurement
import org.wfanet.measurement.api.v2alpha.Measurement.DataProviderEntry
import org.wfanet.measurement.api.v2alpha.Measurement.Result
import org.wfanet.measurement.api.v2alpha.MeasurementConsumer
import org.wfanet.measurement.api.v2alpha.MeasurementConsumersGrpcKt.MeasurementConsumersCoroutineStub
import org.wfanet.measurement.api.v2alpha.MeasurementKt
import org.wfanet.measurement.api.v2alpha.MeasurementKt.ResultKt.frequency
import org.wfanet.measurement.api.v2alpha.MeasurementKt.ResultKt.reach
import org.wfanet.measurement.api.v2alpha.MeasurementKt.dataProviderEntry
import org.wfanet.measurement.api.v2alpha.MeasurementKt.result
import org.wfanet.measurement.api.v2alpha.MeasurementSpec
import org.wfanet.measurement.api.v2alpha.MeasurementSpecKt.reachAndFrequency
import org.wfanet.measurement.api.v2alpha.MeasurementsGrpcKt.MeasurementsCoroutineStub
import org.wfanet.measurement.api.v2alpha.Requisition
import org.wfanet.measurement.api.v2alpha.RequisitionSpecKt.eventGroupEntry
import org.wfanet.measurement.api.v2alpha.RequisitionsGrpcKt.RequisitionsCoroutineStub
import org.wfanet.measurement.api.v2alpha.SignedData
import org.wfanet.measurement.api.v2alpha.createMeasurementRequest
import org.wfanet.measurement.api.v2alpha.dataProviderList
import org.wfanet.measurement.api.v2alpha.eventGroup
import org.wfanet.measurement.api.v2alpha.getMeasurementConsumerRequest
import org.wfanet.measurement.api.v2alpha.getMeasurementRequest
import org.wfanet.measurement.api.v2alpha.hybridCipherSuite
import org.wfanet.measurement.api.v2alpha.listEventGroupsRequest
import org.wfanet.measurement.api.v2alpha.listRequisitionsRequest
import org.wfanet.measurement.api.v2alpha.measurement
import org.wfanet.measurement.api.v2alpha.measurementSpec
import org.wfanet.measurement.api.v2alpha.requisitionSpec
import org.wfanet.measurement.common.crypto.readCertificate
import org.wfanet.measurement.common.flatten
import org.wfanet.measurement.common.loadLibrary
import org.wfanet.measurement.common.toByteString
import org.wfanet.measurement.consent.client.measurementconsumer.createDataProviderListHash
import org.wfanet.measurement.consent.client.measurementconsumer.decryptResult
import org.wfanet.measurement.consent.client.measurementconsumer.encryptRequisitionSpec
import org.wfanet.measurement.consent.client.measurementconsumer.signMeasurementSpec
import org.wfanet.measurement.consent.client.measurementconsumer.signRequisitionSpec
import org.wfanet.measurement.consent.client.measurementconsumer.verifyResult
import org.wfanet.measurement.consent.crypto.hybridencryption.HybridCryptor
import org.wfanet.measurement.consent.crypto.hybridencryption.testing.ReversingHybridCryptor
import org.wfanet.measurement.consent.crypto.keystore.KeyStore
import org.wfanet.measurement.consent.crypto.keystore.PrivateKeyHandle
import org.wfanet.measurement.loadtest.storage.SketchStore

// TODO: get these from the protocolConfig.
private const val MAXIMUM_FREQUENCY = 15L
private const val DECAY_RATE = 12.0
private const val INDEX_SIZE = 100000L

private const val DEFAULT_BUFFER_SIZE_BYTES = 1024 * 32 // 32 KiB
private const val DATA_PROVIDER_WILDCARD = "dataProviders/-"
private val CIPHER_SUITE = hybridCipherSuite {
  kem = KeyEncapsulationMechanism.ECDH_P256_HKDF_HMAC_SHA256
  dem = DataEncapsulationMechanism.AES_128_GCM
}

data class MeasurementConsumerData(
  // The MC's public API resource name
  val name: String,
  // The id of the MC's consent signaling private key in keyStore
  val consentSignalingPrivateKeyId: String,
  // The id of the MC's encryption private key in keyStore
  val encryptionPrivateKeyId: String
)

/** A simulator performing frontend operations. */
class FrontendSimulator(
  private val measurementConsumerData: MeasurementConsumerData,
  private val outputDpParams: DifferentialPrivacyParams,
  private val keyStore: KeyStore,
  private val dataProvidersClient: DataProvidersCoroutineStub,
  private val eventGroupsClient: EventGroupsCoroutineStub,
  private val measurementsClient: MeasurementsCoroutineStub,
  private val requisitionsClient: RequisitionsCoroutineStub,
  private val measurementConsumersClient: MeasurementConsumersCoroutineStub,
  private val storageClient: SketchStore,
  private val runId: String
) {

  /** A sequence of operations done in the simulator. */
  suspend fun process() {
    // Create a new measurement on behalf of the measurement consumer.
    val measurementConsumer = getMeasurementConsumer(measurementConsumerData.name)
    val createdMeasurement = createMeasurement(measurementConsumer)

    // Get the CMMS computed result and compare it with the expected result.
    var mpcResult = getResult(createdMeasurement.name)
    while (mpcResult == null) {
      logger.info("Computation not done yet, wait for another 30 seconds.")
      delay(Duration.ofSeconds(30).toMillis())
      mpcResult = getResult(createdMeasurement.name)
    }
    logger.info("Got computed result from Kingdom: $mpcResult")

    val expectedResult = getExpectedResult(createdMeasurement.name)
    logger.info("Expected result: $expectedResult")

    assertThat(mpcResult).isEqualTo(expectedResult)
    logger.info("Computed result is equal to the expected result. Correctness Test passes.")
  }

  /** Creates a Measurement on behave of the [MeasurementConsumer]. */
  private suspend fun createMeasurement(measurementConsumer: MeasurementConsumer): Measurement {
    val eventGroups = listEventGroups(measurementConsumer.name)
    val serializedDataProviderList =
      dataProviderList { dataProvider += eventGroups.map { extractDataProviderName(it.name) } }
        .toByteString()
    val dataProviderListSalt = Random.Default.nextBytes(32).toByteString()
    val dataProviderListHash =
      createDataProviderListHash(serializedDataProviderList, dataProviderListSalt)
    val dataProviderEntries =
      eventGroups.map { createDataProviderEntry(it, measurementConsumer, dataProviderListHash) }

    val request = createMeasurementRequest {
      measurement =
        measurement {
          measurementConsumerCertificate = measurementConsumer.certificate
          measurementSpec =
            signMeasurementSpec(
              newMeasurementSpec(measurementConsumer.publicKey.data),
              PrivateKeyHandle(measurementConsumerData.consentSignalingPrivateKeyId, keyStore),
              readCertificate(measurementConsumer.certificateDer)
            )
          this.serializedDataProviderList = serializedDataProviderList
          this.dataProviderListSalt = dataProviderListSalt
          dataProviders += dataProviderEntries
          this.measurementReferenceId = runId
        }
    }
    return measurementsClient.createMeasurement(request)
  }

  /** Gets the result of a [Measurement] if it is succeeded. */
  private suspend fun getResult(measurementName: String): Result? {
    val measurement =
      measurementsClient.getMeasurement(getMeasurementRequest { name = measurementName })
    return if (measurement.state == Measurement.State.SUCCEEDED) {
      val signedResult =
        decryptResult(
          measurement.encryptedResult,
          PrivateKeyHandle(measurementConsumerData.encryptionPrivateKeyId, keyStore),
          CIPHER_SUITE,
          ::fakeGetHybridCryptorForCipherSuite
        )
      val result = Result.parseFrom(signedResult.data)
      val aggregatorCertificate = readCertificate(measurement.aggregatorCertificate)
      if (!verifyResult(signedResult.signature, result, aggregatorCertificate)) {
        error("Aggregator signature of the result is invalid.")
      }
      result
    } else {
      null
    }
  }

  /** Gets the expected result of a [Measurement] using raw sketches. */
  suspend fun getExpectedResult(measurementName: String): Result {
    val requisitions = listRequisitions(measurementName)
    require(requisitions.isNotEmpty()) { "Requisition list is empty." }

    val anySketches =
      requisitions.map {
        val storedSketch =
          storageClient.get(it.name)?.read(DEFAULT_BUFFER_SIZE_BYTES)?.flatten()
            ?: error("Sketch blob not found for ${it.name}.")
        SketchProtos.toAnySketch(Sketch.parseFrom(storedSketch))
      }

    val combinedAnySketch = anySketches[0]
    if (anySketches.size > 1) {
      combinedAnySketch.apply { mergeAll(anySketches.subList(1, anySketches.size)) }
    }

    val expectedReach = estimateCardinality(combinedAnySketch, DECAY_RATE, INDEX_SIZE)
    val expectedFrequency = estimateFrequency(combinedAnySketch, MAXIMUM_FREQUENCY)
    return result {
      reach = reach { value = expectedReach }
      frequency = frequency { relativeFrequencyDistribution.putAll(expectedFrequency) }
    }
  }

  /** Estimates the cardinality of an [AnySketch]. */
  private fun estimateCardinality(anySketch: AnySketch, decayRate: Double, indexSize: Long): Long {
    val activeRegisterCount = anySketch.toList().size.toLong()
    return Estimators.EstimateCardinalityLiquidLegions(decayRate, indexSize, activeRegisterCount)
  }

  /** Estimates the relative frequency histogram of an [AnySketch]. */
  private fun estimateFrequency(anySketch: AnySketch, maximumFrequency: Long): Map<Long, Double> {
    val valueIndex = anySketch.getValueIndex("SamplingIndicator").asInt
    val actualHistogram =
      ValueHistogram.calculateHistogram(anySketch, "Frequency") { it.values[valueIndex] != -1L }
    val result = mutableMapOf<Long, Double>()
    actualHistogram.forEach {
      val key = minOf(it.key, maximumFrequency)
      result[key] = result.getOrDefault(key, 0.0) + it.value
    }
    return result
  }

  private suspend fun getMeasurementConsumer(name: String): MeasurementConsumer {
    val request = getMeasurementConsumerRequest { this.name = name }
    return measurementConsumersClient.getMeasurementConsumer(request)
  }

  private fun newMeasurementSpec(serializedMeasurementPublicKey: ByteString): MeasurementSpec {
    return measurementSpec {
      measurementPublicKey = serializedMeasurementPublicKey
      cipherSuite = CIPHER_SUITE
      reachAndFrequency =
        reachAndFrequency {
          reachPrivacyParams = outputDpParams
          frequencyPrivacyParams = outputDpParams
        }
    }
  }

  private suspend fun listEventGroups(measurementConsumer: String): List<EventGroup> {
    val request = listEventGroupsRequest {
      parent = DATA_PROVIDER_WILDCARD
      filter = ListEventGroupsRequestKt.filter { measurementConsumers += measurementConsumer }
    }
    return eventGroupsClient.listEventGroups(request).eventGroupsList
  }

  private suspend fun listRequisitions(measurement: String): List<Requisition> {
    val request = listRequisitionsRequest {
      parent = DATA_PROVIDER_WILDCARD
      filter = ListRequisitionsRequestKt.filter { this.measurement = measurement }
    }
    return requisitionsClient.listRequisitions(request).requisitionsList
  }

  private fun extractDataProviderName(eventGroupName: String): String {
    val eventGroupKey = EventGroupKey.fromName(eventGroupName) ?: error("Invalid eventGroup name.")
    return DataProviderKey(eventGroupKey.dataProviderId).toName()
  }

  private suspend fun getDataProvider(name: String): DataProvider {
    val request = GetDataProviderRequest.newBuilder().also { it.name = name }.build()
    return dataProvidersClient.getDataProvider(request)
  }

  private suspend fun createDataProviderEntry(
    eventGroup: EventGroup,
    measurementConsumer: MeasurementConsumer,
    dataProviderListHash: ByteString
  ): DataProviderEntry {
    val dataProvider = getDataProvider(extractDataProviderName(eventGroup.name))
    val requisitionSpec = requisitionSpec {
      eventGroups +=
        eventGroupEntry {
          key = eventGroup.name
          // TODO: populate other fields when the EventGroup design is done.
        }
      measurementPublicKey = measurementConsumer.publicKey.data
      this.dataProviderListHash = dataProviderListHash
    }
    val signedRequisitionSpec =
      signRequisitionSpec(
        requisitionSpec,
        PrivateKeyHandle(measurementConsumerData.consentSignalingPrivateKeyId, keyStore),
        readCertificate(measurementConsumer.certificateDer)
      )
    return dataProvider.toDataProviderEntry(signedRequisitionSpec)
  }

  private fun DataProvider.toDataProviderEntry(
    signedRequisitionSpec: SignedData
  ): DataProviderEntry {
    val source = this
    return dataProviderEntry {
      key = source.name
      this.value =
        MeasurementKt.DataProviderEntryKt.value {
          dataProviderCertificate = source.certificate
          dataProviderPublicKey = source.publicKey
          encryptedRequisitionSpec =
            encryptRequisitionSpec(
              signedRequisitionSpec,
              EncryptionPublicKey.parseFrom(source.publicKey.data),
              CIPHER_SUITE,
              ::fakeGetHybridCryptorForCipherSuite // TODO: use the real HybridCryptor.
            )
        }
    }
  }

  // TODO: delete this fake when the EciesCryptor is done.
  private fun fakeGetHybridCryptorForCipherSuite(cipherSuite: HybridCipherSuite): HybridCryptor {
    return ReversingHybridCryptor()
  }

  companion object {
    private val logger: Logger = Logger.getLogger(this::class.java.name)
    init {
      loadLibrary(
        name = "estimators",
        directoryPath =
          Paths.get("any_sketch_java", "src", "main", "java", "org", "wfanet", "estimation")
      )
    }
  }
}
