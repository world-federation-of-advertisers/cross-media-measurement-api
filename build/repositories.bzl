# Copyright 2023 The Cross-Media Measurement Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Direct repository dependencies for this workspace."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "maybe")

def wfa_measurement_proto_repositories():
    maybe(
        http_archive,
        name = "rules_proto",
        sha256 = "dc3fb206a2cb3441b485eb1e423165b231235a1ea9b031b4433cf7bc1fa460dd",
        strip_prefix = "rules_proto-5.3.0-21.7",
        urls = [
            "https://github.com/bazelbuild/rules_proto/archive/refs/tags/5.3.0-21.7.tar.gz",
        ],
    )

    maybe(
        http_archive,
        name = "com_google_googleapis",
        sha256 = "70cdef593fbfe340d558ca10c6858b5c0410a54576381c422dc3b9158a12ba03",
        strip_prefix = "googleapis-18becb1d1426feb7399db144d7beeb3284f1ccb0",
        urls = ["https://github.com/googleapis/googleapis/archive/18becb1d1426feb7399db144d7beeb3284f1ccb0.tar.gz"],
    )
