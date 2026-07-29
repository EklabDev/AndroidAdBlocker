package com.eklab.adblocker.di

import android.content.Context
import androidx.room.Room
import com.eklab.adblocker.db.AppDatabase
import com.eklab.adblocker.db.ConnectionLogDao
import com.eklab.adblocker.db.RetentionPolicy
import com.eklab.adblocker.db.RuleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for the application-wide [CoroutineScope] used by long-lived collectors. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DB_NAME).build()

    @Provides
    fun provideConnectionLogDao(db: AppDatabase): ConnectionLogDao = db.connectionLogDao()

    @Provides
    fun provideRuleDao(db: AppDatabase): RuleDao = db.ruleDao()

    @Provides
    @Singleton
    fun provideRetentionPolicy(): RetentionPolicy = RetentionPolicy()

    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
