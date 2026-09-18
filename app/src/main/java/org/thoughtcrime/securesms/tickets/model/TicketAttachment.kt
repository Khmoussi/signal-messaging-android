/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.model

import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.util.Base64
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId
import java.util.Optional

/**
 * Lightweight attachment metadata for ticket discussion (pointers + optional local preview path).
 */
data class TicketAttachment(
  val contentType: String,
  val fileName: String?,
  val size: Long,
  val cdn: Int,
  val remoteId: String,
  val keyBase64: String?,
  val digestBase64: String?,
  val localPath: String? = null,
  val voiceNote: Boolean = false
) {
  val isAudio: Boolean
    get() = voiceNote || contentType.startsWith("audio/", ignoreCase = true)

  val isImage: Boolean
    get() = !isAudio && (
      contentType.startsWith("image/", ignoreCase = true) ||
        fileName?.let { name ->
          name.endsWith(".jpg", true) ||
            name.endsWith(".jpeg", true) ||
            name.endsWith(".png", true) ||
            name.endsWith(".webp", true) ||
            name.endsWith(".gif", true) ||
            name.endsWith(".heic", true)
        } == true
      )

  fun toPointer(): SignalServiceAttachmentPointer? {
    val key = keyBase64?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
    val digest = digestBase64?.let { runCatching { Base64.decode(it) }.getOrNull() }
    return SignalServiceAttachmentPointer(
      cdn,
      SignalServiceAttachmentRemoteId.from(remoteId, cdn),
      contentType,
      key,
      Optional.of(size.toInt().coerceAtLeast(0)),
      Optional.empty(),
      0,
      0,
      Optional.ofNullable(digest),
      Optional.empty(),
      0,
      Optional.ofNullable(fileName),
      voiceNote,
      false,
      contentType.equals("image/gif", ignoreCase = true),
      Optional.empty(),
      Optional.empty(),
      0L,
      null
    )
  }

  companion object {
    fun fromPointer(pointer: SignalServiceAttachmentPointer, localPath: String? = null): TicketAttachment {
      return TicketAttachment(
        contentType = pointer.contentType ?: "application/octet-stream",
        fileName = pointer.fileName.orElse(null),
        size = pointer.size.orElse(0).toLong(),
        cdn = pointer.cdnNumber,
        remoteId = pointer.remoteId.toString(),
        keyBase64 = pointer.key?.let { Base64.encodeWithPadding(it) },
        digestBase64 = pointer.digest.orElse(null)?.let { Base64.encodeWithPadding(it) },
        localPath = localPath,
        voiceNote = pointer.voiceNote
      )
    }

    fun toJson(attachments: List<TicketAttachment>): String {
      val array = JSONArray()
      attachments.forEach { a ->
        array.put(
          JSONObject().apply {
            put("contentType", a.contentType)
            put("fileName", a.fileName)
            put("size", a.size)
            put("cdn", a.cdn)
            put("remoteId", a.remoteId)
            put("keyBase64", a.keyBase64)
            put("digestBase64", a.digestBase64)
            put("localPath", a.localPath)
            put("voiceNote", a.voiceNote)
          }
        )
      }
      return array.toString()
    }

    fun fromJson(json: String?): List<TicketAttachment> {
      if (json.isNullOrBlank()) return emptyList()
      return runCatching {
        val array = JSONArray(json)
        buildList {
          for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val contentType = o.optString("contentType", "application/octet-stream")
            val fileName = o.optString("fileName", null).takeIf { !it.isNullOrBlank() && it != "null" }
            add(
              TicketAttachment(
                contentType = contentType,
                fileName = fileName,
                size = o.optLong("size", 0L),
                cdn = o.optInt("cdn", 2),
                remoteId = o.optString("remoteId", ""),
                keyBase64 = o.optString("keyBase64", null).takeIf { !it.isNullOrBlank() && it != "null" },
                digestBase64 = o.optString("digestBase64", null).takeIf { !it.isNullOrBlank() && it != "null" },
                localPath = o.optString("localPath", null).takeIf { !it.isNullOrBlank() && it != "null" },
                voiceNote = o.optBoolean("voiceNote", false) ||
                  (fileName?.contains("voice-note", ignoreCase = true) == true)
              )
            )
          }
        }
      }.getOrDefault(emptyList())
    }
  }
}
