// Copyright 2020 The Cross-Media Measurement Authors
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

import io.grpc.Status
import org.wfanet.measurement.api.v2alpha.Certificate
import org.wfanet.measurement.api.v2alpha.Certificate.RevocationState
import org.wfanet.measurement.api.v2alpha.CertificateParentKey
import org.wfanet.measurement.api.v2alpha.CertificatesGrpcKt.CertificatesCoroutineImplBase
import org.wfanet.measurement.api.v2alpha.CreateCertificateRequest
import org.wfanet.measurement.api.v2alpha.DataProviderCertificateKey
import org.wfanet.measurement.api.v2alpha.DataProviderKey
import org.wfanet.measurement.api.v2alpha.DuchyCertificateKey
import org.wfanet.measurement.api.v2alpha.DuchyKey
import org.wfanet.measurement.api.v2alpha.GetCertificateRequest
import org.wfanet.measurement.api.v2alpha.MeasurementConsumerCertificateKey
import org.wfanet.measurement.api.v2alpha.MeasurementConsumerKey
import org.wfanet.measurement.api.v2alpha.ModelProviderCertificateKey
import org.wfanet.measurement.api.v2alpha.ModelProviderKey
import org.wfanet.measurement.api.v2alpha.ReleaseCertificateHoldRequest
import org.wfanet.measurement.api.v2alpha.ResourceKey
import org.wfanet.measurement.api.v2alpha.RevokeCertificateRequest
import org.wfanet.measurement.api.v2alpha.certificate
import org.wfanet.measurement.api.v2alpha.makeDataProviderCertificateName
import org.wfanet.measurement.api.v2alpha.makeDuchyCertificateName
import org.wfanet.measurement.api.v2alpha.makeMeasurementConsumerCertificateName
import org.wfanet.measurement.api.v2alpha.makeModelProviderCertificateName
import org.wfanet.measurement.api.v2alpha.principalFromCurrentContext
import org.wfanet.measurement.common.grpc.failGrpc
import org.wfanet.measurement.common.grpc.grpcRequire
import org.wfanet.measurement.common.grpc.grpcRequireNotNull
import org.wfanet.measurement.common.identity.apiIdToExternalId
import org.wfanet.measurement.common.identity.externalIdToApiId
import org.wfanet.measurement.internal.kingdom.Certificate as InternalCertificate
import org.wfanet.measurement.internal.kingdom.Certificate.RevocationState as InternalRevocationState
import org.wfanet.measurement.internal.kingdom.CertificatesGrpcKt.CertificatesCoroutineStub
import org.wfanet.measurement.internal.kingdom.certificate as internalCertificate
import org.wfanet.measurement.internal.kingdom.getCertificateRequest
import org.wfanet.measurement.internal.kingdom.releaseCertificateHoldRequest
import org.wfanet.measurement.internal.kingdom.revokeCertificateRequest

class CertificatesService(private val internalCertificatesStub: CertificatesCoroutineStub) :
  CertificatesCoroutineImplBase() {

  override suspend fun getCertificate(request: GetCertificateRequest): Certificate {
    val key =
      grpcRequireNotNull(createResourceKey(request.name)) { "Resource name unspecified or invalid" }

    val principal = principalFromCurrentContext

    val internalGetCertificateRequest = getCertificateRequest {
      externalCertificateId = apiIdToExternalId(key.certificateId)
      when (key) {
        is DataProviderCertificateKey -> {
          externalDataProviderId = apiIdToExternalId(key.dataProviderId)

          when (val resourceKey = principal.resourceKey) {
            is DataProviderKey -> {
              if (apiIdToExternalId(resourceKey.dataProviderId) != externalDataProviderId) {
                failGrpc(Status.PERMISSION_DENIED) {
                  "Cannot get another DataProvider's Certificate"
                }
              }
            }
            is MeasurementConsumerKey -> {}
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to get a DataProvider's Certificate"
              }
            }
          }
        }
        is DuchyCertificateKey -> externalDuchyId = key.duchyId
        is MeasurementConsumerCertificateKey -> {
          externalMeasurementConsumerId = apiIdToExternalId(key.measurementConsumerId)

          when (val resourceKey = principal.resourceKey) {
            is DataProviderKey -> {}
            is MeasurementConsumerKey -> {
              if (apiIdToExternalId(resourceKey.measurementConsumerId) !=
                  externalMeasurementConsumerId
              ) {
                failGrpc(Status.PERMISSION_DENIED) {
                  "Cannot get another MeasurementConsumer's Certificate"
                }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to get a MeasurementConsumer's Certificate"
              }
            }
          }
        }
        is ModelProviderCertificateKey ->
          externalModelProviderId = apiIdToExternalId(key.modelProviderId)
        else -> failGrpc(Status.INTERNAL) { "Unsupported parent: ${key.toName()}" }
      }
    }

    return internalCertificatesStub.getCertificate(internalGetCertificateRequest).toCertificate()
  }

  override suspend fun createCertificate(request: CreateCertificateRequest): Certificate {
    val dataProviderKey = DataProviderKey.fromName(request.parent)
    val duchyKey = DuchyKey.fromName(request.parent)
    val measurementConsumerKey = MeasurementConsumerKey.fromName(request.parent)
    val modelProviderKey = ModelProviderKey.fromName(request.parent)

    val principal = principalFromCurrentContext

    val internalCertificate = internalCertificate {
      fillCertificateFromDer(request.certificate.x509Der)
      when {
        dataProviderKey != null -> {
          externalDataProviderId = apiIdToExternalId(dataProviderKey.dataProviderId)

          when (val resourceKey = principal.resourceKey) {
            is DataProviderKey -> {
              if (apiIdToExternalId(resourceKey.dataProviderId) != externalDataProviderId) {
                failGrpc(Status.PERMISSION_DENIED) {
                  "Cannot create another DataProvider's Certificate"
                }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to create a DataProvider's Certificate"
              }
            }
          }
        }
        duchyKey != null -> {
          externalDuchyId = duchyKey.duchyId

          when (val resourceKey = principal.resourceKey) {
            is DuchyKey -> {
              if (resourceKey.duchyId != externalDuchyId) {
                failGrpc(Status.PERMISSION_DENIED) { "Cannot create another Duchy's Certificate" }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to create a Duchy's Certificate"
              }
            }
          }
        }
        measurementConsumerKey != null -> {
          externalMeasurementConsumerId =
            apiIdToExternalId(measurementConsumerKey.measurementConsumerId)

          when (val resourceKey = principal.resourceKey) {
            is MeasurementConsumerKey -> {
              if (apiIdToExternalId(resourceKey.measurementConsumerId) !=
                  externalMeasurementConsumerId
              ) {
                failGrpc(Status.PERMISSION_DENIED) {
                  "Cannot create another MeasurementConsumer's Certificate"
                }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to create a MeasurementConsumer's Certificate"
              }
            }
          }
        }
        modelProviderKey != null -> {
          externalModelProviderId = apiIdToExternalId(modelProviderKey.modelProviderId)

          when (val resourceKey = principal.resourceKey) {
            is ModelProviderKey -> {
              if (apiIdToExternalId(resourceKey.modelProviderId) != externalModelProviderId) {
                failGrpc(Status.PERMISSION_DENIED) {
                  "Cannot create another ModelProvider's Certificate"
                }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to create a ModelProvider's Certificate"
              }
            }
          }
        }
        else -> failGrpc(Status.INVALID_ARGUMENT) { "Parent unspecified or invalid" }
      }
    }

    return internalCertificatesStub.createCertificate(internalCertificate).toCertificate()
  }

  override suspend fun revokeCertificate(request: RevokeCertificateRequest): Certificate {
    val principal = principalFromCurrentContext

    val key =
      grpcRequireNotNull(createResourceKey(request.name)) { "Resource name unspecified or invalid" }

    grpcRequire(request.revocationState != RevocationState.REVOCATION_STATE_UNSPECIFIED) {
      "Revocation State unspecified"
    }

    val internalRevokeCertificateRequest = revokeCertificateRequest {
      when (key) {
        is DataProviderCertificateKey -> {
          externalDataProviderId = apiIdToExternalId(key.dataProviderId)
          externalCertificateId = apiIdToExternalId(key.certificateId)

          when (val resourceKey = principal.resourceKey) {
            is DataProviderKey -> {
              if (apiIdToExternalId(resourceKey.dataProviderId) != externalDataProviderId) {
                failGrpc(Status.PERMISSION_DENIED) {
                  "Cannot revoke another DataProvider's Certificate"
                }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to revoke a DataProvider's Certificate"
              }
            }
          }
        }
        is DuchyCertificateKey -> {
          externalDuchyId = key.duchyId
          externalCertificateId = apiIdToExternalId(key.certificateId)

          when (val resourceKey = principal.resourceKey) {
            is DuchyKey -> {
              if (resourceKey.duchyId != externalDuchyId) {
                failGrpc(Status.PERMISSION_DENIED) { "Cannot revoke another Duchy's Certificate" }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to revoke a Duchy's Certificate"
              }
            }
          }
        }
        is MeasurementConsumerCertificateKey -> {
          externalMeasurementConsumerId = apiIdToExternalId(key.measurementConsumerId)
          externalCertificateId = apiIdToExternalId(key.certificateId)

          when (val resourceKey = principal.resourceKey) {
            is MeasurementConsumerKey -> {
              if (apiIdToExternalId(resourceKey.measurementConsumerId) !=
                  externalMeasurementConsumerId
              ) {
                failGrpc(Status.PERMISSION_DENIED) {
                  "Cannot revoke another MeasurementConsumer's Certificate"
                }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to revoke a MeasurementConsumer's Certificate"
              }
            }
          }
        }
        is ModelProviderCertificateKey -> {
          externalModelProviderId = apiIdToExternalId(key.modelProviderId)
          externalCertificateId = apiIdToExternalId(key.certificateId)

          when (val resourceKey = principal.resourceKey) {
            is ModelProviderKey -> {
              if (apiIdToExternalId(resourceKey.modelProviderId) != externalModelProviderId) {
                failGrpc(Status.PERMISSION_DENIED) {
                  "Cannot revoke another ModelProvider's Certificate"
                }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to revoke a ModelProvider's Certificate"
              }
            }
          }
        }
        else -> failGrpc(Status.INVALID_ARGUMENT) { "Parent unspecified or invalid" }
      }
      revocationState = request.revocationState.toInternal()
    }

    return internalCertificatesStub
      .revokeCertificate(internalRevokeCertificateRequest)
      .toCertificate()
  }

  override suspend fun releaseCertificateHold(request: ReleaseCertificateHoldRequest): Certificate {
    val principal = principalFromCurrentContext

    val key =
      grpcRequireNotNull(createResourceKey(request.name)) { "Resource name unspecified or invalid" }

    val internalReleaseCertificateHoldRequest = releaseCertificateHoldRequest {
      externalCertificateId = apiIdToExternalId(key.certificateId)
      when (key) {
        is DataProviderCertificateKey -> {
          externalDataProviderId = apiIdToExternalId(key.dataProviderId)

          when (val resourceKey = principal.resourceKey) {
            is DataProviderKey -> {
              if (apiIdToExternalId(resourceKey.dataProviderId) != externalDataProviderId) {
                failGrpc(Status.PERMISSION_DENIED) {
                  "Cannot release another DataProvider's Certificate"
                }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to release a DataProvider's Certificate"
              }
            }
          }
        }
        is DuchyCertificateKey -> {
          externalDuchyId = key.duchyId

          when (val resourceKey = principal.resourceKey) {
            is DuchyKey -> {
              if (resourceKey.duchyId != externalDuchyId) {
                failGrpc(Status.PERMISSION_DENIED) { "Cannot release another Duchy's Certificate" }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to release a Duchy's Certificate"
              }
            }
          }
        }
        is MeasurementConsumerCertificateKey -> {
          externalMeasurementConsumerId = apiIdToExternalId(key.measurementConsumerId)

          when (val resourceKey = principal.resourceKey) {
            is MeasurementConsumerKey -> {
              if (apiIdToExternalId(resourceKey.measurementConsumerId) !=
                  externalMeasurementConsumerId
              ) {
                failGrpc(Status.PERMISSION_DENIED) {
                  "Cannot release another MeasurementConsumer's Certificate"
                }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to release a MeasurementConsumer's Certificate"
              }
            }
          }
        }
        is ModelProviderCertificateKey -> {
          externalModelProviderId = apiIdToExternalId(key.modelProviderId)

          when (val resourceKey = principal.resourceKey) {
            is ModelProviderKey -> {
              if (apiIdToExternalId(resourceKey.modelProviderId) != externalModelProviderId) {
                failGrpc(Status.PERMISSION_DENIED) {
                  "Cannot release another ModelProvider's Certificate"
                }
              }
            }
            else -> {
              failGrpc(Status.PERMISSION_DENIED) {
                "Caller does not have permission to release a ModelProvider's Certificate"
              }
            }
          }
        }
      }
    }

    return internalCertificatesStub
      .releaseCertificateHold(internalReleaseCertificateHoldRequest)
      .toCertificate()
  }
}

/** Converts an internal [InternalCertificate] to a public [Certificate]. */
private fun InternalCertificate.toCertificate(): Certificate {
  val certificateApiId = externalIdToApiId(externalCertificateId)

  val name =
    @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")
    when (parentCase) {
      InternalCertificate.ParentCase.EXTERNAL_MEASUREMENT_CONSUMER_ID ->
        makeMeasurementConsumerCertificateName(
          externalIdToApiId(externalMeasurementConsumerId),
          certificateApiId
        )
      InternalCertificate.ParentCase.EXTERNAL_DATA_PROVIDER_ID ->
        makeDataProviderCertificateName(externalIdToApiId(externalDataProviderId), certificateApiId)
      InternalCertificate.ParentCase.EXTERNAL_DUCHY_ID ->
        makeDuchyCertificateName(externalDuchyId, certificateApiId)
      InternalCertificate.ParentCase.EXTERNAL_MODEL_PROVIDER_ID ->
        makeModelProviderCertificateName(
          externalIdToApiId(externalModelProviderId),
          certificateApiId
        )
      InternalCertificate.ParentCase.PARENT_NOT_SET ->
        failGrpc(Status.INTERNAL) { "Parent missing" }
    }

  return certificate {
    this.name = name
    x509Der = this@toCertificate.details.x509Der
    revocationState = this@toCertificate.revocationState.toRevocationState()
  }
}

/** Converts an internal [InternalRevocationState] to a public [RevocationState]. */
private fun InternalRevocationState.toRevocationState(): RevocationState =
  when (this) {
    InternalRevocationState.REVOKED -> RevocationState.REVOKED
    InternalRevocationState.HOLD -> RevocationState.HOLD
    InternalRevocationState.UNRECOGNIZED, InternalRevocationState.REVOCATION_STATE_UNSPECIFIED ->
      RevocationState.REVOCATION_STATE_UNSPECIFIED
  }

/** Converts a public [RevocationState] to an internal [InternalRevocationState]. */
private fun RevocationState.toInternal(): InternalRevocationState =
  when (this) {
    RevocationState.REVOKED -> InternalRevocationState.REVOKED
    RevocationState.HOLD -> InternalRevocationState.HOLD
    RevocationState.UNRECOGNIZED, RevocationState.REVOCATION_STATE_UNSPECIFIED ->
      InternalRevocationState.REVOCATION_STATE_UNSPECIFIED
  }

private val CERTIFICATE_PARENT_KEY_PARSERS: List<(String) -> CertificateParentKey?> =
  listOf(
    DataProviderCertificateKey::fromName,
    DuchyCertificateKey::fromName,
    MeasurementConsumerCertificateKey::fromName,
    ModelProviderCertificateKey::fromName
  )

/** Checks the resource name against multiple certificate [ResourceKey]s to find the right one. */
private fun createResourceKey(name: String): CertificateParentKey? {
  for (parse in CERTIFICATE_PARENT_KEY_PARSERS) {
    return parse(name) ?: continue
  }
  return null
}