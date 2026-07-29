package com.eklab.adblocker.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Skeleton for Room migration tests.
 *
 * NOTE: [MigrationTestHelper] resolves the exported schema JSONs from androidTest
 * assets, so app/schemas must be wired into the androidTest assets source set in
 * app/build.gradle.kts for these tests to find them, e.g.:
 *
 *   sourceSets { named("androidTest") { assets.srcDir("$projectDir/schemas") } }
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    @Suppress("DEPRECATION")
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * Version 1 is the initial schema, so there is nothing to migrate yet.
     * This test documents the pattern: it creates the v1 database from the
     * exported schema JSON and verifies it can be opened and closed.
     */
    @Test
    fun createVersion1Database() {
        val db = helper.createDatabase(AppDatabase.DB_NAME, 1)
        assertTrue(db.isOpen)
        db.close()
    }

    /*
     * Template - add a real test like this when the schema moves to version 2:
     *
     * @Test
     * fun migrate1To2() {
     *     helper.createDatabase(AppDatabase.DB_NAME, 1).apply {
     *         execSQL("INSERT INTO rules (id, name, ...) VALUES (...)")
     *         close()
     *     }
     *
     *     val db = helper.runMigrationsAndValidate(
     *         AppDatabase.DB_NAME, 2, true, AppDatabase.MIGRATION_1_2,
     *     )
     *     // Assert migrated rows / new columns here.
     *     db.close()
     * }
     */
}
