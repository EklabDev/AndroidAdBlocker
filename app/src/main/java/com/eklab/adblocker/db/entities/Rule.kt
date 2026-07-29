package com.eklab.adblocker.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.eklab.adblocker.core.RuleAction
import com.eklab.adblocker.core.SelectorType

@Entity(tableName = "rules")
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    /** Lower value = evaluated first. First match wins. */
    val priority: Int = 100,
    val selectorType: SelectorType,
    /**
     * Meaning depends on [selectorType]:
     * APP -> package name, HOST -> exact host, HOST_SUFFIX -> suffix like ".ads.example.com",
     * IP -> IPv4 address or CIDR, TYPE -> ProtocolType name.
     */
    val selectorValue: String,
    val action: RuleAction,
    val createdAt: Long = System.currentTimeMillis(),
)
