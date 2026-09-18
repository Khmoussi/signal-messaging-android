/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.directory

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

data class LocalDirectoryLookupRequest(
  @JsonProperty("e164s") val e164s: List<String>
)

data class LocalDirectorySearchRequest(
  @JsonProperty("query") val query: String? = null
)

data class LocalDirectoryLookupResponse(
  @JsonProperty("results") val results: List<LocalDirectoryEntry> = emptyList()
)

data class LocalDirectoryEntry(
  @JsonProperty("e164") val e164: String,
  @JsonProperty("aci") val aci: UUID,
  @JsonProperty("pni") val pni: UUID
)
