package com.eklab.adblocker.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eklab.adblocker.core.SelectorType
import com.eklab.adblocker.db.entities.Rule
import com.eklab.adblocker.rules.KnownAdDomains

@Composable
fun KnownAdsSection(
    rules: List<Rule>,
    onAdd: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    val addedKeys = remember(rules) {
        rules.filter { it.selectorType == SelectorType.HOST_SUFFIX }
            .map { KnownAdDomains.normalize(it.selectorValue) }
            .toSet()
    }
    fun isAdded(domain: String) = KnownAdDomains.normalize(domain) in addedKeys

    val notYetAdded = KnownAdDomains.DOMAINS.filterNot(::isAdded)
    val selectedToAdd = selected.filterNot(::isAdded)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text("Known ad domains", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (notYetAdded.isEmpty()) {
                            "All ${KnownAdDomains.DOMAINS.size} templates are already in your list."
                        } else {
                            "${notYetAdded.size} of ${KnownAdDomains.DOMAINS.size} not yet added. Block the domain and all subdomains."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse known ads" else "Expand known ads",
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { onAdd(selectedToAdd) },
                    enabled = selectedToAdd.isNotEmpty(),
                ) { Text("Add selected") }
                TextButton(
                    onClick = { onAdd(notYetAdded) },
                    enabled = notYetAdded.isNotEmpty(),
                ) { Text("Add all") }
            }
            if (expanded) {
                KnownAdDomains.DOMAINS.forEach { domain ->
                    val added = isAdded(domain)
                    val checked = added || domain in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !added) {
                                selected = if (domain in selected) selected - domain else selected + domain
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                selected = if (on) selected + domain else selected - domain
                            },
                            enabled = !added,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(domain, style = MaterialTheme.typography.bodyMedium)
                            if (added) {
                                Text(
                                    "Already added",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
