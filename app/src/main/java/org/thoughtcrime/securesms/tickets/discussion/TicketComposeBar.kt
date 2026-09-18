/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.discussion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.models.media.Media
import org.signal.core.util.ServiceUtil
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.AttachmentKeyboardButton
import org.thoughtcrime.securesms.conversation.MessageSendType
import org.thoughtcrime.securesms.mediasend.MediaSendActivityResult
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionActivity
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tickets.model.TicketAttachment
import org.thoughtcrime.securesms.tickets.send.TicketMessageSender
import org.signal.core.ui.R as CoreUiR

/**
 * Ticket discussion compose bar modeled on 1:1 [org.thoughtcrime.securesms.components.InputPanel]:
 * rounded bubble, emoji affordance, camera + hold-to-record mic when empty, circular + / send toggle,
 * and gallery / file / camera attachment entry points.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketComposeBar(
  enabled: Boolean,
  emojiInserts: SharedFlow<String>,
  onEmojiToggle: () -> Unit,
  onSendMessage: (String, List<TicketAttachment>) -> Unit,
  modifier: Modifier = Modifier
) {
  if (!enabled) {
    Text(
      text = stringResource(R.string.TicketDiscussion__read_only),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.background)
        .navigationBarsPadding()
        .padding(16.dp)
    )
    return
  }

  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val keyboard = LocalSoftwareKeyboardController.current
  val density = LocalDensity.current
  val cancelThresholdPx = with(density) { 64.dp.toPx() }

  var messageDraft by remember { mutableStateOf("") }
  var pendingAttachments by remember { mutableStateOf<List<TicketAttachment>>(emptyList()) }
  var showAttachSheet by remember { mutableStateOf(false) }
  var resolvingMedia by remember { mutableStateOf(false) }
  var isRecording by remember { mutableStateOf(false) }
  var recordingStartedAt by remember { mutableLongStateOf(0L) }
  var recordingElapsedMs by remember { mutableLongStateOf(0L) }
  var dragOffsetX by remember { mutableFloatStateOf(0f) }
  var willCancel by remember { mutableStateOf(false) }

  val voiceRecorder = remember {
    TicketVoiceNoteRecorder(
      context = context,
      onRecorded = { attachment ->
        onSendMessage("", listOf(attachment))
      },
      onError = {
        TicketVoiceNoteRecorder.showUnableToRecord(context)
      }
    )
  }
  DisposableEffect(Unit) {
    onDispose { voiceRecorder.dispose() }
  }

  LaunchedEffect(isRecording) {
    if (!isRecording) return@LaunchedEffect
    while (isActive && isRecording) {
      recordingElapsedMs = SystemClock.elapsedRealtime() - recordingStartedAt
      delay(100)
    }
  }

  LaunchedEffect(emojiInserts) {
    emojiInserts.collect { emoji ->
      messageDraft += emoji
    }
  }

  val hasDraft = messageDraft.isNotBlank() || pendingAttachments.isNotEmpty()
  val canSend = hasDraft && !resolvingMedia && !isRecording

  fun clearAndSend(body: String, attachments: List<TicketAttachment>) {
    if (body.isBlank() && attachments.isEmpty()) return
    onSendMessage(body, attachments)
    messageDraft = ""
    pendingAttachments = emptyList()
  }

  fun handleMediaResult(result: MediaSendActivityResult?) {
    if (result == null) return
    resolvingMedia = true
    scope.launch {
      val attachments = withContext(Dispatchers.IO) {
        result.toTicketAttachments(context)
      }
      resolvingMedia = false
      if (attachments.isEmpty() && result.body.isBlank()) return@launch
      clearAndSend(result.body, attachments)
    }
  }

  fun beginVoiceRecording() {
    val audioManager = ServiceUtil.getAudioManager(context)
    val mode = audioManager.mode
    if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
      Toast.makeText(context, R.string.ConversationActivity_unable_to_record_audio, Toast.LENGTH_LONG).show()
      return
    }
    recordingStartedAt = SystemClock.elapsedRealtime()
    recordingElapsedMs = 0L
    dragOffsetX = 0f
    willCancel = false
    isRecording = true
    voiceRecorder.start()
    runCatching {
      @Suppress("DEPRECATION")
      ServiceUtil.getVibrator(context).vibrate(20)
    }
  }

  fun endVoiceRecording(send: Boolean) {
    if (!isRecording) return
    val elapsed = SystemClock.elapsedRealtime() - recordingStartedAt
    isRecording = false
    dragOffsetX = 0f
    willCancel = false
    when {
      !send || elapsed < 1_000L -> {
        voiceRecorder.cancel()
        if (send && elapsed < 1_000L) {
          Toast.makeText(
            context,
            R.string.InputPanel_tap_and_hold_to_record_a_voice_message_release_to_send,
            Toast.LENGTH_SHORT
          ).show()
        }
      }
      else -> voiceRecorder.finishAndSend()
    }
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (!granted) {
      Toast.makeText(
        context,
        R.string.ConversationActivity_signal_needs_microphone_access_voice_message,
        Toast.LENGTH_LONG
      ).show()
    }
  }

  val galleryLauncher = rememberLauncherForActivityResult(TicketMediaGalleryContract()) { handleMediaResult(it) }
  val cameraLauncher = rememberLauncherForActivityResult(TicketMediaCameraContract()) { handleMediaResult(it) }
  val fileLauncher = rememberLauncherForActivityResult(TicketOpenDocumentsContract()) { uris ->
    if (uris.isEmpty()) return@rememberLauncherForActivityResult
    resolvingMedia = true
    scope.launch {
      val copied = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
          runCatching { TicketMessageSender.copyUriToCache(context, uri).second }.getOrNull()
        }
      }
      pendingAttachments = pendingAttachments + copied
      resolvingMedia = false
    }
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.background)
      .navigationBarsPadding()
      .imePadding()
      .padding(top = 4.dp, bottom = 4.dp)
  ) {
    if (pendingAttachments.isNotEmpty() && !isRecording) {
      LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 4.dp)
      ) {
        items(pendingAttachments, key = { it.localPath ?: it.fileName ?: it.hashCode().toString() }) { attachment ->
          Text(
            text = attachment.fileName ?: stringResource(R.string.TicketDiscussion__attachment),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
              .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
              .clickable {
                pendingAttachments = pendingAttachments.filterNot { it === attachment }
              }
              .padding(horizontal = 10.dp, vertical = 6.dp)
          )
        }
      }
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 12.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.Bottom
    ) {
      Row(
        modifier = Modifier
          .weight(1f)
          .heightIn(min = 44.dp)
          .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
          .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        if (isRecording) {
          Spacer(modifier = Modifier.width(12.dp))
          Box(
            modifier = Modifier
              .size(10.dp)
              .background(
                if (willCancel) MaterialTheme.colorScheme.error else Color(0xFFE15C5C),
                CircleShape
              )
          )
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = formatElapsed(recordingElapsedMs),
            style = MaterialTheme.typography.titleMedium,
            color = if (willCancel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
          )
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = stringResource(
              if (willCancel) {
                R.string.TicketDiscussion__release_to_cancel
              } else {
                R.string.TicketDiscussion__slide_to_cancel
              }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
          )
        } else {
          IconButton(
            onClick = {
              keyboard?.hide()
              onEmojiToggle()
            },
            modifier = Modifier.size(44.dp)
          ) {
            Icon(
              imageVector = ImageVector.vectorResource(R.drawable.ic_emoji),
              contentDescription = stringResource(R.string.conversation_activity__emoji_toggle_description),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(24.dp)
            )
          }

          Box(
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 44.dp)
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
          ) {
            if (messageDraft.isEmpty()) {
              Text(
                text = stringResource(R.string.TicketDiscussion__message_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            BasicTextField(
              value = messageDraft,
              onValueChange = { messageDraft = it },
              modifier = Modifier.fillMaxWidth(),
              textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
              ),
              cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
              maxLines = 5
            )
          }

          if (!hasDraft) {
            IconButton(
              onClick = { cameraLauncher.launch(messageDraft) },
              modifier = Modifier.size(44.dp)
            ) {
              Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_camera_24),
                contentDescription = stringResource(R.string.conversation_activity__quick_attachment_drawer_toggle_camera_description),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
              )
            }
          } else {
            IconButton(
              onClick = { showAttachSheet = true },
              modifier = Modifier.size(44.dp)
            ) {
              Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_plus_24),
                contentDescription = stringResource(R.string.ConversationActivity_add_attachment),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
              )
            }
          }
        }

        // Keep mic mounted during recording so the hold gesture isn't cancelled by recomposition.
        if (!hasDraft || isRecording) {
          Box(
            modifier = Modifier
              .size(if (isRecording) 48.dp else 44.dp)
              .pointerInput(cancelThresholdPx) {
                awaitEachGesture {
                  val down = awaitFirstDown(requireUnconsumed = false)
                  val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                  ) == PackageManager.PERMISSION_GRANTED
                  if (!granted) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    waitForUpOrCancellation()
                    return@awaitEachGesture
                  }

                  beginVoiceRecording()
                  val startX = down.position.x
                  while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val cancelled = (change.position.x - startX) < -cancelThresholdPx
                    dragOffsetX = change.position.x - startX
                    willCancel = cancelled
                    if (!change.pressed) {
                      change.consume()
                      endVoiceRecording(send = !cancelled)
                      break
                    }
                    change.consume()
                  }
                }
              },
            contentAlignment = Alignment.Center
          ) {
            Box(
              modifier = Modifier
                .size(if (isRecording) 48.dp else 24.dp)
                .then(
                  if (isRecording) {
                    Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                  } else {
                    Modifier
                  }
                ),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_mic_24),
                contentDescription = stringResource(R.string.conversation_activity__quick_attachment_drawer_record_and_send_audio_description),
                tint = if (isRecording) {
                  MaterialTheme.colorScheme.onPrimary
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
              )
            }
          }
        }
      }

      if (!isRecording) {
        Spacer(modifier = Modifier.width(8.dp))

        Box(
          modifier = Modifier
            .padding(bottom = 2.dp)
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clickable(enabled = !resolvingMedia) {
              if (canSend) {
                clearAndSend(messageDraft.trim(), pendingAttachments)
              } else {
                showAttachSheet = true
              }
            },
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = ImageVector.vectorResource(
              if (canSend) R.drawable.ic_send_unlock_24 else CoreUiR.drawable.symbol_plus_24
            ),
            contentDescription = stringResource(
              if (canSend) R.string.conversation_activity__send else R.string.ConversationActivity_add_attachment
            ),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(22.dp)
          )
        }
      }
    }
  }

  if (showAttachSheet) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
      onDismissRequest = { showAttachSheet = false },
      sheetState = sheetState
    ) {
      TicketAttachmentSheet(
        onGallery = {
          showAttachSheet = false
          galleryLauncher.launch(messageDraft)
        },
        onFile = {
          showAttachSheet = false
          fileLauncher.launch(Unit)
        },
        onCamera = {
          showAttachSheet = false
          cameraLauncher.launch(messageDraft)
        }
      )
      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}

private fun formatElapsed(elapsedMs: Long): String {
  val totalSec = (elapsedMs / 1000L).coerceAtLeast(0L)
  val min = totalSec / 60L
  val sec = totalSec % 60L
  return "%d:%02d".format(min, sec)
}

@Composable
private fun TicketAttachmentSheet(
  onGallery: () -> Unit,
  onFile: () -> Unit,
  onCamera: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp)
  ) {
    Text(
      text = stringResource(R.string.ConversationActivity_add_attachment),
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(bottom = 12.dp)
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly
    ) {
      AttachAction(
        titleRes = AttachmentKeyboardButton.GALLERY.titleRes,
        iconRes = AttachmentKeyboardButton.GALLERY.iconRes,
        onClick = onGallery
      )
      AttachAction(
        titleRes = AttachmentKeyboardButton.FILE.titleRes,
        iconRes = AttachmentKeyboardButton.FILE.iconRes,
        onClick = onFile
      )
      AttachAction(
        titleRes = R.string.MediaSelectionActivity__camera,
        iconRes = R.drawable.ic_camera_24,
        onClick = onCamera
      )
    }
  }
}

@Composable
private fun AttachAction(
  titleRes: Int,
  iconRes: Int,
  onClick: () -> Unit
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .clickable(onClick = onClick)
      .padding(12.dp)
  ) {
    Box(
      modifier = Modifier
        .size(56.dp)
        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = ImageVector.vectorResource(iconRes),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(28.dp)
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = stringResource(titleRes),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface
    )
  }
}

private class TicketMediaGalleryContract : ActivityResultContract<CharSequence?, MediaSendActivityResult?>() {
  override fun createIntent(context: Context, input: CharSequence?): Intent {
    return MediaSelectionActivity.gallery(
      context,
      MessageSendType.SignalMessageSendType,
      emptyList(),
      Recipient.self().id,
      input,
      false
    )
  }

  override fun parseResult(resultCode: Int, intent: Intent?): MediaSendActivityResult? {
    return if (resultCode == Activity.RESULT_OK && intent != null) {
      MediaSendActivityResult.fromData(intent)
    } else {
      null
    }
  }
}

private class TicketMediaCameraContract : ActivityResultContract<CharSequence?, MediaSendActivityResult?>() {
  override fun createIntent(context: Context, input: CharSequence?): Intent {
    return MediaSelectionActivity.camera(
      context,
      MessageSendType.SignalMessageSendType,
      Recipient.self().id,
      false
    )
  }

  override fun parseResult(resultCode: Int, intent: Intent?): MediaSendActivityResult? {
    return if (resultCode == Activity.RESULT_OK && intent != null) {
      MediaSendActivityResult.fromData(intent)
    } else {
      null
    }
  }
}

private class TicketOpenDocumentsContract : ActivityResultContract<Unit, List<Uri>>() {
  override fun createIntent(context: Context, input: Unit): Intent {
    return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
      addCategory(Intent.CATEGORY_OPENABLE)
      type = "*/*"
      putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
  }

  override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
    if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
    val clip = intent.clipData
    if (clip != null && clip.itemCount > 0) {
      return buildList {
        for (i in 0 until clip.itemCount) {
          clip.getItemAt(i).uri?.let(::add)
        }
      }
    }
    return listOfNotNull(intent.data)
  }
}

private fun MediaSendActivityResult.toTicketAttachments(context: Context): List<TicketAttachment> {
  val mediaList: List<Media> = when {
    nonUploadedMedia.isNotEmpty() -> nonUploadedMedia
    else -> preUploadResults.map { it.media }
  }
  return mediaList.mapNotNull { media ->
    runCatching { TicketMessageSender.copyUriToCache(context, media.uri).second }.getOrNull()
  }
}
