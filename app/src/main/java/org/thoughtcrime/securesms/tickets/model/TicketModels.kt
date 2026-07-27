/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.model

enum class TicketStatus {
  OPEN,
  CLOSED
}

enum class TicketPriority {
  LOW,
  MEDIUM,
  HIGH,
  URGENT
}

enum class TicketFilter {
  ALL,
  OPEN,
  CLOSED
}

data class TicketParticipant(
  val id: String,
  val displayName: String
)

data class TicketMessage(
  val id: String,
  val authorId: String,
  val authorName: String,
  val body: String,
  val timestampMs: Long,
  val isFromSelf: Boolean
)

data class Ticket(
  val id: String,
  val number: String,
  val description: String,
  val department: String,
  val location: String,
  val priority: TicketPriority,
  val status: TicketStatus,
  val createdAtMs: Long,
  val lastActivityMs: Long,
  val createdById: String,
  val createdByName: String,
  val administratorId: String,
  val participants: List<TicketParticipant>,
  val messages: List<TicketMessage>
) {
  fun isAdministrator(userId: String): Boolean = administratorId == userId

  fun isParticipant(userId: String): Boolean = participants.any { it.id == userId }
}
