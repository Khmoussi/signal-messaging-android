/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.send

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.tickets.model.TicketAttachment
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream.IntegrityCheck
import java.io.File

/**
 * Downloads a ticket attachment pointer to cache and opens it with a viewer intent.
 */
object TicketAttachmentOpener {

  private val TAG = Log.tag(TicketAttachmentOpener::class.java)
  private val mainHandler = Handler(Looper.getMainLooper())

  fun open(context: Context, attachment: TicketAttachment) {
    val appContext = context.applicationContext
    val file = ensureLocalFile(appContext, attachment)
    viewFile(appContext, file, attachment.contentType)
  }

  /**
   * Returns a local file for [attachment], downloading from CDN when needed.
   */
  fun ensureLocalFile(context: Context, attachment: TicketAttachment): File {
    val local = attachment.localPath?.let { File(it) }?.takeIf { it.isFile }
    if (local != null) {
      return local
    }

    val pointer = attachment.toPointer()
      ?: throw IllegalStateException("Incomplete attachment metadata")

    val digest = pointer.digest.orElse(null)
      ?: throw IllegalStateException("Missing attachment digest")

    val cipherFile = File(context.cacheDir, "ticket_cipher_${System.currentTimeMillis()}")
    val plainFile = File(context.cacheDir, "ticket_view_${System.currentTimeMillis()}")
    val maxSize = maxOf(attachment.size, 1L) * 2 + (1024L * 1024L)

    AppDependencies.signalServiceMessageReceiver.retrieveAttachment(
      pointer,
      cipherFile,
      maxSize,
      IntegrityCheck.forEncryptedDigest(digest)
    ).use { input ->
      plainFile.outputStream().use { output -> input.copyTo(output) }
    }

    return plainFile
  }

  private fun viewFile(context: Context, file: File, contentType: String) {
    val uri: Uri = try {
      FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileprovider", file)
    } catch (e: Exception) {
      Log.w(TAG, "FileProvider failed for ${file.absolutePath}", e)
      throw e
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, contentType.ifBlank { "*/*" })
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val launch = {
      context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      launch()
    } else {
      mainHandler.post(launch)
    }
  }
}
