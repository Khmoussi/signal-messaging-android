/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.contacts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.core.util.logging.Log
import org.signal.network.NetworkResult
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.recipients.Recipient
import org.whispersystems.signalservice.api.directory.LocalDirectoryEntry
import java.io.IOException
import java.util.UUID

/**
 * Self-host MVP: load registered users from chat `/v1/directory/local/search`
 * and ensure each has a local recipient marked registered.
 */
object NetworkDirectoryRepository {

  private val TAG = Log.tag(NetworkDirectoryRepository::class.java)

  data class DirectoryUser(
    val aci: UUID,
    val pni: UUID,
    val e164: String,
    val displayName: String
  )

  fun isEnabled(): Boolean = BuildConfig.USE_SELF_HOSTED_SIGNAL_SERVER

  @Throws(IOException::class)
  suspend fun search(query: String? = null, excludeAcis: Set<UUID> = emptySet()): List<DirectoryUser> {
    if (!isEnabled()) {
      return emptyList()
    }

    return withContext(Dispatchers.IO) {
      when (val result = SignalNetwork.localDirectory.search(query)) {
        is NetworkResult.Success -> result.result.results
          .asSequence()
          .filter { it.aci !in excludeAcis }
          .filter { it.aci != SignalStore.account.aci?.rawUuid }
          .map { entry -> toDirectoryUser(entry) }
          .toList()

        is NetworkResult.StatusCodeError -> {
          Log.w(TAG, "Directory search failed", result.exception)
          throw result.exception
        }

        is NetworkResult.NetworkError -> {
          Log.w(TAG, "Directory search network error", result.exception)
          throw result.exception
        }

        is NetworkResult.ApplicationError -> {
          Log.w(TAG, "Directory search application error", result.throwable)
          throw IOException(result.throwable)
        }
      }
    }
  }

  private fun toDirectoryUser(entry: LocalDirectoryEntry): DirectoryUser {
    val recipient = Recipient.trustedPush(
      ACI.from(entry.aci),
      PNI.from(entry.pni),
      entry.e164
    )
    // trustedPush marks registered when needed; ensure e164 association is discoverable for pickers.
    SignalDatabase.recipients.markRegistered(recipient.id, ACI.from(entry.aci))

    val display = recipient.getDisplayName(org.thoughtcrime.securesms.dependencies.AppDependencies.application)
      .ifBlank { entry.e164 }

    return DirectoryUser(
      aci = entry.aci,
      pni = entry.pni,
      e164 = entry.e164,
      displayName = display
    )
  }
}
