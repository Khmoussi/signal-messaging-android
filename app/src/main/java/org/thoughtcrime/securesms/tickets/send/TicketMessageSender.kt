/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.send

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import java.io.IOException
import java.util.UUID

/**
 * Builds per-destination ciphertext (optional attachments) and sends via the ticket-aware endpoint.
 */
object TicketMessageSender {

  private val TAG = Log.tag(TicketMessageSender::class.java)

  fun send(
    ticketId: UUID,
    body: String,
    timestamp: Long = System.currentTimeMillis(),
    attachmentPaths: List<String> = emptyList()
  ) {
    val ticket = TicketsRepository.getTicket(ticketId)
      ?: throw IllegalStateException("Ticket not found: $ticketId")

    if (ticket.status != TicketStatus.OPEN) {
      throw IllegalStateException("Ticket is closed")
    }

    val trimmed = body.trim()
    if (trimmed.isEmpty() && attachmentPaths.isEmpty()) {
      Log.w(TAG, "Nothing to send for ticket $ticketId")
      return
    }

    val selfAci = SignalStore.account.requireAci()
    val destinations = ticket.participants
      .map { it.aci }
      .filter { it != selfAci.rawUuid }
      .distinct()

    if (destinations.isEmpty()) {
      Log.i(TAG, "No remote destinations for ticket $ticketId; local-only")
      return
    }

    val messageSender = AppDependencies.signalServiceMessageSender
    val cdnService = CdnService(AppDependencies.signalRestClient, AppDependencies.attachmentApi)
    val uploaded = attachmentPaths.map { path ->
      val file = File(path)
      if (!file.isFile) {
        throw IOException("Missing attachment file: $path")
      }
      val isVoiceNote = file.name.contains("voice-note", ignoreCase = true)
      val contentType = when {
        isVoiceNote -> MediaUtil.AUDIO_AAC
        else -> MediaUtil.getMimeType(AppDependencies.application, Uri.fromFile(file))
          ?: guessMimeFromName(file.name)
          ?: "application/octet-stream"
      }
      val ciphertextLength = AttachmentCipherStreamUtil.getCiphertextLength(
        PaddingInputStream.getPaddedSize(file.length())
      )
      val uploadSpec = cdnService.getResumableUploadSpecBlocking(ciphertextLength)
      val pointer = FileInputStream(file).use { input ->
        val stream = SignalServiceAttachment.newStreamBuilder()
          .withStream(input)
          .withContentType(contentType)
          .withLength(file.length())
          .withFileName(if (isVoiceNote) "voice-note.aac" else file.name.substringAfter('_', file.name))
          .withVoiceNote(isVoiceNote)
          .withUploadTimestamp(timestamp)
          .withResumableUploadSpec(uploadSpec)
          .build()
        messageSender.uploadAttachment(stream)
      }
      TicketAttachment.fromPointer(pointer, localPath = path) to pointer
    }
    val pointers = uploaded.map { it.second }
    val persistedAttachments = uploaded.map { it.first }

    if (persistedAttachments.isNotEmpty()) {
      TicketsRepository.updateMessageAttachments(ticketId, timestamp, persistedAttachments)
    }

    val dataMessageBuilder = SignalServiceDataMessage.newBuilder()
      .withTimestamp(timestamp)
      .withTicketId(ticketId.toString())

    if (trimmed.isNotEmpty()) {
      dataMessageBuilder.withBody(trimmed)
    }
    if (pointers.isNotEmpty()) {
      dataMessageBuilder.withAttachments(pointers)
    }

    val dataMessage = dataMessageBuilder.build()
    val bundles = destinations.map { destAci ->
      val address = SignalServiceAddress(ACI.from(destAci))
      runCatching {
        RecipientUtil.getEligibleForSending(listOf(Recipient.externalPush(ACI.from(destAci))))
      }
      val encrypted = messageSender.encryptDataMessage(address, dataMessage, false, true)
      TicketMessageRequest.DestinationBundle(
        aci = destAci,
        messages = encrypted.messages
      )
    }

    NetworkResultUtil.toBasicLegacy(
      SignalNetwork.tickets.sendMessages(
        ticketId,
        TicketMessageRequest(
          destinations = bundles,
          online = false,
          urgent = true,
          timestamp = timestamp
        )
      )
    )
  }

  /**
   * Copies a content [Uri] into app cache so [TicketSendJob] can upload after the picker closes.
   */
  @Throws(IOException::class)
  fun copyUriToCache(context: Context, uri: Uri): Pair<File, TicketAttachment> {
    val dir = File(context.filesDir, "ticket_attach").also { it.mkdirs() }
    val displayName = queryDisplayName(context, uri) ?: "attachment"
    val mime = context.contentResolver.getType(uri) ?: MediaUtil.getMimeType(context, uri) ?: "application/octet-stream"
    val out = File(dir, "${UUID.randomUUID()}_$displayName")
    context.contentResolver.openInputStream(uri)?.use { input ->
      out.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IOException("Unable to open $uri")

    if (out.length() <= 0L) {
      out.delete()
      throw IOException("Empty attachment")
    }

    val placeholder = TicketAttachment(
      contentType = mime,
      fileName = displayName,
      size = out.length(),
      cdn = 2,
      remoteId = "",
      keyBase64 = null,
      digestBase64 = null,
      localPath = out.absolutePath
    )
    return out to placeholder
  }

  private fun queryDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        cursor.getString(0)
      } else {
        null
      }
    }
  }

  private fun guessMimeFromName(name: String): String? {
    val lower = name.lowercase()
    return when {
      lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
      lower.endsWith(".png") -> "image/png"
      lower.endsWith(".webp") -> "image/webp"
      lower.endsWith(".gif") -> "image/gif"
      lower.endsWith(".heic") -> "image/heic"
      lower.endsWith(".aac") || lower.endsWith(".m4a") -> MediaUtil.AUDIO_AAC
      else -> null
    }
  }
}
