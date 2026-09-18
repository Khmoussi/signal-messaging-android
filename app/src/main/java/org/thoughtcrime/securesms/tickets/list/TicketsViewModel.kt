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
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tickets.data.TicketsRepository
import org.thoughtcrime.securesms.tickets.model.Ticket
import org.thoughtcrime.securesms.tickets.model.TicketFilter

data class TicketsUiState(
  val tickets: List<Ticket> = emptyList(),
  val filter: TicketFilter = TicketFilter.ALL,
  val searchQuery: String = "",
  val isLoading: Boolean = false,
  val error: String? = null
)

class TicketsViewModel : ViewModel() {
  companion object {
    private val TAG = Log.tag(TicketsViewModel::class.java)
  }

  private val filter = MutableStateFlow(TicketFilter.ALL)
  private val searchQuery = MutableStateFlow("")
  private val isLoading = MutableStateFlow(false)
  private val error = MutableStateFlow<String?>(null)

  val uiState: StateFlow<TicketsUiState> = combine(
    TicketsRepository.tickets,
    filter,
    searchQuery,
    isLoading,
    error
  ) { _, selectedFilter, query, loading, err ->
    TicketsUiState(
      tickets = TicketsRepository.observeFiltered(selectedFilter, query),
      filter = selectedFilter,
      searchQuery = query,
      isLoading = loading,
      error = err
    )
  }.stateIn(viewModelScope, SharingStarted.Eagerly, TicketsUiState())

  init {
    TicketsRepository.reloadFromDb()
    refresh()
  }

  fun setFilter(ticketFilter: TicketFilter) {
    filter.value = ticketFilter
  }

  fun setSearchQuery(query: String) {
    searchQuery.update { query }
  }

  fun refresh() {
    viewModelScope.launch {
      isLoading.value = true
      error.value = null
      try {
        val status = when (filter.value) {
          TicketFilter.ALL -> null
          TicketFilter.OPEN -> "OPEN"
          TicketFilter.CLOSED -> "CLOSED"
        }
        TicketsRepository.refreshList(status = status, query = searchQuery.value.ifBlank { null })
      } catch (e: Exception) {
        Log.w(TAG, "Failed to refresh tickets", e)
        error.value = e.message
        TicketsRepository.reloadFromDb()
      } finally {
        isLoading.value = false
      }
    }
  }
}
