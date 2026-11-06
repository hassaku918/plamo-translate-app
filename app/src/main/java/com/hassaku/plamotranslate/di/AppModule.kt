package com.hassaku.plamotranslate.di

import com.hassaku.plamotranslate.data.inference.MockTranslationEngine
import com.hassaku.plamotranslate.data.inference.PlamoLlamaEngine
import com.hassaku.plamotranslate.data.inference.TranslationEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @Named("real")
    fun provideRealEngine(engine: PlamoLlamaEngine): TranslationEngine = engine

    @Provides
    @Singleton
    @Named("mock")
    fun provideMockEngine(engine: MockTranslationEngine): TranslationEngine = engine

    /** Primary binding: real engine; ViewModel falls back to mock until model is loaded. */
    @Provides
    @Singleton
    fun providePrimaryEngine(engine: PlamoLlamaEngine): TranslationEngine = engine
}
