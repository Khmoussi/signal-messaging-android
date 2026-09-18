/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.data

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.signal.core.models.ServiceId.ACI
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tickets.model.Ticket
import org.thoughtcrime.securesms.tickets.model.TicketAttachment
import org.thoughtcrime.securesms.tickets.model.TicketFilter
import org.thoughtcrime.securesms.tickets.model.TicketPriority
import org.thoughtcrime.securesms.tickets.model.TicketStatus
import org.thoughtcrime.securesms.jobs.TicketHistorySyncJob
import org.whispersystems.signalservice.api.NetworkResultUtil
import org.whispersystems.signalservice.api.tickets.CreateTicketRequest
import org.whispersystems.signalservice.api.tickets.InviteMemberRequest
import org.whispersystems.signalservice.api.tickets.TicketResponse
import org.whispersystems.signalservice.api.tickets.TransferAdminRequest
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Real tickets repository backed by [TicketApi] + local [TicketDatabase].
 */
object TicketsRepository {

  private val TAG = Log.tag(TicketsRepository::class.java)

  val DEPARTMENTS = listOf("IT Support", "Facilities", "HR", "Security", "Operations")
  val LOCATIONS = listOf("HQ - Floor 1", "HQ - Floor 2", "Warehouse", "Remote", "Branch Office")

  private val db: TicketDatabase by lazy {
    TicketDatabase.getInstance(AppDependencies.application)
  }

  private val internalTickets = MutableStateFlow<List<Ticket>>(emptyList())
  val tickets: StateFlow<List<Ticket>> = internalTickets.asStateFlow()

  fun selfAci(): UUID = SignalStore.account.requireAci().rawUuid

  fun resolveName(aci: UUID): String {
    return try {
      if (aci == selfAci()) {
        Recipient.self().getDisplayName(AppDependencies.application)
      } else {
        Recipient.externalPush(ACI.from(aci)).getDisplayName(AppDependencies.application)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to resolve name for $aci", e)
      aci.toString().take(8)
    }
  }

  fun reloadFromDb() {
    internalTickets.value = db.getAllTickets(::resolveName, selfAci())
  }

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

  fun getTicket(id: UUID): Ticket? = internalTickets.value.firstOrNull { it.id == id }
    ?: db.getTicket(id, ::resolveName, selfAci())

  fun getTicket(id: String): Ticket? {
    return try {
      getTicket(UUID.fromString(id))
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  @Throws(IOException::class)
  suspend fun refreshList(status: String? = null, query: String? = null) = withContext(Dispatchers.IO) {
    val rows = NetworkResultUtil.toBasicLegacy(SignalNetwork.tickets.getTickets(status, query))
    rows.forEach { row ->
      db.upsertListRow(
        ticketId = row.ticketId,
        ticketNumber = row.ticketNumber,
        status = row.status,
        description = row.description,
        lastActivityAt = row.lastActivityAt
      )
    }
    reloadFromDb()
  }

  @Throws(IOException::class)
  suspend fun refreshTicket(ticketId: UUID) = withContext(Dispatchers.IO) {
    val detail = NetworkResultUtil.toBasicLegacy(SignalNetwork.tickets.getTicket(ticketId))
    persistDetail(detail)
    val members = NetworkResultUtil.toBasicLegacy(SignalNetwork.tickets.getMembers(ticketId))
    db.replaceMembers(
      ticketId,
      members.map { m -> m.aci to (m.role to m.joinedAt) }
    )
    reloadFromDb()
  }

  @Throws(IOException::class)
  suspend fun createTicket(
    department: String,
    location: String,
    priority: TicketPriority,
    description: String,
    participantAcis: List<UUID>
  ): Ticket = withContext(Dispatchers.IO) {
    val response = NetworkResultUtil.toBasicLegacy(
      SignalNetwork.tickets.createTicket(
        CreateTicketRequest(
          department = department,
          location = location,
          priority = priority.name,
          description = description.trim(),
          participantAcis = participantAcis
        )
      )
    )
    persistDetail(response)
    val members = NetworkResultUtil.toBasicLegacy(SignalNetwork.tickets.getMembers(response.ticketId))
    db.replaceMembers(response.ticketId, members.map { m -> m.aci to (m.role to m.joinedAt) })

    val now = response.createdAt
    db.insertMessage(
      messageId = UUID.randomUUID().toString(),
      ticketId = response.ticketId,
      authorAci = response.createdByAci,
      body = response.description,
      timestampMs = now,
      isFromSelf = true
    )
    reloadFromDb()
    getTicket(response.ticketId) ?: error("Created ticket missing locally")
  }

  @Throws(IOException::class)
  suspend fun closeTicket(ticketId: UUID) = withContext(Dispatchers.IO) {
    NetworkResultUtil.toBasicLegacy(SignalNetwork.tickets.closeTicket(ticketId))
    db.updateStatus(ticketId, TicketStatus.CLOSED, System.currentTimeMillis())
    reloadFromDb()
  }

  @Throws(IOException::class)
  suspend fun reopenTicket(ticketId: UUID) = withContext(Dispatchers.IO) {
    NetworkResultUtil.toBasicLegacy(SignalNetwork.tickets.reopenTicket(ticketId))
    db.updateStatus(ticketId, TicketStatus.OPEN, System.currentTimeMillis())
    reloadFromDb()
  }

  @Throws(IOException::class)
  suspend fun transferAdministration(ticketId: UUID, newAdminAci: UUID) = withContext(Dispatchers.IO) {
    NetworkResultUtil.toBasicLegacy(
      SignalNetwork.tickets.transferAdmin(ticketId, TransferAdminRequest(newAdminAci))
    )
    db.updateAdmin(ticketId, newAdminAci, System.currentTimeMillis())
    refreshTicket(ticketId)
  }

  @Throws(IOException::class)
  suspend fun addParticipant(ticketId: UUID, aci: UUID) = withContext(Dispatchers.IO) {
    NetworkResultUtil.toBasicLegacy(
      SignalNetwork.tickets.inviteMember(ticketId, InviteMemberRequest(aci))
    )
    refreshTicket(ticketId)
    // Server stores membership only; re-encrypt local history to the invitee.
    TicketHistorySyncJob.enqueue(ticketId, aci)
  }

  /**
   * Ensures ticket metadata exists locally (needed before history messages can appear in the UI).
   */
  fun ensureTicketCached(ticketId: UUID) {
    if (getTicket(ticketId) != null) return
    try {
      runBlocking { refreshTicket(ticketId) }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to refresh ticket $ticketId after invite/history message", e)
    }
  }

  fun insertLocalMessage(
    ticketId: UUID,
    authorAci: UUID,
    body: String,
    timestampMs: Long,
    isFromSelf: Boolean,
    messageId: String = "${ticketId}_$timestampMs",
    attachments: List<TicketAttachment> = emptyList(),
    touchActivity: Boolean = true
  ) {
    db.insertMessage(messageId, ticketId, authorAci, body, timestampMs, isFromSelf, attachments)
    if (touchActivity) {
      db.touchActivity(ticketId, timestampMs)
    }
    reloadFromDb()
  }

  fun updateMessageAttachments(ticketId: UUID, timestampMs: Long, attachments: List<TicketAttachment>) {
    val messageId = "${ticketId}_$timestampMs"
    db.updateMessageAttachments(messageId, attachments)
    reloadFromDb()
  }

  private fun persistDetail(response: TicketResponse) {
    db.upsertTicket(
      ticketId = response.ticketId,
      ticketNumber = response.ticketNumber,
      status = response.status,
      department = response.department,
      location = response.location,
      priority = response.priority,
      description = response.description,
      createdByAci = response.createdByAci,
      adminAci = response.adminAci,
      discussionId = response.discussionId,
      createdAt = response.createdAt,
      lastActivityAt = response.lastActivityAt
    )
  }

  fun init(application: Application) {
    TicketDatabase.getInstance(application)
    reloadFromDb()
  }
}
