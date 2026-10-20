package vn.vumap.feature.tpms.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vn.vumap.feature.tpms.TpmsThresholds
import vn.vumap.feature.tpms.Wheel

private val Context.tpmsDataStore by preferencesDataStore(name = "vumap_tpms")

private val KEY_LOW_KPA = floatPreferencesKey("tpms_low_kpa")
private val KEY_HIGH_KPA = floatPreferencesKey("tpms_high_kpa")
private val KEY_HIGH_TEMP = floatPreferencesKey("tpms_high_temp")
private val KEY_RAPID_LOSS = floatPreferencesKey("tpms_rapid_loss")
private val KEY_LOW_BATT = intPreferencesKey("tpms_low_batt")
private val KEY_STALE_MS = longPreferencesKey("tpms_stale_ms")
private val KEY_BIND_PREFIX = "tpms_bind_"

/**
 * Local TPMS persistence (thresholds + wheel mapping). On-device only.
 *
 * Thresholds stay null until the owner sets placard values; the monitor
 * treats null as "rule disabled / UNKNOWN", never guessing 2.0/2.2 bar.
 */
class TpmsSettingsRepository(private val appContext: Context) {
    val thresholds: Flow<TpmsThresholds> = appContext.tpmsDataStore.data.map { prefs ->
        TpmsThresholds(
            lowPressureKpa = prefs[KEY_LOW_KPA],
            highPressureKpa = prefs[KEY_HIGH_KPA],
            highTempC = prefs[KEY_HIGH_TEMP],
            rapidLossKpaPerMin = prefs[KEY_RAPID_LOSS],
            lowBatteryPercent = prefs[KEY_LOW_BATT],
            staleTimeoutMs = prefs[KEY_STALE_MS] ?: 120_000L,
        )
    }

    val mapping: Flow<Map<Wheel, String>> = appContext.tpmsDataStore.data.map { prefs ->
        Wheel.entries.mapNotNull { wheel ->
            val id = prefs[stringPreferencesKey(KEY_BIND_PREFIX + wheel.name)]
            if (id.isNullOrBlank()) null else wheel to id
        }.toMap()
    }

    suspend fun saveThresholds(t: TpmsThresholds) {
        val reason = t.validate()
        require(reason == null) { reason.orEmpty() }
        appContext.tpmsDataStore.edit { prefs ->
            if (t.lowPressureKpa == null) prefs.remove(KEY_LOW_KPA) else prefs[KEY_LOW_KPA] = t.lowPressureKpa
            if (t.highPressureKpa == null) prefs.remove(KEY_HIGH_KPA) else prefs[KEY_HIGH_KPA] = t.highPressureKpa
            if (t.highTempC == null) prefs.remove(KEY_HIGH_TEMP) else prefs[KEY_HIGH_TEMP] = t.highTempC
            if (t.rapidLossKpaPerMin == null) prefs.remove(KEY_RAPID_LOSS) else prefs[KEY_RAPID_LOSS] = t.rapidLossKpaPerMin
            if (t.lowBatteryPercent == null) prefs.remove(KEY_LOW_BATT) else prefs[KEY_LOW_BATT] = t.lowBatteryPercent
            prefs[KEY_STALE_MS] = t.staleTimeoutMs
        }
    }

    suspend fun saveBinding(wheel: Wheel, sensorId: String?) {
        appContext.tpmsDataStore.edit { prefs ->
            val key = stringPreferencesKey(KEY_BIND_PREFIX + wheel.name)
            if (sensorId.isNullOrBlank()) prefs.remove(key) else prefs[key] = sensorId.trim().uppercase()
        }
    }

    suspend fun clearBindings() {
        appContext.tpmsDataStore.edit { prefs ->
            Wheel.entries.forEach { prefs.remove(stringPreferencesKey(KEY_BIND_PREFIX + it.name)) }
        }
    }
}
