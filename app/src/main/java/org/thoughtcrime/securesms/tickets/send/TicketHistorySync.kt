/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.send

import android.net.Uri
import org.json.JSONObject
import org.signal.core.models.ServiceId.ACI
import org.signal.core.util.logging.Log
import org.signal.network.service.CdnService
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.tickets.data.TicketsRepository
import org.thoughtcrime.securesms.tickets.model.TicketAttachment
import org.thoughtcrime.securesms.tickets.model.TicketMessage
import org.thoughtcrime.securesms.tickets.model.TicketStatus
import org.thoughtcrime.securesms.util.MediaUtil
import org.whispersystems.signalservice.api.NetworkResultUtil
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.tickets.TicketMessageRequest
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/**
 * Catch-up sync so a newly invited ticket member receives prior E2E discussion history.
 * Server never stores chat bodies; an existing member must re-encrypt and fan out locally held messages.
 */
object TicketHistorySync {

  private val TAG = Log.tag(TicketHistorySync::class.java)

  private const val MARKER = "__th"
  private const val VERSION = 1

  data class Decoded(
    val authorAci: UUID,
    val body: String,
    val timestampMs: Long
  )

  fun encodeBody(authorAci: UUID, body: String, timestampMs: Long): String {
    return JSONObject()
      .put(MARKER, VERSION)
      .put("a", authorAci.toString())
      .put("b", body)
      .put("ts", timestampMs)
      .toString()
  }

  fun tryDecode(body: String): Decoded? {
    if (!body.startsWith("{") || !body.contains("\"$MARKER\"")) {
      return null
    }
    return runCatching {
      val json = JSONObject(body)
      if (json.optInt(MARKER, 0) != VERSION) return null
      Decoded(
        authorAci = UUID.fromString(json.getString("a")),
        body = json.optString("b", ""),
        timestampMs = json.getLong("ts")
      )
    }.getOrNull()
  }

  /**
   * Re-encrypts every locally cached message for [inviteeAci] only.
   */
  fun sendToInvitee(ticketId: UUID, inviteeAci: UUID) {
    val ticket = TicketsRepository.getTicket(ticketId)
      ?: throw IllegalStateException("Ticket not found: $ticketId")

    if (ticket.status != TicketStatus.OPEN) {
      Log.w(TAG, "Skip history sync; ticket $ticketId is closed")
      return
    }

    val selfAci = SignalStore.account.requireAci().rawUuid
    if (inviteeAci == selfAci) {
      return
    }

    val messages = ticket.messages
    if (messages.isEmpty()) {
      Log.i(TAG, "No local history to sync for $ticketId")
      return
    }

    Log.i(TAG, "Syncing ${messages.size} ticket messages to $inviteeAci for $ticketId")
    messages.forEach { message ->
      sendEntry(ticketId, inviteeAci, message)
    }
  }

  private fun sendEntry(ticketId: UUID, inviteeAci: UUID, message: TicketMessage) {
    val messageSender = AppDependencies.signalServiceMessageSender
    val pointers = resolvePointers(message.attachments, message.timestampMs)
    val encoded = encodeBody(message.authorAci, message.body, message.timestampMs)

    val dataMessageBuilder = SignalServiceDataMessage.newBuilder()
      .withTimestamp(message.timestampMs)
      .withTicketId(ticketId.toString())
      .withBody(encoded)

    if (pointers.isNotEmpty()) {
      dataMessageBuilder.withAttachments(pointers)
    }

    val dataMessage = dataMessageBuilder.build()
    val address = SignalServiceAddress(ACI.from(inviteeAci))
    runCatching {
      RecipientUtil.getEligibleForSending(listOf(Recipient.externalPush(ACI.from(inviteeAci))))
    }

    val encrypted = messageSender.encryptDataMessage(address, dataMessage, false, true)
    NetworkResultUtil.toBasicLegacy(
      SignalNetwork.tickets.sendMessages(
        ticketId,
        TicketMessageRequest(
          destinations = listOf(
            TicketMessageRequest.DestinationBundle(
              aci = inviteeAci,
              messages = encrypted.messages
            )
          ),
          online = false,
          urgent = false,
          timestamp = message.timestampMs
        )
      )
    )
  }

  private fun resolvePointers(
    attachments: List<TicketAttachment>,
    timestamp: Long
  ): List<SignalServiceAttachment> {
    if (attachments.isEmpty()) return emptyList()

    val messageSender = AppDependencies.signalServiceMessageSender
    val cdnService = CdnService(AppDependencies.signalRestClient, AppDependencies.attachmentApi)
    val result = mutableListOf<SignalServiceAttachment>()

    for (attachment in attachments) {
      val existing = attachment.toPointer()
      if (existing != null && attachment.remoteId.isNotBlank()) {
        result.add(existing)
        continue
      }

      val path = attachment.localPath ?: continue
      val file = File(path)
      if (!file.isFile || file.length() <= 0L) continue

      runCatching {
        val contentType = attachment.contentType.ifBlank {
          MediaUtil.getMimeType(AppDependencies.application, Uri.fromFile(file))
            ?: "application/octet-stream"
        }
        val ciphertextLength = AttachmentCipherStreamUtil.getCiphertextLength(
          PaddingInputStream.getPaddedSize(file.length())
        )
        val uploadSpec = cdnService.getResumableUploadSpecBlocking(ciphertextLength)
        FileInputStream(file).use { input ->
          val stream = SignalServiceAttachment.newStreamBuilder()
            .withStream(input)
            .withContentType(contentType)
            .withLength(file.length())
            .withFileName(attachment.fileName ?: file.name)
            .withVoiceNote(attachment.voiceNote)
            .withUploadTimestamp(timestamp)
            .withResumableUploadSpec(uploadSpec)
            .build()
          result.add(messageSender.uploadAttachment(stream))
        }
      }.onFailure { e ->
        Log.w(TAG, "Failed to re-upload attachment for history sync", e)
      }
    }

    return result
  }
}
