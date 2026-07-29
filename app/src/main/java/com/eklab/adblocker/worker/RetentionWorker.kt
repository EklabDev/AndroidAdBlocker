package com.eklab.adblocker.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eklab.adblocker.db.ConnectionLogDao
import com.eklab.adblocker.db.RetentionPolicy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Daily prune of connection-log rows older than the retention cutoff.
 *
 * Dependencies are fetched through an [EntryPoint] instead of hilt-work, so the
 * default WorkManager configuration can construct this worker reflectively with
 * the standard (Context, WorkerParameters) constructor.
 */
class RetentionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RetentionEntryPoint {
        fun connectionLogDao(): ConnectionLogDao
        fun retentionPolicy(): RetentionPolicy
    }

    override suspend fun doWork(): Result = try {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            RetentionEntryPoint::class.java,
        )
        val deleted = entryPoint.connectionLogDao()
            .deleteOlderThan(entryPoint.retentionPolicy().cutoffMillis())
        Log.d(TAG, "Retention prune deleted $deleted rows")
        Result.success()
    } catch (t: Throwable) {
        Log.w(TAG, "Retention prune failed, scheduling retry", t)
        Result.retry()
    }

    companion object {
        private const val TAG = "RetentionWorker"
        private const val UNIQUE_WORK_NAME = "retention_prune"

        /** Enqueues the unique daily prune job; an already-scheduled job is kept. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
