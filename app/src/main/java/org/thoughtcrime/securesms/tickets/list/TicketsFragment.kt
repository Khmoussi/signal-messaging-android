/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.concurrent.LifecycleDisposable
import org.thoughtcrime.securesms.MainNavigator
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.main.MainNavigationListLocation
import org.thoughtcrime.securesms.main.MainNavigationViewModel
import org.thoughtcrime.securesms.main.MainToolbarViewModel
import org.thoughtcrime.securesms.tickets.discussion.TicketDiscussionActivity
import org.thoughtcrime.securesms.tickets.model.Ticket
import org.thoughtcrime.securesms.tickets.model.TicketFilter
import org.thoughtcrime.securesms.tickets.model.TicketPriority
import org.thoughtcrime.securesms.tickets.model.TicketStatus
import java.text.DateFormat
import java.util.Date

private val KadiGold = Color(0xFFC9A227)

/**
 * Tickets tab list backed by [org.thoughtcrime.securesms.tickets.data.TicketsRepository].
 */
class TicketsFragment : Fragment() {

  private val viewModel: TicketsViewModel by viewModels()
  private val mainToolbarViewModel: MainToolbarViewModel by activityViewModels()
  private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
  private val disposables = LifecycleDisposable()

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return ComposeView(requireContext()).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        SignalTheme {
          val uiState by viewModel.uiState.collectAsStateWithLifecycle()
          TicketsScreen(
            uiState = uiState,
            onFilterSelected = viewModel::setFilter,
            onTicketClick = ::openTicket
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.refresh()
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    disposables.bindTo(viewLifecycleOwner)

    disposables += mainToolbarViewModel.getSearchEventsFlowable().subscribeBy {
      when (it) {
        MainToolbarViewModel.Event.Search.Close -> viewModel.setSearchQuery("")
        MainToolbarViewModel.Event.Search.Open -> Unit
        is MainToolbarViewModel.Event.Search.Query -> viewModel.setSearchQuery(it.query)
      }
    }

    disposables += mainNavigationViewModel.tabClickEventsObservable
      .filter { it == MainNavigationListLocation.TICKETS }
      .subscribe {
        (requireActivity() as? MainNavigator.NavigatorProvider)?.onFirstRender()
      }

    (requireActivity() as? MainNavigator.NavigatorProvider)?.onFirstRender()
  }

  private fun openTicket(ticket: Ticket) {
    startActivity(TicketDiscussionActivity.createIntent(requireContext(), ticket.idString))
  }
}

@Composable
private fun TicketsScreen(
  uiState: TicketsUiState,
  onFilterSelected: (TicketFilter) -> Unit,
  onTicketClick: (Ticket) -> Unit
) {
  Surface(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      TicketFilterRow(
        selected = uiState.filter,
        onFilterSelected = onFilterSelected,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp)
      )

      if (uiState.tickets.isEmpty()) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = stringResource(R.string.TicketsFragment__no_tickets),
            style = MaterialTheme.typography.bodyLarge
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(R.string.TicketsFragment__no_tickets_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      } else {
        LazyColumn(
          contentPadding = PaddingValues(bottom = 96.dp),
          modifier = Modifier.fillMaxSize()
        ) {
          items(uiState.tickets, key = { it.idString }) { ticket ->
            TicketRow(
              ticket = ticket,
              onClick = { onTicketClick(ticket) }
            )
            HorizontalDivider()
          }
        }
      }
    }
  }
}

@Composable
private fun TicketFilterRow(
  selected: TicketFilter,
  onFilterSelected: (TicketFilter) -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    FilterChip(
      selected = selected == TicketFilter.ALL,
      onClick = { onFilterSelected(TicketFilter.ALL) },
      label = { Text(stringResource(R.string.TicketsFragment__filter_all)) }
    )
    FilterChip(
      selected = selected == TicketFilter.OPEN,
      onClick = { onFilterSelected(TicketFilter.OPEN) },
      label = { Text(stringResource(R.string.TicketsFragment__filter_open)) }
    )
    FilterChip(
      selected = selected == TicketFilter.CLOSED,
      onClick = { onFilterSelected(TicketFilter.CLOSED) },
      label = { Text(stringResource(R.string.TicketsFragment__filter_closed)) }
    )
  }
}

@Composable
private fun TicketRow(
  ticket: Ticket,
  onClick: () -> Unit
) {
  val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
  val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = ticket.number,
        style = MaterialTheme.typography.titleSmall
      )
      StatusBadge(status = ticket.status)
    }

    Spacer(modifier = Modifier.height(4.dp))

    Text(
      text = ticket.description,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis
    )

    Spacer(modifier = Modifier.height(6.dp))

    Text(
      text = stringResource(
        R.string.TicketsFragment__last_activity,
        "${dateFormat.format(Date(ticket.lastActivityMs))} ${timeFormat.format(Date(ticket.lastActivityMs))}"
      ),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text(
      text = stringResource(
        R.string.TicketsFragment__created,
        dateFormat.format(Date(ticket.createdAtMs))
      ),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun StatusBadge(status: TicketStatus) {
  val background = if (status == TicketStatus.OPEN) {
    MaterialTheme.colorScheme.primary
  } else {
    KadiGold
  }
  Text(
    text = statusLabel(status),
    style = MaterialTheme.typography.labelMedium,
    color = Color.White,
    modifier = Modifier
      .background(background, RoundedCornerShape(50))
      .padding(horizontal = 10.dp, vertical = 4.dp)
  )
}

@Composable
private fun statusLabel(status: TicketStatus): String {
  return when (status) {
    TicketStatus.OPEN -> stringResource(R.string.Tickets__status_open)
    TicketStatus.CLOSED -> stringResource(R.string.Tickets__status_closed)
  }
}

@DayNightPreviews
@Composable
private fun TicketsScreenPreview() {
  Previews.Preview {
    TicketsScreen(
      uiState = TicketsUiState(
        tickets = listOf(
          Ticket(
            id = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
            number = "TKT-1001",
            description = "Sample ticket description",
            department = "IT",
            location = "HQ",
            priority = TicketPriority.HIGH,
            status = TicketStatus.OPEN,
            createdAtMs = System.currentTimeMillis(),
            lastActivityMs = System.currentTimeMillis(),
            createdByAci = java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"),
            createdByName = "You",
            administratorAci = java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"),
            discussionId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000003"),
            participants = emptyList(),
            messages = emptyList()
          )
        )
      ),
      onFilterSelected = {},
      onTicketClick = {}
    )
  }
}
