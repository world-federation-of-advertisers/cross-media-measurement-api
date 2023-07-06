workspace(name = "wfa_measurement_proto")

load("//build:repositories.bzl", "wfa_measurement_proto_repositories")

wfa_measurement_proto_repositories()

load("//build:deps.bzl", "wfa_measurement_proto_deps")

wfa_measurement_proto_deps()

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")

http_file(
    name = "plantuml",
    downloaded_file_path = "plantuml.jar",
    sha256 = "3a659c3d87ea5ebac7aadb645233176c51d0290777ebc28285dd2a35dc947752",
    urls = ["https://github.com/plantuml/plantuml/releases/download/v1.2023.4/plantuml-1.2023.4.jar"],
)
