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

package org.wfanet.measurement.loadtest.resourcesetup

import io.grpc.ManagedChannel
import kotlinx.coroutines.runBlocking
import org.wfanet.measurement.api.v2alpha.CertificatesGrpcKt.CertificatesCoroutineStub
import org.wfanet.measurement.api.v2alpha.DataProvidersGrpcKt.DataProvidersCoroutineStub
import org.wfanet.measurement.api.v2alpha.MeasurementConsumersGrpcKt.MeasurementConsumersCoroutineStub
import org.wfanet.measurement.common.commandLineMain
import org.wfanet.measurement.common.crypto.testing.SigningCertsTesting
import org.wfanet.measurement.common.crypto.testing.loadSigningKey
import org.wfanet.measurement.common.crypto.tink.testing.loadPublicKey
import org.wfanet.measurement.common.grpc.buildMutualTlsChannel
import org.wfanet.measurement.common.readByteString
import org.wfanet.measurement.consent.client.common.toEncryptionPublicKey
import picocli.CommandLine

@CommandLine.Command(
  name = "RunResourceSetupJob",
  mixinStandardHelpOptions = true,
  showDefaultValues = true
)
private fun run(@CommandLine.Mixin flags: ResourceSetupFlags) {
  val clientCerts =
    SigningCertsTesting.fromPemFiles(
      certificateFile = flags.tlsFlags.certFile,
      privateKeyFile = flags.tlsFlags.privateKeyFile,
      trustedCertCollectionFile = flags.tlsFlags.certCollectionFile
    )
  val v2alphaPublicApiChannel: ManagedChannel =
    buildMutualTlsChannel(
      flags.kingdomPublicApiFlags.target,
      clientCerts,
      flags.kingdomPublicApiFlags.certHost
    )
  val dataProvidersStub = DataProvidersCoroutineStub(v2alphaPublicApiChannel)
  val measurementConsumersStub = MeasurementConsumersCoroutineStub(v2alphaPublicApiChannel)
  val certificatesStub = CertificatesCoroutineStub(v2alphaPublicApiChannel)

  // Makes sure the three maps contain the same set of EDPs.
  require(
    flags.edpCsCertDerFiles.keys == flags.edpCsKeyDerFiles.keys &&
      flags.edpCsCertDerFiles.keys == flags.edpEncryptionPublicKeysets.keys
  )
  val dataProviderContents =
    flags.edpCsCertDerFiles.map {
      EntityContent(
        displayName = it.key,
        signingKey = loadSigningKey(it.value, flags.edpCsKeyDerFiles.getValue(it.key)),
        encryptionPublicKey =
          loadPublicKey(flags.edpEncryptionPublicKeysets.getValue(it.key)).toEncryptionPublicKey()
      )
    }
  val measurementConsumerContent =
    EntityContent(
      displayName = "mc_001",
      signingKey = loadSigningKey(flags.mcCsCertDerFile, flags.mcCsKeyDerFile),
      encryptionPublicKey =
        loadPublicKey(flags.mcEncryptionPublicKeyDerFile).toEncryptionPublicKey()
    )
  val duchyCerts =
    flags.duchyCsCertDerFiles.map {
      DuchyCert(duchyId = it.key, consentSignalCertificateDer = it.value.readByteString())
    }

  runBlocking {
    // Runs the resource setup job.
    ResourceSetup(dataProvidersStub, certificatesStub, measurementConsumersStub, flags.runId)
      .process(dataProviderContents, measurementConsumerContent, duchyCerts, "MTIzNDU2NzM", "token")
  }
}

fun main(args: Array<String>) = commandLineMain(::run, args)
