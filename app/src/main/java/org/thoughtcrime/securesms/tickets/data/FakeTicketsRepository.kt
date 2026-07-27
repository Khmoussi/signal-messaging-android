/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.thoughtcrime.securesms.tickets.model.Ticket
import org.thoughtcrime.securesms.tickets.model.TicketFilter
import org.thoughtcrime.securesms.tickets.model.TicketMessage
import org.thoughtcrime.securesms.tickets.model.TicketParticipant
import org.thoughtcrime.securesms.tickets.model.TicketPriority
import org.thoughtcrime.securesms.tickets.model.TicketStatus
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * In-memory ticket store for static UI demos. No persistence or network.
 */
object FakeTicketsRepository {

  const val CURRENT_USER_ID = "me"
  const val CURRENT_USER_NAME = "You"

  val DEPARTMENTS = listOf("IT Support", "Facilities", "HR", "Security", "Operations")
  val LOCATIONS = listOf("HQ - Floor 1", "HQ - Floor 2", "Warehouse", "Remote", "Branch Office")
  val AVAILABLE_PARTICIPANTS = listOf(
    TicketParticipant("alice", "Alice Chen"),
    TicketParticipant("bob", "Bob Martinez"),
    TicketParticipant("cara", "Cara Nguyen"),
    TicketParticipant("dan", "Dan Okonkwo"),
    TicketParticipant("eve", "Eve Patel")
  )

  private val self = TicketParticipant(CURRENT_USER_ID, CURRENT_USER_NAME)

  private val now = System.currentTimeMillis()

  private val seed: List<Ticket> = listOf(
    Ticket(
      id = "1",
      number = "TKT-1001",
      description = "Printer on Floor 2 is jamming repeatedly",
      department = "IT Support",
      location = "HQ - Floor 2",
      priority = TicketPriority.HIGH,
      status = TicketStatus.OPEN,
      createdAtMs = now - TimeUnit.HOURS.toMillis(5),
      lastActivityMs = now - TimeUnit.MINUTES.toMillis(20),
      createdById = CURRENT_USER_ID,
      createdByName = CURRENT_USER_NAME,
      administratorId = CURRENT_USER_ID,
      participants = listOf(self, AVAILABLE_PARTICIPANTS[0], AVAILABLE_PARTICIPANTS[1]),
      messages = listOf(
        TicketMessage("m1", CURRENT_USER_ID, CURRENT_USER_NAME, "Printer keeps jamming on every other page.", now - TimeUnit.HOURS.toMillis(5), true),
        TicketMessage("m2", "alice", "Alice Chen", "I'll check the toner and paper path.", now - TimeUnit.HOURS.toMillis(4), false),
        TicketMessage("m3", CURRENT_USER_ID, CURRENT_USER_NAME, "Thanks — it's the large conference room printer.", now - TimeUnit.MINUTES.toMillis(20), true)
      )
    ),
    Ticket(
      id = "2",
      number = "TKT-1002",
      description = "Badge reader at warehouse entrance intermittent",
      department = "Security",
      location = "Warehouse",
      priority = TicketPriority.URGENT,
      status = TicketStatus.OPEN,
      createdAtMs = now - TimeUnit.DAYS.toMillis(1),
      lastActivityMs = now - TimeUnit.HOURS.toMillis(2),
      createdById = "bob",
      createdByName = "Bob Martinez",
      administratorId = "bob",
      participants = listOf(AVAILABLE_PARTICIPANTS[1], self, AVAILABLE_PARTICIPANTS[3]),
      messages = listOf(
        TicketMessage("m4", "bob", "Bob Martinez", "Badge reader fails about every 10 minutes.", now - TimeUnit.DAYS.toMillis(1), false),
        TicketMessage("m5", CURRENT_USER_ID, CURRENT_USER_NAME, "I can reproduce it — lights blink red then recover.", now - TimeUnit.HOURS.toMillis(2), true)
      )
    ),
    Ticket(
      id = "3",
      number = "TKT-1003",
      description = "Request additional chairs for training room",
      department = "Facilities",
      location = "HQ - Floor 1",
      priority = TicketPriority.MEDIUM,
      status = TicketStatus.CLOSED,
      createdAtMs = now - TimeUnit.DAYS.toMillis(7),
      lastActivityMs = now - TimeUnit.DAYS.toMillis(3),
      createdById = CURRENT_USER_ID,
      createdByName = CURRENT_USER_NAME,
      administratorId = CURRENT_USER_ID,
      participants = listOf(self, AVAILABLE_PARTICIPANTS[2]),
      messages = listOf(
        TicketMessage("m6", CURRENT_USER_ID, CURRENT_USER_NAME, "Need 8 extra chairs for Thursday training.", now - TimeUnit.DAYS.toMillis(7), true),
        TicketMessage("m7", "cara", "Cara Nguyen", "Delivered and set up. Closing ticket.", now - TimeUnit.DAYS.toMillis(3), false)
      )
    )
  )

  private val internalTickets = MutableStateFlow(seed)
  val tickets: StateFlow<List<Ticket>> = internalTickets.asStateFlow()

  private var nextNumber = 1004

  fun getTicket(id: String): Ticket? = internalTickets.value.firstOrNull { it.id == id }

  fun observeFiltered(filter: TicketFilter, query: String): List<Ticket> {
    val normalized = query.trim()
    return internalTickets.value
      .asSequence()
      .filter { ticket ->
        when (filter) {
          TicketFilter.ALL -> true
          TicketFilter.OPEN -> ticket.status == TicketStatus.OPEN
          TicketFilter.CLOSED -> ticket.status == TicketStatus.CLOSED
        }
      }
      .filter { ticket ->
        if (normalized.isEmpty()) {
          true
        } else {
          ticket.number.contains(normalized, ignoreCase = true) ||
            ticket.description.contains(normalized, ignoreCase = true)
        }
      }
      .sortedByDescending { it.lastActivityMs }
      .toList()
  }

  fun createTicket(
    department: String,
    location: String,
    priority: TicketPriority,
    description: String,
    initialParticipantIds: List<String> = emptyList()
  ): Ticket {
    val participants = buildList {
      add(self)
      initialParticipantIds
        .mapNotNull { id -> AVAILABLE_PARTICIPANTS.firstOrNull { it.id == id } }
        .forEach { add(it) }
    }.distinctBy { it.id }

    val createdAt = System.currentTimeMillis()
    val ticket = Ticket(
      id = UUID.randomUUID().toString(),
      number = "TKT-${nextNumber++}",
      description = description.trim(),
      department = department,
      location = location,
      priority = priority,
      status = TicketStatus.OPEN,
      createdAtMs = createdAt,
      lastActivityMs = createdAt,
      createdById = CURRENT_USER_ID,
      createdByName = CURRENT_USER_NAME,
      administratorId = CURRENT_USER_ID,
      participants = participants,
      messages = listOf(
        TicketMessage(
          id = UUID.randomUUID().toString(),
          authorId = CURRENT_USER_ID,
          authorName = CURRENT_USER_NAME,
          body = description.trim(),
          timestampMs = createdAt,
          isFromSelf = true
        )
      )
    )

    internalTickets.update { listOf(ticket) + it }
    return ticket
  }

  fun closeTicket(ticketId: String) {
    updateTicket(ticketId) { ticket ->
      if (!ticket.isAdministrator(CURRENT_USER_ID) || ticket.status == TicketStatus.CLOSED) {
        ticket
      } else {
        ticket.copy(status = TicketStatus.CLOSED, lastActivityMs = System.currentTimeMillis())
      }
    }
  }

  fun reopenTicket(ticketId: String) {
    updateTicket(ticketId) { ticket ->
      if (!ticket.isAdministrator(CURRENT_USER_ID) || ticket.status == TicketStatus.OPEN) {
        ticket
      } else {
        ticket.copy(status = TicketStatus.OPEN, lastActivityMs = System.currentTimeMillis())
      }
    }
  }

  fun transferAdministration(ticketId: String, newAdminId: String) {
    updateTicket(ticketId) { ticket ->
      if (!ticket.isAdministrator(CURRENT_USER_ID)) {
        ticket
      } else if (!ticket.isParticipant(newAdminId)) {
        ticket
      } else {
        ticket.copy(administratorId = newAdminId, lastActivityMs = System.currentTimeMillis())
      }
    }
  }

  fun addParticipant(ticketId: String, participantId: String) {
    updateTicket(ticketId) { ticket ->
      if (ticket.status == TicketStatus.CLOSED) {
        ticket
      } else if (!ticket.isParticipant(CURRENT_USER_ID)) {
        ticket
      } else if (ticket.isParticipant(participantId)) {
        ticket
      } else {
        val participant = AVAILABLE_PARTICIPANTS.firstOrNull { it.id == participantId } ?: return@updateTicket ticket
        ticket.copy(
          participants = ticket.participants + participant,
          lastActivityMs = System.currentTimeMillis()
        )
      }
    }
  }

  fun sendMessage(ticketId: String, body: String) {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return

    updateTicket(ticketId) { ticket ->
      if (ticket.status == TicketStatus.CLOSED || !ticket.isParticipant(CURRENT_USER_ID)) {
        ticket
      } else {
        val message = TicketMessage(
          id = UUID.randomUUID().toString(),
          authorId = CURRENT_USER_ID,
          authorName = CURRENT_USER_NAME,
          body = trimmed,
          timestampMs = System.currentTimeMillis(),
          isFromSelf = true
        )
        ticket.copy(
          messages = ticket.messages + message,
          lastActivityMs = message.timestampMs
        )
      }
    }
  }

  private fun updateTicket(ticketId: String, transform: (Ticket) -> Ticket) {
    internalTickets.update { tickets ->
      tickets.map { if (it.id == ticketId) transform(it) else it }
    }
  }
}
