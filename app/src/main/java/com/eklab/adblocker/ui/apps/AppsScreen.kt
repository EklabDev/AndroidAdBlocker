package com.eklab.adblocker.ui.apps

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eklab.adblocker.db.AppUsageSummary
import com.eklab.adblocker.ui.common.AppIconImage
import com.eklab.adblocker.ui.common.formatBytes

@Composable
fun AppsScreen(viewModel: AppsViewModel = hiltViewModel()) {
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Apps",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        Text(
            "Traffic per app over the last 7 days.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (usage.isEmpty()) {
            Text(
                "No traffic recorded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
        ) {
            items(usage, key = { it.appPackage ?: "" }) { summary ->
                val packageName = summary.appPackage ?: return@items
                val label = viewModel.appLabel(packageName, summary.appName)
                AppUsageCard(
                    summary = summary,
                    label = label,
                    icon = viewModel.appIcon(packageName),
                    blocked = viewModel.isInternetBlocked(rules, packageName),
                    onBlockChanged = { block ->
                        viewModel.setBlockAllInternet(packageName, label, block)
                    },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AppUsageCard(
    summary: AppUsageSummary,
    label: String,
    icon: Drawable?,
    blocked: Boolean,
    onBlockChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconImage(
                drawable = icon,
                contentDescription = label,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary.appPackage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${summary.connectionCount} connections · " +
                        "${formatBytes(summary.totalBytes)} · " +
                        "${summary.blockedCount} blocked · " +
                        "${summary.distinctHosts} hosts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Block all\ninternet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(checked = blocked, onCheckedChange = onBlockChanged)
            }
        }
    }
}
