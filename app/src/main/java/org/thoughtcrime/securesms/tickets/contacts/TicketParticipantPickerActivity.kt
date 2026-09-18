/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import java.util.UUID

private val TICKET_PICKER_TAG = Log.tag(TicketParticipantPickerActivity::class.java)

/**
 * Ticket-only participant picker. Loads registered users from the self-host directory API
 * and does **not** reuse the Groups / RecipientPicker UI (avoids group member limits).
 */
class TicketParticipantPickerActivity : PassphraseRequiredActivity() {

  companion object {
    const val EXTRA_SELECTED_ACIS = "selected_acis"
    private const val EXTRA_EXCLUDE_ACIS = "exclude_acis"
    private const val EXTRA_SINGLE = "single"

    fun createIntent(
      context: Context,
      excludeAcis: Collection<UUID> = emptyList(),
      singleSelection: Boolean = false
    ): Intent {
      return Intent(context, TicketParticipantPickerActivity::class.java)
        .putExtra(EXTRA_EXCLUDE_ACIS, ArrayList(excludeAcis.map { it.toString() }))
        .putExtra(EXTRA_SINGLE, singleSelection)
    }
  }

  class Contract(
    private val excludeAcis: Collection<UUID> = emptyList(),
    private val singleSelection: Boolean = false
  ) : ActivityResultContract<Unit, List<UUID>>() {
    override fun createIntent(context: Context, input: Unit): Intent {
      return TicketParticipantPickerActivity.createIntent(context, excludeAcis, singleSelection)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<UUID> {
      if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
      return intent.getStringArrayListExtra(EXTRA_SELECTED_ACIS)
        ?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        .orEmpty()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState, ready)

    val exclude = intent.getStringArrayListExtra(EXTRA_EXCLUDE_ACIS)
      ?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
      .orEmpty()
      .toSet()
    val single = intent.getBooleanExtra(EXTRA_SINGLE, false)

    setContent {
      SignalTheme {
        TicketNetworkParticipantPickerScreen(
          excludeAcis = exclude,
          singleSelection = single,
          onCancel = { finish() },
          onDone = { acis ->
            setResult(
              Activity.RESULT_OK,
              Intent().putStringArrayListExtra(EXTRA_SELECTED_ACIS, ArrayList(acis.map { it.toString() }))
            )
            finish()
          }
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TicketNetworkParticipantPickerScreen(
  excludeAcis: Set<UUID>,
  singleSelection: Boolean,
  onCancel: () -> Unit,
  onDone: (List<UUID>) -> Unit
) {
  var query by remember { mutableStateOf("") }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }
  var users by remember { mutableStateOf(listOf<NetworkDirectoryRepository.DirectoryUser>()) }
  var selected by remember { mutableStateOf(setOf<UUID>()) }
  val scope = rememberCoroutineScope()
  var searchJob by remember { mutableStateOf<Job?>(null) }

  fun load(q: String?) {
    searchJob?.cancel()
    searchJob = scope.launch {
      loading = true
      error = null
      try {
        // Debounce typing; empty query lists everyone immediately on first load.
        if (!q.isNullOrBlank()) {
          delay(250)
        }
        users = NetworkDirectoryRepository.search(query = q, excludeAcis = excludeAcis)
      } catch (e: Exception) {
        Log.w(TICKET_PICKER_TAG, "Failed to load directory", e)
        error = e.message ?: "Failed to load users"
        users = emptyList()
      } finally {
        loading = false
      }
    }
  }

  LaunchedEffect(Unit) {
    load(null)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.TicketDiscussion__add_participant)) },
        navigationIcon = {
          TextButton(onClick = onCancel) {
            Text(stringResource(R.string.TicketParticipantPicker__cancel))
          }
        },
        actions = {
          if (!singleSelection) {
            TextButton(
              onClick = { onDone(selected.toList()) },
              enabled = selected.isNotEmpty()
            ) {
              Text(stringResource(R.string.TicketParticipantPicker__done))
            }
          }
        }
      )
    }
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 16.dp)
    ) {
      OutlinedTextField(
        value = query,
        onValueChange = {
          query = it
          load(it)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.TicketParticipantPicker__filter_optional)) },
        placeholder = { Text(stringResource(R.string.TicketParticipantPicker__filter_hint)) }
      )

      Spacer(modifier = Modifier.height(12.dp))

      when {
        loading && users.isEmpty() -> {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(24.dp),
            horizontalArrangement = Arrangement.Center
          ) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
          }
        }

        error != null && users.isEmpty() -> {
          Text(
            text = error ?: "",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
          )
        }

        users.isEmpty() -> {
          Text(
            text = stringResource(R.string.TicketParticipantPicker__no_users),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        else -> {
          LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(users, key = { it.aci }) { user ->
              val isSelected = user.aci in selected
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable {
                    if (singleSelection) {
                      onDone(listOf(user.aci))
                    } else {
                      selected = if (isSelected) selected - user.aci else selected + user.aci
                    }
                  }
                  .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                if (!singleSelection) {
                  Checkbox(
                    checked = isSelected,
                    onCheckedChange = {
                      selected = if (it) selected + user.aci else selected - user.aci
                    }
                  )
                }
                Column(modifier = Modifier.weight(1f)) {
                  Text(user.displayName, style = MaterialTheme.typography.bodyLarge)
                  Text(
                    user.e164,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
