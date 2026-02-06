package com.madhuram.ideacoach.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    companion object {
        private val QUESTION_DEPTH = intPreferencesKey("question_depth")
        private val WAVEFORM_CACHE_LIMIT = intPreferencesKey("waveform_cache_limit")
    }

    val questionDepth: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[QUESTION_DEPTH] ?: 3
    }

    suspend fun setQuestionDepth(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[QUESTION_DEPTH] = value
        }
    }

    val waveformCacheLimit: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[WAVEFORM_CACHE_LIMIT] ?: 40
    }

    suspend fun setWaveformCacheLimit(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[WAVEFORM_CACHE_LIMIT] = value
        }
    }
}
