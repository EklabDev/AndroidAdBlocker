package com.eklab.adblocker.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eklab.adblocker.core.RuleAction
import com.eklab.adblocker.core.SelectorType
import com.eklab.adblocker.db.RetentionPolicy
import com.eklab.adblocker.db.entities.Rule
import com.eklab.adblocker.repository.AppsRepository
import com.eklab.adblocker.repository.ConnectionsRepository
import com.eklab.adblocker.repository.InstalledApp
import com.eklab.adblocker.repository.RulesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DAY_MS = 24L * 60L * 60L * 1000L

@HiltViewModel
class RulesViewModel @Inject constructor(
    private val rulesRepository: RulesRepository,
    private val connectionsRepository: ConnectionsRepository,
    private val appsRepository: AppsRepository,
) : ViewModel() {

    val rules: StateFlow<List<Rule>> = rulesRepository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun addRule(rule: Rule) {
        viewModelScope.launch { rulesRepository.add(rule) }
    }

    fun addKnownAdDomains(values: List<String>) {
        if (values.isEmpty()) return
        viewModelScope.launch {
            val rules = values.map { domain ->
                Rule(
                    name = domain,
                    enabled = true,
                    priority = 100,
                    selectorType = SelectorType.HOST_SUFFIX,
                    selectorValue = domain,
                    action = RuleAction.BLOCK,
                )
            }
            rulesRepository.addAllSkippingDuplicates(rules)
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { rulesRepository.setEnabled(id, enabled) }
    }

    fun deleteRule(rule: Rule) {
        viewModelScope.launch { rulesRepository.delete(rule) }
    }

    fun reorder(orderedIds: List<Long>) {
        viewModelScope.launch { rulesRepository.reorder(orderedIds) }
    }

    suspend fun previewMatchCount(selectorType: SelectorType, selectorValue: String): Int =
        rulesRepository.previewMatchCount(selectorType, selectorValue)
}
