/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.tickets.send.TicketMessageSender
import org.thoughtcrime.securesms.transport.RetryLaterException
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException
import org.whispersystems.signalservice.api.push.exceptions.ProofRequiredException
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Sends an encrypted ticket discussion message (text and/or attachments) via PUT /v1/tickets/{id}/messages.
 */
class TicketSendJob private constructor(
  parameters: Parameters,
  private val ticketId: String,
  private val body: String,
  private val timestamp: Long,
  private val attachmentPaths: List<String>
) : BaseJob(parameters) {

  companion object {
    const val KEY = "TicketSendJob"

    private val TAG = Log.tag(TicketSendJob::class.java)
    private const val KEY_TICKET_ID = "ticket_id"
    private const val KEY_BODY = "body"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_ATTACHMENT_PATHS = "attachment_paths"

    @JvmStatic
    fun enqueue(
      ticketId: UUID,
      body: String,
      timestamp: Long = System.currentTimeMillis(),
      attachmentPaths: List<String> = emptyList()
    ) {
      val trimmed = body.trim()
      if (trimmed.isEmpty() && attachmentPaths.isEmpty()) return

      AppDependencies.jobManager.add(
        TicketSendJob(
          Parameters.Builder()
            .setQueue("TicketSend_$ticketId")
            .addConstraint(NetworkConstraint.KEY)
            .setLifespan(TimeUnit.DAYS.toMillis(1))
            .setMaxAttempts(Parameters.UNLIMITED)
            .build(),
          ticketId.toString(),
          trimmed,
          timestamp,
          attachmentPaths
        )
      )
    }
  }

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putString(KEY_TICKET_ID, ticketId)
      .putString(KEY_BODY, body)
      .putLong(KEY_TIMESTAMP, timestamp)
      .putStringListAsArray(KEY_ATTACHMENT_PATHS, attachmentPaths)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  override fun onRun() {
    TicketMessageSender.send(UUID.fromString(ticketId), body, timestamp, attachmentPaths)
  }

  override fun onShouldRetry(e: Exception): Boolean {
    return when (e) {
      is ServerRejectedException -> false
      is ProofRequiredException -> false
      is UntrustedIdentityException -> false
      is RetryLaterException -> true
      is IOException -> true
      else -> e.cause is IOException
    }
  }

  override fun onFailure() {
    Log.w(TAG, "Failed to send ticket message for $ticketId")
  }

  class Factory : Job.Factory<TicketSendJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): TicketSendJob {
      val data = JsonJobData.deserialize(serializedData)
      val paths = if (data.hasStringArray(KEY_ATTACHMENT_PATHS)) {
        data.getStringArrayAsList(KEY_ATTACHMENT_PATHS)
      } else {
        emptyList()
      }
      return TicketSendJob(
        parameters,
        data.getString(KEY_TICKET_ID),
        data.getString(KEY_BODY),
        data.getLong(KEY_TIMESTAMP),
        paths
      )
    }
  }
}
