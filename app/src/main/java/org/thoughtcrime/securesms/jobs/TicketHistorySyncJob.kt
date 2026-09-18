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
import org.thoughtcrime.securesms.tickets.send.TicketHistorySync
import org.thoughtcrime.securesms.transport.RetryLaterException
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException
import org.whispersystems.signalservice.api.push.exceptions.ProofRequiredException
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * After a participant is invited, re-sends locally held ticket chat history to that ACI only.
 */
class TicketHistorySyncJob private constructor(
  parameters: Parameters,
  private val ticketId: String,
  private val inviteeAci: String
) : BaseJob(parameters) {

  companion object {
    const val KEY = "TicketHistorySyncJob"

    private val TAG = Log.tag(TicketHistorySyncJob::class.java)
    private const val KEY_TICKET_ID = "ticket_id"
    private const val KEY_INVITEE_ACI = "invitee_aci"

    @JvmStatic
    fun enqueue(ticketId: UUID, inviteeAci: UUID) {
      AppDependencies.jobManager.add(
        TicketHistorySyncJob(
          Parameters.Builder()
            .setQueue("TicketHistorySync_$ticketId")
            .addConstraint(NetworkConstraint.KEY)
            .setLifespan(TimeUnit.DAYS.toMillis(1))
            .setMaxAttempts(Parameters.UNLIMITED)
            .build(),
          ticketId.toString(),
          inviteeAci.toString()
        )
      )
    }
  }

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putString(KEY_TICKET_ID, ticketId)
      .putString(KEY_INVITEE_ACI, inviteeAci)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  override fun onRun() {
    TicketHistorySync.sendToInvitee(UUID.fromString(ticketId), UUID.fromString(inviteeAci))
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
    Log.w(TAG, "Failed to sync ticket history for $ticketId -> $inviteeAci")
  }

  class Factory : Job.Factory<TicketHistorySyncJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): TicketHistorySyncJob {
      val data = JsonJobData.deserialize(serializedData)
      return TicketHistorySyncJob(
        parameters,
        data.getString(KEY_TICKET_ID),
        data.getString(KEY_INVITEE_ACI)
      )
    }
  }
}
