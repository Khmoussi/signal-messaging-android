/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.discussion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.DropdownMenus
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.signal.glide.compose.GlideImage
import org.signal.glide.compose.GlideImageScaleType
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.jobs.TicketSendJob
import org.thoughtcrime.securesms.reactions.any.ReactWithAnyEmojiBottomSheetDialogFragment
import org.thoughtcrime.securesms.tickets.contacts.TicketParticipantPickerActivity
import org.thoughtcrime.securesms.tickets.data.TicketsRepository
import org.thoughtcrime.securesms.tickets.model.Ticket
import org.thoughtcrime.securesms.tickets.model.TicketAttachment
import org.thoughtcrime.securesms.tickets.model.TicketMessage
import org.thoughtcrime.securesms.tickets.model.TicketPriority
import org.thoughtcrime.securesms.tickets.model.TicketStatus
import org.thoughtcrime.securesms.tickets.send.TicketAttachmentOpener
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import org.signal.core.ui.R as CoreUiR

private val KadiGold = Color(0xFFC9A227)

class TicketDiscussionActivity :
  PassphraseRequiredActivity(),
  ReactWithAnyEmojiBottomSheetDialogFragment.Callback {

  companion object {
    private val TAG = Log.tag(TicketDiscussionActivity::class.java)
    private const val EXTRA_TICKET_ID = "ticket_id"
    private const val EMOJI_PICKER_TAG = "ticket_emoji_picker"

    fun createIntent(context: Context, ticketId: String): Intent {
      return Intent(context, TicketDiscussionActivity::class.java).putExtra(EXTRA_TICKET_ID, ticketId)
    }
  }

  private val emojiInserts = MutableSharedFlow<String>(extraBufferCapacity = 8)

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState, ready)

    val ticketIdString = intent.getStringExtra(EXTRA_TICKET_ID)
    val ticketId = ticketIdString?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (ticketId == null) {
      finish()
      return
    }

    setContent {
      SignalTheme {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val tickets by TicketsRepository.tickets.collectAsStateWithLifecycle()
        val ticket = tickets.firstOrNull { it.id == ticketId }

        LaunchedEffect(ticketId) {
          try {
            TicketsRepository.refreshTicket(ticketId)
          } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh ticket", e)
          }
        }

        if (ticket == null) {
          LaunchedEffect(Unit) {
            // Keep waiting briefly for local load; finish if still missing after refresh attempt.
            kotlinx.coroutines.delay(500)
            if (TicketsRepository.getTicket(ticketId) == null) {
              finish()
            }
          }
        } else {
          TicketDiscussionScreen(
            ticket = ticket,
            selfAci = TicketsRepository.selfAci(),
            emojiInserts = emojiInserts,
            onEmojiToggle = { showEmojiPicker() },
            onBack = { finish() },
            onClose = {
              scope.launch {
                try {
                  TicketsRepository.closeTicket(ticketId)
                } catch (e: Exception) {
                  Toast.makeText(context, e.message ?: getString(R.string.Tickets__error_generic), Toast.LENGTH_LONG).show()
                }
              }
            },
            onReopen = {
              scope.launch {
                try {
                  TicketsRepository.reopenTicket(ticketId)
                } catch (e: Exception) {
                  Toast.makeText(context, e.message ?: getString(R.string.Tickets__error_generic), Toast.LENGTH_LONG).show()
                }
              }
            },
            onTransfer = { adminAci ->
              scope.launch {
                try {
                  TicketsRepository.transferAdministration(ticketId, adminAci)
                } catch (e: Exception) {
                  Toast.makeText(context, e.message ?: getString(R.string.Tickets__error_generic), Toast.LENGTH_LONG).show()
                }
              }
            },
            onAddParticipant = { aci ->
              scope.launch {
                try {
                  TicketsRepository.addParticipant(ticketId, aci)
                } catch (e: Exception) {
                  Toast.makeText(context, e.message ?: getString(R.string.Tickets__error_generic), Toast.LENGTH_LONG).show()
                }
              }
            },
            onSendMessage = { body, pendingAttachments ->
              val timestamp = System.currentTimeMillis()
              val trimmed = body.trim()
              val paths = pendingAttachments.mapNotNull { it.localPath }
              TicketsRepository.insertLocalMessage(
                ticketId = ticketId,
                authorAci = TicketsRepository.selfAci(),
                body = trimmed,
                timestampMs = timestamp,
                isFromSelf = true,
                attachments = pendingAttachments
              )
              TicketSendJob.enqueue(ticketId, trimmed, timestamp, paths)
            }
          )
        }
      }
    }
  }

  private fun showEmojiPicker() {
    if (supportFragmentManager.findFragmentByTag(EMOJI_PICKER_TAG) != null) return
    ReactWithAnyEmojiBottomSheetDialogFragment.createForAboutSelection()
      .show(supportFragmentManager, EMOJI_PICKER_TAG)
  }

  override fun onReactWithAnyEmojiSelected(emoji: String) {
    emojiInserts.tryEmit(emoji)
  }

  override fun onReactWithAnyEmojiDialogDismissed() = Unit
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketDiscussionScreen(
  ticket: Ticket,
  selfAci: UUID,
  emojiInserts: SharedFlow<String>,
  onEmojiToggle: () -> Unit,
  onBack: () -> Unit,
  onClose: () -> Unit,
  onReopen: () -> Unit,
  onTransfer: (UUID) -> Unit,
  onAddParticipant: (UUID) -> Unit,
  onSendMessage: (String, List<TicketAttachment>) -> Unit
) {
  val isAdmin = ticket.isAdministrator(selfAci)
  val isOpen = ticket.status == TicketStatus.OPEN
  var showParticipants by remember { mutableStateOf(false) }
  var showTransfer by remember { mutableStateOf(false) }

  val invitePicker = rememberLauncherForActivityResult(
    TicketParticipantPickerActivity.Contract(
      excludeAcis = ticket.participants.map { it.aci },
      singleSelection = true
    )
  ) { acis ->
    acis.firstOrNull()?.let(onAddParticipant)
  }

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
              color = if (ticket.status == TicketStatus.OPEN) {
                MaterialTheme.colorScheme.primary
              } else {
                KadiGold
              }
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
                  invitePicker.launch(Unit)
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
      TicketComposeBar(
        enabled = isOpen,
        emojiInserts = emojiInserts,
        onEmojiToggle = onEmojiToggle,
        onSendMessage = onSendMessage
      )
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
            val adminSuffix = if (participant.aci == ticket.administratorAci) {
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
    val candidates = ticket.participants.filter { it.aci != selfAci }
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
                  onTransfer(participant.aci)
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
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
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
      if (message.body.isNotBlank()) {
        Text(text = message.body, style = MaterialTheme.typography.bodyMedium)
      }
      message.attachments.forEach { attachment ->
        Spacer(modifier = Modifier.height(6.dp))
        when {
          attachment.isAudio -> TicketVoiceNotePlayer(attachment = attachment)
          attachment.isImage -> TicketImageAttachment(
            attachment = attachment,
            onOpen = {
              scope.launch {
                runCatching {
                  withContext(Dispatchers.IO) {
                    TicketAttachmentOpener.open(context, attachment)
                  }
                }.onFailure {
                  Toast.makeText(
                    context,
                    context.getString(R.string.TicketDiscussion__download_failed),
                    Toast.LENGTH_LONG
                  ).show()
                }
              }
            }
          )
          else -> {
            Text(
              text = "📎 ${attachment.fileName ?: stringResource(R.string.TicketDiscussion__attachment)}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.clickable {
                scope.launch {
                  runCatching {
                    withContext(Dispatchers.IO) {
                      TicketAttachmentOpener.open(context, attachment)
                    }
                  }.onFailure {
                    Toast.makeText(
                      context,
                      context.getString(R.string.TicketDiscussion__download_failed),
                      Toast.LENGTH_LONG
                    ).show()
                  }
                }
              }
            )
          }
        }
      }
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
private fun TicketImageAttachment(
  attachment: TicketAttachment,
  onOpen: () -> Unit
) {
  val context = LocalContext.current
  var previewModel by remember(attachment.localPath, attachment.remoteId, attachment.keyBase64) {
    mutableStateOf<Any?>(attachment.localPath?.let { File(it) }?.takeIf { it.isFile })
  }

  LaunchedEffect(attachment.localPath, attachment.remoteId, attachment.keyBase64) {
    if (previewModel != null) return@LaunchedEffect
    previewModel = runCatching {
      withContext(Dispatchers.IO) {
        TicketAttachmentOpener.ensureLocalFile(context, attachment)
      }
    }.getOrNull()
  }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onOpen)
  ) {
    if (previewModel != null) {
      GlideImage(
        model = previewModel,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 120.dp, max = 240.dp),
        imageSize = DpSize(280.dp, 240.dp),
        scaleType = GlideImageScaleType.CENTER_CROP
      )
    } else {
      Text(
        text = "📎 ${attachment.fileName ?: stringResource(R.string.TicketDiscussion__attachment)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
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
