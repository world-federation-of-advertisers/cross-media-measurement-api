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

#include "wfa/measurement/common/crypto/protocol_encryption_utility_wrapper.h"

#include "util/canonical_errors.h"
#include "util/status_macros.h"
#include "wfa/measurement/common/crypto/protocol_encryption_methods.pb.h"

namespace wfa {
namespace measurement {
namespace common {
namespace crypto {

private_join_and_compute::StatusOr<std::string> AddNoiseToSketch(
    const std::string& serialized_request) {
  AddNoiseToSketchRequest request_proto;
  if (!request_proto.ParseFromString(serialized_request)) {
    return private_join_and_compute::InternalError(
        "failed to parse the AddNoiseToSketchRequest proto.");
  }
  ASSIGN_OR_RETURN(AddNoiseToSketchResponse result,
                   AddNoiseToSketch(request_proto));
  return result.SerializeAsString();
};

private_join_and_compute::StatusOr<std::string> BlindOneLayerRegisterIndex(
    const std::string& serialized_request) {
  BlindOneLayerRegisterIndexRequest request_proto;
  if (!request_proto.ParseFromString(serialized_request)) {
    return private_join_and_compute::InternalError(
        "failed to parse the BlindOneLayerRegisterIndexRequest proto.");
  }
  ASSIGN_OR_RETURN(BlindOneLayerRegisterIndexResponse result,
                   BlindOneLayerRegisterIndex(request_proto));
  return result.SerializeAsString();
};

private_join_and_compute::StatusOr<std::string>
BlindLastLayerIndexThenJoinRegisters(const std::string& serialized_request) {
  BlindLastLayerIndexThenJoinRegistersRequest request_proto;
  if (!request_proto.ParseFromString(serialized_request)) {
    return private_join_and_compute::InternalError(
        "failed to parse the BlindLastLayerIndexThenJoinRegistersRequest "
        "proto.");
  }
  ASSIGN_OR_RETURN(BlindLastLayerIndexThenJoinRegistersResponse result,
                   BlindLastLayerIndexThenJoinRegisters(request_proto));
  return result.SerializeAsString();
};

private_join_and_compute::StatusOr<std::string> DecryptOneLayerFlagAndCount(
    const std::string& serialized_request) {
  DecryptOneLayerFlagAndCountRequest request_proto;
  if (!request_proto.ParseFromString(serialized_request)) {
    return private_join_and_compute::InternalError(
        "failed to parse the DecryptOneLayerFlagAndCountRequest proto.");
  }
  ASSIGN_OR_RETURN(DecryptOneLayerFlagAndCountResponse result,
                   DecryptOneLayerFlagAndCount(request_proto));
  return result.SerializeAsString();
};

private_join_and_compute::StatusOr<std::string> DecryptLastLayerFlagAndCount(
    const std::string& serialized_request) {
  DecryptLastLayerFlagAndCountRequest request_proto;
  if (!request_proto.ParseFromString(serialized_request)) {
    return private_join_and_compute::InternalError(
        "failed to parse the DecryptLastLayerFlagAndCountRequest proto.");
  }
  ASSIGN_OR_RETURN(DecryptLastLayerFlagAndCountResponse result,
                   DecryptLastLayerFlagAndCount(request_proto));
  return result.SerializeAsString();
};

}  // namespace crypto
}  // namespace common
}  // namespace measurement
}  // namespace wfa