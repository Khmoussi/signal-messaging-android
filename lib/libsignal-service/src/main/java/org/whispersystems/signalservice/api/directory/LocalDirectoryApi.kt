/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.directory

import org.signal.network.NetworkResult
import org.signal.network.websocket.WebSocketRequestMessage
import org.signal.network.websocket.post
import org.whispersystems.signalservice.api.fromWebSocketRequest
import org.whispersystems.signalservice.api.websocket.SignalWebSocket

/**
 * Self-host MVP stand-in for CDSI.
 *
 * POST /v1/directory/local/lookup
 * POST /v1/directory/local/search
 */
class LocalDirectoryApi(private val authWebSocket: SignalWebSocket.AuthenticatedWebSocket) {

  companion object {
    const val MAX_E164S_PER_REQUEST = 200
  }

  fun lookup(e164s: Collection<String>): NetworkResult<LocalDirectoryLookupResponse> {
    val unique = e164s.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (unique.isEmpty()) {
      return NetworkResult.Success(LocalDirectoryLookupResponse(emptyList()))
    }

    val chunks = unique.chunked(MAX_E164S_PER_REQUEST)
    val merged = ArrayList<LocalDirectoryEntry>()

    for (chunk in chunks) {
      val wsRequest = WebSocketRequestMessage.post(
        "/v1/directory/local/lookup",
        LocalDirectoryLookupRequest(chunk)
      )
      when (val result = NetworkResult.fromWebSocketRequest(authWebSocket, wsRequest, LocalDirectoryLookupResponse::class)) {
        is NetworkResult.Success -> merged.addAll(result.result.results)
        else -> return result
      }
    }

    return NetworkResult.Success(LocalDirectoryLookupResponse(merged))
  }

  /**
   * Live directory search for self-host contact pickers.
   * Empty [query] returns up to 100 registered accounts; digit queries filter by E164 substring.
   */
  fun search(query: String?): NetworkResult<LocalDirectoryLookupResponse> {
    val wsRequest = WebSocketRequestMessage.post(
      "/v1/directory/local/search",
      LocalDirectorySearchRequest(query)
    )
    return NetworkResult.fromWebSocketRequest(authWebSocket, wsRequest, LocalDirectoryLookupResponse::class)
  }
}
