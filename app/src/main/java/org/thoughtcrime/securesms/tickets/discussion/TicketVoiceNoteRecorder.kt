/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.discussion

import android.content.Context
import android.widget.Toast
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.audio.AudioRecorder
import org.thoughtcrime.securesms.components.voice.VoiceNoteDraft
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.tickets.model.TicketAttachment
import org.thoughtcrime.securesms.tickets.send.TicketMessageSender
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Hold-to-record session for ticket voice notes, reusing [AudioRecorder] (AAC) like 1:1 chat.
 */
class TicketVoiceNoteRecorder(
  context: Context,
  private val onRecorded: (TicketAttachment) -> Unit,
  private val onError: (Throwable) -> Unit = {}
) {
  private val appContext = context.applicationContext
  private val audioRecorder = AudioRecorder(appContext, null)

  private var disposable: Disposable? = null
  private var shouldSend = false
  private var active = false

  val isRecording: Boolean get() = active

  fun start() {
    if (active) return
    active = true
    shouldSend = false
    disposable?.dispose()
    disposable = audioRecorder.startRecording()
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe(
        { draft ->
          active = false
          if (shouldSend) {
            runCatching { draft.toTicketAttachment(appContext) }
              .onSuccess(onRecorded)
              .onFailure {
                Log.w(TAG, "Failed to persist voice note", it)
                discardDraft(draft)
                onError(it)
              }
          } else {
            discardDraft(draft)
          }
        },
        { error ->
          active = false
          Log.w(TAG, "Voice note recording failed", error)
          onError(error)
        }
      )
  }

  fun finishAndSend() {
    if (!active) return
    shouldSend = true
    audioRecorder.stopRecording()
  }

  fun cancel() {
    if (!active) return
    shouldSend = false
    val snapshot = audioRecorder.getCurrentRecordingSnapshot()
    audioRecorder.discardRecording()
    snapshot?.let(::discardDraft)
    active = false
    disposable?.dispose()
    disposable = null
  }

  fun dispose() {
    if (active) {
      cancel()
    }
    disposable?.dispose()
    disposable = null
  }

  private fun discardDraft(draft: VoiceNoteDraft) {
    runCatching { AppDependencies.blobs.delete(appContext, draft.uri) }
  }

  companion object {
    private val TAG = Log.tag(TicketVoiceNoteRecorder::class.java)

    @Throws(IOException::class)
    fun VoiceNoteDraft.toTicketAttachment(context: Context): TicketAttachment {
      val dir = File(context.filesDir, "ticket_attach").also { it.mkdirs() }
      val out = File(dir, "${UUID.randomUUID()}_voice-note.aac")
      // Draft blob URIs embed size=0; ContentResolver/BlobContentProvider rejects that.
      // Read the on-disk draft via BlobProvider like chat voice notes do.
      AppDependencies.blobs.getStream(context, uri).use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
      }

      if (out.length() <= 0L) {
        out.delete()
        throw IOException("Empty voice note")
      }

      AppDependencies.blobs.delete(context, uri)

      return TicketAttachment(
        contentType = MediaUtil.AUDIO_AAC,
        fileName = "voice-note.aac",
        size = out.length(),
        cdn = 2,
        remoteId = "",
        keyBase64 = null,
        digestBase64 = null,
        localPath = out.absolutePath,
        voiceNote = true
      )
    }

    fun showUnableToRecord(context: Context) {
      Toast.makeText(context, R.string.ConversationActivity_unable_to_record_audio, Toast.LENGTH_LONG).show()
    }
  }
}
