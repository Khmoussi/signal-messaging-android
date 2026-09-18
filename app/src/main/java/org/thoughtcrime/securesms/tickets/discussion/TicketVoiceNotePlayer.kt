/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.discussion

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.tickets.model.TicketAttachment
import org.thoughtcrime.securesms.tickets.send.TicketAttachmentOpener

/**
 * Minimal play/pause control for ticket voice notes.
 */
@Composable
fun TicketVoiceNotePlayer(
  attachment: TicketAttachment,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var isPlaying by remember { mutableStateOf(false) }
  var isLoading by remember { mutableStateOf(false) }
  val player = remember { MediaPlayer() }

  DisposableEffect(Unit) {
    player.setOnCompletionListener { isPlaying = false }
    onDispose {
      runCatching {
        if (player.isPlaying) player.stop()
        player.release()
      }
    }
  }

  fun toggle() {
    if (isPlaying) {
      runCatching { player.pause() }
      isPlaying = false
      return
    }
    if (isLoading) return
    isLoading = true
    scope.launch {
      runCatching {
        val file = withContext(Dispatchers.IO) {
          TicketAttachmentOpener.ensureLocalFile(context, attachment)
        }
        withContext(Dispatchers.Main) {
          player.reset()
          player.setDataSource(file.absolutePath)
          player.prepare()
          player.start()
          isPlaying = true
        }
      }.onFailure {
        Toast.makeText(context, R.string.TicketDiscussion__download_failed, Toast.LENGTH_LONG).show()
      }
      isLoading = false
    }
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
      .clickable(onClick = ::toggle)
      .padding(horizontal = 8.dp, vertical = 6.dp)
  ) {
    Box(
      modifier = Modifier
        .size(36.dp)
        .background(MaterialTheme.colorScheme.primary, CircleShape),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = ImageVector.vectorResource(
          if (isPlaying) R.drawable.pause_solid_24 else R.drawable.play_button_solid_24
        ),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(18.dp)
      )
    }
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = if (isLoading) {
        stringResource(R.string.TicketDiscussion__attachment)
      } else {
        stringResource(R.string.TicketDiscussion__voice_note)
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f)
    )
  }
}
