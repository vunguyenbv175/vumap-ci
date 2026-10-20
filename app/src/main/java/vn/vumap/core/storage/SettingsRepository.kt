package vn.vumap.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vn.vumap.core.model.MapStyles
import vn.vumap.core.model.UserSettings

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "vumap_settings")

private val KEY_FOLLOW_LOCATION = booleanPreferencesKey("follow_location")
private val KEY_STYLE_URL = stringPreferencesKey("style_url")
private val KEY_DEMO_SIGNS_ENABLED = booleanPreferencesKey("demo_signs_enabled")

/**
 * Local settings repository (project plan core/storage).
 *
 * DataStore file stays in the app sandbox. No account, no cloud sync, no analytics.
 */
class SettingsRepository(private val appContext: Context) {

    val settings: Flow<UserSettings> =
        appContext.settingsDataStore.data.map { prefs ->
            UserSettings(
                followLocation = prefs[KEY_FOLLOW_LOCATION] ?: true,
                styleUrl = prefs[KEY_STYLE_URL] ?: MapStyles.LIBERTY,
                demoSignsEnabled = prefs[KEY_DEMO_SIGNS_ENABLED] ?: false,
            ).sanitized()
        }

    suspend fun setFollowLocation(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs -> prefs[KEY_FOLLOW_LOCATION] = enabled }
    }

    suspend fun setStyleUrl(styleUrl: String) {
        val safe = if (MapStyles.isKnown(styleUrl)) styleUrl else MapStyles.LIBERTY
        appContext.settingsDataStore.edit { prefs -> prefs[KEY_STYLE_URL] = safe }
    }

    suspend fun setDemoSignsEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs -> prefs[KEY_DEMO_SIGNS_ENABLED] = enabled }
    }
}
