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

package k8s

#FrontendSimulator: {
	_mc_resource_name: string
	_simulator_image:  string
	_blob_storage_flags: [...string]

	frontend_simulator_job: #Job & {
		_name:  "frontend-simulator"
		_image: _simulator_image
		_args:  [
			"--tls-cert-file=/var/run/secrets/files/mc_tls.pem",
			"--tls-key-file=/var/run/secrets/files/mc_tls.key",
			"--cert-collection-file=/var/run/secrets/files/all_root_certs.pem",
			"--kingdom-public-api-target=" + (#Target & {name: "v2alpha-public-api-server"}).target,
			"--kingdom-public-api-cert-host=localhost",
			"--mc-resource-name=\(_mc_resource_name)",
			"--mc-consent-signaling-key-der-file=/var/run/secrets/files/mc_cs_private.der",
			"--mc-encryption-private-key-der-file=var/run/secrets/files/mc_enc_private.der",
			"--output-differential-privacy-epsilon=0.1",
			"--output-differential-privacy-delta=0.000001",
		] + _blob_storage_flags
	}
}