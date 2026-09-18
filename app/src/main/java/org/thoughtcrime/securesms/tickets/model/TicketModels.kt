/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.model

import java.util.UUID

enum class TicketStatus {
  OPEN,
  CLOSED;

  companion object {
    fun fromServer(value: String): TicketStatus {
      return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OPEN
    }
  }
}

enum class TicketPriority {
  LOW,
  MEDIUM,
  HIGH,
  URGENT;

  companion object {
    fun fromServer(value: String): TicketPriority {
      return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MEDIUM
    }
  }
}

enum class TicketFilter {
  ALL,
  OPEN,
  CLOSED
}

enum class TicketRole {
  ADMIN,
  PARTICIPANT;

  companion object {
    fun fromServer(value: String): TicketRole {
      return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PARTICIPANT
    }
  }
}

data class TicketParticipant(
  val aci: UUID,
  val displayName: String,
  val role: TicketRole = TicketRole.PARTICIPANT
) {
  val id: String get() = aci.toString()
}

data class TicketMessage(
  val id: String,
  val authorAci: UUID,
  val authorName: String,
  val body: String,
  val timestampMs: Long,
  val isFromSelf: Boolean,
  val attachments: List<TicketAttachment> = emptyList()
) {
  val authorId: String get() = authorAci.toString()
  val hasAttachments: Boolean get() = attachments.isNotEmpty()
}

data class Ticket(
  val id: UUID,
  val number: String,
  val description: String,
  val department: String,
  val location: String,
  val priority: TicketPriority,
  val status: TicketStatus,
  val createdAtMs: Long,
  val lastActivityMs: Long,
  val createdByAci: UUID,
  val createdByName: String,
  val administratorAci: UUID,
  val discussionId: UUID,
  val participants: List<TicketParticipant>,
  val messages: List<TicketMessage>
) {
  val idString: String get() = id.toString()

  fun isAdministrator(aci: UUID): Boolean = administratorAci == aci

  fun isParticipant(aci: UUID): Boolean = participants.any { it.aci == aci }
}
