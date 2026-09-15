package com.eklab.adblocker.ui.rules

import com.eklab.adblocker.core.SelectorType

/**
 * Keeps the typed selector value when the match type changes.
 * HOST <-> HOST_SUFFIX only adds or strips a leading dot; other switches leave
 * the text untouched so a connection-prefilled host is not wiped.
 */
fun reshapeSelectorValue(
    oldType: SelectorType,
    newType: SelectorType,
    value: String,
): String = when {
    oldType == SelectorType.HOST && newType == SelectorType.HOST_SUFFIX ->
        if (value.isEmpty() || value.startsWith(".")) value else ".$value"
    oldType == SelectorType.HOST_SUFFIX && newType == SelectorType.HOST ->
        value.removePrefix(".")
    else -> value
}
