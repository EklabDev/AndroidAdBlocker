package com.eklab.adblocker.ui.rules

import com.eklab.adblocker.core.SelectorType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelectorValueTest {

    @Test
    fun hostToHostSuffixAddsLeadingDot() {
        assertThat(
            reshapeSelectorValue(SelectorType.HOST, SelectorType.HOST_SUFFIX, "ads.example.com"),
        ).isEqualTo(".ads.example.com")
    }

    @Test
    fun hostToHostSuffixKeepsExistingDot() {
        assertThat(
            reshapeSelectorValue(SelectorType.HOST, SelectorType.HOST_SUFFIX, ".ads.example.com"),
        ).isEqualTo(".ads.example.com")
    }

    @Test
    fun hostSuffixToHostStripsLeadingDot() {
        assertThat(
            reshapeSelectorValue(SelectorType.HOST_SUFFIX, SelectorType.HOST, ".ads.example.com"),
        ).isEqualTo("ads.example.com")
    }

    @Test
    fun emptyValueIsNotInvented() {
        assertThat(
            reshapeSelectorValue(SelectorType.HOST, SelectorType.HOST_SUFFIX, ""),
        ).isEmpty()
    }

    @Test
    fun otherTypeSwitchesPreserveTheTypedValue() {
        assertThat(
            reshapeSelectorValue(SelectorType.HOST, SelectorType.IP, "ads.example.com"),
        ).isEqualTo("ads.example.com")
        assertThat(
            reshapeSelectorValue(SelectorType.IP, SelectorType.APP, "203.0.113.1"),
        ).isEqualTo("203.0.113.1")
        assertThat(
            reshapeSelectorValue(SelectorType.HOST, SelectorType.TYPE, "cdn.example.com"),
        ).isEqualTo("cdn.example.com")
    }
}
