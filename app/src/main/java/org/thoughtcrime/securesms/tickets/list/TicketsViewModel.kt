/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tickets.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.thoughtcrime.securesms.tickets.data.FakeTicketsRepository
import org.thoughtcrime.securesms.tickets.model.Ticket
import org.thoughtcrime.securesms.tickets.model.TicketFilter

data class TicketsUiState(
  val tickets: List<Ticket> = emptyList(),
  val filter: TicketFilter = TicketFilter.ALL,
  val searchQuery: String = ""
)

class TicketsViewModel : ViewModel() {
  private val filter = MutableStateFlow(TicketFilter.ALL)
  private val searchQuery = MutableStateFlow("")

  val uiState: StateFlow<TicketsUiState> = combine(
    FakeTicketsRepository.tickets,
    filter,
    searchQuery
  ) { _, selectedFilter, query ->
    TicketsUiState(
      tickets = FakeTicketsRepository.observeFiltered(selectedFilter, query),
      filter = selectedFilter,
      searchQuery = query
    )
  }.stateIn(viewModelScope, SharingStarted.Eagerly, TicketsUiState())

  fun setFilter(ticketFilter: TicketFilter) {
    filter.value = ticketFilter
  }

  fun setSearchQuery(query: String) {
    searchQuery.update { query }
  }
}
