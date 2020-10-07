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

package org.wfanet.measurement.duchy.mill

import java.nio.file.Paths
import org.wfanet.measurement.common.loadLibrary
import org.wfanet.measurement.crypto.ProtocolEncryptionUtility
import org.wfanet.measurement.internal.duchy.AddNoiseToSketchRequest
import org.wfanet.measurement.internal.duchy.AddNoiseToSketchResponse
import org.wfanet.measurement.internal.duchy.BlindLastLayerIndexThenJoinRegistersRequest
import org.wfanet.measurement.internal.duchy.BlindLastLayerIndexThenJoinRegistersResponse
import org.wfanet.measurement.internal.duchy.BlindOneLayerRegisterIndexRequest
import org.wfanet.measurement.internal.duchy.BlindOneLayerRegisterIndexResponse
import org.wfanet.measurement.internal.duchy.DecryptLastLayerFlagAndCountRequest
import org.wfanet.measurement.internal.duchy.DecryptLastLayerFlagAndCountResponse
import org.wfanet.measurement.internal.duchy.DecryptOneLayerFlagAndCountRequest
import org.wfanet.measurement.internal.duchy.DecryptOneLayerFlagAndCountResponse

/**
 * An implementation of the LiquidLegionsCryptoWorker using the JNI c++ protocol_encryption_utility
 * library.
 */
class LiquidLegionsCryptoWorkerImpl : LiquidLegionsCryptoWorker {

  override fun addNoiseToSketch(request: AddNoiseToSketchRequest): AddNoiseToSketchResponse {
    return AddNoiseToSketchResponse.parseFrom(
      ProtocolEncryptionUtility.AddNoiseToSketch(request.toByteArray())
    )
  }

  override fun blindOneLayerRegisterIndex(
    request: BlindOneLayerRegisterIndexRequest
  ): BlindOneLayerRegisterIndexResponse {
    return BlindOneLayerRegisterIndexResponse.parseFrom(
      ProtocolEncryptionUtility.BlindOneLayerRegisterIndex(request.toByteArray())
    )
  }

  override fun blindLastLayerIndexThenJoinRegisters(
    request: BlindLastLayerIndexThenJoinRegistersRequest
  ): BlindLastLayerIndexThenJoinRegistersResponse {
    return BlindLastLayerIndexThenJoinRegistersResponse.parseFrom(
      ProtocolEncryptionUtility.BlindLastLayerIndexThenJoinRegisters(request.toByteArray())
    )
  }

  override fun decryptLastLayerFlagAndCount(
    request: DecryptLastLayerFlagAndCountRequest
  ): DecryptLastLayerFlagAndCountResponse {
    return DecryptLastLayerFlagAndCountResponse.parseFrom(
      ProtocolEncryptionUtility.DecryptLastLayerFlagAndCount(request.toByteArray())
    )
  }

  override fun decryptOneLayerFlagAndCount(
    request: DecryptOneLayerFlagAndCountRequest
  ): DecryptOneLayerFlagAndCountResponse {
    return DecryptOneLayerFlagAndCountResponse.parseFrom(
      ProtocolEncryptionUtility.DecryptOneLayerFlagAndCount(request.toByteArray())
    )
  }

  companion object {
    init {
      loadLibrary(
        name = "protocol_encryption_utility",
        directoryPath =
          Paths.get("wfa_measurement_system/src/main/java/org/wfanet/measurement/crypto")
      )
    }
  }
}