/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.tickets

import com.fasterxml.jackson.core.type.TypeReference
import org.signal.network.NetworkResult
import org.signal.network.util.JsonUtil
import org.signal.network.websocket.WebSocketRequestMessage
import org.signal.network.websocket.delete
import org.signal.network.websocket.get
import org.signal.network.websocket.post
import org.signal.network.websocket.patch
import org.signal.network.websocket.put
import org.whispersystems.signalservice.api.fromWebSocketRequest
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.push.SendMessageResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Authenticated ticket lifecycle, membership, and ticket-aware messaging APIs.
 *
 * Base path: /v1/tickets
 */
class TicketApi(private val authWebSocket: SignalWebSocket.AuthenticatedWebSocket) {

  fun createTicket(request: CreateTicketRequest): NetworkResult<TicketResponse> {
    val wsRequest = WebSocketRequestMessage.post("/v1/tickets", request)
    return NetworkResult.fromWebSocketRequest(authWebSocket, wsRequest, TicketResponse::class)
  }

  fun getTickets(status: String? = null, query: String? = null): NetworkResult<List<TicketListResponse>> {
    val params = buildList {
      if (!status.isNullOrBlank()) add("status=${urlEncode(status)}")
      if (!query.isNullOrBlank()) add("q=${urlEncode(query)}")
    }
    val path = if (params.isEmpty()) "/v1/tickets" else "/v1/tickets?${params.joinToString("&")}"
    val wsRequest = WebSocketRequestMessage.get(path)
    return NetworkResult.fromWebSocketRequest(
      signalWebSocket = authWebSocket,
      request = wsRequest,
      webSocketResponseConverter = listConverter(object : TypeReference<List<TicketListResponse>>() {})
    )
  }

  fun getTicket(ticketId: UUID): NetworkResult<TicketResponse> {
    val wsRequest = WebSocketRequestMessage.get("/v1/tickets/$ticketId")
    return NetworkResult.fromWebSocketRequest(authWebSocket, wsRequest, TicketResponse::class)
  }

  fun updateTicket(ticketId: UUID, request: UpdateTicketRequest): NetworkResult<Unit> {
    val wsRequest = WebSocketRequestMessage.patch("/v1/tickets/$ticketId", request)
    return NetworkResult.fromWebSocketRequest(authWebSocket, wsRequest)
  }

  fun closeTicket(ticketId: UUID): NetworkResult<Unit> {
    val wsRequest = WebSocketRequestMessage.post("/v1/tickets/$ticketId/close", null)
    return NetworkResult.fromWebSocketRequest(authWebSocket, wsRequest)
  }

  fun reopenTicket(ticketId: UUID): NetworkResult<Unit> {
    val wsRequest = WebSocketRequestMessage.post("/v1/tickets/$ticketId/reopen", null)
    return NetworkResult.fromWebSocketRequest(authWebSocket, wsRequest)
  }

  fun transferAdmin(ticketId: UUID, request: TransferAdminRequest): NetworkResult<Unit> {
    val wsRequest = WebSocketRequestMessage.post("/v1/tickets/$ticketId/transfer", request)
    return NetworkResult.fromWebSocketRequest(authWebSocket, wsRequest)
  }

  fun getMembers(ticketId: UUID): NetworkResult<List<MemberResponse>> {
    val wsRequest = WebSocketRequestMessage.get("/v1/tickets/$ticketId/members")
    return NetworkResult.fromWebSocketRequest(
      signalWebSocket = authWebSocket,
      request = wsRequest,
      webSocketResponseConverter = listConverter(object : TypeReference<List<MemberResponse>>() {})
    )
  }

  fun inviteMember(ticketId: UUID, request: InviteMemberRequest): NetworkResult<Unit> {
    val wsRequest = WebSocketRequestMessage.post("/v1/tickets/$ticketId/members", request)
    return NetworkResult.fromWebSocketRequest(authWebSocket, wsRequest)
  }

  fun removeMember(ticketId: UUID, aci: UUID): NetworkResult<Unit> {
    val wsRequest = WebSocketRequestMessage.delete("/v1/tickets/$ticketId/members/$aci")
    return NetworkResult.fromWebSocketRequest(authWebSocket, wsRequest)
  }

  fun leaveTicket(ticketId: UUID): NetworkResult<Unit> {
    val wsRequest = WebSocketRequestMessage.post("/v1/tickets/$ticketId/leave", null)
    return NetworkResult.fromWebSocketRequest(authWebSocket, wsRequest)
  }

  fun sendMessages(ticketId: UUID, request: TicketMessageRequest): NetworkResult<SendMessageResponse> {
    val wsRequest = WebSocketRequestMessage.put("/v1/tickets/$ticketId/messages", request)
    return NetworkResult.fromWebSocketRequest(authWebSocket, wsRequest, SendMessageResponse::class)
  }

  private fun <T : Any> listConverter(typeRef: TypeReference<T>): NetworkResult.WebSocketResponseConverter<T> {
    return object : NetworkResult.WebSocketResponseConverter<T> {
      override fun convert(response: org.signal.network.websocket.WebsocketResponse): NetworkResult<T> {
        return if (response.status < 200 || response.status > 299) {
          response.toStatusCodeError()
        } else {
          NetworkResult.Success(JsonUtil.fromJson(response.body, typeRef))
        }
      }
    }
  }

  private fun urlEncode(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
  }
}
