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

"""Transitive dependencies for this workspace."""

load(
    "@rules_proto//proto:repositories.bzl",
    "rules_proto_dependencies",
    "rules_proto_toolchains",
)
load(
    "@com_google_googleapis//:repository_rules.bzl",
    "switched_rules_by_language",
)

def wfa_measurement_proto_deps():
    rules_proto_dependencies()
    rules_proto_toolchains()

    switched_rules_by_language(
        name = "com_google_googleapis_imports",
    )
