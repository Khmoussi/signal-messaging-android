/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.discussion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.compose.DropdownMenus
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.tickets.data.FakeTicketsRepository
import org.thoughtcrime.securesms.tickets.model.Ticket
import org.thoughtcrime.securesms.tickets.model.TicketMessage
import org.thoughtcrime.securesms.tickets.model.TicketPriority
import org.thoughtcrime.securesms.tickets.model.TicketStatus
import java.text.DateFormat
import java.util.Date
import org.signal.core.ui.R as CoreUiR

class TicketDiscussionActivity : PassphraseRequiredActivity() {
  companion object {
    private const val EXTRA_TICKET_ID = "ticket_id"

    fun createIntent(context: Context, ticketId: String): Intent {
      return Intent(context, TicketDiscussionActivity::class.java).putExtra(EXTRA_TICKET_ID, ticketId)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState, ready)

    val ticketId = intent.getStringExtra(EXTRA_TICKET_ID)
    if (ticketId.isNullOrBlank()) {
      finish()
      return
    }

    setContent {
      SignalTheme {
        val tickets by FakeTicketsRepository.tickets.collectAsStateWithLifecycle()
        val ticket = tickets.firstOrNull { it.id == ticketId }

        if (ticket == null) {
          LaunchedEffect(Unit) {
            finish()
          }
        } else {
          TicketDiscussionScreen(
            ticket = ticket,
            onBack = { finish() },
            onClose = { FakeTicketsRepository.closeTicket(ticketId) },
            onReopen = { FakeTicketsRepository.reopenTicket(ticketId) },
            onTransfer = { adminId -> FakeTicketsRepository.transferAdministration(ticketId, adminId) },
            onAddParticipant = { participantId -> FakeTicketsRepository.addParticipant(ticketId, participantId) },
            onSendMessage = { body -> FakeTicketsRepository.sendMessage(ticketId, body) }
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketDiscussionScreen(
  ticket: Ticket,
  onBack: () -> Unit,
  onClose: () -> Unit,
  onReopen: () -> Unit,
  onTransfer: (String) -> Unit,
  onAddParticipant: (String) -> Unit,
  onSendMessage: (String) -> Unit
) {
  val isAdmin = ticket.isAdministrator(FakeTicketsRepository.CURRENT_USER_ID)
  val isOpen = ticket.status == TicketStatus.OPEN
  var messageDraft by remember { mutableStateOf("") }
  var showParticipants by remember { mutableStateOf(false) }
  var showTransfer by remember { mutableStateOf(false) }
  var showAddParticipant by remember { mutableStateOf(false) }

  val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
  val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(ticket.number)
            Text(
              text = statusLabel(ticket.status),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
              contentDescription = stringResource(R.string.TicketDiscussion__back)
            )
          }
        },
        actions = {
          val controller = remember { DropdownMenus.MenuController() }
          IconButton(onClick = { controller.show() }) {
            Icon(
              imageVector = ImageVector.vectorResource(R.drawable.symbol_more_vertical),
              contentDescription = stringResource(R.string.TicketDiscussion__actions)
            )
          }
          DropdownMenus.Menu(controller = controller, offsetX = 24.dp) {
            DropdownMenus.Item(
              text = { Text(stringResource(R.string.TicketDiscussion__participants)) },
              onClick = {
                controller.hide()
                showParticipants = true
              }
            )
            if (isOpen) {
              DropdownMenus.Item(
                text = { Text(stringResource(R.string.TicketDiscussion__add_participant)) },
                onClick = {
                  controller.hide()
                  showAddParticipant = true
                }
              )
            }
            if (isAdmin && isOpen) {
              DropdownMenus.Item(
                text = { Text(stringResource(R.string.TicketDiscussion__close_ticket)) },
                onClick = {
                  controller.hide()
                  onClose()
                }
              )
              DropdownMenus.Item(
                text = { Text(stringResource(R.string.TicketDiscussion__transfer_admin)) },
                onClick = {
                  controller.hide()
                  showTransfer = true
                }
              )
            }
            if (isAdmin && !isOpen) {
              DropdownMenus.Item(
                text = { Text(stringResource(R.string.TicketDiscussion__reopen_ticket)) },
                onClick = {
                  controller.hide()
                  onReopen()
                }
              )
            }
          }
        }
      )
    },
    bottomBar = {
      if (isOpen) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = messageDraft,
            onValueChange = { messageDraft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.TicketDiscussion__message_hint)) }
          )
          TextButton(
            onClick = {
              onSendMessage(messageDraft)
              messageDraft = ""
            },
            enabled = messageDraft.isNotBlank()
          ) {
            Text(stringResource(R.string.TicketDiscussion__send))
          }
        }
      } else {
        Text(
          text = stringResource(R.string.TicketDiscussion__read_only),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
        )
      }
    }
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
    ) {
      TicketHeader(
        ticket = ticket,
        createdLabel = stringResource(
          R.string.TicketDiscussion__created_on,
          dateFormat.format(Date(ticket.createdAtMs))
        )
      )

      HorizontalDivider()

      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        items(ticket.messages, key = { it.id }) { message ->
          MessageBubble(message = message, timeFormat = timeFormat)
        }
      }
    }
  }

  if (showParticipants) {
    AlertDialog(
      onDismissRequest = { showParticipants = false },
      title = { Text(stringResource(R.string.TicketDiscussion__participants)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          ticket.participants.forEach { participant ->
            val adminSuffix = if (participant.id == ticket.administratorId) {
              " (${stringResource(R.string.TicketDiscussion__admin)})"
            } else {
              ""
            }
            Text("${participant.displayName}$adminSuffix")
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showParticipants = false }) {
          Text(stringResource(android.R.string.ok))
        }
      }
    )
  }

  if (showTransfer) {
    val candidates = ticket.participants.filter { it.id != FakeTicketsRepository.CURRENT_USER_ID }
    AlertDialog(
      onDismissRequest = { showTransfer = false },
      title = { Text(stringResource(R.string.TicketDiscussion__transfer_admin)) },
      text = {
        Column {
          if (candidates.isEmpty()) {
            Text(stringResource(R.string.TicketDiscussion__no_transfer_candidates))
          } else {
            candidates.forEach { participant ->
              TextButton(
                onClick = {
                  onTransfer(participant.id)
                  showTransfer = false
                },
                modifier = Modifier.fillMaxWidth()
              ) {
                Text(participant.displayName)
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showTransfer = false }) {
          Text(stringResource(R.string.NewTicketActivity__cancel))
        }
      }
    )
  }

  if (showAddParticipant) {
    val candidates = FakeTicketsRepository.AVAILABLE_PARTICIPANTS.filterNot { candidate ->
      ticket.participants.any { it.id == candidate.id }
    }
    AlertDialog(
      onDismissRequest = { showAddParticipant = false },
      title = { Text(stringResource(R.string.TicketDiscussion__add_participant)) },
      text = {
        Column {
          if (candidates.isEmpty()) {
            Text(stringResource(R.string.TicketDiscussion__no_more_participants))
          } else {
            candidates.forEach { participant ->
              TextButton(
                onClick = {
                  onAddParticipant(participant.id)
                  showAddParticipant = false
                },
                modifier = Modifier.fillMaxWidth()
              ) {
                Text(participant.displayName)
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showAddParticipant = false }) {
          Text(stringResource(R.string.NewTicketActivity__cancel))
        }
      }
    )
  }
}

@Composable
private fun TicketHeader(
  ticket: Ticket,
  createdLabel: String
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Text(ticket.description, style = MaterialTheme.typography.titleMedium)
    Text(
      text = stringResource(R.string.TicketDiscussion__meta, ticket.department, ticket.location, priorityLabel(ticket.priority)),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
      text = createdLabel,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun MessageBubble(
  message: TicketMessage,
  timeFormat: DateFormat
) {
  val alignment = if (message.isFromSelf) Alignment.CenterEnd else Alignment.CenterStart
  val bubbleColor = if (message.isFromSelf) {
    MaterialTheme.colorScheme.primaryContainer
  } else {
    MaterialTheme.colorScheme.surfaceVariant
  }

  Box(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier
        .align(alignment)
        .widthIn(max = 320.dp)
        .background(bubbleColor, RoundedCornerShape(16.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
      if (!message.isFromSelf) {
        Text(
          text = message.authorName,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(2.dp))
      }
      Text(text = message.body, style = MaterialTheme.typography.bodyMedium)
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = timeFormat.format(Date(message.timestampMs)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.End)
      )
    }
  }
}

@Composable
private fun statusLabel(status: TicketStatus): String {
  return when (status) {
    TicketStatus.OPEN -> stringResource(R.string.Tickets__status_open)
    TicketStatus.CLOSED -> stringResource(R.string.Tickets__status_closed)
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
