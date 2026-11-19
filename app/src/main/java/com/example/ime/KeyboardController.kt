package com.example.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import com.example.audio.AudioPreBufferManager
import com.example.command.ParsedCommand
import com.example.command.VoiceCommandParser
import com.example.data.EmojiCatalog
import com.example.data.EmojiCategory
import com.example.data.EmojiItem
import com.example.data.EmojiRepository
import com.example.data.RecordingMode
import com.example.data.SettingsRepository
import com.example.data.VoxKeySettings
import com.example.speech.OnDeviceSpeechEngine
import com.example.speech.SpeechCapabilityInspector
import com.example.speech.SpeechLanguages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class EmojiInputTarget {
  SEARCH,
  ALIAS,
}

data class CommandDevPanelState(
  val heard: String = "—",
  val parsed: String = "—",
  val outcome: String = "idle",
  val timing: String = "—",
)

data class KeyboardUiState(
  val settings: VoxKeySettings = VoxKeySettings(),
  val isListening: Boolean = false,
  val isCommandRecording: Boolean = false,
  val isCommandPendingFinal: Boolean = false,
  val isSpeechReady: Boolean = false,
  val rmsDb: Float = 0f,
  val isNavigateMode: Boolean = false,
  val isSelectEnabled: Boolean = false,
  val isFallbackExpanded: Boolean = false,
  val isEmojiPickerExpanded: Boolean = false,
  val fallbackText: String = "",
  val fallbackSelStart: Int = 0,
  val fallbackSelEnd: Int = 0,
  val canFallbackUndo: Boolean = false,
  val canFallbackRedo: Boolean = false,
  val hasImportedSnapshot: Boolean = false,
  val imeActionType: ImeActionType = ImeActionType.NEWLINE,
  val isRestrictedAppTarget: Boolean = false,
  val livePartialAppPreview: String = "",
  val commandPartialFeedback: String = "",
  val commandDevState: CommandDevPanelState = CommandDevPanelState(),
  val statusMessage: String? = null,
  val statusIsError: Boolean = false,
  val tooltipText: String? = null,
  val activeLanguageShort: String = "EN",
  val hasMicPermission: Boolean = false,
  val emojiItems: List<EmojiItem> =
    EmojiCatalog.all.map {
      EmojiItem(it.emoji, it.defaultName, null, it.keywords, it.category, 0, 0L)
    },
  val emojiSearchQuery: String = "",
  val selectedEmojiCategory: EmojiCategory = EmojiCategory.SMILEYS,
  val editingAliasEmoji: EmojiItem? = null,
  val aliasDraft: String = "",
  val activeEmojiTextTarget: EmojiInputTarget? = null,
  val lastWordTyped: String = "",
) {
  val isTargetingFallback: Boolean
    get() = isFallbackExpanded
}

class KeyboardController(
  private val context: Context,
  private val settingsRepository: SettingsRepository,
  private val emojiRepository: EmojiRepository,
  val targetController: ImeTargetController,
  private val audioPreBufferManager: AudioPreBufferManager,
  private val speechEngine: OnDeviceSpeechEngine,
  private val onSwitchToNextKeyboard: () -> Unit,
  private val onOpenSettings: () -> Unit,
  private val nowMsProvider: () -> Long = { SystemClock.elapsedRealtime() },
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val undoHistory = UndoHistoryManager()
  private val capabilityInspector = SpeechCapabilityInspector(context)

  private val _uiState = MutableStateFlow(KeyboardUiState())
  val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

  private var sessionCounter = 0L
  private var activeSessionToken = -1L
  private var activeDictationPartial = ""

  private var dictationStartedForFallback = false
  private var dictationStartedForEmojiInput: EmojiInputTarget? = null
  private var dictationTargetSig: TargetSignature? = null
  private var dictationNeedsLeadingSpace = false
  private var dictationContinueLowercase = false
  private var fallbackBaseSnapshot: TextSnapshot? = null
  private var emojiBaseText: String = ""
  private var fallbackSelectionAnchor: Int = -1
  private var rememberedAppTail: String = ""
  private var rememberedAppSig: TargetSignature? = null

  // Command mode (hold-to-record OR continuous tap-to-toggle with breaks) state
  private var activeCommandId: Long = -1L
  private var activeCommandTargetSig: TargetSignature? = null
  private var activeCommandForFallback: Boolean = false
  private var activeCommandExecuted: Boolean = false
  private var isCommandContinuous: Boolean = false
  private var isCommandTypeStreamActive: Boolean = false
  private var commandHoldActive: Boolean = false
  private var commandReleaseElapsedMs: Long? = null
  private var commandFinalElapsedMs: Long? = null
  private var bufferedEarlyCommandFinal: String? = null
  private var lastCommandPartial: String = ""
  private var commandTimeoutJob: Job? = null

  private var importedSnapshot: ImportedTargetSnapshot? = null
  private var pendingTargetMismatchSig: TargetSignature? = null

  private var isWindowVisible = false
  private var repeatJob: Job? = null
  private var statusClearJob: Job? = null
  private var tooltipClearJob: Job? = null
  private var pendingFinishInputJob: Job? = null

  init {
    scope.launch {
      settingsRepository.settingsFlow.collect { settings ->
        val prevSettings = _uiState.value.settings
        val langShort = SpeechLanguages.find(settings.activeLanguage).shortCode
        _uiState.update {
          it.copy(
            settings = settings,
            activeLanguageShort = langShort,
            hasMicPermission = audioPreBufferManager.hasRecordPermission(),
          )
        }
        if (isWindowVisible && prevSettings.preBufferMs != settings.preBufferMs) {
          audioPreBufferManager.updatePreBufferDuration(settings.preBufferMs)
        }
        if (isWindowVisible && prevSettings.activeLanguage != settings.activeLanguage) {
          if (isCommandContinuous) {
            stopContinuousCommand(resumePreBuffer = false, outcomeReason = "language changed")
            startContinuousCommand()
          } else if (activeCommandId >= 0L && !activeCommandExecuted) {
            invalidatePendingCommand("language changed")
          }
          if (_uiState.value.isListening) {
            val emojiTarget = dictationStartedForEmojiInput
            stopDictation(commitToTarget = true, resumePreBuffer = false)
            startDictation(overrideEmojiTarget = emojiTarget)
          }
        }
      }
    }

    scope.launch {
      emojiRepository.emojisFlow.collect { items ->
        _uiState.update { current ->
          val refreshedEditing =
            current.editingAliasEmoji?.let { editing ->
              items.firstOrNull { it.emoji == editing.emoji }
            }
          current.copy(emojiItems = items, editingAliasEmoji = refreshedEditing)
        }
      }
    }
  }

  fun onWindowShown() {
    isWindowVisible = true
    val hasMic = audioPreBufferManager.hasRecordPermission()
    _uiState.update {
      it.copy(
        hasMicPermission = hasMic,
        imeActionType = targetController.resolveImeAction(),
        isRestrictedAppTarget = targetController.isRestrictedInput(),
      )
    }
    if (hasMic) {
      speechEngine.warmUp(_uiState.value.settings.preBufferMs)
    }
  }

  fun onWindowHidden() {
    isWindowVisible = false
    stopRepeatingAction()
    pendingFinishInputJob?.cancel()
    pendingFinishInputJob = null
    if (isCommandContinuous) {
      stopContinuousCommand(resumePreBuffer = false, outcomeReason = "keyboard hidden")
    } else if (activeCommandId >= 0L && !activeCommandExecuted) {
      invalidatePendingCommand("keyboard hidden", resumePreBuffer = false)
    }
    if (_uiState.value.isListening) {
      stopDictation(commitToTarget = true, resumePreBuffer = false)
    }
    speechEngine.shutDown()
    _uiState.update {
      it.copy(
        isListening = false,
        isCommandRecording = false,
        isCommandPendingFinal = false,
        isSpeechReady = false,
        rmsDb = 0f,
        livePartialAppPreview = "",
        commandPartialFeedback = "",
        tooltipText = null,
      )
    }
  }

  fun onStartInput(restarting: Boolean) {
    pendingFinishInputJob?.cancel()
    pendingFinishInputJob = null
    val newSig = targetController.currentTargetSignature()
    if (!restarting && newSig != rememberedAppSig) {
      rememberedAppTail = ""
      rememberedAppSig = newSig
    }
    if (
      !restarting &&
        isCommandContinuous &&
        !activeCommandForFallback &&
        newSig != null &&
        activeCommandTargetSig != null &&
        activeCommandTargetSig != newSig
    ) {
      stopContinuousCommand(resumePreBuffer = true, outcomeReason = "target changed")
    } else if (
      !restarting &&
        activeCommandId >= 0L &&
        !activeCommandExecuted &&
        !activeCommandForFallback &&
        activeCommandTargetSig != newSig
    ) {
      invalidatePendingCommand("target changed")
    }
    if (
      !restarting &&
        _uiState.value.isListening &&
        !dictationStartedForFallback &&
        dictationStartedForEmojiInput == null
    ) {
      if (newSig != null && dictationTargetSig != null && dictationTargetSig != newSig) {
        stopDictation(commitToTarget = false, resumePreBuffer = true)
      }
    }
    if (newSig == importedSnapshot?.signature) {
      pendingTargetMismatchSig = null
    }
    targetController.resetSelectionAnchor()
    _uiState.update {
      it.copy(
        imeActionType = targetController.resolveImeAction(),
        isRestrictedAppTarget = targetController.isRestrictedInput(),
        lastWordTyped = resolveLastWordForState(it),
      )
    }
  }

  fun onFinishInput() {
    val activeDictationToApp =
      _uiState.value.isListening &&
        !dictationStartedForFallback &&
        dictationStartedForEmojiInput == null
    val activeCommandToApp = isCommandContinuous && !activeCommandForFallback
    if (activeDictationToApp || activeCommandToApp) {
      pendingFinishInputJob?.cancel()
      val startedDictationSig = dictationTargetSig
      val startedCommandSig = activeCommandTargetSig
      pendingFinishInputJob =
        scope.launch {
          delay(300L)
          if (
            _uiState.value.isListening &&
              !dictationStartedForFallback &&
              dictationStartedForEmojiInput == null &&
              (!isWindowVisible || targetController.currentTargetSignature() != startedDictationSig)
          ) {
            stopDictation(commitToTarget = true, resumePreBuffer = isWindowVisible)
          }
          if (
            isCommandContinuous &&
              !activeCommandForFallback &&
              (!isWindowVisible || targetController.currentTargetSignature() != startedCommandSig)
          ) {
            stopContinuousCommand(resumePreBuffer = isWindowVisible, outcomeReason = "target changed")
          }
        }
    }
  }

  fun onUpdateSelection(newSelStart: Int, newSelEnd: Int) {
    targetController.updateSelection(newSelStart, newSelEnd)
  }

  fun onDictateTap() {
    dismissFirstUseHint()
    if (isCommandContinuous) {
      stopContinuousCommand(resumePreBuffer = false, outcomeReason = "switched to dictate")
    } else if (activeCommandId >= 0L && !activeCommandExecuted) {
      invalidatePendingCommand("switched to dictate")
    }
    if (_uiState.value.settings.recordingMode != RecordingMode.TAP_TO_TOGGLE) return
    if (_uiState.value.isListening) {
      stopDictation(commitToTarget = true, resumePreBuffer = true)
    } else {
      startDictation()
    }
  }

  fun onDictatePressStart() {
    if (isCommandContinuous) {
      stopContinuousCommand(resumePreBuffer = false, outcomeReason = "switched to dictate")
    } else if (activeCommandId >= 0L && !activeCommandExecuted) {
      invalidatePendingCommand("switched to dictate")
    }
    if (_uiState.value.settings.recordingMode != RecordingMode.HOLD_TO_RECORD) return
    if (!_uiState.value.isListening) {
      startDictation()
    }
  }

  fun onDictatePressEnd() {
    dismissFirstUseHint()
    if (_uiState.value.settings.recordingMode != RecordingMode.HOLD_TO_RECORD) return
    if (_uiState.value.isListening) {
      stopDictation(commitToTarget = true, resumePreBuffer = true)
    }
  }

  fun onCommandTap() {
    dismissFirstUseHint()
    if (_uiState.value.settings.recordingMode != RecordingMode.TAP_TO_TOGGLE) return
    if (isCommandContinuous || _uiState.value.isCommandRecording) {
      stopContinuousCommand(resumePreBuffer = true)
    } else {
      startContinuousCommand()
    }
  }

  private fun startContinuousCommand() {
    if (!audioPreBufferManager.hasRecordPermission()) {
      _uiState.update {
        it.copy(
          hasMicPermission = false,
          commandDevState = CommandDevPanelState(outcome = "mic permission required"),
        )
      }
      showStatus("Grant microphone permission in Settings", isError = true)
      return
    }

    if (_uiState.value.isListening) {
      stopDictation(commitToTarget = true, resumePreBuffer = false)
    }
    if (activeCommandId >= 0L && !activeCommandExecuted) {
      invalidatePendingCommand("superseded", resumePreBuffer = false)
    }
    commandTimeoutJob?.cancel()

    val s = _uiState.value
    val cmdId = ++sessionCounter
    activeCommandId = cmdId
    activeCommandForFallback = s.isTargetingFallback
    activeCommandTargetSig = if (activeCommandForFallback) null else targetController.currentTargetSignature()
    activeCommandExecuted = false
    isCommandContinuous = true
    isCommandTypeStreamActive = false
    commandHoldActive = false
    commandReleaseElapsedMs = null
    commandFinalElapsedMs = null
    bufferedEarlyCommandFinal = null
    lastCommandPartial = ""

    _uiState.update {
      it.copy(
        isCommandRecording = true,
        isCommandPendingFinal = false,
        isSpeechReady = false,
        rmsDb = 0f,
        commandPartialFeedback = "",
        commandDevState =
          CommandDevPanelState(
            heard = "…",
            parsed = "…",
            outcome = "listening (continuous)",
            timing = "ready…",
          ),
      )
    }

    speechEngine.startDictation(
      sessionToken = cmdId,
      languageTag = s.settings.activeLanguage,
      preBufferMs = s.settings.preBufferMs,
      singleUtterance = false,
      listener =
        object : OnDeviceSpeechEngine.Listener {
          override fun onReady(sessionToken: Long, usingPreBufferPipe: Boolean) {
            runOnMain {
              if (sessionToken != activeCommandId || !isCommandContinuous) return@runOnMain
              val label =
                when {
                  isCommandTypeStreamActive && usingPreBufferPipe -> "type stream (pipe)"
                  isCommandTypeStreamActive -> "type stream (mic)"
                  usingPreBufferPipe -> "listening (pipe)"
                  else -> "listening (mic)"
                }
              _uiState.update {
                it.copy(
                  isSpeechReady = true,
                  commandDevState = it.commandDevState.copy(outcome = label),
                )
              }
            }
          }

          override fun onRmsChanged(sessionToken: Long, rmsDb: Float) {
            if (sessionToken != activeCommandId || !isCommandContinuous) return
            _uiState.update { it.copy(rmsDb = rmsDb.coerceIn(0f, 12f)) }
          }

          override fun onPartialText(sessionToken: Long, partial: String) {
            runOnMain {
              if (sessionToken != activeCommandId || !isCommandContinuous) return@runOnMain
              lastCommandPartial = partial
              _uiState.update {
                it.copy(
                  commandPartialFeedback = partial,
                  commandDevState = it.commandDevState.copy(heard = "$partial (partial)"),
                )
              }
            }
          }

          override fun onSegmentFinal(sessionToken: Long, text: String) {
            runOnMain {
              if (sessionToken != activeCommandId || !isCommandContinuous) return@runOnMain
              lastCommandPartial = ""
              val arrivedAt = nowMsProvider()
              executeCompletedCommand(
                cmdId = sessionToken,
                finalText = text,
                finalElapsedMs = arrivedAt,
                isContinuous = true,
              )
            }
          }

          override fun onFatalError(
            sessionToken: Long,
            errorCode: Int,
            message: String,
            isModelMissing: Boolean,
          ) {
            runOnMain {
              if (sessionToken != activeCommandId || !isCommandContinuous) return@runOnMain
              stopContinuousCommand(resumePreBuffer = true, outcomeReason = "error ($errorCode)")
              if (isModelMissing) {
                capabilityInspector.triggerModelDownload(_uiState.value.settings.activeLanguage)
                showStatus("Downloading offline model or check Settings", isError = true)
              } else {
                showStatus(message, isError = true)
              }
            }
          }
        },
    )
  }

  private fun stopContinuousCommand(
    resumePreBuffer: Boolean,
    outcomeReason: String? = null,
  ) {
    val wasActive = isCommandContinuous || _uiState.value.isCommandRecording
    isCommandContinuous = false
    isCommandTypeStreamActive = false
    commandHoldActive = false
    activeCommandExecuted = true
    activeCommandId = -1L
    bufferedEarlyCommandFinal = null
    lastCommandPartial = ""
    commandTimeoutJob?.cancel()

    if (wasActive) {
      speechEngine.stopDictation(
        resumePreBuffer = resumePreBuffer && isWindowVisible,
        preBufferMs = _uiState.value.settings.preBufferMs,
      )
    }

    _uiState.update {
      it.copy(
        isCommandRecording = false,
        isCommandPendingFinal = false,
        isSpeechReady = false,
        rmsDb = 0f,
        commandPartialFeedback = "",
        commandDevState =
          if (outcomeReason != null) {
            it.commandDevState.copy(outcome = outcomeReason, timing = "—")
          } else {
            it.commandDevState
          },
      )
    }
  }

  fun onCommandPressStart() {
    if (!audioPreBufferManager.hasRecordPermission()) {
      _uiState.update {
        it.copy(
          hasMicPermission = false,
          commandDevState = CommandDevPanelState(outcome = "mic permission required"),
        )
      }
      showStatus("Grant microphone permission in Settings", isError = true)
      return
    }

    if (_uiState.value.isListening) {
      stopDictation(commitToTarget = true, resumePreBuffer = false)
    }
    if (isCommandContinuous) {
      stopContinuousCommand(resumePreBuffer = false, outcomeReason = "superseded")
    } else if (activeCommandId >= 0L && !activeCommandExecuted) {
      invalidatePendingCommand("superseded", resumePreBuffer = false)
    }
    commandTimeoutJob?.cancel()

    val s = _uiState.value
    val cmdId = ++sessionCounter
    activeCommandId = cmdId
    activeCommandForFallback = s.isTargetingFallback
    activeCommandTargetSig = if (activeCommandForFallback) null else targetController.currentTargetSignature()
    activeCommandExecuted = false
    isCommandContinuous = false
    isCommandTypeStreamActive = false
    commandHoldActive = true
    commandReleaseElapsedMs = null
    commandFinalElapsedMs = null
    bufferedEarlyCommandFinal = null
    lastCommandPartial = ""

    _uiState.update {
      it.copy(
        isCommandRecording = true,
        isCommandPendingFinal = false,
        isSpeechReady = false,
        rmsDb = 0f,
        commandPartialFeedback = "",
        commandDevState =
          CommandDevPanelState(
            heard = "…",
            parsed = "…",
            outcome = "listening",
            timing = "recording…",
          ),
      )
    }

    speechEngine.startDictation(
      sessionToken = cmdId,
      languageTag = s.settings.activeLanguage,
      preBufferMs = s.settings.preBufferMs,
      singleUtterance = true,
      listener =
        object : OnDeviceSpeechEngine.Listener {
          override fun onReady(sessionToken: Long, usingPreBufferPipe: Boolean) {
            runOnMain {
              if (sessionToken != activeCommandId || activeCommandExecuted) return@runOnMain
              _uiState.update {
                it.copy(
                  isSpeechReady = true,
                  commandDevState =
                    it.commandDevState.copy(
                      outcome =
                        if (commandHoldActive) {
                          if (usingPreBufferPipe) "listening (pipe)" else "listening (mic)"
                        } else {
                          it.commandDevState.outcome
                        },
                    ),
                )
              }
            }
          }

          override fun onRmsChanged(sessionToken: Long, rmsDb: Float) {
            if (sessionToken != activeCommandId || !commandHoldActive) return
            _uiState.update { it.copy(rmsDb = rmsDb.coerceIn(0f, 12f)) }
          }

          override fun onPartialText(sessionToken: Long, partial: String) {
            runOnMain {
              if (sessionToken != activeCommandId || activeCommandExecuted) return@runOnMain
              // Partial transcripts appear ONLY as temporary feedback; never inserted as text
              lastCommandPartial = partial
              _uiState.update {
                it.copy(
                  commandPartialFeedback = partial,
                  commandDevState = it.commandDevState.copy(heard = "$partial (partial)"),
                )
              }
            }
          }

          override fun onSegmentFinal(sessionToken: Long, text: String) = Unit

          override fun onUtteranceCompleted(sessionToken: Long, finalText: String) {
            runOnMain {
              if (sessionToken != activeCommandId || activeCommandExecuted) return@runOnMain
              val arrivedAt = nowMsProvider()
              if (commandHoldActive) {
                commandFinalElapsedMs = arrivedAt
                bufferedEarlyCommandFinal = finalText
                _uiState.update {
                  it.copy(
                    commandPartialFeedback = "",
                    commandDevState =
                      it.commandDevState.copy(
                        heard = finalText.ifEmpty { "(empty)" },
                        outcome = "ready (release to run)",
                      ),
                  )
                }
              } else {
                completePendingCommand(sessionToken, finalText, arrivedAt)
              }
            }
          }

          override fun onFatalError(
            sessionToken: Long,
            errorCode: Int,
            message: String,
            isModelMissing: Boolean,
          ) {
            runOnMain {
              if (sessionToken != activeCommandId || activeCommandExecuted) return@runOnMain
              activeCommandExecuted = true
              activeCommandId = -1L
              commandHoldActive = false
              val lastPartial = lastCommandPartial
              lastCommandPartial = ""
              commandTimeoutJob?.cancel()
              val outcomeLabel =
                if (
                  errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                  "no speech"
                } else {
                  "error ($errorCode)"
                }
              val errMs = nowMsProvider()
              val timingLabel =
                commandReleaseElapsedMs?.let { rel ->
                  "release 0ms → err +${(errMs - rel).coerceAtLeast(0L)}ms"
                } ?: "—"

              _uiState.update {
                it.copy(
                  isCommandRecording = false,
                  isCommandPendingFinal = false,
                  isSpeechReady = false,
                  rmsDb = 0f,
                  commandPartialFeedback = "",
                  commandDevState =
                    CommandDevPanelState(
                      heard = if (lastPartial.isNotEmpty()) "— (partial: $lastPartial)" else "—",
                      parsed = "—",
                      outcome = outcomeLabel,
                      timing = timingLabel,
                    ),
                )
              }
              if (isModelMissing) {
                capabilityInspector.triggerModelDownload(_uiState.value.settings.activeLanguage)
                showStatus("Downloading offline model or check Settings", isError = true)
              } else if (outcomeLabel != "no speech") {
                showStatus(message, isError = true)
              }
              if (isWindowVisible) {
                speechEngine.stopDictation(
                  resumePreBuffer = true,
                  preBufferMs = _uiState.value.settings.preBufferMs,
                )
              }
            }
          }
        },
    )
  }

  fun onCommandPressEnd() {
    dismissFirstUseHint()
    if (!commandHoldActive) return
    commandHoldActive = false
    val cmdId = activeCommandId
    if (cmdId < 0L || activeCommandExecuted) {
      _uiState.update {
        it.copy(isCommandRecording = false, isCommandPendingFinal = false, isSpeechReady = false, rmsDb = 0f)
      }
      return
    }

    val releaseMs = nowMsProvider()
    commandReleaseElapsedMs = releaseMs

    val earlyFinal = bufferedEarlyCommandFinal
    if (earlyFinal != null) {
      bufferedEarlyCommandFinal = null
      completePendingCommand(cmdId, earlyFinal, commandFinalElapsedMs ?: releaseMs)
      return
    }

    _uiState.update {
      it.copy(
        isCommandRecording = false,
        isCommandPendingFinal = true,
        rmsDb = 0f,
        commandDevState =
          it.commandDevState.copy(
            outcome = "waiting final…",
            timing = "release 0ms → waiting final…",
          ),
      )
    }

    speechEngine.finishUtteranceForFinal(cmdId)

    commandTimeoutJob?.cancel()
    commandTimeoutJob =
      scope.launch {
        delay(3500L)
        if (activeCommandId == cmdId && !activeCommandExecuted) {
          invalidatePendingCommand("timeout")
        }
      }
  }

  fun dismissCommandDevPanel() {
    scope.launch { settingsRepository.setShowCommandDevPanel(false) }
  }

  fun toggleNavigateMode() {
    dismissFirstUseHint()
    val current = _uiState.value
    val nextNav = !current.isNavigateMode
    if (nextNav && isCommandContinuous) {
      stopContinuousCommand(resumePreBuffer = true, outcomeReason = "mode changed")
    } else if (activeCommandId >= 0L && !activeCommandExecuted) {
      invalidatePendingCommand("mode changed")
    }
    if (
      nextNav &&
        current.isListening &&
        current.settings.recordingMode == RecordingMode.TAP_TO_TOGGLE
    ) {
      stopDictation(commitToTarget = true, resumePreBuffer = true)
    }
    if (!nextNav) {
      targetController.resetSelectionAnchor()
      fallbackSelectionAnchor = -1
    }
    _uiState.update {
      it.copy(
        isNavigateMode = nextNav,
        isSelectEnabled = if (nextNav) it.isSelectEnabled else false,
      )
    }
  }

  fun toggleSelectMode() {
    val next = !_uiState.value.isSelectEnabled
    if (!next) {
      targetController.resetSelectionAnchor()
      fallbackSelectionAnchor = -1
    } else {
      val s = _uiState.value
      if (s.isTargetingFallback) {
        fallbackSelectionAnchor = s.fallbackSelStart
      }
    }
    _uiState.update { it.copy(isSelectEnabled = next) }
  }

  fun toggleFallbackExpanded() {
    dismissFirstUseHint()
    if (isCommandContinuous) {
      stopContinuousCommand(resumePreBuffer = true, outcomeReason = "target switched")
    } else if (activeCommandId >= 0L && !activeCommandExecuted) {
      invalidatePendingCommand("target switched")
    }
    if (_uiState.value.isListening) {
      stopDictation(commitToTarget = true, resumePreBuffer = true)
    }
    fallbackSelectionAnchor = -1
    _uiState.update {
      val nextExpanded = !it.isFallbackExpanded
      val updated =
        it.copy(
          isFallbackExpanded = nextExpanded,
          isEmojiPickerExpanded = false,
          activeEmojiTextTarget = null,
        )
      updated.copy(lastWordTyped = resolveLastWordForState(updated))
    }
  }

  fun toggleEmojiPicker() {
    dismissFirstUseHint()
    if (isCommandContinuous) {
      stopContinuousCommand(resumePreBuffer = true, outcomeReason = "mode changed")
    } else if (activeCommandId >= 0L && !activeCommandExecuted) {
      invalidatePendingCommand("mode changed")
    }
    if (_uiState.value.isListening) {
      stopDictation(commitToTarget = true, resumePreBuffer = true)
    }
    _uiState.update {
      val opening = !it.isEmojiPickerExpanded
      it.copy(
        isEmojiPickerExpanded = opening,
        editingAliasEmoji = if (opening) it.editingAliasEmoji else null,
        activeEmojiTextTarget = null,
      )
    }
  }

  fun closeEmojiPicker() {
    if (_uiState.value.isListening && dictationStartedForEmojiInput != null) {
      stopDictation(commitToTarget = true, resumePreBuffer = true)
    }
    _uiState.update {
      it.copy(
        isEmojiPickerExpanded = false,
        editingAliasEmoji = null,
        activeEmojiTextTarget = null,
      )
    }
  }

  fun setEmojiSearchQuery(query: String) {
    _uiState.update { it.copy(emojiSearchQuery = query) }
  }

  fun selectEmojiCategory(category: EmojiCategory) {
    _uiState.update { it.copy(selectedEmojiCategory = category) }
  }

  fun setEmojiTextTarget(target: EmojiInputTarget?) {
    _uiState.update { it.copy(activeEmojiTextTarget = target) }
  }

  fun appendToEmojiTextInput(ch: String) {
    val s = _uiState.value
    when (s.activeEmojiTextTarget) {
      EmojiInputTarget.SEARCH ->
        _uiState.update { it.copy(emojiSearchQuery = it.emojiSearchQuery + ch) }
      EmojiInputTarget.ALIAS -> _uiState.update { it.copy(aliasDraft = it.aliasDraft + ch) }
      null -> Unit
    }
  }

  fun backspaceEmojiTextInput() {
    val s = _uiState.value
    when (s.activeEmojiTextTarget) {
      EmojiInputTarget.SEARCH ->
        _uiState.update {
          val next =
            if (it.emojiSearchQuery.isEmpty()) ""
            else it.emojiSearchQuery.dropLast(GraphemeUtils.lastGraphemeLength(it.emojiSearchQuery))
          it.copy(emojiSearchQuery = next)
        }
      EmojiInputTarget.ALIAS ->
        _uiState.update {
          val next =
            if (it.aliasDraft.isEmpty()) ""
            else it.aliasDraft.dropLast(GraphemeUtils.lastGraphemeLength(it.aliasDraft))
          it.copy(aliasDraft = next)
        }
      null -> Unit
    }
  }

  fun startDictateForEmojiInput(target: EmojiInputTarget) {
    _uiState.update { it.copy(activeEmojiTextTarget = target) }
    if (_uiState.value.isListening) {
      stopDictation(commitToTarget = true, resumePreBuffer = true)
    } else {
      startDictation(overrideEmojiTarget = target)
    }
  }

  fun openAliasEditor(item: EmojiItem) {
    _uiState.update {
      it.copy(
        editingAliasEmoji = item,
        aliasDraft = item.customAlias.orEmpty(),
        activeEmojiTextTarget = EmojiInputTarget.ALIAS,
      )
    }
  }

  fun updateAliasDraft(draft: String) {
    _uiState.update { it.copy(aliasDraft = draft) }
  }

  fun saveCustomAlias() {
    val item = _uiState.value.editingAliasEmoji ?: return
    val draft = _uiState.value.aliasDraft
    scope.launch {
      emojiRepository.setCustomAlias(item.emoji, draft)
      _uiState.update {
        it.copy(
          editingAliasEmoji = null,
          aliasDraft = "",
          activeEmojiTextTarget = null,
        )
      }
    }
  }

  fun resetCustomAlias() {
    val item = _uiState.value.editingAliasEmoji ?: return
    scope.launch {
      emojiRepository.setCustomAlias(item.emoji, null)
      _uiState.update {
        it.copy(
          editingAliasEmoji = null,
          aliasDraft = "",
          activeEmojiTextTarget = null,
        )
      }
    }
  }

  fun closeAliasEditor() {
    _uiState.update {
      it.copy(
        editingAliasEmoji = null,
        aliasDraft = "",
        activeEmojiTextTarget = null,
      )
    }
  }

  fun onEmojiTapped(item: EmojiItem) {
    scope.launch { emojiRepository.recordUsage(item.emoji) }
    if (_uiState.value.isListening) {
      stopDictation(commitToTarget = true, resumePreBuffer = true)
    }
    if (_uiState.value.isTargetingFallback) {
      val s = _uiState.value
      val before = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
      val (nextText, newStart, newEnd) =
        GraphemeUtils.insertOrReplace(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd, item.emoji)
      val after = TextSnapshot(nextText, newStart, newEnd)
      undoHistory.recordChange(before, after, EditGroupKind.EMOJI_INSERT)
      applyFallbackSnapshot(after)
    } else {
      targetController.commitText(item.emoji)
      recordAppTailAppend(item.emoji)
    }
  }

  fun onFallbackEditorChanged(newText: String, newSelStart: Int, newSelEnd: Int) {
    val s = _uiState.value
    val before = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
    val after = TextSnapshot(newText, newSelStart, newSelEnd)
    if (before.text != after.text) {
      val kind =
        if (after.text.length < before.text.length) EditGroupKind.MANUAL_DELETE
        else EditGroupKind.MANUAL_INSERT
      undoHistory.recordChange(before, after, kind)
    }
    applyFallbackSnapshot(after)
  }

  fun clearFallbackText() {
    if (activeCommandId >= 0L && !activeCommandExecuted && activeCommandForFallback) {
      invalidatePendingCommand("draft cleared")
    }
    if (_uiState.value.isListening && dictationStartedForFallback) {
      stopDictation(commitToTarget = false, resumePreBuffer = true)
    }
    val s = _uiState.value
    if (s.fallbackText.isEmpty()) return
    val before = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
    val after = TextSnapshot("", 0, 0)
    undoHistory.recordChange(before, after, EditGroupKind.CLEAR)
    fallbackSelectionAnchor = -1
    applyFallbackSnapshot(after)
  }

  fun onDeleteSingle() {
    dismissFirstUseHint()
    val wasListening = _uiState.value.isListening
    if (wasListening) {
      commitCurrentSegmentAndResetBase()
    }
    val s = _uiState.value
    if (s.isTargetingFallback) {
      val before = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
      val (nextText, newStart, newEnd) =
        GraphemeUtils.deleteSelectionOrBackward(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
      if (nextText != s.fallbackText) {
        val after = TextSnapshot(nextText, newStart, newEnd)
        undoHistory.recordChange(before, after, EditGroupKind.MANUAL_DELETE)
        applyFallbackSnapshot(after)
        if (wasListening && dictationStartedForFallback) {
          fallbackBaseSnapshot = after
        }
      }
    } else {
      targetController.deleteSelectionOrBackwardGrapheme()
      recordAppTailBackspace()
      if (wasListening && !dictationStartedForFallback) {
        dictationNeedsLeadingSpace = resolveAppLeadingSpaceNeeded()
        dictationContinueLowercase = resolveAppShouldContinueLowercase()
      }
    }
  }

  fun startRepeatDelete() {
    stopRepeatingAction()
    onDeleteSingle()
    repeatJob =
      scope.launch {
        delay(360L)
        var step = 0
        while (isActive) {
          onDeleteSingle()
          val interval = if (step < 8) 70L else 42L
          step++
          delay(interval)
        }
      }
  }

  fun onSpaceSingle() {
    dismissFirstUseHint()
    if (activeCommandId >= 0L && !activeCommandExecuted) {
      invalidatePendingCommand("space pressed")
    }
    val wasListening = _uiState.value.isListening
    if (wasListening) {
      commitCurrentSegmentAndResetBase()
    }
    val s = _uiState.value
    if (s.isTargetingFallback) {
      val before = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
      val (nextText, newStart, newEnd) =
        GraphemeUtils.insertOrReplace(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd, " ")
      val after = TextSnapshot(nextText, newStart, newEnd)
      undoHistory.recordChange(before, after, EditGroupKind.MANUAL_INSERT)
      applyFallbackSnapshot(after)
      if (wasListening && dictationStartedForFallback) {
        fallbackBaseSnapshot = after
      }
    } else {
      targetController.commitText(" ")
      recordAppTailAppend(" ")
      if (wasListening && !dictationStartedForFallback) {
        dictationNeedsLeadingSpace = false
        dictationContinueLowercase = resolveAppShouldContinueLowercase()
      }
    }
  }

  fun startRepeatSpace() {
    stopRepeatingAction()
    onSpaceSingle()
    repeatJob =
      scope.launch {
        delay(360L)
        while (isActive) {
          onSpaceSingle()
          delay(55L)
        }
      }
  }

  fun stopRepeatingAction() {
    repeatJob?.cancel()
    repeatJob = null
  }

  fun onEnterOrAction() {
    dismissFirstUseHint()
    val wasListening = _uiState.value.isListening
    if (wasListening) {
      commitCurrentSegmentAndResetBase()
    }
    val s = _uiState.value
    if (s.isTargetingFallback) {
      val before = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
      val (nextText, newStart, newEnd) =
        GraphemeUtils.insertOrReplace(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd, "\n")
      val after = TextSnapshot(nextText, newStart, newEnd)
      undoHistory.recordChange(before, after, EditGroupKind.MANUAL_INSERT)
      applyFallbackSnapshot(after)
      if (wasListening && dictationStartedForFallback) {
        fallbackBaseSnapshot = after
      }
    } else {
      targetController.performEnterOrAction()
      if (targetController.resolveImeAction() == ImeActionType.NEWLINE) {
        recordAppTailAppend("\n")
      } else {
        rememberedAppTail = ""
        refreshLastWordState()
      }
      if (wasListening && !dictationStartedForFallback) {
        dictationNeedsLeadingSpace = resolveAppLeadingSpaceNeeded()
        dictationContinueLowercase = resolveAppShouldContinueLowercase()
      }
    }
  }

  fun startRepeatNavigate(direction: NavDirection) {
    stopRepeatingAction()
    navigateOnce(direction)
    repeatJob =
      scope.launch {
        delay(340L)
        while (isActive) {
          navigateOnce(direction)
          delay(65L)
        }
      }
  }

  fun navigateOnce(direction: NavDirection) {
    val s = _uiState.value
    if (s.isTargetingFallback) {
      navigateFallback(direction, s.isSelectEnabled)
    } else {
      targetController.navigate(direction, s.isSelectEnabled)
    }
  }

  fun navigateToEdge(toEnd: Boolean) {
    val s = _uiState.value
    if (s.isTargetingFallback) {
      val target = if (toEnd) s.fallbackText.length else 0
      val (newStart, newEnd) =
        if (s.isSelectEnabled) {
          if (fallbackSelectionAnchor !in 0..s.fallbackText.length) {
            fallbackSelectionAnchor = s.fallbackSelStart
          }
          fallbackSelectionAnchor to target
        } else {
          fallbackSelectionAnchor = -1
          target to target
        }
      _uiState.update { it.copy(fallbackSelStart = newStart, fallbackSelEnd = newEnd) }
    } else {
      targetController.moveToEdge(toEnd, s.isSelectEnabled)
    }
  }

  fun onUndo() {
    dismissFirstUseHint()
    if (_uiState.value.isListening) {
      stopDictation(commitToTarget = true, resumePreBuffer = true)
    }
    val s = _uiState.value
    if (s.isTargetingFallback) {
      val current = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
      val restored = undoHistory.undo(current) ?: return
      applyFallbackSnapshot(restored)
    } else {
      targetController.delegateUndo()
    }
  }

  fun onRedo() {
    dismissFirstUseHint()
    if (_uiState.value.isListening) {
      stopDictation(commitToTarget = true, resumePreBuffer = true)
    }
    val s = _uiState.value
    if (s.isTargetingFallback) {
      val current = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
      val restored = undoHistory.redo(current) ?: return
      applyFallbackSnapshot(restored)
    } else {
      targetController.delegateRedo()
    }
  }

  fun onImportFromApp() {
    dismissFirstUseHint()
    when (val result = targetController.importText()) {
      is ImportResult.Success -> {
        val s = _uiState.value
        val before = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
        val after = TextSnapshot(result.text, result.text.length, result.text.length)
        undoHistory.recordChange(before, after, EditGroupKind.IMPORT)
        importedSnapshot = result.snapshot
        pendingTargetMismatchSig = null
        fallbackSelectionAnchor = -1
        _uiState.update {
          it.copy(
            isFallbackExpanded = true,
            isEmojiPickerExpanded = false,
            fallbackText = after.text,
            fallbackSelStart = after.selStart,
            fallbackSelEnd = after.selEnd,
            canFallbackUndo = undoHistory.canUndo,
            canFallbackRedo = undoHistory.canRedo,
            hasImportedSnapshot = true,
          )
        }
      }
      ImportResult.EmptyField -> {
        showStatus("Field is empty", isError = false)
      }
      ImportResult.Unavailable -> {
        showStatus("App did not expose text; draft kept", isError = true)
      }
    }
  }

  fun onApplyFallback() {
    dismissFirstUseHint()
    if (_uiState.value.isListening && dictationStartedForFallback) {
      stopDictation(commitToTarget = true, resumePreBuffer = true)
    }
    val s = _uiState.value
    if (s.fallbackText.isEmpty() && importedSnapshot == null) {
      showStatus("Fallback editor is empty", isError = false)
      return
    }

    val currentSig = targetController.currentTargetSignature()
    val activeImport = importedSnapshot
    if (activeImport != null && currentSig != null && activeImport.signature != currentSig) {
      if (pendingTargetMismatchSig != currentSig) {
        pendingTargetMismatchSig = currentSig
        showStatus("Target changed—select destination and Apply again", isError = true)
        return
      } else {
        importedSnapshot = null
        pendingTargetMismatchSig = null
      }
    }

    when (targetController.applyFallbackText(s.fallbackText, importedSnapshot)) {
      ApplyResult.Applied -> {
        importedSnapshot = null
        pendingTargetMismatchSig = null
        _uiState.update { it.copy(hasImportedSnapshot = false) }
      }
      ApplyResult.TargetChanged -> {
        if (currentSig != null) pendingTargetMismatchSig = currentSig
        showStatus("Target changed—select destination and Apply again", isError = true)
      }
      ApplyResult.NoTarget -> {
        showStatus("No active input field", isError = true)
      }
    }
  }

  fun onCycleLanguage() {
    dismissFirstUseHint()
    val enabled = _uiState.value.settings.enabledLanguages
    if (enabled.size <= 1) return
    val current = _uiState.value.settings.activeLanguage
    val idx = enabled.indexOf(current)
    val next = enabled[(idx + 1) % enabled.size]
    scope.launch {
      settingsRepository.setActiveLanguage(next)
      val option = SpeechLanguages.find(next)
      showStatus(option.displayName, isError = false)
    }
  }

  fun onSwitchKeyboard() {
    onSwitchToNextKeyboard()
  }

  fun onOpenSettings() {
    onOpenSettings.invoke()
  }

  fun showTooltip(text: String) {
    tooltipClearJob?.cancel()
    _uiState.update { it.copy(tooltipText = text) }
    tooltipClearJob =
      scope.launch {
        delay(1600L)
        _uiState.update { it.copy(tooltipText = null) }
      }
  }

  fun dismissFirstUseHint() {
    if (!_uiState.value.settings.firstUseHintDismissed) {
      scope.launch { settingsRepository.dismissFirstUseHint() }
    }
  }

  fun destroy() {
    stopRepeatingAction()
    pendingFinishInputJob?.cancel()
    pendingFinishInputJob = null
    commandTimeoutJob?.cancel()
    speechEngine.shutDown()
  }

  private fun completePendingCommand(
    cmdId: Long,
    finalText: String,
    finalElapsedMs: Long,
  ) {
    if (cmdId != activeCommandId || activeCommandExecuted) return
    activeCommandExecuted = true
    activeCommandId = -1L
    isCommandContinuous = false
    isCommandTypeStreamActive = false
    commandHoldActive = false
    lastCommandPartial = ""
    commandTimeoutJob?.cancel()

    executeCompletedCommand(
      cmdId = cmdId,
      finalText = finalText,
      finalElapsedMs = finalElapsedMs,
      isContinuous = false,
    )

    if (isWindowVisible) {
      speechEngine.stopDictation(
        resumePreBuffer = true,
        preBufferMs = _uiState.value.settings.preBufferMs,
      )
    }
  }

  private fun executeCompletedCommand(
    cmdId: Long,
    finalText: String,
    finalElapsedMs: Long,
    isContinuous: Boolean,
  ) {
    val releaseMs = commandReleaseElapsedMs
    val releaseToFinalStr =
      if (!isContinuous && releaseMs != null) {
        val delta = finalElapsedMs - releaseMs
        if (delta >= 0L) "release 0ms → final +${delta}ms"
        else "final ${delta}ms pre-release"
      } else {
        "segment"
      }

    val currentSig = targetController.currentTargetSignature()
    val modeMatches = activeCommandForFallback == _uiState.value.isTargetingFallback
    val targetMatches = activeCommandForFallback || currentSig == activeCommandTargetSig
    if (!modeMatches || !targetMatches) {
      if (isContinuous) {
        stopContinuousCommand(resumePreBuffer = isWindowVisible, outcomeReason = "cancelled (target changed)")
      } else {
        _uiState.update {
          it.copy(
            isCommandRecording = false,
            isCommandPendingFinal = false,
            isSpeechReady = false,
            rmsDb = 0f,
            commandPartialFeedback = "",
            commandDevState =
              CommandDevPanelState(
                heard = finalText.ifEmpty { "—" },
                parsed = "—",
                outcome = "cancelled (target changed)",
                timing = "$releaseToFinalStr → —",
              ),
          )
        }
      }
      return
    }

    val trimmed = finalText.trim()
    if (trimmed.isEmpty()) {
      _uiState.update {
        it.copy(
          isCommandRecording = isContinuous,
          isCommandPendingFinal = false,
          isSpeechReady = if (isContinuous) it.isSpeechReady else false,
          rmsDb = if (isContinuous) it.rmsDb else 0f,
          commandPartialFeedback = "",
          commandDevState =
            CommandDevPanelState(
              heard = "(empty)",
              parsed = "—",
              outcome = if (isContinuous) "listening (empty segment)" else "empty (no insertion)",
              timing = "$releaseToFinalStr → —",
            ),
        )
      }
      return
    }

    val s = _uiState.value
    // Allow "type" alone in continuous Command mode to open a sticky type stream
    if (isContinuous && VoiceCommandParser.isBareTypeCommand(trimmed)) {
      isCommandTypeStreamActive = true
      _uiState.update {
        it.copy(
          isCommandRecording = true,
          isCommandPendingFinal = false,
          commandPartialFeedback = "",
          commandDevState =
            CommandDevPanelState(
              heard = trimmed,
              parsed = "EnterTypeStream",
              outcome = "type stream active",
              timing = "$releaseToFinalStr → stream on",
            ),
        )
      }
      return
    }

    val parsed =
      VoiceCommandParser.parse(
        transcript = trimmed,
        emojiDictionary = s.emojiItems,
        allowTypeContinuation = isContinuous && isCommandTypeStreamActive,
      )
    when (parsed) {
      is ParsedCommand.InsertEmoji -> {
        isCommandTypeStreamActive = false
        if (activeCommandForFallback) {
          val before = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
          val (nextText, newStart, newEnd) =
            GraphemeUtils.insertOrReplace(
              s.fallbackText,
              s.fallbackSelStart,
              s.fallbackSelEnd,
              parsed.emoji,
            )
          val after = TextSnapshot(nextText, newStart, newEnd)
          undoHistory.recordChange(before, after, EditGroupKind.EMOJI_INSERT)
          applyFallbackSnapshot(after)
        } else {
          targetController.commitText(parsed.emoji)
          recordAppTailAppend(parsed.emoji)
        }
        scope.launch { emojiRepository.recordUsage(parsed.emoji) }

        val insertMs = nowMsProvider()
        val finalToInsertDelta = (insertMs - finalElapsedMs).coerceAtLeast(0L)
        _uiState.update {
          it.copy(
            isCommandRecording = isContinuous,
            isCommandPendingFinal = false,
            isSpeechReady = if (isContinuous) it.isSpeechReady else false,
            rmsDb = if (isContinuous) it.rmsDb else 0f,
            commandPartialFeedback = "",
            commandDevState =
              CommandDevPanelState(
                heard = trimmed,
                parsed = "InsertEmoji(${parsed.emoji})",
                outcome = "inserted",
                timing = "$releaseToFinalStr → insert +${finalToInsertDelta}ms",
              ),
          )
        }
      }
      is ParsedCommand.TypeText -> {
        if (isContinuous) {
          isCommandTypeStreamActive = parsed.startsOrContinuesTypeStream
        }
        if (activeCommandForFallback) {
          val before = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
          val (nextText, newStart, newEnd) =
            GraphemeUtils.insertOrReplace(
              s.fallbackText,
              s.fallbackSelStart,
              s.fallbackSelEnd,
              parsed.text,
            )
          val after = TextSnapshot(nextText, newStart, newEnd)
          undoHistory.recordChange(before, after, EditGroupKind.DISCRETE)
          applyFallbackSnapshot(after)
        } else {
          targetController.commitText(parsed.text)
          recordAppTailAppend(parsed.text)
        }

        val execMs = nowMsProvider()
        val finalToExecDelta = (execMs - finalElapsedMs).coerceAtLeast(0L)
        val displayToken =
          when (parsed.text) {
            " " -> "Space"
            "\n" -> "\\n"
            "\t" -> "\\t"
            else -> parsed.text
          }
        _uiState.update {
          it.copy(
            isCommandRecording = isContinuous,
            isCommandPendingFinal = false,
            isSpeechReady = if (isContinuous) it.isSpeechReady else false,
            rmsDb = if (isContinuous) it.rmsDb else 0f,
            commandPartialFeedback = "",
            commandDevState =
              CommandDevPanelState(
                heard = trimmed,
                parsed = "TypeText($displayToken)",
                outcome = if (isContinuous && isCommandTypeStreamActive) "typed (stream)" else "typed",
                timing = "$releaseToFinalStr → type +${finalToExecDelta}ms",
              ),
          )
        }
      }
      ParsedCommand.EnterTypeStream -> {
        if (isContinuous) isCommandTypeStreamActive = true
        _uiState.update {
          it.copy(
            isCommandRecording = isContinuous,
            isCommandPendingFinal = false,
            commandPartialFeedback = "",
            commandDevState =
              CommandDevPanelState(
                heard = trimmed,
                parsed = "EnterTypeStream",
                outcome = "type stream active",
                timing = "$releaseToFinalStr → stream on",
              ),
          )
        }
      }
      ParsedCommand.StopTypeStream -> {
        isCommandTypeStreamActive = false
        _uiState.update {
          it.copy(
            isCommandRecording = isContinuous,
            isCommandPendingFinal = false,
            commandPartialFeedback = "",
            commandDevState =
              CommandDevPanelState(
                heard = trimmed,
                parsed = "StopTypeStream",
                outcome = "type stream paused",
                timing = "$releaseToFinalStr → stream off",
              ),
          )
        }
      }
      ParsedCommand.Backspace -> {
        var outcomeLabel = "deleted"
        if (activeCommandForFallback) {
          val before = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
          val (nextText, newStart, newEnd) =
            GraphemeUtils.deleteSelectionOrBackward(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
          if (nextText != s.fallbackText) {
            val after = TextSnapshot(nextText, newStart, newEnd)
            undoHistory.recordChange(before, after, EditGroupKind.MANUAL_DELETE)
            applyFallbackSnapshot(after)
          } else {
            outcomeLabel = "empty (nothing to delete)"
          }
        } else {
          targetController.deleteSelectionOrBackwardGrapheme()
          recordAppTailBackspace()
        }
        val execMs = nowMsProvider()
        val delta = (execMs - finalElapsedMs).coerceAtLeast(0L)
        _uiState.update {
          it.copy(
            isCommandRecording = isContinuous,
            isCommandPendingFinal = false,
            isSpeechReady = if (isContinuous) it.isSpeechReady else false,
            rmsDb = if (isContinuous) it.rmsDb else 0f,
            commandPartialFeedback = "",
            commandDevState =
              CommandDevPanelState(
                heard = trimmed,
                parsed = "Backspace",
                outcome = outcomeLabel,
                timing = "$releaseToFinalStr → del +${delta}ms",
              ),
          )
        }
      }
      ParsedCommand.ControlC -> {
        val outcomeLabel =
          if (activeCommandForFallback) {
            val min = minOf(s.fallbackSelStart, s.fallbackSelEnd).coerceIn(0, s.fallbackText.length)
            val max = maxOf(s.fallbackSelStart, s.fallbackSelEnd).coerceIn(0, s.fallbackText.length)
            if (max > min) {
              val selected = s.fallbackText.substring(min, max)
              val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
              clipboard?.setPrimaryClip(ClipData.newPlainText("VoxKey", selected))
              "copied (${selected.length} chars)"
            } else {
              "nothing selected"
            }
          } else {
            if (targetController.sendControlC()) "sent Ctrl+C" else "no target"
          }
        val execMs = nowMsProvider()
        val delta = (execMs - finalElapsedMs).coerceAtLeast(0L)
        _uiState.update {
          it.copy(
            isCommandRecording = isContinuous,
            isCommandPendingFinal = false,
            isSpeechReady = if (isContinuous) it.isSpeechReady else false,
            rmsDb = if (isContinuous) it.rmsDb else 0f,
            commandPartialFeedback = "",
            commandDevState =
              CommandDevPanelState(
                heard = trimmed,
                parsed = "ControlC",
                outcome = outcomeLabel,
                timing = "$releaseToFinalStr → ctrl+c +${delta}ms",
              ),
          )
        }
      }
      is ParsedCommand.UnknownCommand -> {
        showStatus("Unknown command: ${parsed.normalizedTranscript}", isError = false)
        _uiState.update {
          it.copy(
            isCommandRecording = isContinuous,
            isCommandPendingFinal = false,
            isSpeechReady = if (isContinuous) it.isSpeechReady else false,
            rmsDb = if (isContinuous) it.rmsDb else 0f,
            commandPartialFeedback = "",
            commandDevState =
              CommandDevPanelState(
                heard = trimmed,
                parsed = "UnknownCommand",
                outcome = "ignored (${parsed.reason})",
                timing = "$releaseToFinalStr → —",
              ),
          )
        }
      }
    }
  }

  private fun invalidatePendingCommand(
    reason: String,
    resumePreBuffer: Boolean = isWindowVisible,
  ) {
    if (activeCommandId < 0L || activeCommandExecuted) return
    activeCommandExecuted = true
    activeCommandId = -1L
    commandHoldActive = false
    bufferedEarlyCommandFinal = null
    lastCommandPartial = ""
    commandTimeoutJob?.cancel()
    speechEngine.stopDictation(
      resumePreBuffer = resumePreBuffer,
      preBufferMs = _uiState.value.settings.preBufferMs,
    )
    _uiState.update {
      it.copy(
        isCommandRecording = false,
        isCommandPendingFinal = false,
        isSpeechReady = false,
        rmsDb = 0f,
        commandPartialFeedback = "",
        commandDevState = it.commandDevState.copy(outcome = "cancelled ($reason)", timing = "—"),
      )
    }
  }

  private fun startDictation(overrideEmojiTarget: EmojiInputTarget? = null) {
    if (!audioPreBufferManager.hasRecordPermission()) {
      _uiState.update { it.copy(hasMicPermission = false) }
      showStatus("Grant microphone permission in Settings", isError = true)
      return
    }

    val s = _uiState.value
    val token = ++sessionCounter
    activeSessionToken = token
    activeDictationPartial = ""

    dictationStartedForEmojiInput = overrideEmojiTarget
    dictationStartedForFallback = overrideEmojiTarget == null && s.isTargetingFallback
    dictationTargetSig =
      if (dictationStartedForFallback || overrideEmojiTarget != null) null
      else targetController.currentTargetSignature()
    dictationNeedsLeadingSpace =
      when {
        overrideEmojiTarget != null -> false
        dictationStartedForFallback -> {
          val beforeCursor = s.fallbackText.substring(0, s.fallbackSelStart.coerceIn(0, s.fallbackText.length))
          beforeCursor.isNotEmpty() && !beforeCursor.last().isWhitespace()
        }
        else -> resolveAppLeadingSpaceNeeded()
      }
    dictationContinueLowercase =
      when {
        overrideEmojiTarget != null -> false
        dictationStartedForFallback -> {
          val beforeCursor = s.fallbackText.substring(0, s.fallbackSelStart.coerceIn(0, s.fallbackText.length))
          SentenceContinuity.shouldContinueLowercase(beforeCursor)
        }
        else -> resolveAppShouldContinueLowercase()
      }

    fallbackBaseSnapshot =
      if (dictationStartedForFallback) {
        TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
      } else {
        null
      }
    emojiBaseText =
      when (overrideEmojiTarget) {
        EmojiInputTarget.SEARCH -> s.emojiSearchQuery
        EmojiInputTarget.ALIAS -> s.aliasDraft
        null -> ""
      }

    _uiState.update {
      it.copy(
        isListening = true,
        isSpeechReady = false,
        rmsDb = 0f,
        livePartialAppPreview = "",
      )
    }

    speechEngine.startDictation(
      sessionToken = token,
      languageTag = s.settings.activeLanguage,
      preBufferMs = s.settings.preBufferMs,
      singleUtterance = false,
      listener =
        object : OnDeviceSpeechEngine.Listener {
          override fun onReady(sessionToken: Long, usingPreBufferPipe: Boolean) {
            runOnMain {
              if (sessionToken != activeSessionToken) return@runOnMain
              _uiState.update { it.copy(isSpeechReady = true) }
            }
          }

          override fun onRmsChanged(sessionToken: Long, rmsDb: Float) {
            if (sessionToken != activeSessionToken) return
            if (!_uiState.value.isListening) return
            _uiState.update { it.copy(rmsDb = rmsDb.coerceIn(0f, 12f)) }
          }

          override fun onPartialText(sessionToken: Long, partial: String) {
            runOnMain {
              if (sessionToken != activeSessionToken || !_uiState.value.isListening) return@runOnMain
              val formatted = SentenceContinuity.applyContinuityCasing(partial, dictationContinueLowercase)
              activeDictationPartial = formatted
              renderPartialForActiveTarget(formatted)
            }
          }

          override fun onSegmentFinal(sessionToken: Long, text: String) {
            runOnMain {
              if (sessionToken != activeSessionToken || !_uiState.value.isListening) return@runOnMain
              activeDictationPartial = ""
              if (text.isNotEmpty()) {
                val formatted = SentenceContinuity.applyContinuityCasing(text, dictationContinueLowercase)
                commitFinalSegmentAndPrepareNext(formatted)
              }
            }
          }

          override fun onFatalError(
            sessionToken: Long,
            errorCode: Int,
            message: String,
            isModelMissing: Boolean,
          ) {
            runOnMain {
              if (sessionToken != activeSessionToken) return@runOnMain
              stopDictation(commitToTarget = true, resumePreBuffer = true)
              if (isModelMissing) {
                capabilityInspector.triggerModelDownload(_uiState.value.settings.activeLanguage)
                showStatus("Downloading offline model or check Settings", isError = true)
              } else {
                showStatus(message, isError = true)
              }
            }
          }
        },
    )
  }

  private fun stopDictation(commitToTarget: Boolean, resumePreBuffer: Boolean) {
    pendingFinishInputJob?.cancel()
    pendingFinishInputJob = null
    activeSessionToken = -1L
    val pending = activeDictationPartial
    activeDictationPartial = ""

    if (commitToTarget && pending.isNotEmpty()) {
      commitSegmentInternal(pending)
    } else if (
      commitToTarget && !dictationStartedForFallback && dictationStartedForEmojiInput == null
    ) {
      targetController.finishComposingText()
    }

    speechEngine.stopDictation(
      resumePreBuffer = resumePreBuffer && isWindowVisible,
      preBufferMs = _uiState.value.settings.preBufferMs,
    )

    fallbackBaseSnapshot = null
    dictationTargetSig = null
    dictationStartedForEmojiInput = null
    dictationStartedForFallback = false
    emojiBaseText = ""

    _uiState.update {
      it.copy(
        isListening = false,
        isSpeechReady = false,
        rmsDb = 0f,
        livePartialAppPreview = "",
      )
    }
  }

  private fun commitCurrentSegmentAndResetBase() {
    val pending = activeDictationPartial
    activeDictationPartial = ""
    if (pending.isNotEmpty()) {
      commitSegmentInternal(pending)
    } else if (!dictationStartedForFallback && dictationStartedForEmojiInput == null) {
      targetController.finishComposingText()
    }
    if (dictationStartedForFallback) {
      val s = _uiState.value
      fallbackBaseSnapshot = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
      val beforeCursor = s.fallbackText.substring(0, s.fallbackSelStart.coerceIn(0, s.fallbackText.length))
      dictationContinueLowercase = SentenceContinuity.shouldContinueLowercase(beforeCursor)
    } else if (dictationStartedForEmojiInput == null) {
      dictationNeedsLeadingSpace = resolveAppLeadingSpaceNeeded()
      dictationContinueLowercase = resolveAppShouldContinueLowercase()
    }
  }

  private fun renderPartialForActiveTarget(activeText: String) {
    val emojiTarget = dictationStartedForEmojiInput
    if (emojiTarget != null) {
      val combined =
        if (emojiBaseText.isEmpty()) activeText
        else "${emojiBaseText.trimEnd()} $activeText"
      when (emojiTarget) {
        EmojiInputTarget.SEARCH -> _uiState.update { it.copy(emojiSearchQuery = combined) }
        EmojiInputTarget.ALIAS -> _uiState.update { it.copy(aliasDraft = combined) }
      }
      return
    }

    if (dictationStartedForFallback) {
      val base = fallbackBaseSnapshot ?: return
      val insertion = formatWithLeadingSpace(base.text, base.selStart, activeText)
      val (nextText, newStart, newEnd) =
        GraphemeUtils.insertOrReplace(base.text, base.selStart, base.selEnd, insertion)
      _uiState.update {
        it.copy(
          fallbackText = nextText,
          fallbackSelStart = newStart,
          fallbackSelEnd = newEnd,
        )
      }
      return
    }

    val currentSig = targetController.currentTargetSignature()
    if (currentSig != null && dictationTargetSig != null && currentSig != dictationTargetSig) {
      stopDictation(commitToTarget = false, resumePreBuffer = true)
      return
    }
    if (targetController.isRestrictedInput()) {
      _uiState.update { it.copy(livePartialAppPreview = activeText) }
    } else {
      targetController.setComposingPartial(activeText, dictationNeedsLeadingSpace)
    }
  }

  private fun commitFinalSegmentAndPrepareNext(finalText: String) {
    commitSegmentInternal(finalText)
    if (dictationStartedForFallback) {
      val s = _uiState.value
      fallbackBaseSnapshot = TextSnapshot(s.fallbackText, s.fallbackSelStart, s.fallbackSelEnd)
      val beforeCursor = s.fallbackText.substring(0, s.fallbackSelStart.coerceIn(0, s.fallbackText.length))
      dictationContinueLowercase = SentenceContinuity.shouldContinueLowercase(beforeCursor)
    } else if (dictationStartedForEmojiInput != null) {
      val s = _uiState.value
      emojiBaseText =
        when (dictationStartedForEmojiInput) {
          EmojiInputTarget.SEARCH -> s.emojiSearchQuery
          EmojiInputTarget.ALIAS -> s.aliasDraft
          null -> ""
        }
    } else {
      dictationNeedsLeadingSpace = resolveAppLeadingSpaceNeeded()
      dictationContinueLowercase = resolveAppShouldContinueLowercase()
    }
    _uiState.update { it.copy(livePartialAppPreview = "") }
  }

  private fun commitSegmentInternal(segmentText: String) {
    val clean = segmentText.trim()
    if (clean.isEmpty()) return

    val emojiTarget = dictationStartedForEmojiInput
    if (emojiTarget != null) {
      val combined =
        if (emojiBaseText.isEmpty()) clean
        else "${emojiBaseText.trimEnd()} $clean"
      when (emojiTarget) {
        EmojiInputTarget.SEARCH -> _uiState.update { it.copy(emojiSearchQuery = combined) }
        EmojiInputTarget.ALIAS -> _uiState.update { it.copy(aliasDraft = combined) }
      }
      return
    }

    if (dictationStartedForFallback) {
      val base = fallbackBaseSnapshot ?: return
      val insertion = formatWithLeadingSpace(base.text, base.selStart, clean)
      val (nextText, newStart, newEnd) =
        GraphemeUtils.insertOrReplace(base.text, base.selStart, base.selEnd, insertion)
      val after = TextSnapshot(nextText, newStart, newEnd)
      undoHistory.recordChange(base, after, EditGroupKind.DICTATION_SEGMENT)
      applyFallbackSnapshot(after)
      return
    }

    val currentSig = targetController.currentTargetSignature()
    if (currentSig == dictationTargetSig) {
      targetController.commitDictatedSegment(clean, dictationNeedsLeadingSpace)
      val committed = if (dictationNeedsLeadingSpace) " $clean" else clean
      recordAppTailAppend(committed)
    }
  }

  private fun navigateFallback(direction: NavDirection, extendSelection: Boolean) {
    val s = _uiState.value
    val text = s.fallbackText
    val curStart = s.fallbackSelStart.coerceIn(0, text.length)
    val curEnd = s.fallbackSelEnd.coerceIn(0, text.length)

    if (extendSelection && fallbackSelectionAnchor !in 0..text.length) {
      fallbackSelectionAnchor = curStart
    }
    val activeCursor =
      if (extendSelection) {
        if (curStart == fallbackSelectionAnchor) curEnd else curStart
      } else {
        if (curStart != curEnd) {
          if (direction == NavDirection.LEFT || direction == NavDirection.UP) minOf(curStart, curEnd)
          else maxOf(curStart, curEnd)
        } else {
          curStart
        }
      }

    val nextPos =
      when (direction) {
        NavDirection.LEFT -> GraphemeUtils.prevGraphemeBoundary(text, activeCursor)
        NavDirection.RIGHT -> GraphemeUtils.nextGraphemeBoundary(text, activeCursor)
        NavDirection.UP -> moveLine(text, activeCursor, up = true)
        NavDirection.DOWN -> moveLine(text, activeCursor, up = false)
      }

    val (newStart, newEnd) =
      if (extendSelection) {
        fallbackSelectionAnchor to nextPos
      } else {
        fallbackSelectionAnchor = -1
        nextPos to nextPos
      }

    _uiState.update { it.copy(fallbackSelStart = newStart, fallbackSelEnd = newEnd) }
  }

  private fun moveLine(text: String, cursor: Int, up: Boolean): Int {
    val lineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
    val col = cursor - lineStart
    return if (up) {
      if (lineStart == 0) 0
      else {
        val prevEnd = lineStart - 1
        val prevStart = text.lastIndexOf('\n', (prevEnd - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        (prevStart + col).coerceAtMost(prevEnd)
      }
    } else {
      if (lineEnd >= text.length) text.length
      else {
        val nextStart = lineEnd + 1
        val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
        (nextStart + col).coerceAtMost(nextEnd)
      }
    }
  }

  private fun applyFallbackSnapshot(snapshot: TextSnapshot) {
    _uiState.update {
      val updated =
        it.copy(
          fallbackText = snapshot.text,
          fallbackSelStart = snapshot.selStart.coerceIn(0, snapshot.text.length),
          fallbackSelEnd = snapshot.selEnd.coerceIn(0, snapshot.text.length),
          canFallbackUndo = undoHistory.canUndo,
          canFallbackRedo = undoHistory.canRedo,
        )
      updated.copy(lastWordTyped = resolveLastWordForState(updated))
    }
  }

  private fun resolveAppContextBeforeCursor(): String {
    if (!targetController.isRestrictedInput()) {
      val surrounding = targetController.getContextBeforeCursor(64)
      if (surrounding != null) return surrounding
    }
    return rememberedAppTail
  }

  private fun resolveAppLeadingSpaceNeeded(): Boolean {
    val ctx = resolveAppContextBeforeCursor()
    if (ctx.isEmpty()) return false
    return !ctx.last().isWhitespace()
  }

  private fun resolveAppShouldContinueLowercase(): Boolean =
    SentenceContinuity.shouldContinueLowercase(resolveAppContextBeforeCursor())

  private fun recordAppTailAppend(text: String) {
    rememberedAppSig = targetController.currentTargetSignature()
    rememberedAppTail = (rememberedAppTail + text).takeLast(128)
    refreshLastWordState()
  }

  private fun recordAppTailBackspace() {
    rememberedAppSig = targetController.currentTargetSignature()
    if (rememberedAppTail.isNotEmpty()) {
      val len = GraphemeUtils.lastGraphemeLength(rememberedAppTail)
      rememberedAppTail = rememberedAppTail.dropLast(len.coerceAtMost(rememberedAppTail.length))
    }
    refreshLastWordState()
  }

  private fun resolveLastWordForState(state: KeyboardUiState): String =
    if (state.isTargetingFallback) {
      val before = state.fallbackText.substring(0, state.fallbackSelStart.coerceIn(0, state.fallbackText.length))
      SentenceContinuity.extractLastWord(before)
    } else {
      SentenceContinuity.extractLastWord(resolveAppContextBeforeCursor())
    }

  private fun refreshLastWordState() {
    _uiState.update { it.copy(lastWordTyped = resolveLastWordForState(it)) }
  }

  private fun formatWithLeadingSpace(existing: String, insertAt: Int, word: String): String {
    if (insertAt <= 0 || existing.isEmpty() || word.isEmpty()) return word
    val prevChar = existing[insertAt.coerceAtMost(existing.length) - 1]
    return if (!prevChar.isWhitespace() && !word.first().isWhitespace()) " $word" else word
  }

  private inline fun runOnMain(crossinline block: () -> Unit) {
    if (Looper.myLooper() == mainHandler.looper) {
      block()
    } else {
      mainHandler.post { block() }
    }
  }

  private fun showStatus(message: String, isError: Boolean) {
    statusClearJob?.cancel()
    _uiState.update { it.copy(statusMessage = message, statusIsError = isError) }
    statusClearJob =
      scope.launch {
        delay(2800L)
        _uiState.update { it.copy(statusMessage = null, statusIsError = false) }
      }
  }
}
