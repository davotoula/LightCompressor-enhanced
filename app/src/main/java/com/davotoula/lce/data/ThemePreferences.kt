package com.davotoula.lce.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "theme_preferences")

class ThemePreferences(private val context: Context) {
    private val darkThemeOverrideKey = booleanPreferencesKey("dark_theme_override")

    val darkThemeOverride: Flow<Boolean?> = context.themeDataStore.data.map { prefs ->
        prefs[darkThemeOverrideKey]
    }

    suspend fun setDarkThemeOverride(enabled: Boolean) {
        context.themeDataStore.edit { prefs ->
            prefs[darkThemeOverrideKey] = enabled
        }
    }
}
