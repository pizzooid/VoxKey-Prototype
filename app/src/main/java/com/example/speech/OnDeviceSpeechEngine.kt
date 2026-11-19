package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.audio.AudioPreBufferManager

interface SpeechRecognizerHandle {
  fun setRecognitionListener(listener: RecognitionListener)
  fun startListening(intent: Intent)
  fun stopListening()
  fun cancel()
  fun destroy()
}

class AndroidSpeechRecognizerHandle(
  private val delegate: SpeechRecognizer,
) : SpeechRecognizerHandle {
  override fun setRecognitionListener(listener: RecognitionListener) =
    delegate.setRecognitionListener(listener)

  override fun startListening(intent: Intent) = delegate.startListening(intent)

  override fun stopListening() = delegate.stopListening()

  override fun cancel() = delegate.cancel()

  override fun destroy() = delegate.destroy()
}

fun interface SpeechRecognizerFactory {
  fun create(context: Context): SpeechRecognizerHandle?
}

/**
 * On-demand on-device SpeechRecognizer engine paired with silent AudioRecord pre-buffering.
 *
 * - Supports continuous dictation (singleUtterance = false) with segmented sessions and active-only
 *   rollover across long pauses.
 * - Supports hold-to-record command utterances (singleUtterance = true) that finalize via
 *   finishUtteranceForFinal() on release and deliver raw final transcripts without preserving partials.
 */
class OnDeviceSpeechEngine(
  private val context: Context,
  private val audioPreBufferManager: AudioPreBufferManager,
  private val recognizerFactory: SpeechRecognizerFactory = DefaultSpeechRecognizerFactory,
  private val mainHandler: Handler = Handler(Looper.getMainLooper()),
  private val restartDelayMs: Long = 120L,
  private val cancelCooldownMs: Long = 140L,
  private val commandReleaseDrainMs: Long = 200L,
  private val maxTransientErrors: Int? = null,
  private val nowMsProvider: () -> Long = { SystemClock.elapsedRealtime() },
) {

  interface Listener {
    fun onReady(sessionToken: Long, usingPreBufferPipe: Boolean)
    fun onRmsChanged(sessionToken: Long, rmsDb: Float)
    fun onPartialText(sessionToken: Long, partial: String)
    fun onSegmentFinal(sessionToken: Long, text: String)
    fun onUtteranceCompleted(sessionToken: Long, finalText: String) {}
    fun onFatalError(
      sessionToken: Long,
      errorCode: Int,
      message: String,
      isModelMissing: Boolean,
    )
  }

  companion object {
    private const val VAD_RECENT_SPEECH_WINDOW_MS = 700L
    private const val CLEAN_ROLLOVER_MAX_DELAY_MS = 80L
    private const val ERROR_TOO_MANY_REQUESTS_CODE = 10
    private const val ERROR_CANNOT_CHECK_SUPPORT_CODE = 14
  }

  private var recognizer: SpeechRecognizerHandle? = null
  private var activeSessionToken: Long = -1L
  private var activeLanguageTag: String = "en-US"
  private var activePreBufferMs: Int = 800
  private var activeListener: Listener? = null
  private var singleUtteranceMode: Boolean = false
  private var awaitingFinalAfterStop: Boolean = false
  private var readyForCurrentUtterance: Boolean = false
  private var emittedTextInCurrentCycle: Boolean = false
  private var inVadStandby: Boolean = false
  private var isDictating: Boolean = false
  private var isRecognizerListening: Boolean = false
  private var lastPartialForSegment: String = ""
  private var consecutiveTransientErrors: Int = 0
  private var pipeVerifiedWorking: Boolean = false
  private var consecutivePreReadyPipeFailures: Int = 0
  private var pipeRejected: Boolean = false
  private var segmentedRejected: Boolean = false
  private var lastUsedSegmented: Boolean? = null
  private var activeReadPfd: ParcelFileDescriptor? = null
  private var lastCancelElapsedMs: Long = 0L
  private var pendingRestartRunnable: Runnable? = null
  private var pendingFinishRunnable: Runnable? = null

  fun isPipeSupportedForPreBuffer(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !pipeRejected

  fun isInVadStandbyForTesting(): Boolean = inVadStandby

  fun warmUp(preBufferMs: Int) {
    activePreBufferMs = preBufferMs
    postOnMain {
      if (!audioPreBufferManager.hasRecordPermission()) return@postOnMain
      if (recognizer == null) {
        recognizer = recognizerFactory.create(context)
      }
      if (!isDictating && isPipeSupportedForPreBuffer()) {
        audioPreBufferManager.startPreBuffering(preBufferMs)
      }
    }
  }

  fun startDictation(
    sessionToken: Long,
    languageTag: String,
    preBufferMs: Int,
    listener: Listener,
    singleUtterance: Boolean = false,
  ) {
    postOnMain {
      cancelPendingRestart()
      cancelPendingFinish()
      audioPreBufferManager.disarmSpeechTrigger()
      activeSessionToken = sessionToken
      activeLanguageTag = languageTag
      activePreBufferMs = preBufferMs
      activeListener = listener
      singleUtteranceMode = singleUtterance
      awaitingFinalAfterStop = false
      readyForCurrentUtterance = false
      emittedTextInCurrentCycle = false
      inVadStandby = false
      isDictating = true
      consecutiveTransientErrors = 0
      lastPartialForSegment = ""

      val elapsedSinceCancel =
        if (lastCancelElapsedMs > 0L) (nowMsProvider() - lastCancelElapsedMs).coerceAtLeast(0L)
        else cancelCooldownMs
      val cooldownRemaining = (cancelCooldownMs - elapsedSinceCancel).coerceAtLeast(0L)

      if (cooldownRemaining > 0L) {
        scheduleRestart(
          delayMs = cooldownRemaining,
          recreateRecognizer = recognizer == null,
          replayUnconfirmed = false,
        )
      } else {
        startListeningCycle(recreateRecognizer = recognizer == null, replayUnconfirmed = false)
      }
    }
  }

  /**
   * Signals end of user speech (e.g., releasing the Command button) while keeping the session
   * alive long enough to receive the final onResults() transcript.
   */
  fun finishUtteranceForFinal(sessionToken: Long) {
    postOnMain {
      if (!isDictating || activeSessionToken != sessionToken) return@postOnMain
      awaitingFinalAfterStop = true
      if (!isRecognizerListening && pendingRestartRunnable != null) {
        if (commandReleaseDrainMs <= 0L) {
          cancelPendingRestart()
          isDictating = false
          closeActivePipeAndPfd()
          activeListener?.onUtteranceCompleted(sessionToken, "")
        }
        // In production, let the scheduled cooldown start run so pre-buffered audio still reaches ASR.
        return@postOnMain
      }
      val delayMs =
        when {
          commandReleaseDrainMs <= 0L -> 0L
          readyForCurrentUtterance -> commandReleaseDrainMs
          else -> maxOf(commandReleaseDrainMs, 550L)
        }
      scheduleFinishStop(sessionToken = sessionToken, delayMs = delayMs)
    }
  }

  fun stopDictation(resumePreBuffer: Boolean, preBufferMs: Int) {
    activeSessionToken = -1L
    activeListener = null
    isDictating = false
    singleUtteranceMode = false
    awaitingFinalAfterStop = false
    readyForCurrentUtterance = false
    emittedTextInCurrentCycle = false
    inVadStandby = false
    lastPartialForSegment = ""
    consecutiveTransientErrors = 0
    activePreBufferMs = preBufferMs
    audioPreBufferManager.disarmSpeechTrigger()

    postOnMain {
      cancelPendingRestart()
      cancelPendingFinish()
      closeActivePipeAndPfd()
      if (isRecognizerListening) {
        runCatching { recognizer?.cancel() }
        lastCancelElapsedMs = nowMsProvider()
        isRecognizerListening = false
      }
      if (resumePreBuffer && isPipeSupportedForPreBuffer() && audioPreBufferManager.hasRecordPermission()) {
        audioPreBufferManager.startPreBuffering(preBufferMs)
      } else if (!resumePreBuffer) {
        audioPreBufferManager.stopAndDiscard()
      }
    }
  }

  fun shutDown() {
    activeSessionToken = -1L
    activeListener = null
    isDictating = false
    singleUtteranceMode = false
    awaitingFinalAfterStop = false
    readyForCurrentUtterance = false
    emittedTextInCurrentCycle = false
    inVadStandby = false
    lastPartialForSegment = ""
    consecutiveTransientErrors = 0
    lastUsedSegmented = null
    audioPreBufferManager.disarmSpeechTrigger()

    postOnMain {
      cancelPendingRestart()
      cancelPendingFinish()
      closeActivePipeAndPfd()
      isRecognizerListening = false
      audioPreBufferManager.stopAndDiscard()
      destroyRecognizerInternal()
    }
  }

  private fun postOnMain(block: () -> Unit) {
    if (Looper.myLooper() == mainHandler.looper) {
      block()
    } else {
      mainHandler.post(block)
    }
  }

  private fun startListeningCycle(
    recreateRecognizer: Boolean,
    replayUnconfirmed: Boolean = false,
  ) {
    val token = activeSessionToken
    val listener = activeListener ?: return
    if (!isDictating || token < 0L) return

    audioPreBufferManager.disarmSpeechTrigger()
    inVadStandby = false

    if (!audioPreBufferManager.hasRecordPermission()) {
      isDictating = false
      listener.onFatalError(
        token,
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
        "Grant microphone permission in Settings",
        false,
      )
      return
    }

    closeActivePipeAndPfd()

    var usePipe = isPipeSupportedForPreBuffer()
    if (usePipe) {
      audioPreBufferManager.startPreBuffering(activePreBufferMs)
      val pipeReadFd =
        audioPreBufferManager.attachLivePipe(
          preBufferMs = activePreBufferMs,
          replayUnconfirmed = replayUnconfirmed,
        )
      if (pipeReadFd != null) {
        activeReadPfd = pipeReadFd
      } else {
        usePipe = false
        audioPreBufferManager.stopAndDiscard()
      }
    } else {
      audioPreBufferManager.stopAndDiscard()
    }

    cancelPendingFinish()
    readyForCurrentUtterance = false
    emittedTextInCurrentCycle = false
    val useSegmented =
      !singleUtteranceMode &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !segmentedRejected

    val modeChanged = lastUsedSegmented != null && lastUsedSegmented != useSegmented
    if (recreateRecognizer || recognizer == null || modeChanged) {
      destroyRecognizerInternal()
      val created = recognizerFactory.create(context)
      if (created == null) {
        closeActivePipeAndPfd()
        isDictating = false
        listener.onFatalError(
          token,
          SpeechRecognizer.ERROR_CLIENT,
          "On-device recognition unavailable",
          false,
        )
        return
      }
      recognizer = created
    }
    lastUsedSegmented = useSegmented

    recognizer?.setRecognitionListener(
      createRecognitionListener(
        token = token,
        usingPipe = usePipe,
        usingSegmented = useSegmented,
      ),
    )

    lastPartialForSegment = ""
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLanguageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          if (usePipe && activeReadPfd != null) {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, activeReadPfd)
            putExtra(
              RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
              AudioPreBufferManager.SAMPLE_RATE_HZ,
            )
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(
              RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
              AudioPreBufferManager.AUDIO_ENCODING,
            )
            if (useSegmented) {
              putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
          } else if (useSegmented) {
            putExtra(
              RecognizerIntent.EXTRA_SEGMENTED_SESSION,
              RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
            )
          }
        }
      }

    try {
      isRecognizerListening = true
      recognizer?.startListening(intent)
      if (singleUtteranceMode && awaitingFinalAfterStop) {
        val delayMs = maxOf(commandReleaseDrainMs, 550L)
        scheduleFinishStop(sessionToken = token, delayMs = delayMs)
      }
    } catch (_: Exception) {
      isRecognizerListening = false
      closeActivePipeAndPfd()
      handleRecognitionError(
        token = token,
        error = SpeechRecognizer.ERROR_CLIENT,
        usingPipe = usePipe,
        usingSegmented = useSegmented,
      )
    }
  }

  private fun createRecognitionListener(
    token: Long,
    usingPipe: Boolean,
    usingSegmented: Boolean,
  ): RecognitionListener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        if (!isDictating || activeSessionToken != token) return
        readyForCurrentUtterance = true
        consecutiveTransientErrors = 0
        consecutivePreReadyPipeFailures = 0
        if (usingPipe) {
          pipeVerifiedWorking = true
        }
        activeListener?.onReady(token, usingPipe)
        if (singleUtteranceMode && awaitingFinalAfterStop) {
          scheduleFinishStop(sessionToken = token, delayMs = commandReleaseDrainMs)
        }
      }

      override fun onBeginningOfSpeech() {
        if (!isDictating || activeSessionToken != token) return
        consecutiveTransientErrors = 0
      }

      override fun onRmsChanged(rmsdB: Float) {
        if (!isDictating || activeSessionToken != token) return
        activeListener?.onRmsChanged(token, rmsdB)
      }

      override fun onBufferReceived(buffer: ByteArray?) = Unit

      override fun onEndOfSpeech() = Unit

      override fun onPartialResults(partialResults: Bundle?) {
        if (!isDictating || activeSessionToken != token) return
        val text = extractTopText(partialResults)
        if (text.isNotEmpty()) {
          consecutiveTransientErrors = 0
          lastPartialForSegment = text
          activeListener?.onPartialText(token, text)
        }
      }

      override fun onSegmentResults(segmentResults: Bundle) {
        if (!isDictating || activeSessionToken != token) return
        consecutiveTransientErrors = 0
        val raw = extractTopText(segmentResults)
        if (singleUtteranceMode) {
          cancelPendingFinish()
          isRecognizerListening = false
          isDictating = false
          closeActivePipeAndPfd()
          lastPartialForSegment = ""
          activeListener?.onUtteranceCompleted(token, raw)
          return
        }
        val resolved = raw.ifEmpty { lastPartialForSegment }
        lastPartialForSegment = ""
        if (resolved.isNotEmpty()) {
          emittedTextInCurrentCycle = true
          audioPreBufferManager.markAudioConfirmedNow()
          activeListener?.onSegmentFinal(token, resolved)
        }
      }

      override fun onEndOfSegmentedSession() {
        cancelPendingFinish()
        isRecognizerListening = false
        closeActivePipeAndPfd()
        if (!isDictating || activeSessionToken != token) return
        if (singleUtteranceMode) {
          if (!awaitingFinalAfterStop) {
            scheduleRestart(restartDelayMs, recreateRecognizer = false, replayUnconfirmed = false)
            return
          }
          isDictating = false
          lastPartialForSegment = ""
          activeListener?.onUtteranceCompleted(token, "")
          return
        }
        val leftover = lastPartialForSegment
        lastPartialForSegment = ""
        if (leftover.isNotEmpty()) {
          emittedTextInCurrentCycle = true
          audioPreBufferManager.markAudioConfirmedNow()
          activeListener?.onSegmentFinal(token, leftover)
        } else if (emittedTextInCurrentCycle) {
          audioPreBufferManager.markAudioConfirmedNow()
        }

        if (leftover.isEmpty() && shouldEnterVadStandby()) {
          enterVadStandby(token)
        } else {
          scheduleRestart(
            delayMs = cleanRolloverDelayMs(),
            recreateRecognizer = false,
            replayUnconfirmed =
              !emittedTextInCurrentCycle || audioPreBufferManager.hasRecentSpeech(VAD_RECENT_SPEECH_WINDOW_MS),
          )
        }
      }

      override fun onResults(results: Bundle?) {
        cancelPendingFinish()
        isRecognizerListening = false
        closeActivePipeAndPfd()
        if (!isDictating || activeSessionToken != token) return
        consecutiveTransientErrors = 0
        val raw = extractTopText(results)
        if (singleUtteranceMode) {
          if (raw.isEmpty() && !awaitingFinalAfterStop) {
            scheduleRestart(restartDelayMs, recreateRecognizer = false, replayUnconfirmed = false)
            return
          }
          isDictating = false
          lastPartialForSegment = ""
          activeListener?.onUtteranceCompleted(token, raw)
          return
        }
        val resolved = raw.ifEmpty { lastPartialForSegment }
        lastPartialForSegment = ""
        if (resolved.isNotEmpty()) {
          emittedTextInCurrentCycle = true
          audioPreBufferManager.markAudioConfirmedNow()
          activeListener?.onSegmentFinal(token, resolved)
          // Keep dictating across utterance boundaries with minimal rollover gap
          scheduleRestart(
            delayMs = cleanRolloverDelayMs(),
            recreateRecognizer = false,
            replayUnconfirmed = audioPreBufferManager.hasRecentSpeech(VAD_RECENT_SPEECH_WINDOW_MS),
          )
        } else if (shouldEnterVadStandby()) {
          enterVadStandby(token)
        } else {
          scheduleRestart(
            delayMs = cleanRolloverDelayMs(),
            recreateRecognizer = false,
            replayUnconfirmed =
              !emittedTextInCurrentCycle || audioPreBufferManager.hasRecentSpeech(VAD_RECENT_SPEECH_WINDOW_MS),
          )
        }
      }

      override fun onError(error: Int) {
        cancelPendingFinish()
        isRecognizerListening = false
        closeActivePipeAndPfd()
        if (!isDictating || activeSessionToken != token) return
        handleRecognitionError(
          token = token,
          error = error,
          usingPipe = usingPipe,
          usingSegmented = usingSegmented,
        )
      }

      override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

  private fun handleRecognitionError(
    token: Long,
    error: Int,
    usingPipe: Boolean,
    usingSegmented: Boolean,
  ) {
    val listener = activeListener ?: return
    val preserved = if (singleUtteranceMode) "" else lastPartialForSegment
    lastPartialForSegment = ""
    if (preserved.isNotEmpty()) {
      emittedTextInCurrentCycle = true
      audioPreBufferManager.markAudioConfirmedNow()
      listener.onSegmentFinal(token, preserved)
    }
    val canReplayAudio =
      !singleUtteranceMode &&
        (!emittedTextInCurrentCycle || audioPreBufferManager.hasRecentSpeech(VAD_RECENT_SPEECH_WINDOW_MS))

    val isServerDisconnected =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
    val isModelMissing =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
          error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)

    val preReadyPipeCandidate =
      usingPipe &&
        !readyForCurrentUtterance &&
        !pipeVerifiedWorking &&
        (isServerDisconnected ||
          error == SpeechRecognizer.ERROR_CLIENT ||
          error == SpeechRecognizer.ERROR_AUDIO)
    if (preReadyPipeCandidate) {
      consecutivePreReadyPipeFailures++
    }
    // Only reject the pipe permanently if it has NEVER reached onReadyForSpeech AND either failed
    // with CLIENT/AUDIO directly or failed repeatedly before ready (so a single disconnect blip
    // never discards AudioPreBufferManager permanently).
    val pipeFailedPreReady =
      preReadyPipeCandidate &&
        (error == SpeechRecognizer.ERROR_CLIENT ||
          error == SpeechRecognizer.ERROR_AUDIO ||
          consecutivePreReadyPipeFailures >= 2)

    val isRecoverableError =
      error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
        isServerDisconnected ||
        error == SpeechRecognizer.ERROR_AUDIO ||
        error == SpeechRecognizer.ERROR_CLIENT ||
        error == SpeechRecognizer.ERROR_NETWORK ||
        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
        error == SpeechRecognizer.ERROR_SERVER ||
        error == ERROR_TOO_MANY_REQUESTS_CODE ||
        error == ERROR_CANNOT_CHECK_SUPPORT_CODE

    when {
      isModelMissing -> {
        isDictating = false
        listener.onFatalError(token, error, "Language model unavailable offline", true)
      }
      error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
        isDictating = false
        listener.onFatalError(token, error, "Microphone permission required", false)
      }
      pipeFailedPreReady -> {
        // OS recognizer rejected EXTRA_AUDIO_SOURCE pipe -> remember and fall back to direct mic
        pipeRejected = true
        audioPreBufferManager.stopAndDiscard()
        if (singleUtteranceMode && awaitingFinalAfterStop && readyForCurrentUtterance) {
          isDictating = false
          listener.onFatalError(token, error, "Audio pipe unsupported", false)
        } else {
          consecutiveTransientErrors = 0
          destroyRecognizerInternal()
          scheduleRestart(0L, recreateRecognizer = true, replayUnconfirmed = false)
        }
      }
      usingSegmented && error == SpeechRecognizer.ERROR_CLIENT -> {
        segmentedRejected = true
        consecutiveTransientErrors = 0
        destroyRecognizerInternal()
        scheduleRestart(0L, recreateRecognizer = true, replayUnconfirmed = canReplayAudio)
      }
      error == SpeechRecognizer.ERROR_NO_MATCH ||
        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
        if (singleUtteranceMode && awaitingFinalAfterStop) {
          isDictating = false
          listener.onFatalError(token, error, "No speech recognized", false)
        } else if (singleUtteranceMode) {
          // User is still holding the Command button -> keep listening without aborting
          consecutiveTransientErrors = 0
          scheduleRestart(restartDelayMs, recreateRecognizer = false, replayUnconfirmed = false)
        } else {
          // Silence / long break while Dictate or continuous Command is active: enter VAD standby
          // if pipe+mic active, otherwise restart seamlessly without clearing active listening state.
          consecutiveTransientErrors = 0
          if (preserved.isEmpty() && shouldEnterVadStandby()) {
            enterVadStandby(token)
          } else {
            scheduleRestart(
              delayMs = cleanRolloverDelayMs(),
              recreateRecognizer = false,
              replayUnconfirmed = canReplayAudio,
            )
          }
        }
      }
      isRecoverableError -> {
        if (singleUtteranceMode && awaitingFinalAfterStop && readyForCurrentUtterance) {
          isDictating = false
          listener.onFatalError(token, error, "Speech recognition error ($error)", false)
          return
        }
        consecutiveTransientErrors++
        val limit = maxTransientErrors
        if (limit != null && consecutiveTransientErrors >= limit) {
          isDictating = false
          val msg =
            when {
              isServerDisconnected -> "Speech service disconnected"
              error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
              error == SpeechRecognizer.ERROR_AUDIO -> "Audio capture error"
              else -> "Speech recognition error ($error)"
            }
          listener.onFatalError(token, error, msg, false)
        } else if (limit == null && !singleUtteranceMode && consecutiveTransientErrors >= 2 && shouldEnterVadStandby()) {
          // Repeated disconnects/rate limits during silence: stop churning startListening() and
          // rest silently in VAD standby until the user actually speaks again.
          destroyRecognizerInternal()
          enterVadStandby(token)
        } else {
          val backoff =
            when (consecutiveTransientErrors) {
              1 -> maxOf(restartDelayMs, 180L)
              2 -> maxOf(restartDelayMs, 340L)
              3 -> maxOf(restartDelayMs, 600L)
              else -> maxOf(restartDelayMs, 900L)
            }
          val recreate =
            isServerDisconnected ||
              error == SpeechRecognizer.ERROR_CLIENT ||
              error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
              error == SpeechRecognizer.ERROR_AUDIO ||
              error == SpeechRecognizer.ERROR_SERVER ||
              error == ERROR_TOO_MANY_REQUESTS_CODE
          if (recreate) {
            destroyRecognizerInternal()
          }
          scheduleRestart(
            delayMs = backoff,
            recreateRecognizer = recreate,
            replayUnconfirmed = canReplayAudio,
          )
        }
      }
      else -> {
        isDictating = false
        listener.onFatalError(token, error, "Speech recognition error ($error)", false)
      }
    }
  }

  private fun shouldEnterVadStandby(): Boolean =
    !singleUtteranceMode &&
      isPipeSupportedForPreBuffer() &&
      audioPreBufferManager.isCapturingActive() &&
      !audioPreBufferManager.hasRecentSpeech(VAD_RECENT_SPEECH_WINDOW_MS)

  private fun enterVadStandby(token: Long) {
    if (!isDictating || activeSessionToken != token || singleUtteranceMode) return
    cancelPendingRestart()
    inVadStandby = true
    isRecognizerListening = false
    consecutiveTransientErrors = 0
    audioPreBufferManager.markAudioConfirmedNow()
    audioPreBufferManager.armSpeechTrigger {
      postOnMain {
        if (!isDictating || activeSessionToken != token || !inVadStandby) return@postOnMain
        inVadStandby = false
        consecutiveTransientErrors = 0
        startListeningCycle(recreateRecognizer = false, replayUnconfirmed = false)
      }
    }
  }

  private fun cleanRolloverDelayMs(): Long =
    if (isPipeSupportedForPreBuffer() && audioPreBufferManager.isCapturingActive()) {
      minOf(restartDelayMs, CLEAN_ROLLOVER_MAX_DELAY_MS)
    } else {
      restartDelayMs
    }

  private fun extractTopText(bundle: Bundle?): String =
    bundle
      ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
      ?.firstOrNull()
      .orEmpty()
      .trim()

  private fun scheduleRestart(
    delayMs: Long,
    recreateRecognizer: Boolean,
    replayUnconfirmed: Boolean = false,
  ) {
    cancelPendingRestart()
    if (!isDictating) return
    val runnable = Runnable {
      pendingRestartRunnable = null
      if (isDictating) {
        startListeningCycle(
          recreateRecognizer = recreateRecognizer,
          replayUnconfirmed = replayUnconfirmed,
        )
      }
    }
    pendingRestartRunnable = runnable
    if (delayMs <= 0L) {
      mainHandler.post(runnable)
    } else {
      mainHandler.postDelayed(runnable, delayMs)
    }
  }

  private fun cancelPendingRestart() {
    pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingRestartRunnable = null
  }

  private fun scheduleFinishStop(sessionToken: Long, delayMs: Long) {
    cancelPendingFinish()
    if (!isDictating || activeSessionToken != sessionToken) return
    if (delayMs <= 0L) {
      finalizeStopCommand(sessionToken)
      return
    }
    val runnable = Runnable {
      pendingFinishRunnable = null
      finalizeStopCommand(sessionToken)
    }
    pendingFinishRunnable = runnable
    mainHandler.postDelayed(runnable, delayMs)
  }

  private fun finalizeStopCommand(sessionToken: Long) {
    if (!isDictating || activeSessionToken != sessionToken || !awaitingFinalAfterStop) return
    audioPreBufferManager.closePipeOnly()
    runCatching { recognizer?.stopListening() }
  }

  private fun cancelPendingFinish() {
    pendingFinishRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingFinishRunnable = null
  }

  private fun closeActivePipeAndPfd() {
    audioPreBufferManager.closePipeOnly()
    activeReadPfd?.let { runCatching { it.close() } }
    activeReadPfd = null
  }

  private fun destroyRecognizerInternal() {
    recognizer?.let { rec ->
      runCatching { rec.cancel() }
      runCatching { rec.destroy() }
    }
    recognizer = null
  }

  private object DefaultSpeechRecognizerFactory : SpeechRecognizerFactory {
    override fun create(context: Context): SpeechRecognizerHandle? {
      val sr =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null
          runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        } else {
          if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
          runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        }
      return sr?.let(::AndroidSpeechRecognizerHandle)
    }
  }
}
