package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.voxKeyDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "voxkey_settings")

enum class RecordingMode {
  TAP_TO_TOGGLE,
  HOLD_TO_RECORD,
}

enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
}

data class VoxKeySettings(
  val recordingMode: RecordingMode = RecordingMode.TAP_TO_TOGGLE,
  val preBufferMs: Int = 800,
  val enabledLanguages: List<String> = listOf("en-US", "es-ES", "fr-FR", "de-DE"),
  val activeLanguage: String = "en-US",
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val firstUseHintDismissed: Boolean = false,
  val showCommandDevPanel: Boolean = true,
)

class SettingsRepository private constructor(private val context: Context) {

  private object Keys {
    val RECORDING_MODE = stringPreferencesKey("recording_mode")
    val PRE_BUFFER_MS = intPreferencesKey("pre_buffer_ms")
    val ENABLED_LANGUAGES = stringPreferencesKey("enabled_languages")
    val ACTIVE_LANGUAGE = stringPreferencesKey("active_language")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val FIRST_USE_HINT_DISMISSED = booleanPreferencesKey("first_use_hint_dismissed")
    val SHOW_COMMAND_DEV_PANEL = booleanPreferencesKey("show_command_dev_panel")
  }

  val settingsFlow: Flow<VoxKeySettings> =
    context.voxKeyDataStore.data.map { prefs ->
      val mode =
        prefs[Keys.RECORDING_MODE]?.let { runCatching { RecordingMode.valueOf(it) }.getOrNull() }
          ?: RecordingMode.TAP_TO_TOGGLE
      val preBuffer = (prefs[Keys.PRE_BUFFER_MS] ?: 800).coerceIn(500, 1000)
      val enabled =
        prefs[Keys.ENABLED_LANGUAGES]
          ?.split(",")
          ?.map { it.trim() }
          ?.filter { it.isNotEmpty() }
          ?.ifEmpty { null }
          ?: listOf("en-US", "es-ES", "fr-FR", "de-DE")
      val activeRaw = prefs[Keys.ACTIVE_LANGUAGE] ?: enabled.first()
      val active = if (enabled.contains(activeRaw)) activeRaw else enabled.first()
      val theme =
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
          ?: ThemeMode.SYSTEM
      val hintDismissed = prefs[Keys.FIRST_USE_HINT_DISMISSED] ?: false
      val showDevPanel = prefs[Keys.SHOW_COMMAND_DEV_PANEL] ?: true

      VoxKeySettings(
        recordingMode = mode,
        preBufferMs = preBuffer,
        enabledLanguages = enabled,
        activeLanguage = active,
        themeMode = theme,
        firstUseHintDismissed = hintDismissed,
        showCommandDevPanel = showDevPanel,
      )
    }

  suspend fun setRecordingMode(mode: RecordingMode) {
    context.voxKeyDataStore.edit { it[Keys.RECORDING_MODE] = mode.name }
  }

  suspend fun setPreBufferMs(ms: Int) {
    context.voxKeyDataStore.edit { it[Keys.PRE_BUFFER_MS] = ms.coerceIn(500, 1000) }
  }

  suspend fun setEnabledLanguages(languages: List<String>) {
    val sanitized = languages.distinct().ifEmpty { listOf("en-US") }
    context.voxKeyDataStore.edit { prefs ->
      prefs[Keys.ENABLED_LANGUAGES] = sanitized.joinToString(",")
      val current = prefs[Keys.ACTIVE_LANGUAGE]
      if (current == null || !sanitized.contains(current)) {
        prefs[Keys.ACTIVE_LANGUAGE] = sanitized.first()
      }
    }
  }

  suspend fun setActiveLanguage(languageTag: String) {
    context.voxKeyDataStore.edit { prefs ->
      prefs[Keys.ACTIVE_LANGUAGE] = languageTag
      val currentEnabled =
        prefs[Keys.ENABLED_LANGUAGES]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
          ?: listOf("en-US", "es-ES", "fr-FR", "de-DE")
      if (!currentEnabled.contains(languageTag)) {
        prefs[Keys.ENABLED_LANGUAGES] = (currentEnabled + languageTag).joinToString(",")
      }
    }
  }

  suspend fun setThemeMode(themeMode: ThemeMode) {
    context.voxKeyDataStore.edit { it[Keys.THEME_MODE] = themeMode.name }
  }

  suspend fun dismissFirstUseHint() {
    context.voxKeyDataStore.edit { it[Keys.FIRST_USE_HINT_DISMISSED] = true }
  }

  suspend fun setShowCommandDevPanel(show: Boolean) {
    context.voxKeyDataStore.edit { it[Keys.SHOW_COMMAND_DEV_PANEL] = show }
  }

  companion object {
    @Volatile private var instance: SettingsRepository? = null

    fun getInstance(context: Context): SettingsRepository =
      instance
        ?: synchronized(this) {
          instance ?: SettingsRepository(context.applicationContext).also { instance = it }
        }
  }
}
