/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.thoughtcrime.securesms.tickets.model.TicketAttachment
import org.thoughtcrime.securesms.tickets.model.TicketMessage
import org.thoughtcrime.securesms.tickets.model.TicketParticipant
import org.thoughtcrime.securesms.tickets.model.TicketPriority
import org.thoughtcrime.securesms.tickets.model.TicketRole
import org.thoughtcrime.securesms.tickets.model.TicketStatus
import org.thoughtcrime.securesms.tickets.model.Ticket
import java.util.UUID

/**
 * Lightweight local cache for ticket metadata, members, and discussion messages.
 * Server does not store chat history; messages live here after send/receive.
 */
class TicketDatabase private constructor(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE ticket (
        ticket_id TEXT PRIMARY KEY,
        ticket_number TEXT NOT NULL,
        status TEXT NOT NULL,
        department TEXT NOT NULL,
        location TEXT NOT NULL,
        priority TEXT NOT NULL,
        description TEXT NOT NULL,
        created_by_aci TEXT NOT NULL,
        admin_aci TEXT NOT NULL,
        discussion_id TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        last_activity_at INTEGER NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      """
      CREATE TABLE ticket_member (
        ticket_id TEXT NOT NULL,
        aci TEXT NOT NULL,
        role TEXT NOT NULL,
        joined_at INTEGER NOT NULL,
        PRIMARY KEY (ticket_id, aci)
      )
      """.trimIndent()
    )
    db.execSQL(
      """
      CREATE TABLE ticket_message (
        message_id TEXT PRIMARY KEY,
        ticket_id TEXT NOT NULL,
        author_aci TEXT NOT NULL,
        body TEXT NOT NULL,
        timestamp_ms INTEGER NOT NULL,
        is_from_self INTEGER NOT NULL,
        attachment_json TEXT
      )
      """.trimIndent()
    )
    db.execSQL("CREATE INDEX ticket_message_ticket_ts ON ticket_message (ticket_id, timestamp_ms)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE ticket_message ADD COLUMN attachment_json TEXT")
    }
  }

  fun upsertTicket(
    ticketId: UUID,
    ticketNumber: String,
    status: String,
    department: String,
    location: String,
    priority: String,
    description: String,
    createdByAci: UUID,
    adminAci: UUID,
    discussionId: UUID,
    createdAt: Long,
    lastActivityAt: Long
  ) {
    val values = ContentValues().apply {
      put("ticket_id", ticketId.toString())
      put("ticket_number", ticketNumber)
      put("status", status)
      put("department", department)
      put("location", location)
      put("priority", priority)
      put("description", description)
      put("created_by_aci", createdByAci.toString())
      put("admin_aci", adminAci.toString())
      put("discussion_id", discussionId.toString())
      put("created_at", createdAt)
      put("last_activity_at", lastActivityAt)
    }
    writableDatabase.insertWithOnConflict("ticket", null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun upsertListRow(
    ticketId: UUID,
    ticketNumber: String,
    status: String,
    description: String,
    lastActivityAt: Long
  ) {
    val existing = readableDatabase.query(
      "ticket",
      arrayOf("department", "location", "priority", "created_by_aci", "admin_aci", "discussion_id", "created_at"),
      "ticket_id = ?",
      arrayOf(ticketId.toString()),
      null,
      null,
      null
    )
    existing.use { cursor ->
      if (cursor.moveToFirst()) {
        upsertTicket(
          ticketId = ticketId,
          ticketNumber = ticketNumber,
          status = status,
          department = cursor.getString(0),
          location = cursor.getString(1),
          priority = cursor.getString(2),
          description = description,
          createdByAci = UUID.fromString(cursor.getString(3)),
          adminAci = UUID.fromString(cursor.getString(4)),
          discussionId = UUID.fromString(cursor.getString(5)),
          createdAt = cursor.getLong(6),
          lastActivityAt = lastActivityAt
        )
      } else {
        upsertTicket(
          ticketId = ticketId,
          ticketNumber = ticketNumber,
          status = status,
          department = "",
          location = "",
          priority = TicketPriority.MEDIUM.name,
          description = description,
          createdByAci = ticketId,
          adminAci = ticketId,
          discussionId = ticketId,
          createdAt = lastActivityAt,
          lastActivityAt = lastActivityAt
        )
      }
    }
  }

  fun replaceMembers(ticketId: UUID, members: List<Pair<UUID, Pair<String, Long>>>) {
    writableDatabase.beginTransaction()
    try {
      writableDatabase.delete("ticket_member", "ticket_id = ?", arrayOf(ticketId.toString()))
      members.forEach { (aci, roleAndJoined) ->
        val values = ContentValues().apply {
          put("ticket_id", ticketId.toString())
          put("aci", aci.toString())
          put("role", roleAndJoined.first)
          put("joined_at", roleAndJoined.second)
        }
        writableDatabase.insert("ticket_member", null, values)
      }
      writableDatabase.setTransactionSuccessful()
    } finally {
      writableDatabase.endTransaction()
    }
  }

  fun insertMessage(
    messageId: String,
    ticketId: UUID,
    authorAci: UUID,
    body: String,
    timestampMs: Long,
    isFromSelf: Boolean,
    attachments: List<TicketAttachment> = emptyList()
  ) {
    val values = ContentValues().apply {
      put("message_id", messageId)
      put("ticket_id", ticketId.toString())
      put("author_aci", authorAci.toString())
      put("body", body)
      put("timestamp_ms", timestampMs)
      put("is_from_self", if (isFromSelf) 1 else 0)
      put("attachment_json", if (attachments.isEmpty()) null else TicketAttachment.toJson(attachments))
    }
    writableDatabase.insertWithOnConflict("ticket_message", null, values, SQLiteDatabase.CONFLICT_IGNORE)
  }

  fun updateMessageAttachments(messageId: String, attachments: List<TicketAttachment>) {
    val values = ContentValues().apply {
      put("attachment_json", if (attachments.isEmpty()) null else TicketAttachment.toJson(attachments))
    }
    writableDatabase.update("ticket_message", values, "message_id = ?", arrayOf(messageId))
  }

  fun updateStatus(ticketId: UUID, status: TicketStatus, lastActivityAt: Long) {
    val values = ContentValues().apply {
      put("status", status.name)
      put("last_activity_at", lastActivityAt)
    }
    writableDatabase.update("ticket", values, "ticket_id = ?", arrayOf(ticketId.toString()))
  }

  fun updateAdmin(ticketId: UUID, adminAci: UUID, lastActivityAt: Long) {
    val values = ContentValues().apply {
      put("admin_aci", adminAci.toString())
      put("last_activity_at", lastActivityAt)
    }
    writableDatabase.update("ticket", values, "ticket_id = ?", arrayOf(ticketId.toString()))
  }

  fun touchActivity(ticketId: UUID, lastActivityAt: Long) {
    val values = ContentValues().apply { put("last_activity_at", lastActivityAt) }
    writableDatabase.update("ticket", values, "ticket_id = ?", arrayOf(ticketId.toString()))
  }

  fun getAllTickets(
    nameResolver: (UUID) -> String,
    selfAci: UUID
  ): List<Ticket> {
    val tickets = mutableListOf<Ticket>()
    readableDatabase.query(
      "ticket",
      null,
      null,
      null,
      null,
      null,
      "last_activity_at DESC"
    ).use { cursor ->
      val idIdx = cursor.getColumnIndexOrThrow("ticket_id")
      val numberIdx = cursor.getColumnIndexOrThrow("ticket_number")
      val statusIdx = cursor.getColumnIndexOrThrow("status")
      val deptIdx = cursor.getColumnIndexOrThrow("department")
      val locIdx = cursor.getColumnIndexOrThrow("location")
      val priIdx = cursor.getColumnIndexOrThrow("priority")
      val descIdx = cursor.getColumnIndexOrThrow("description")
      val createdByIdx = cursor.getColumnIndexOrThrow("created_by_aci")
      val adminIdx = cursor.getColumnIndexOrThrow("admin_aci")
      val discussionIdx = cursor.getColumnIndexOrThrow("discussion_id")
      val createdAtIdx = cursor.getColumnIndexOrThrow("created_at")
      val lastActivityIdx = cursor.getColumnIndexOrThrow("last_activity_at")

      while (cursor.moveToNext()) {
        val ticketId = UUID.fromString(cursor.getString(idIdx))
        val createdBy = UUID.fromString(cursor.getString(createdByIdx))
        val admin = UUID.fromString(cursor.getString(adminIdx))
        tickets.add(
          Ticket(
            id = ticketId,
            number = cursor.getString(numberIdx),
            description = cursor.getString(descIdx),
            department = cursor.getString(deptIdx),
            location = cursor.getString(locIdx),
            priority = TicketPriority.fromServer(cursor.getString(priIdx)),
            status = TicketStatus.fromServer(cursor.getString(statusIdx)),
            createdAtMs = cursor.getLong(createdAtIdx),
            lastActivityMs = cursor.getLong(lastActivityIdx),
            createdByAci = createdBy,
            createdByName = nameResolver(createdBy),
            administratorAci = admin,
            discussionId = UUID.fromString(cursor.getString(discussionIdx)),
            participants = getMembers(ticketId, nameResolver),
            messages = getMessages(ticketId, nameResolver, selfAci)
          )
        )
      }
    }
    return tickets
  }

  fun getTicket(
    ticketId: UUID,
    nameResolver: (UUID) -> String,
    selfAci: UUID
  ): Ticket? {
    return getAllTickets(nameResolver, selfAci).firstOrNull { it.id == ticketId }
  }

  private fun getMembers(ticketId: UUID, nameResolver: (UUID) -> String): List<TicketParticipant> {
    val members = mutableListOf<TicketParticipant>()
    readableDatabase.query(
      "ticket_member",
      arrayOf("aci", "role"),
      "ticket_id = ?",
      arrayOf(ticketId.toString()),
      null,
      null,
      "joined_at ASC"
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val aci = UUID.fromString(cursor.getString(0))
        members.add(
          TicketParticipant(
            aci = aci,
            displayName = nameResolver(aci),
            role = TicketRole.fromServer(cursor.getString(1))
          )
        )
      }
    }
    return members
  }

  private fun getMessages(
    ticketId: UUID,
    nameResolver: (UUID) -> String,
    selfAci: UUID
  ): List<TicketMessage> {
    val messages = mutableListOf<TicketMessage>()
    readableDatabase.query(
      "ticket_message",
      arrayOf("message_id", "author_aci", "body", "timestamp_ms", "is_from_self", "attachment_json"),
      "ticket_id = ?",
      arrayOf(ticketId.toString()),
      null,
      null,
      "timestamp_ms ASC"
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val author = UUID.fromString(cursor.getString(1))
        val attachmentJson = if (cursor.isNull(5)) null else cursor.getString(5)
        messages.add(
          TicketMessage(
            id = cursor.getString(0),
            authorAci = author,
            authorName = nameResolver(author),
            body = cursor.getString(2),
            timestampMs = cursor.getLong(3),
            isFromSelf = cursor.getInt(4) == 1 || author == selfAci,
            attachments = TicketAttachment.fromJson(attachmentJson)
          )
        )
      }
    }
    return messages
  }

  companion object {
    private const val DB_NAME = "signal_tickets.db"
    private const val DB_VERSION = 2

    @Volatile
    private var instance: TicketDatabase? = null

    fun getInstance(context: Context): TicketDatabase {
      return instance ?: synchronized(this) {
        instance ?: TicketDatabase(context.applicationContext).also { instance = it }
      }
    }
  }
}
