package com.eklab.adblocker.di

import com.eklab.adblocker.core.RuleEngine
import com.eklab.adblocker.rules.RuleEngineImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RulesModule {

    @Binds
    @Singleton
    abstract fun bindRuleEngine(impl: RuleEngineImpl): RuleEngine
}
