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

package org.wfanet.measurement.loadtest

import java.io.File
import kotlin.properties.Delegates
import org.wfanet.measurement.db.gcp.SpannerFromFlags
import picocli.CommandLine

class CorrectnessFlags {

  @CommandLine.Mixin
  lateinit var spannerFlags: SpannerFromFlags.Flags
    private set

  @set:CommandLine.Option(
    names = ["--data-provider-count"],
    description = ["Number of Data Providers."],
    defaultValue = "2"
  )
  var dataProviderCount by Delegates.notNull<Int>()
    private set

  @set:CommandLine.Option(
    names = ["--campaign-count"],
    description = ["Number of Campaigns per each Data Provider."],
    defaultValue = "1"
  )
  var campaignCount by Delegates.notNull<Int>()
    private set

  @set:CommandLine.Option(
    names = ["--generated-set-size"],
    description = ["Set size of the reach to generate per campaign."],
    defaultValue = "1000"
  )
  var generatedSetSize by Delegates.notNull<Int>()
    private set

  @set:CommandLine.Option(
    names = ["--universe-size"],
    description = ["Universe size of the reach per campaign (Default is 10B)."],
    defaultValue = "10000000000"
  )
  var universeSize by Delegates.notNull<Long>()
    private set

  @CommandLine.Option(
    names = ["--sketch-config-file"],
    description = ["File path for SketchConfig proto message in text format."],
    defaultValue = "config/liquid_legions_sketch_config.textproto"
  )
  lateinit var sketchConfigFile: File
    private set

  @set:CommandLine.Option(
    names = ["--elliptic-curve-id"],
    description = ["ID of the OpenSSL built-in elliptic curve required to encrypt sketches."],
    defaultValue = "415"
  )
  var curveId by Delegates.notNull<Int>()
    private set

  @CommandLine.Option(
    names = ["--encryption-key-generator"],
    description = ["Generator `g` of the public key."],
    required = true
  )
  lateinit var encryptionKeyGenerator: String
    private set

  @CommandLine.Option(
    names = ["--encryption-key-element"],
    description = ["Element `g^x` where `x` is the secret key."],
    required = true
  )
  lateinit var encryptionKeyElement: String
    private set

  @CommandLine.Option(
    names = ["--combined-public-key-id"],
    description = ["Resource ID of the CombinedPublicKey."],
    required = true
  )
  lateinit var combinedPublicKeyId: String
    private set

  @CommandLine.Option(
    names = ["--publisher-data-service-target"],
    description = ["Address and port of the Publisher Data Service."],
    required = true
  )
  lateinit var publisherDataServiceTarget: String
    private set

  @CommandLine.Option(
    names = ["--output-directory"],
    description = ["File path of output directory where files will be written."],
    required = true
  )
  lateinit var outputDir: File
    private set

  @CommandLine.Option(
    names = ["--run-id"],
    description = ["Unique identifier of the run (Default is timestamp)."],
    required = false
  )
  lateinit var runId: String
    private set
}
