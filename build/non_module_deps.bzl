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

"""Module extension for non-module dependencies."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")

def _non_module_deps_impl(
        # buildifier: disable=unused-variable
        mctx):
    http_file(
        name = "plantuml",
        downloaded_file_path = "plantuml.jar",
        sha256 = "3a659c3d87ea5ebac7aadb645233176c51d0290777ebc28285dd2a35dc947752",
        urls = ["https://github.com/plantuml/plantuml/releases/download/v1.2023.4/plantuml-1.2023.4.jar"],
    )

non_module_deps = module_extension(implementation = _non_module_deps_impl)
