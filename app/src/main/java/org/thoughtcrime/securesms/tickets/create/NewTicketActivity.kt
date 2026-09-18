/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.create

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.tickets.contacts.TicketParticipantPickerActivity
import org.thoughtcrime.securesms.tickets.data.TicketsRepository
import org.thoughtcrime.securesms.tickets.discussion.TicketDiscussionActivity
import org.thoughtcrime.securesms.tickets.model.TicketParticipant
import org.thoughtcrime.securesms.tickets.model.TicketPriority
import java.util.UUID
import org.signal.core.ui.R as CoreUiR

class NewTicketActivity : PassphraseRequiredActivity() {
  companion object {
    private val TAG = Log.tag(NewTicketActivity::class.java)

    fun createIntent(context: Context): Intent {
      return Intent(context, NewTicketActivity::class.java)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState, ready)

    setContent {
      SignalTheme {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        var submitting by remember { mutableStateOf(false) }

        NewTicketScreen(
          submitting = submitting,
          onCancel = { finish() },
          onSubmit = { department, location, priority, description, participantAcis ->
            if (submitting) return@NewTicketScreen
            submitting = true
            scope.launch {
              try {
                val ticket = TicketsRepository.createTicket(
                  department = department,
                  location = location,
                  priority = priority,
                  description = description,
                  participantAcis = participantAcis
                )
                startActivity(TicketDiscussionActivity.createIntent(this@NewTicketActivity, ticket.idString))
                finish()
              } catch (e: Exception) {
                Log.w(TAG, "Failed to create ticket", e)
                Toast.makeText(context, e.message ?: getString(R.string.Tickets__error_generic), Toast.LENGTH_LONG).show()
                submitting = false
              }
            }
          }
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewTicketScreen(
  submitting: Boolean = false,
  onCancel: () -> Unit,
  onSubmit: (department: String, location: String, priority: TicketPriority, description: String, participantAcis: List<UUID>) -> Unit,
  departments: List<String> = TicketsRepository.DEPARTMENTS,
  locations: List<String> = TicketsRepository.LOCATIONS
) {
  var department by remember { mutableStateOf(departments.first()) }
  var location by remember { mutableStateOf(locations.first()) }
  var priority by remember { mutableStateOf(TicketPriority.MEDIUM) }
  var description by remember { mutableStateOf("") }
  var selectedParticipants by remember { mutableStateOf(listOf<TicketParticipant>()) }
  var showValidationError by remember { mutableStateOf(false) }
  val priorityLabels = TicketPriority.entries.associateWith { priorityLabel(it) }
  val priorityByLabel = priorityLabels.entries.associate { (key, value) -> value to key }

  val contactPicker = rememberLauncherForActivityResult(
    TicketParticipantPickerActivity.Contract(excludeAcis = emptyList(), singleSelection = false)
  ) { acis ->
    val added = acis.map { aci ->
      TicketParticipant(aci = aci, displayName = TicketsRepository.resolveName(aci))
    }
    selectedParticipants = (selectedParticipants + added).distinctBy { it.aci }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.NewTicketActivity__title)) },
        navigationIcon = {
          IconButton(onClick = onCancel) {
            Icon(
              imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
              contentDescription = stringResource(R.string.NewTicketActivity__cancel)
            )
          }
        },
        actions = {
          TextButton(onClick = onCancel) {
            Text(stringResource(R.string.NewTicketActivity__cancel))
          }
        }
      )
    }
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      DropdownField(
        label = stringResource(R.string.NewTicketActivity__department),
        value = department,
        options = departments,
        onSelected = { department = it }
      )

      DropdownField(
        label = stringResource(R.string.NewTicketActivity__location),
        value = location,
        options = locations,
        onSelected = { location = it }
      )

      DropdownField(
        label = stringResource(R.string.NewTicketActivity__priority),
        value = priorityLabels.getValue(priority),
        options = TicketPriority.entries.map { priorityLabels.getValue(it) },
        onSelected = { label ->
          priority = priorityByLabel[label] ?: TicketPriority.MEDIUM
        }
      )

      OutlinedTextField(
        value = description,
        onValueChange = {
          description = it
          showValidationError = false
        },
        label = { Text(stringResource(R.string.NewTicketActivity__description)) },
        isError = showValidationError && description.isBlank(),
        supportingText = if (showValidationError && description.isBlank()) {
          { Text(stringResource(R.string.NewTicketActivity__description_required)) }
        } else {
          null
        },
        minLines = 4,
        modifier = Modifier.fillMaxWidth()
      )

      Text(
        text = stringResource(R.string.NewTicketActivity__participants_optional),
        style = MaterialTheme.typography.titleSmall
      )

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        selectedParticipants.forEach { participant ->
          FilterChip(
            selected = true,
            onClick = {
              selectedParticipants = selectedParticipants.filterNot { it.aci == participant.aci }
            },
            label = { Text(participant.displayName) }
          )
        }
        FilterChip(
          selected = false,
          onClick = { contactPicker.launch(Unit) },
          label = { Text(stringResource(R.string.TicketDiscussion__add_participant)) }
        )
      }

      Text(
        text = stringResource(R.string.NewTicketActivity__attachments_placeholder),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
          .fillMaxWidth()
          .clickable { }
          .padding(vertical = 8.dp)
      )

      Spacer(modifier = Modifier.height(8.dp))

      Buttons.LargePrimary(
        onClick = {
          if (description.isBlank()) {
            showValidationError = true
          } else {
            onSubmit(
              department,
              location,
              priority,
              description,
              selectedParticipants.map { it.aci }
            )
          }
        },
        enabled = !submitting,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(stringResource(R.string.NewTicketActivity__submit))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
  label: String,
  value: String,
  options: List<String>,
  onSelected: (String) -> Unit
) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it }
  ) {
    OutlinedTextField(
      value = value,
      onValueChange = {},
      readOnly = true,
      label = { Text(label) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier
        .fillMaxWidth()
        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false }
    ) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { Text(option) },
          onClick = {
            onSelected(option)
            expanded = false
          }
        )
      }
    }
  }
}

@Composable
private fun priorityLabel(priority: TicketPriority): String {
  return when (priority) {
    TicketPriority.LOW -> stringResource(R.string.Tickets__priority_low)
    TicketPriority.MEDIUM -> stringResource(R.string.Tickets__priority_medium)
    TicketPriority.HIGH -> stringResource(R.string.Tickets__priority_high)
    TicketPriority.URGENT -> stringResource(R.string.Tickets__priority_urgent)
  }
}

@DayNightPreviews
@Composable
private fun NewTicketScreenPreview() {
  Previews.Preview {
    NewTicketScreen(onCancel = {}, onSubmit = { _, _, _, _, _ -> })
  }
}
