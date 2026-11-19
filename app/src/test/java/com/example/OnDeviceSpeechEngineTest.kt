package com.example

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnectionWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioPreBufferManager
import com.example.data.EmojiRepository
import com.example.data.SettingsRepository
import com.example.ime.ImeTargetController
import com.example.ime.KeyboardController
import com.example.speech.OnDeviceSpeechEngine
import com.example.speech.SpeechRecognizerFactory
import com.example.speech.SpeechRecognizerHandle
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnDeviceSpeechEngineTest {

  private lateinit var app: Application

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
  }

  private class FakeRecognizerHandle(
    private val onStart: (Intent, RecognitionListener, FakeRecognizerHandle) -> Unit = { _, _, _ -> },
  ) : SpeechRecognizerHandle {
    lateinit var listener: RecognitionListener
      private set

    var startCalls = 0
      private set
    var cancelCalls = 0
      private set
    var destroyed = false
      private set
    var lastIntent: Intent? = null
      private set

    override fun setRecognitionListener(listener: RecognitionListener) {
      this.listener = listener
    }

    override fun startListening(intent: Intent) {
      startCalls++
      lastIntent = intent
      onStart(intent, listener, this)
    }

    override fun stopListening() = Unit

    override fun cancel() {
      cancelCalls++
    }

    override fun destroy() {
      destroyed = true
    }
  }

  private fun speechBundle(text: String): Bundle =
    Bundle().apply {
      putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

  @Test
  fun `warmUp creates recognizer silently without calling startListening and stopDictation stops cleanly`() {
    val audioMgr = AudioPreBufferManager(app)
    val handles = mutableListOf<FakeRecognizerHandle>()
    val engine =
      OnDeviceSpeechEngine(
        context = app,
        audioPreBufferManager = audioMgr,
        recognizerFactory = SpeechRecognizerFactory { FakeRecognizerHandle().also { handles.add(it) } },
        mainHandler = Handler(Looper.getMainLooper()),
        restartDelayMs = 0L,
        cancelCooldownMs = 0L,
      )

    engine.warmUp(800)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    // Zero startListening calls while idle -> zero recognition sounds!
    assertEquals(1, handles.size)
    assertEquals(0, handles.single().startCalls)

    // Start dictation -> reuses pre-warmed handle
    val segments = mutableListOf<String>()
    engine.startDictation(
      sessionToken = 1L,
      languageTag = "en-US",
      preBufferMs = 800,
      listener =
        object : OnDeviceSpeechEngine.Listener {
          override fun onReady(sessionToken: Long, usingPreBufferPipe: Boolean) = Unit
          override fun onRmsChanged(sessionToken: Long, rmsDb: Float) = Unit
          override fun onPartialText(sessionToken: Long, partial: String) = Unit
          override fun onSegmentFinal(sessionToken: Long, text: String) {
            segments.add(text)
          }

          override fun onFatalError(
            sessionToken: Long,
            errorCode: Int,
            message: String,
            isModelMissing: Boolean,
          ) = Unit
        },
    )
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, handles.single().startCalls)

    // Stop dictation -> cancels active recognizer and NEVER restarts in background
    engine.stopDictation(resumePreBuffer = true, preBufferMs = 800)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    assertEquals(1, handles.single().cancelCalls)
    assertEquals(1, handles.single().startCalls)

    // Late callbacks after stop must be ignored and must not restart
    handles.single().listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
    handles.single().listener.onResults(speechBundle("late ignored"))
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    assertEquals(1, handles.single().startCalls)
    assertTrue(segments.isEmpty())

    engine.shutDown()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(handles.single().destroyed)
  }

  @Test
  fun `active dictation survives segmented results, long silence timeouts, and busy cooldown`() {
    val audioMgr = AudioPreBufferManager(app)
    var fakeTimeMs = 1000L
    val handles = mutableListOf<FakeRecognizerHandle>()

    val engine =
      OnDeviceSpeechEngine(
        context = app,
        audioPreBufferManager = audioMgr,
        recognizerFactory = SpeechRecognizerFactory { FakeRecognizerHandle().also { handles.add(it) } },
        mainHandler = Handler(Looper.getMainLooper()),
        restartDelayMs = 0L,
        cancelCooldownMs = 140L,
        nowMsProvider = { fakeTimeMs },
      )

    val segments = mutableListOf<String>()
    var readyCount = 0
    var fatalErrors = 0

    val listener =
      object : OnDeviceSpeechEngine.Listener {
        override fun onReady(sessionToken: Long, usingPreBufferPipe: Boolean) {
          readyCount++
        }

        override fun onRmsChanged(sessionToken: Long, rmsDb: Float) = Unit
        override fun onPartialText(sessionToken: Long, partial: String) = Unit
        override fun onSegmentFinal(sessionToken: Long, text: String) {
          segments.add(text)
        }

        override fun onFatalError(
          sessionToken: Long,
          errorCode: Int,
          message: String,
          isModelMissing: Boolean,
        ) {
          fatalErrors++
        }
      }

    engine.startDictation(1L, "en-US", 800, listener)
    shadowOf(Looper.getMainLooper()).idle()

    val handle = handles.single()
    assertEquals(1, handle.startCalls)
    assertEquals(
      RecognizerIntent.EXTRA_AUDIO_SOURCE,
      handle.lastIntent?.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION),
    )
    assertEquals(
      AudioPreBufferManager.SAMPLE_RATE_HZ,
      handle.lastIntent?.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 0),
    )

    // Segmented recognition delivers multiple segments across long pauses without restarting
    handle.listener.onReadyForSpeech(Bundle.EMPTY)
    handle.listener.onPartialResults(speechBundle("first"))
    handle.listener.onSegmentResults(speechBundle("first segment"))
    handle.listener.onPartialResults(speechBundle("second"))
    handle.listener.onSegmentResults(speechBundle("second segment"))
    assertEquals(1, handle.startCalls)

    // If recognizer ends with silence timeout during a break, enter VAD standby instead of churning restarts
    handle.listener.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(engine.isInVadStandbyForTesting())
    assertEquals(1, handle.startCalls)

    // When user speaks again, PCM speech activity immediately wakes recognizer with zero lost onset
    val spokenChunk = ByteArray(512).apply {
      for (i in 0 until size step 2) {
        val sample = if ((i / 2) % 2 == 0) 2000 else -2000
        this[i] = (sample and 0xFF).toByte()
        this[i + 1] = ((sample shr 8) and 0xFF).toByte()
      }
    }
    audioMgr.processCapturedChunk(spokenChunk, spokenChunk.size)
    audioMgr.processCapturedChunk(spokenChunk, spokenChunk.size)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(engine.isInVadStandbyForTesting())
    assertEquals(2, handle.startCalls)

    // Stop then immediately re-tap inside cooldown -> delays start cleanly instead of hitting busy
    engine.stopDictation(resumePreBuffer = false, preBufferMs = 800)
    shadowOf(Looper.getMainLooper()).idle()
    fakeTimeMs += 30L // only 30ms elapsed (< 140ms cooldown)
    engine.startDictation(2L, "en-US", 800, listener)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(2, handle.startCalls) // waiting out remaining 110ms cooldown

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    assertEquals(3, handle.startCalls) // now started cleanly!

    // Even if OS reports ERROR_RECOGNIZER_BUSY once, it silently recovers without calling onFatalError
    handles.last().listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    assertEquals(4, handles.sumOf { it.startCalls })
    assertEquals(0, fatalErrors)
    assertEquals(listOf("first segment", "second segment"), segments)
    assertEquals(1, readyCount)

    engine.shutDown()
  }

  @Test
  fun `verified pipe survives pre-ready disconnects and unverified pipe falls back to direct mic on repeated failures`() {
    val audioMgr = AudioPreBufferManager(app)
    var cycle = 0
    val handles = mutableListOf<FakeRecognizerHandle>()

    val engine =
      OnDeviceSpeechEngine(
        context = app,
        audioPreBufferManager = audioMgr,
        recognizerFactory =
          SpeechRecognizerFactory {
            FakeRecognizerHandle { _, listener, _ ->
                cycle++
                when (cycle) {
                  // Cycle 1 verifies pipe via onReadyForSpeech, emits partial, then hits error 11
                  1 -> {
                    listener.onReadyForSpeech(Bundle.EMPTY)
                    listener.onPartialResults(speechBundle("keep this text"))
                    listener.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                  }
                  // Cycle 2 succeeds while pipe is STILL enabled
                  2 -> {
                    listener.onReadyForSpeech(Bundle.EMPTY)
                    listener.onResults(speechBundle("recovered next"))
                  }
                  // Cycle 3 hits pre-ready ERROR_SERVER_DISCONNECTED AFTER pipe was verified -> keeps pipe!
                  3 -> listener.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                  // Cycle 4 succeeds with pipe STILL intact
                  4 -> {
                    listener.onReadyForSpeech(Bundle.EMPTY)
                    listener.onResults(speechBundle("still on pipe"))
                  }
                }
              }
              .also { handles.add(it) }
          },
        mainHandler = Handler(Looper.getMainLooper()),
        restartDelayMs = 0L,
        cancelCooldownMs = 0L,
      )

    val segments = mutableListOf<String>()
    var fatalCount = 0

    engine.startDictation(
      sessionToken = 7L,
      languageTag = "en-US",
      preBufferMs = 800,
      listener =
        object : OnDeviceSpeechEngine.Listener {
          override fun onReady(sessionToken: Long, usingPreBufferPipe: Boolean) = Unit
          override fun onRmsChanged(sessionToken: Long, rmsDb: Float) = Unit
          override fun onPartialText(sessionToken: Long, partial: String) = Unit
          override fun onSegmentFinal(sessionToken: Long, text: String) {
            segments.add(text)
          }

          override fun onFatalError(
            sessionToken: Long,
            errorCode: Int,
            message: String,
            isModelMissing: Boolean,
          ) {
            fatalCount++
          }
        },
    )

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))

    assertTrue(handles.first().destroyed)
    assertTrue(engine.isPipeSupportedForPreBuffer())
    assertEquals(0, fatalCount)
    assertEquals(listOf("keep this text", "recovered next", "still on pipe"), segments)
    engine.shutDown()
  }

  @Test
  fun `unverified pipe rejects after pre-ready error and explicit maxTransientErrors triggers fatal`() {
    val audioMgr = AudioPreBufferManager(app)
    var cycle = 0

    val engine =
      OnDeviceSpeechEngine(
        context = app,
        audioPreBufferManager = audioMgr,
        recognizerFactory =
          SpeechRecognizerFactory {
            FakeRecognizerHandle { _, listener, _ ->
              cycle++
              // Never calls onReadyForSpeech -> Cycle 1 rejects pipe with ERROR_CLIENT, Cycles 2..6 hit 5 errors
              if (cycle == 1) {
                listener.onError(SpeechRecognizer.ERROR_CLIENT)
              } else {
                listener.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
              }
            }
          },
        mainHandler = Handler(Looper.getMainLooper()),
        restartDelayMs = 0L,
        cancelCooldownMs = 0L,
        maxTransientErrors = 5,
      )

    var fatalCode = -1
    engine.startDictation(
      sessionToken = 8L,
      languageTag = "en-US",
      preBufferMs = 800,
      listener =
        object : OnDeviceSpeechEngine.Listener {
          override fun onReady(sessionToken: Long, usingPreBufferPipe: Boolean) = Unit
          override fun onRmsChanged(sessionToken: Long, rmsDb: Float) = Unit
          override fun onPartialText(sessionToken: Long, partial: String) = Unit
          override fun onSegmentFinal(sessionToken: Long, text: String) = Unit
          override fun onFatalError(
            sessionToken: Long,
            errorCode: Int,
            message: String,
            isModelMissing: Boolean,
          ) {
            fatalCode = errorCode
          }
        },
    )

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    assertFalse(engine.isPipeSupportedForPreBuffer())
    assertEquals(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, fatalCode)
    assertEquals(6, cycle)
  }

  @Test
  fun `keyboard controller commits partial on Stop, stays silent when stopped, and blocks cross-field leaks`() {
    val fieldABuffer = StringBuilder()
    var composingText = ""
    var currentFieldId = 1

    val fakeIc =
      object : InputConnectionWrapper(null, true) {
        override fun beginBatchEdit(): Boolean = true

        override fun endBatchEdit(): Boolean = true

        override fun getSelectedText(flags: Int): CharSequence = ""

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
          fieldABuffer.toString() + composingText

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
          composingText = text?.toString().orEmpty()
          return true
        }

        override fun finishComposingText(): Boolean {
          if (composingText.isNotEmpty()) {
            fieldABuffer.append(composingText)
            composingText = ""
          }
          return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
          composingText = ""
          fieldABuffer.append(text?.toString().orEmpty())
          return true
        }
      }

    val targetController =
      ImeTargetController().apply {
        attachProviders(
          icProvider = { fakeIc },
          eiProvider = {
            EditorInfo().apply {
              packageName = "com.example.notes"
              fieldId = currentFieldId
              inputType = InputType.TYPE_CLASS_TEXT
            }
          },
        )
      }

    val handles = mutableListOf<FakeRecognizerHandle>()
    val audioMgr = AudioPreBufferManager(app)
    val speechEngine =
      OnDeviceSpeechEngine(
        context = app,
        audioPreBufferManager = audioMgr,
        recognizerFactory =
          SpeechRecognizerFactory { FakeRecognizerHandle().also { handles.add(it) } },
        mainHandler = Handler(Looper.getMainLooper()),
        restartDelayMs = 0L,
        cancelCooldownMs = 0L,
      )

    val controller =
      KeyboardController(
        context = app,
        settingsRepository = SettingsRepository.getInstance(app),
        emojiRepository = EmojiRepository.getInstance(app),
        targetController = targetController,
        audioPreBufferManager = audioMgr,
        speechEngine = speechEngine,
        onSwitchToNextKeyboard = {},
        onOpenSettings = {},
      )

    // Keyboard visible -> pre-warmed silently without calling startListening
    controller.onWindowShown()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, handles.size)
    val handle = handles.single()
    assertEquals(0, handle.startCalls)
    assertFalse(controller.uiState.value.isListening)

    // Tap Dictate -> starts listening & reflects onReadyForSpeech
    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(controller.uiState.value.isListening)
    assertFalse(controller.uiState.value.isSpeechReady)
    assertEquals(1, handle.startCalls)

    handle.listener.onReadyForSpeech(Bundle.EMPTY)
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(controller.uiState.value.isSpeechReady)

    // Partial shown while talking; pressing Stop preserves & commits partial and halts recognizer
    handle.listener.onPartialResults(speechBundle("hello world"))
    shadowOf(Looper.getMainLooper()).idle()
    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()

    assertFalse(controller.uiState.value.isListening)
    assertFalse(controller.uiState.value.isSpeechReady)
    assertEquals("hello world", fieldABuffer.toString())
    assertEquals(1, handle.cancelCalls)

    // Verify long idle wait after Stop triggers ZERO recognition restarts
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    assertEquals(1, handle.startCalls)

    // Switching to another field while dictating stops immediately without committing into new field
    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(controller.uiState.value.isListening)
    currentFieldId = 2
    controller.onStartInput(restarting = false)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(controller.uiState.value.isListening)
    assertEquals("hello world", fieldABuffer.toString())

    controller.onWindowHidden()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(handle.destroyed)
    controller.destroy()
  }

  @Test
  fun `continuous dictation recreates recognizer on BUSY, recovers from rate limit and timeout errors, and survives transient onFinishInput`() {
    val fieldBuffer = StringBuilder()
    var currentFieldId = 1

    val fakeIc =
      object : InputConnectionWrapper(null, true) {
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun getSelectedText(flags: Int): CharSequence = ""
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = fieldBuffer.toString()
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun finishComposingText(): Boolean = true
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
          fieldBuffer.append(text?.toString().orEmpty())
          return true
        }
      }

    val targetController =
      ImeTargetController().apply {
        attachProviders(
          icProvider = { fakeIc },
          eiProvider = {
            EditorInfo().apply {
              packageName = "com.example.notes"
              fieldId = currentFieldId
              inputType = InputType.TYPE_CLASS_TEXT
            }
          },
        )
      }

    val handles = mutableListOf<FakeRecognizerHandle>()
    val audioMgr = AudioPreBufferManager(app)
    val speechEngine =
      OnDeviceSpeechEngine(
        context = app,
        audioPreBufferManager = audioMgr,
        recognizerFactory = SpeechRecognizerFactory { FakeRecognizerHandle().also { handles.add(it) } },
        mainHandler = Handler(Looper.getMainLooper()),
        restartDelayMs = 20L,
        cancelCooldownMs = 0L,
      )

    val controller =
      KeyboardController(
        context = app,
        settingsRepository = SettingsRepository.getInstance(app),
        emojiRepository = EmojiRepository.getInstance(app),
        targetController = targetController,
        audioPreBufferManager = audioMgr,
        speechEngine = speechEngine,
        onSwitchToNextKeyboard = {},
        onOpenSettings = {},
      )

    controller.onWindowShown()
    controller.onStartInput(restarting = false)
    shadowOf(Looper.getMainLooper()).idle()

    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(controller.uiState.value.isListening)
    assertEquals(1, handles.size)

    // Transient BUSY error destroys stuck handle, creates fresh handle #2, and stays listening
    handles.last().listener.onReadyForSpeech(Bundle.EMPTY)
    handles.last().listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    assertEquals(2, handles.size)
    assertTrue(handles.first().destroyed)
    assertTrue(controller.uiState.value.isListening)

    // ERROR_TOO_MANY_REQUESTS (10), ERROR_CANNOT_CHECK_SUPPORT (14), and repeated disconnects recover silently
    handles.last().listener.onReadyForSpeech(Bundle.EMPTY)
    handles.last().listener.onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    assertEquals(3, handles.size)
    assertTrue(controller.uiState.value.isListening)

    handles.last().listener.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    assertEquals(4, handles.size)
    assertTrue(controller.uiState.value.isListening)

    // App briefly fires onFinishInput() followed by onStartInput(restarting = true) -> stays listening!
    controller.onFinishInput()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    controller.onStartInput(restarting = true)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    assertTrue(controller.uiState.value.isListening)

    handles.last().listener.onResults(speechBundle("still going"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("still going", fieldBuffer.toString())

    // Truly finishing input / switching to different field stops dictation after 300ms grace
    currentFieldId = 99
    controller.onFinishInput()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
    assertFalse(controller.uiState.value.isListening)

    controller.onWindowHidden()
    controller.destroy()
  }
}
