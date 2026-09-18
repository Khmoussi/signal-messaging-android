/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.tickets

import com.fasterxml.jackson.annotation.JsonProperty
import org.whispersystems.signalservice.internal.push.OutgoingPushMessage
import java.util.UUID

data class CreateTicketRequest(
  @JsonProperty("department") val department: String,
  @JsonProperty("location") val location: String,
  @JsonProperty("priority") val priority: String,
  @JsonProperty("description") val description: String,
  @JsonProperty("participantAcis") val participantAcis: List<UUID> = emptyList()
)

data class UpdateTicketRequest(
  @JsonProperty("description") val description: String? = null,
  @JsonProperty("priority") val priority: String? = null
)

data class InviteMemberRequest(
  @JsonProperty("aci") val aci: UUID
)

data class TransferAdminRequest(
  @JsonProperty("newAdminAci") val newAdminAci: UUID
)

data class TicketResponse(
  @JsonProperty("ticketId") val ticketId: UUID,
  @JsonProperty("ticketNumber") val ticketNumber: String,
  @JsonProperty("status") val status: String,
  @JsonProperty("department") val department: String,
  @JsonProperty("location") val location: String,
  @JsonProperty("priority") val priority: String,
  @JsonProperty("description") val description: String,
  @JsonProperty("createdByAci") val createdByAci: UUID,
  @JsonProperty("adminAci") val adminAci: UUID,
  @JsonProperty("createdAt") val createdAt: Long,
  @JsonProperty("lastActivityAt") val lastActivityAt: Long,
  @JsonProperty("discussionId") val discussionId: UUID,
  @JsonProperty("closedAt") val closedAt: Long? = null,
  @JsonProperty("closedByAci") val closedByAci: UUID? = null,
  @JsonProperty("reopenedAt") val reopenedAt: Long? = null,
  @JsonProperty("reopenedByAci") val reopenedByAci: UUID? = null
)

data class TicketListResponse(
  @JsonProperty("ticketId") val ticketId: UUID,
  @JsonProperty("ticketNumber") val ticketNumber: String,
  @JsonProperty("status") val status: String,
  @JsonProperty("description") val description: String,
  @JsonProperty("lastActivityAt") val lastActivityAt: Long
)

data class MemberResponse(
  @JsonProperty("aci") val aci: UUID,
  @JsonProperty("role") val role: String,
  @JsonProperty("joinedAt") val joinedAt: Long
)

data class TicketMessageRequest(
  @JsonProperty("destinations") val destinations: List<DestinationBundle>,
  @JsonProperty("online") val online: Boolean = false,
  @JsonProperty("urgent") val urgent: Boolean = true,
  @JsonProperty("timestamp") val timestamp: Long = 0
) {
  data class DestinationBundle(
    @JsonProperty("aci") val aci: UUID,
    @JsonProperty("messages") val messages: List<OutgoingPushMessage>
  )
}
