package com.eklab.adblocker.ui.connections

import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.eklab.adblocker.core.SelectorType
import com.eklab.adblocker.db.RetentionPolicy
import com.eklab.adblocker.db.entities.ConnectionLog
import com.eklab.adblocker.db.entities.Rule
import com.eklab.adblocker.repository.AppsRepository
import com.eklab.adblocker.repository.ConnectionsRepository
import com.eklab.adblocker.repository.InstalledApp
import com.eklab.adblocker.repository.RulesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DAY_MS = 24L * 60L * 60L * 1000L

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val connectionsRepository: ConnectionsRepository,
    private val rulesRepository: RulesRepository,
    private val appsRepository: AppsRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val blockedOnly = MutableStateFlow(false)
    private val appFilter = MutableStateFlow<String?>(null)

    val searchQueryState: StateFlow<String> = searchQuery
    val blockedOnlyState: StateFlow<Boolean> = blockedOnly
    val appFilterState: StateFlow<String?> = appFilter

    /** Live paged feed, rebuilt whenever a filter changes (search debounced 300 ms). */
    val pagingFlow: Flow<PagingData<ConnectionLog>> =
        combine(
            searchQuery.debounce(300),
            blockedOnly,
            appFilter,
        ) { query, blocked, app -> Triple(query, blocked, app) }
            .flatMapLatest { (query, blocked, app) ->
                connectionsRepository.pagedFeed(
                    appPackage = app,
                    hostQuery = query.trim().ifBlank { null },
                    blockedOnly = blocked,
                    startTime = 0L,
                    endTime = Long.MAX_VALUE,
                )
            }
            .cachedIn(viewModelScope)

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    val hostSuggestions: StateFlow<List<String>> = connectionsRepository
        .distinctHosts(System.currentTimeMillis() - RetentionPolicy.RETENTION_DAYS * DAY_MS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // PackageManager query; must not run on the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            _installedApps.value = appsRepository.installedApps()
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setBlockedOnly(enabled: Boolean) {
        blockedOnly.value = enabled
    }

    fun setAppFilter(packageName: String?) {
        appFilter.value = packageName
    }

    private val iconCache = mutableMapOf<String, Drawable?>()

    /** Cached launcher icon for [packageName]; null when unknown/not installed. */
    fun appIcon(packageName: String?): Drawable? = packageName?.let { pkg ->
        iconCache.getOrPut(pkg) { appsRepository.appIcon(pkg) }
    }

    fun addRule(rule: Rule) {
        viewModelScope.launch { rulesRepository.add(rule) }
    }

    suspend fun previewMatchCount(selectorType: SelectorType, selectorValue: String): Int =
        rulesRepository.previewMatchCount(selectorType, selectorValue)
}
