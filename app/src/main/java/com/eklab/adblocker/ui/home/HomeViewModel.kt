package com.eklab.adblocker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eklab.adblocker.repository.ConnectionsRepository
import com.eklab.adblocker.repository.TodayStats
import com.eklab.adblocker.settings.SettingsRepository
import com.eklab.adblocker.vpn.VpnControl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    connectionsRepository: ConnectionsRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** Live VPN running state, mirrored from the service via [VpnControl]. */
    val vpnRunning: StateFlow<Boolean> = VpnControl.isRunning

    private val startOfTodayMillis: Long = LocalDate.now()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    val todayStats: StateFlow<TodayStats> = connectionsRepository.todayStats(startOfTodayMillis)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodayStats(0, 0, 0))

    val blockQuic: StateFlow<Boolean> = settingsRepository.blockQuic

    fun setBlockQuic(enabled: Boolean) = settingsRepository.setBlockQuic(enabled)
}
