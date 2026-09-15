package com.eklab.adblocker.ui.rules

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.eklab.adblocker.core.ProtocolType
import com.eklab.adblocker.core.RuleAction
import com.eklab.adblocker.core.SelectorType
import com.eklab.adblocker.db.entities.Rule
import com.eklab.adblocker.repository.InstalledApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Prefill state for the add-rule dialog (used when creating a rule from a connection). */
data class RuleDraft(
    val name: String = "",
    val selectorType: SelectorType = SelectorType.HOST,
    val selectorValue: String = "",
    val action: RuleAction = RuleAction.BLOCK,
    val priority: Int = 100,
)

private val selectorLabels = linkedMapOf(
    SelectorType.APP to "App",
    SelectorType.HOST to "Exact host",
    SelectorType.HOST_SUFFIX to "Domain & subdomains",
    SelectorType.IP to "IP or CIDR",
    SelectorType.TYPE to "Traffic type",
)

private val cidrRegex = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?:/(\d{1,2}))?$""")

private fun isValidIpOrCidr(value: String): Boolean {
    val match = cidrRegex.matchEntire(value) ?: return false
    val octetsOk = (1..4).all { match.groupValues[it].toInt() <= 255 }
    val prefix = match.groupValues[5]
    val prefixOk = prefix.isEmpty() || prefix.toInt() <= 32
    return octetsOk && prefixOk
}

/**
 * Shared add-rule dialog used by the Rules screen and the connection detail sheet.
 * [onPreview] counts how many recent connections the selector would have matched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditDialog(
    initial: RuleDraft = RuleDraft(),
    installedApps: List<InstalledApp>,
    hostSuggestions: List<String>,
    onPreview: suspend (SelectorType, String) -> Int,
    onDismiss: () -> Unit,
    onConfirm: (Rule) -> Unit,
) {
    var selectorType by remember { mutableStateOf(initial.selectorType) }
    var value by remember { mutableStateOf(initial.selectorValue) }
    var action by remember { mutableStateOf(initial.action) }
    var priorityText by remember { mutableStateOf(initial.priority.toString()) }
    var name by remember { mutableStateOf(initial.name) }
    var nameTouched by remember { mutableStateOf(initial.name.isNotEmpty()) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var suggestionsExpanded by remember { mutableStateOf(false) }

    fun autoName(type: SelectorType, v: String): String = when (type) {
        SelectorType.APP -> installedApps.firstOrNull { it.packageName == v }?.label ?: v
        SelectorType.TYPE -> "${v} traffic"
        else -> v
    }

    fun onValueChange(newValue: String) {
        value = newValue
        suggestionsExpanded = true
        if (!nameTouched) name = autoName(selectorType, newValue)
    }

    fun onTypeChange(newType: SelectorType) {
        val reshaped = reshapeSelectorValue(selectorType, newType, value)
        selectorType = newType
        value = reshaped
        suggestionsExpanded = false
        if (!nameTouched) name = autoName(newType, reshaped)
    }

    // Suggestions as (value to insert) to (display text).
    val suggestionItems: List<Pair<String, String>> = remember(selectorType, value, installedApps, hostSuggestions) {
        when (selectorType) {
            SelectorType.APP -> installedApps
                .filter {
                    it.label.contains(value, ignoreCase = true) ||
                        it.packageName.contains(value, ignoreCase = true)
                }
                .take(8)
                .map { it.packageName to "${it.label} (${it.packageName})" }
            SelectorType.HOST -> hostSuggestions
                .filter { it.contains(value, ignoreCase = true) }
                .take(8)
                .map { it to it }
            SelectorType.HOST_SUFFIX -> hostSuggestions
                .filter { it.contains(value, ignoreCase = true) }
                .take(8)
                .map { ".$it" to ".$it" }
            SelectorType.TYPE -> ProtocolType.entries
                .map { it.name }
                .filter { it.contains(value, ignoreCase = true) }
                .map { it to it }
            SelectorType.IP -> emptyList()
        }
    }

    val trimmedValue = value.trim()
    val valueError: String? = when {
        trimmedValue.isEmpty() -> "Value is required"
        selectorType == SelectorType.IP && !isValidIpOrCidr(trimmedValue) ->
            "Enter an IPv4 address or CIDR (e.g. 203.0.113.0/24)"
        else -> null
    }
    val priority = priorityText.trim().toIntOrNull()
    val nameError: String? = if (name.trim().isEmpty()) "Name is required" else null
    val canConfirm = valueError == null && nameError == null && priority != null

    // Live match preview, debounced while editing.
    var previewCount by remember { mutableStateOf<Int?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    LaunchedEffect(selectorType, trimmedValue, valueError) {
        previewCount = null
        previewLoading = false
        if (valueError == null) {
            previewLoading = true
            delay(400)
            previewCount = try {
                onPreview(selectorType, trimmedValue)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                null
            }
            previewLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add rule") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // Selector type picker.
                Text("Match", style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(
                        onClick = { typeMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            selectorLabels.getValue(selectorType),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false },
                    ) {
                        selectorLabels.forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onTypeChange(type)
                                    typeMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                // Selector value with autocomplete.
                Box {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { onValueChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                when (selectorType) {
                                    SelectorType.APP -> "Package name"
                                    SelectorType.HOST -> "Host (exact)"
                                    SelectorType.HOST_SUFFIX -> "Domain suffix (e.g. .ads.example.com)"
                                    SelectorType.IP -> "IP or CIDR"
                                    SelectorType.TYPE -> "Traffic type"
                                },
                            )
                        },
                        singleLine = true,
                        isError = value.isNotEmpty() && valueError != null,
                    )
                    DropdownMenu(
                        expanded = suggestionsExpanded && suggestionItems.isNotEmpty(),
                        onDismissRequest = { suggestionsExpanded = false },
                        properties = PopupProperties(focusable = false),
                    ) {
                        suggestionItems.forEach { (suggestionValue, display) ->
                            DropdownMenuItem(
                                text = { Text(display) },
                                onClick = {
                                    value = suggestionValue
                                    suggestionsExpanded = false
                                    if (!nameTouched) name = autoName(selectorType, suggestionValue)
                                },
                            )
                        }
                    }
                }
                if (value.isNotEmpty() && valueError != null) {
                    Text(
                        valueError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // Action.
                Text(
                    "Action",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = action == RuleAction.BLOCK,
                        onClick = { action = RuleAction.BLOCK },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Block") }
                    SegmentedButton(
                        selected = action == RuleAction.ALLOW,
                        onClick = { action = RuleAction.ALLOW },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Allow") }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameTouched = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    label = { Text("Rule name") },
                    singleLine = true,
                    isError = nameError != null,
                )

                OutlinedTextField(
                    value = priorityText,
                    onValueChange = { priorityText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    label = { Text("Priority (lower runs first)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = priority == null,
                )

                Text(
                    text = when {
                        valueError != null -> " "
                        previewLoading -> "Checking recent history…"
                        previewCount != null ->
                            "This rule would have matched $previewCount connections in the last 7 days."
                        else -> "Match preview: —"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    onConfirm(
                        Rule(
                            name = name.trim(),
                            enabled = true,
                            priority = priority ?: 100,
                            selectorType = selectorType,
                            selectorValue = trimmedValue,
                            action = action,
                        ),
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
