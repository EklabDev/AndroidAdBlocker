package com.eklab.adblocker.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.eklab.adblocker.db.entities.Rule
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {

    @Query("SELECT * FROM rules ORDER BY priority ASC, id ASC")
    fun observeAll(): Flow<List<Rule>>

    @Query("SELECT * FROM rules ORDER BY priority ASC, id ASC")
    suspend fun getAll(): List<Rule>

    @Insert
    suspend fun insert(rule: Rule): Long

    @Insert
    suspend fun insertAll(rules: List<Rule>): List<Long>

    @Update
    suspend fun update(rule: Rule)

    @Delete
    suspend fun delete(rule: Rule)

    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE rules SET priority = :priority WHERE id = :id")
    suspend fun updatePriority(id: Long, priority: Int)

    /**
     * Reorders rules: each id in [orderedIds] gets priority = its index (0-based).
     * Rules absent from the list keep their current relative order and are
     * assigned priorities right after the listed ones.
     */
    @Transaction
    suspend fun reorder(orderedIds: List<Long>) {
        // Snapshot before reassigning so unlisted rules keep their current order.
        val current = getAll()
        val listed = orderedIds.toSet()
        orderedIds.forEachIndexed { index, id ->
            updatePriority(id, index)
        }
        current
            .filter { it.id !in listed }
            .forEachIndexed { index, rule ->
                updatePriority(rule.id, orderedIds.size + index)
            }
    }
}
