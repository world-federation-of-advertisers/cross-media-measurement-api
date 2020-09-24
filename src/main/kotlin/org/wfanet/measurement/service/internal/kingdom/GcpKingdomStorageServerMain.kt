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

package org.wfanet.measurement.service.internal.kingdom

import java.time.Clock
import java.time.Duration
import java.util.logging.Logger
import org.wfanet.measurement.common.RandomIdGenerator
import org.wfanet.measurement.common.commandLineMain
import org.wfanet.measurement.common.identity.DuchyIdFlags
import org.wfanet.measurement.common.identity.DuchyIds
import org.wfanet.measurement.db.gcp.SpannerFromFlags
import org.wfanet.measurement.db.gcp.isReady
import org.wfanet.measurement.db.kingdom.gcp.GcpKingdomRelationalDatabase
import org.wfanet.measurement.service.common.CommonServer
import picocli.CommandLine

@CommandLine.Command(
  name = "gcp_kingdom_storage_server",
  description = [
    "Start the internal Kingdom storage services in a single blocking server.",
    "This brings up its own Cloud Spanner Emulator."
  ],
  mixinStandardHelpOptions = true,
  showDefaultValues = true
)
private fun run(
  @CommandLine.Mixin commonServerFlags: CommonServer.Flags,
  @CommandLine.Mixin spannerFlags: SpannerFromFlags.Flags,
  @CommandLine.Mixin duchyIdFlags: DuchyIdFlags
) {
  DuchyIds.setDuchyIdsFromFlags(duchyIdFlags)

  var spannerFromFlags = SpannerFromFlags(spannerFlags)

  // TODO: push this retry logic into SpannerFromFlags itself.
  while (!spannerFromFlags.databaseClient.isReady()) {
    logger.info("Spanner isn't ready yet, sleeping 1s")
    Thread.sleep(Duration.ofSeconds(1).toMillis())
    spannerFromFlags = SpannerFromFlags(spannerFlags)
  }

  val clock = Clock.systemUTC()

  val relationalDatabase = GcpKingdomRelationalDatabase(
    clock,
    RandomIdGenerator(clock),
    spannerFromFlags.databaseClient
  )

  val services = buildStorageServices(relationalDatabase).toTypedArray()
  val server = CommonServer.fromFlags(commonServerFlags, "GcpKingdomStorageServer", *services)

  server.start().blockUntilShutdown()
}

private val logger: Logger = Logger.getAnonymousLogger()

/** Runs the internal Kingdom storage services in a single server with a Spanner backend. */
fun main(args: Array<String>) = commandLineMain(::run, args)
