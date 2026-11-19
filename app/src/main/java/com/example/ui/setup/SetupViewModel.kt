package com.example.ui.setup

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.RecordingMode
import com.example.data.SettingsRepository
import com.example.data.ThemeMode
import com.example.data.VoxKeySettings
import com.example.ime.VoiceImeService
import com.example.speech.LanguageAvailability
import com.example.speech.SpeechCapabilityInspector
import com.example.speech.SpeechLanguages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SystemStatus(
  val isImeEnabled: Boolean = false,
  val isImeSelected: Boolean = false,
  val hasMicPermission: Boolean = false,
  val isOnDeviceApiSupported: Boolean = false,
  val isAudioPipeSupported: Boolean = false,
  val apiLevel: Int = Build.VERSION.SDK_INT,
)

data class SetupUiState(
  val settings: VoxKeySettings = VoxKeySettings(),
  val systemStatus: SystemStatus = SystemStatus(),
  val languageAvailability: Map<String, LanguageAvailability> = emptyMap(),
  val modelFeedback: String? = null,
)

class SetupViewModel(application: Application) : AndroidViewModel(application) {
  private val appContext = application.applicationContext
  private val settingsRepo = SettingsRepository.getInstance(appContext)
  private val capabilityInspector = SpeechCapabilityInspector(appContext)

  private val systemStatusFlow = MutableStateFlow(SystemStatus())
  private val languageAvailFlow = MutableStateFlow<Map<String, LanguageAvailability>>(emptyMap())
  private val feedbackFlow = MutableStateFlow<String?>(null)

  val uiState: StateFlow<SetupUiState> =
    combine(settingsRepo.settingsFlow, systemStatusFlow, languageAvailFlow, feedbackFlow) {
        settings,
        sys,
        avail,
        feedback ->
        SetupUiState(
          settings = settings,
          systemStatus = sys,
          languageAvailability = avail,
          modelFeedback = feedback,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SetupUiState(),
      )

  init {
    refreshStatus()
  }

  fun refreshStatus() {
    val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val expectedComponent = ComponentName(appContext, VoiceImeService::class.java)
    val enabledList = imm?.enabledInputMethodList.orEmpty()
    val isEnabled = enabledList.any { it.component == expectedComponent }

    val defaultIme =
      runCatching {
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
      }.getOrNull()
    val isSelected =
      defaultIme != null &&
        (ComponentName.unflattenFromString(defaultIme) == expectedComponent ||
          defaultIme == expectedComponent.flattenToShortString())

    val hasMic =
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    systemStatusFlow.value =
      SystemStatus(
        isImeEnabled = isEnabled,
        isImeSelected = isSelected,
        hasMicPermission = hasMic,
        isOnDeviceApiSupported = capabilityInspector.isOnDeviceApiSupported(),
        isAudioPipeSupported = capabilityInspector.isAudioPipeSupported(),
      )

    val allTags = SpeechLanguages.catalog.map { it.tag }
    languageAvailFlow.update { current ->
      if (current.isEmpty()) allTags.associateWith { LanguageAvailability.Checking } else current
    }
    capabilityInspector.checkLanguageSupport(allTags) { result ->
      languageAvailFlow.value = result
    }
  }

  fun setRecordingMode(mode: RecordingMode) {
    viewModelScope.launch { settingsRepo.setRecordingMode(mode) }
  }

  fun setPreBufferMs(ms: Int) {
    viewModelScope.launch { settingsRepo.setPreBufferMs(ms) }
  }

  fun setThemeMode(themeMode: ThemeMode) {
    viewModelScope.launch { settingsRepo.setThemeMode(themeMode) }
  }

  fun setShowCommandDevPanel(show: Boolean) {
    viewModelScope.launch { settingsRepo.setShowCommandDevPanel(show) }
  }

  fun toggleLanguageEnabled(tag: String) {
    viewModelScope.launch {
      val current = uiState.value.settings.enabledLanguages
      val next =
        if (current.contains(tag)) {
          if (current.size > 1) current - tag else current
        } else {
          current + tag
        }
      settingsRepo.setEnabledLanguages(next)
    }
  }

  fun setActiveLanguage(tag: String) {
    viewModelScope.launch { settingsRepo.setActiveLanguage(tag) }
  }

  fun triggerModelDownload(tag: String) {
    val started = capabilityInspector.triggerModelDownload(tag)
    feedbackFlow.value =
      if (started) {
        "Requested offline speech pack download for $tag"
      } else {
        "System model download requires Android 13+ with on-device speech services"
      }
    refreshStatus()
  }

  fun clearFeedback() {
    feedbackFlow.value = null
  }
}
