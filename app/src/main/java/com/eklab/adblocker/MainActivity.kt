package com.eklab.adblocker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.eklab.adblocker.ui.AppRoot
import com.eklab.adblocker.ui.theme.TrafficInspectorTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrafficInspectorTheme {
                AppRoot()
            }
        }
    }
}
