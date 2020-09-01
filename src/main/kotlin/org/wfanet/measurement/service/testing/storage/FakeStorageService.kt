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

package org.wfanet.measurement.service.testing.storage

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.wfanet.measurement.internal.testing.BlobMetadata
import org.wfanet.measurement.internal.testing.CreateBlobRequest
import org.wfanet.measurement.internal.testing.DeleteBlobRequest
import org.wfanet.measurement.internal.testing.DeleteBlobResponse
import org.wfanet.measurement.internal.testing.ForwardingStorageServiceGrpcKt
import org.wfanet.measurement.internal.testing.GetBlobMetadataRequest
import org.wfanet.measurement.internal.testing.ReadBlobRequest
import org.wfanet.measurement.internal.testing.ReadBlobResponse
import org.wfanet.measurement.storage.testing.FileSystemStorageClient

class FakeStorageService :
  ForwardingStorageServiceGrpcKt.ForwardingStorageServiceCoroutineImplBase() {
  val storageClient: FileSystemStorageClient = FileSystemStorageClient(createTempDir())

  override suspend fun createBlob(requests: Flow<CreateBlobRequest>): BlobMetadata {
    var blobKey: String? = null
    var content = ByteString.EMPTY
    requests.collect { request ->
      if (blobKey == null) {
        blobKey = request.blobKey
      }
      content = content.concat(request.content)
    }
    if (blobKey == null) {
      throw StatusException(Status.INVALID_ARGUMENT.withDescription("Missing blob key"))
    }
    val blob = storageClient.createBlob(blobKey!!, flowOf(content))
    return BlobMetadata.newBuilder().setSize(blob.size).build()
  }

  override suspend fun getBlobMetadata(request: GetBlobMetadataRequest): BlobMetadata =
    BlobMetadata.newBuilder().setSize(getBlob(request.blobKey).size).build()

  override fun readBlob(request: ReadBlobRequest): Flow<ReadBlobResponse> =
    getBlob(request.blobKey).read(request.chunkSize).map {
      ReadBlobResponse.newBuilder().setChunk(it).build()
    }

  override suspend fun deleteBlob(request: DeleteBlobRequest): DeleteBlobResponse {
    getBlob(request.blobKey).delete()
    return DeleteBlobResponse.getDefaultInstance()
  }

  private fun getBlob(blobKey: String) = storageClient.getBlob(blobKey) ?: throw StatusException(
    Status.NOT_FOUND.withDescription("Blob not found with key $blobKey")
  )
}