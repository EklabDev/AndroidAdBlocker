package com.eklab.adblocker.ui.apps

import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eklab.adblocker.core.RuleAction
import com.eklab.adblocker.core.SelectorType
import com.eklab.adblocker.db.AppUsageSummary
import com.eklab.adblocker.db.RetentionPolicy
import com.eklab.adblocker.db.entities.Rule
import com.eklab.adblocker.repository.AppsRepository
import com.eklab.adblocker.repository.ConnectionsRepository
import com.eklab.adblocker.repository.RulesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DAY_MS = 24L * 60L * 60L * 1000L

@HiltViewModel
class AppsViewModel @Inject constructor(
    connectionsRepository: ConnectionsRepository,
    private val rulesRepository: RulesRepository,
    private val appsRepository: AppsRepository,
) : ViewModel() {

    private val windowStart =
        System.currentTimeMillis() - RetentionPolicy.RETENTION_DAYS * DAY_MS

    /** Per-app traffic totals over the retention window, heaviest first. */
    val usage: StateFlow<List<AppUsageSummary>> = connectionsRepository
        .appUsage(windowStart, Long.MAX_VALUE)
        .map { list -> list.filter { it.appPackage != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rules: StateFlow<List<Rule>> = rulesRepository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val iconCache = mutableMapOf<String, Drawable?>()
    private val labelCache = mutableMapOf<String, String>()

    fun appIcon(packageName: String): Drawable? =
        iconCache.getOrPut(packageName) { appsRepository.appIcon(packageName) }

    fun appLabel(packageName: String, fallbackName: String?): String =
        labelCache.getOrPut(packageName) {
            fallbackName ?: appsRepository.appLabel(packageName) ?: packageName
        }

    /** True when an enabled APP-scoped BLOCK rule exists for [packageName]. */
    fun isInternetBlocked(rules: List<Rule>, packageName: String): Boolean =
        rules.any {
            it.enabled &&
                it.selectorType == SelectorType.APP &&
                it.selectorValue == packageName &&
                it.action == RuleAction.BLOCK
        }

    /**
     * ON creates a "Block <label>" APP rule; OFF disables every enabled APP-scoped
     * BLOCK rule for the package.
     */
    fun setBlockAllInternet(packageName: String, label: String, block: Boolean) {
        viewModelScope.launch {
            if (block) {
                rulesRepository.add(
                    Rule(
                        name = "Block $label",
                        enabled = true,
                        selectorType = SelectorType.APP,
                        selectorValue = packageName,
                        action = RuleAction.BLOCK,
                    ),
                )
            } else {
                rules.value
                    .filter {
                        it.enabled &&
                            it.selectorType == SelectorType.APP &&
                            it.selectorValue == packageName &&
                            it.action == RuleAction.BLOCK
                    }
                    .forEach { rulesRepository.setEnabled(it.id, false) }
            }
        }
    }
}
