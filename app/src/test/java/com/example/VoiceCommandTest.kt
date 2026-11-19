package com.example

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnectionWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioPreBufferManager
import com.example.command.ParsedCommand
import com.example.command.VoiceCommandParser
import com.example.data.EmojiCatalog
import com.example.data.EmojiItem
import com.example.data.EmojiRepository
import com.example.data.SettingsRepository
import com.example.ime.ImeTargetController
import com.example.ime.KeyboardController
import com.example.speech.OnDeviceSpeechEngine
import com.example.speech.SpeechRecognizerFactory
import com.example.speech.SpeechRecognizerHandle
import java.time.Duration
import kotlinx.coroutines.runBlocking
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
class VoiceCommandTest {

  private lateinit var app: Application

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
  }

  private fun defaultDictionary(customAliases: Map<String, String> = emptyMap()): List<EmojiItem> =
    EmojiCatalog.all.map { def ->
      EmojiItem(
        emoji = def.emoji,
        defaultName = def.defaultName,
        customAlias = customAliases[def.emoji],
        keywords = def.keywords,
        category = def.category,
        usageCount = 0,
        lastUsedAt = 0L,
      )
    }

  private fun speechBundle(text: String): Bundle =
    Bundle().apply {
      putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

  private class FakeCommandRecognizerHandle : SpeechRecognizerHandle {
    lateinit var listener: RecognitionListener
      private set
    var startCalls = 0
      private set
    var stopCalls = 0
      private set
    var cancelCalls = 0
      private set

    override fun setRecognitionListener(listener: RecognitionListener) {
      this.listener = listener
    }

    override fun startListening(intent: Intent) {
      startCalls++
    }

    override fun stopListening() {
      stopCalls++
    }

    override fun cancel() {
      cancelCalls++
    }

    override fun destroy() = Unit
  }

  @Test
  fun `parser handles normalization, single-name commands, custom aliases, and unknown rejection`() {
    val dict = defaultDictionary()

    assertEquals(
      ParsedCommand.InsertEmoji("🔥", "fire"),
      VoiceCommandParser.parse("Insert emoji fire", dict),
    )
    assertEquals(
      ParsedCommand.InsertEmoji("😉", "wink"),
      VoiceCommandParser.parse("insert emoji wink.", dict),
    )
    assertEquals(
      ParsedCommand.InsertEmoji("🤗", "hugging face"),
      VoiceCommandParser.parse("  INSERT   EMOJI   Hugging Face! ", dict),
    )
    assertEquals(
      ParsedCommand.InsertEmoji("😕", "confused"),
      VoiceCommandParser.parse("insert emoji confused", dict),
    )
    assertEquals(
      ParsedCommand.InsertEmoji("👻", "ghost"),
      VoiceCommandParser.parse("insert emoji ghost", dict),
    )
    assertEquals(
      ParsedCommand.InsertEmoji("🤖", "robot"),
      VoiceCommandParser.parse("insert emoji robot", dict),
    )
    assertEquals(
      ParsedCommand.InsertEmoji("💔", "broken heart"),
      VoiceCommandParser.parse("insert emoji broken heart", dict),
    )
    assertEquals(
      ParsedCommand.InsertEmoji("🥶", "cold"),
      VoiceCommandParser.parse("insert emoji cold", dict),
    )
    assertEquals(
      ParsedCommand.InsertEmoji("🤢", "sick"),
      VoiceCommandParser.parse("insert emoji sick", dict),
    )

    // Without command prefix -> UnknownCommand
    assertTrue(VoiceCommandParser.parse("Fire", dict) is ParsedCommand.UnknownCommand)
    assertTrue(VoiceCommandParser.parse("confused", dict) is ParsedCommand.UnknownCommand)
    // Prefix alone -> UnknownCommand
    assertTrue(VoiceCommandParser.parse("insert emoji", dict) is ParsedCommand.UnknownCommand)
    // Unknown name -> UnknownCommand
    assertTrue(VoiceCommandParser.parse("insert emoji dragonfruit", dict) is ParsedCommand.UnknownCommand)
    // Empty -> UnknownCommand
    assertTrue(VoiceCommandParser.parse("   ", dict) is ParsedCommand.UnknownCommand)

    // Custom alias on 🌿 ("calm") resolves before keyword matches on other emojis
    val customDict = defaultDictionary(customAliases = mapOf("🌿" to "calm"))
    assertEquals(
      ParsedCommand.InsertEmoji("🌿", "calm"),
      VoiceCommandParser.parse("insert emoji calm", customDict),
    )
  }

  @Test
  fun `parser handles type letters, coding symbols, capital letters, backspace, and control c`() {
    val dict = defaultDictionary()

    // Coding symbols — spoken names and literal ASR punctuation
    assertEquals(ParsedCommand.TypeText(".", "period"), VoiceCommandParser.parse("type period", dict))
    assertEquals(ParsedCommand.TypeText(".", "."), VoiceCommandParser.parse("type .", dict))
    assertEquals(ParsedCommand.TypeText(",", "comma"), VoiceCommandParser.parse("type comma", dict))
    assertEquals(ParsedCommand.TypeText(",", ","), VoiceCommandParser.parse("type ,", dict))
    assertEquals(ParsedCommand.TypeText("<", "less than"), VoiceCommandParser.parse("type less than", dict))
    assertEquals(ParsedCommand.TypeText("<", "<"), VoiceCommandParser.parse("type <", dict))
    assertEquals(ParsedCommand.TypeText("[", "left bracket"), VoiceCommandParser.parse("type left bracket", dict))
    assertEquals(ParsedCommand.TypeText("[", "["), VoiceCommandParser.parse("type [", dict))
    assertEquals(ParsedCommand.TypeText("{", "open brace"), VoiceCommandParser.parse("type open brace", dict))
    assertEquals(ParsedCommand.TypeText("{", "{"), VoiceCommandParser.parse("type {", dict))
    assertEquals(ParsedCommand.TypeText("}", "close brace"), VoiceCommandParser.parse("type close brace", dict))
    assertEquals(ParsedCommand.TypeText(";", "semicolon"), VoiceCommandParser.parse("type semicolon.", dict))
    assertEquals(ParsedCommand.TypeText("\\", "backslash"), VoiceCommandParser.parse("type backslash", dict))
    assertEquals(ParsedCommand.TypeText("|", "pipe"), VoiceCommandParser.parse("type pipe", dict))
    assertEquals(ParsedCommand.TypeText("_", "underscore"), VoiceCommandParser.parse("type underscore", dict))
    assertEquals(ParsedCommand.TypeText("?", "?"), VoiceCommandParser.parse("type ?", dict))
    assertEquals(ParsedCommand.TypeText("=>", "fat arrow"), VoiceCommandParser.parse("symbol fat arrow", dict))

    // Letters, digits, and capital letters
    assertEquals(ParsedCommand.TypeText("a", "a"), VoiceCommandParser.parse("type a", dict))
    assertEquals(ParsedCommand.TypeText("z", "z"), VoiceCommandParser.parse("type letter z", dict))
    assertEquals(ParsedCommand.TypeText("7", "7"), VoiceCommandParser.parse("type 7", dict))
    assertEquals(ParsedCommand.TypeText("5", "five"), VoiceCommandParser.parse("type five", dict))
    assertEquals(ParsedCommand.TypeText("A", "capital A"), VoiceCommandParser.parse("capital a", dict))
    assertEquals(ParsedCommand.TypeText("M", "capital M"), VoiceCommandParser.parse("type capital m", dict))
    assertEquals(ParsedCommand.TypeText("Z", "capital Z"), VoiceCommandParser.parse("uppercase z!", dict))
    assertEquals(ParsedCommand.TypeText("B", "capital B"), VoiceCommandParser.parse("type uppercase letter b", dict))

    // Backspace & Control+C
    assertEquals(ParsedCommand.Backspace, VoiceCommandParser.parse("backspace", dict))
    assertEquals(ParsedCommand.Backspace, VoiceCommandParser.parse("press backspace.", dict))
    assertEquals(ParsedCommand.Backspace, VoiceCommandParser.parse("back-space", dict))
    assertEquals(ParsedCommand.Backspace, VoiceCommandParser.parse("delete", dict))
    assertEquals(ParsedCommand.ControlC, VoiceCommandParser.parse("control c", dict))
    assertEquals(ParsedCommand.ControlC, VoiceCommandParser.parse("Ctrl C", dict))
    assertEquals(ParsedCommand.ControlC, VoiceCommandParser.parse("Ctrl+C", dict))
    assertEquals(ParsedCommand.ControlC, VoiceCommandParser.parse("control see", dict))
    assertEquals(ParsedCommand.ControlC, VoiceCommandParser.parse("press control c", dict))

    // Glued ASR punctuation directly on command prefix ("Type?" when user says "Type questionmark")
    assertEquals(ParsedCommand.TypeText("?", "?"), VoiceCommandParser.parse("Type?", dict))
    assertEquals(ParsedCommand.TypeText(".", "."), VoiceCommandParser.parse("Type.", dict))
    assertEquals(ParsedCommand.TypeText(",", ","), VoiceCommandParser.parse("Type,", dict))
    assertEquals(ParsedCommand.TypeText("!", "!"), VoiceCommandParser.parse("Type!", dict))
    assertEquals(ParsedCommand.TypeText(":", ":"), VoiceCommandParser.parse("Type:", dict))
    assertEquals(ParsedCommand.TypeText(";", ";"), VoiceCommandParser.parse("Type;", dict))
    assertEquals(ParsedCommand.TypeText("...", "..."), VoiceCommandParser.parse("Type...", dict))
    assertEquals(ParsedCommand.TypeText("...", "..."), VoiceCommandParser.parse("Type…", dict))
    assertEquals(ParsedCommand.TypeText("?", "questionmark"), VoiceCommandParser.parse("Type questionmark", dict))
    assertEquals(ParsedCommand.TypeText("?", "?"), VoiceCommandParser.parse("symbol?", dict))
    assertEquals(ParsedCommand.TypeText("!", "!"), VoiceCommandParser.parse("insert symbol!", dict))
    assertEquals(ParsedCommand.TypeText("a?", "a?"), VoiceCommandParser.parse("Type a?", dict))

    // Indefinite type streams ("Type FM capital M" -> "fmM")
    assertEquals(ParsedCommand.TypeText("fmM", "fmM"), VoiceCommandParser.parse("Type FM capital M", dict))
    assertEquals(
      ParsedCommand.TypeText("<K,[{a", "<K,[{a"),
      VoiceCommandParser.parse("type less than capital k comma open bracket open brace a", dict),
    )
    assertEquals(
      ParsedCommand.TypeText("fm M", "fm M"),
      VoiceCommandParser.parse("type f m space capital m", dict),
    )
    assertEquals(
      ParsedCommand.TypeText("x=1;", "x=1;"),
      VoiceCommandParser.parse("type x equals one semicolon", dict),
    )
    assertEquals(ParsedCommand.TypeText("hello", "hello"), VoiceCommandParser.parse("type hello", dict))

    // Non-prefixed bare words still rejected when not in an active type stream
    assertTrue(VoiceCommandParser.parse("hello", dict) is ParsedCommand.UnknownCommand)
  }

  @Test
  fun `command mode hold release waits for final, inserts once, never leaks partials, and contrasts with dictate`() {
    var fieldText = "hello "
    var selStart = fieldText.length
    var selEnd = fieldText.length
    var composingText = ""
    var currentFieldId = 1

    val fakeIc =
      object : InputConnectionWrapper(null, true) {
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun getSelectedText(flags: Int): CharSequence =
          if (selEnd > selStart) fieldText.substring(selStart, selEnd) else ""

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
          fieldText.substring(0, selStart) + composingText

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
          composingText = text?.toString().orEmpty()
          return true
        }

        override fun finishComposingText(): Boolean {
          if (composingText.isNotEmpty()) {
            fieldText = fieldText.substring(0, selStart) + composingText + fieldText.substring(selEnd)
            selStart += composingText.length
            selEnd = selStart
            composingText = ""
          }
          return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
          composingText = ""
          val str = text?.toString().orEmpty()
          val min = minOf(selStart, selEnd).coerceIn(0, fieldText.length)
          val max = maxOf(selStart, selEnd).coerceIn(0, fieldText.length)
          fieldText = fieldText.substring(0, min) + str + fieldText.substring(max)
          selStart = min + str.length
          selEnd = selStart
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

    val fakeHandle = FakeCommandRecognizerHandle()
    val audioMgr = AudioPreBufferManager(app)
    val speechEngine =
      OnDeviceSpeechEngine(
        context = app,
        audioPreBufferManager = audioMgr,
        recognizerFactory = SpeechRecognizerFactory { fakeHandle },
        mainHandler = Handler(Looper.getMainLooper()),
        restartDelayMs = 0L,
        cancelCooldownMs = 0L,
        commandReleaseDrainMs = 0L,
      )

    var fakeNow = 1000L
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
        nowMsProvider = { fakeNow },
      )

    controller.onWindowShown()
    shadowOf(Looper.getMainLooper()).idle()

    // 1. Hold Command -> partial transcript shown ONLY in dev panel, never inserted into app
    controller.onCommandPressStart()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onReadyForSpeech(Bundle.EMPTY)
    fakeHandle.listener.onPartialResults(speechBundle("insert emoji fire"))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals("hello ", fieldText)
    assertEquals("", composingText)
    assertTrue(controller.uiState.value.commandDevState.heard.contains("partial"))

    // 2. Release -> stopListening called -> final transcript arrives 140ms later -> inserts 🔥 once
    fakeNow = 1200L
    controller.onCommandPressEnd()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, fakeHandle.stopCalls)
    assertTrue(controller.uiState.value.isCommandPendingFinal)

    fakeNow = 1340L
    fakeHandle.listener.onResults(speechBundle("insert emoji fire"))
    // Duplicate onResults for same recording must be ignored
    fakeHandle.listener.onResults(speechBundle("insert emoji fire"))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals("hello 🔥", fieldText)
    assertFalse(controller.uiState.value.isCommandPendingFinal)
    assertEquals("InsertEmoji(🔥)", controller.uiState.value.commandDevState.parsed)
    assertEquals("inserted", controller.uiState.value.commandDevState.outcome)
    assertTrue(controller.uiState.value.commandDevState.timing.contains("final +140ms"))

    // 3. Partial command text on ERROR_NO_MATCH is NOT preserved into document
    controller.onCommandPressStart()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onPartialResults(speechBundle("insert emoji wink"))
    shadowOf(Looper.getMainLooper()).idle()
    controller.onCommandPressEnd()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("hello 🔥", fieldText)
    assertEquals("no speech", controller.uiState.value.commandDevState.outcome)

    // 4. Unknown command or missing prefix ("fire") leaves document unchanged
    controller.onCommandPressStart()
    shadowOf(Looper.getMainLooper()).idle()
    controller.onCommandPressEnd()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onResults(speechBundle("fire"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("hello 🔥", fieldText)
    assertEquals("UnknownCommand", controller.uiState.value.commandDevState.parsed)

    // 5. Select text, then issue command -> selection replaced by emoji
    fieldText = "hi world!"
    selStart = 3
    selEnd = 8 // "world" selected
    controller.onCommandPressStart()
    shadowOf(Looper.getMainLooper()).idle()
    controller.onCommandPressEnd()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onResults(speechBundle("insert emoji wink"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("hi 😉!", fieldText)

    // 6. Change field before final transcript arrives -> pending command invalidated, nothing inserted
    controller.onCommandPressStart()
    shadowOf(Looper.getMainLooper()).idle()
    controller.onCommandPressEnd()
    shadowOf(Looper.getMainLooper()).idle()
    currentFieldId = 2
    controller.onStartInput(restarting = false)
    fakeHandle.listener.onResults(speechBundle("insert emoji fire"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("hi 😉!", fieldText)
    assertTrue(controller.uiState.value.commandDevState.outcome.startsWith("cancelled"))

    // 7. Dictate mode inserts "Insert emoji fire" as ordinary text
    currentFieldId = 1
    fieldText = ""
    selStart = 0
    selEnd = 0
    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onResults(speechBundle("Insert emoji fire"))
    shadowOf(Looper.getMainLooper()).idle()
    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("Insert emoji fire", fieldText)

    // 8. Fallback editor command + Undo restores original text + renamed emoji alias works
    val emojiRepo = EmojiRepository.getInstance(app)
    runBlocking { emojiRepo.setCustomAlias("🌿", "calm") }
    repeat(50) {
      shadowOf(Looper.getMainLooper()).idle()
      if (controller.uiState.value.emojiItems.firstOrNull { it.emoji == "🌿" }?.customAlias == "calm") {
        return@repeat
      }
      Thread.sleep(10)
    }
    assertEquals(
      "calm",
      controller.uiState.value.emojiItems.firstOrNull { it.emoji == "🌿" }?.customAlias,
    )

    controller.toggleFallbackExpanded()
    controller.onFallbackEditorChanged("feel tense now", 5, 10) // "tense" selected
    shadowOf(Looper.getMainLooper()).idle()

    controller.onCommandPressStart()
    shadowOf(Looper.getMainLooper()).idle()
    controller.onCommandPressEnd()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onResults(speechBundle("insert emoji calm"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("feel 🌿 now", controller.uiState.value.fallbackText)

    controller.onUndo()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("feel tense now", controller.uiState.value.fallbackText)

    controller.onWindowHidden()
    controller.destroy()
  }

  @Test
  fun `command mode executes coding symbols, capital letters, backspace, control c, and dictation continues lowercase`() {
    var fieldText = ""
    var selStart = 0
    var selEnd = 0
    var composingText = ""
    var currentInputType = InputType.TYPE_CLASS_TEXT
    var currentFieldId = 10
    val sentKeyEvents = mutableListOf<android.view.KeyEvent>()

    val fakeIc =
      object : InputConnectionWrapper(null, true) {
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun getSelectedText(flags: Int): CharSequence =
          if (selEnd > selStart) fieldText.substring(selStart, selEnd) else ""

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
          if (currentInputType == InputType.TYPE_NULL) null
          else fieldText.substring(0, selStart) + composingText

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
          composingText = text?.toString().orEmpty()
          return true
        }

        override fun finishComposingText(): Boolean {
          if (composingText.isNotEmpty()) {
            fieldText = fieldText.substring(0, selStart) + composingText + fieldText.substring(selEnd)
            selStart += composingText.length
            selEnd = selStart
            composingText = ""
          }
          return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
          composingText = ""
          val str = text?.toString().orEmpty()
          val min = minOf(selStart, selEnd).coerceIn(0, fieldText.length)
          val max = maxOf(selStart, selEnd).coerceIn(0, fieldText.length)
          fieldText = fieldText.substring(0, min) + str + fieldText.substring(max)
          selStart = min + str.length
          selEnd = selStart
          return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
          if (selStart <= 0) return false
          val count = beforeLength.coerceAtMost(selStart)
          fieldText = fieldText.removeRange(selStart - count, selStart)
          selStart -= count
          selEnd = selStart
          return true
        }

        override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean {
          if (event != null) {
            sentKeyEvents.add(event)
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DEL && event.action == android.view.KeyEvent.ACTION_DOWN) {
              if (fieldText.isNotEmpty() && selStart > 0) {
                fieldText = fieldText.removeRange(selStart - 1, selStart)
                selStart--
                selEnd = selStart
              }
            }
          }
          return true
        }
      }

    val targetController =
      ImeTargetController().apply {
        attachProviders(
          icProvider = { fakeIc },
          eiProvider = {
            EditorInfo().apply {
              packageName = "com.example.code"
              fieldId = currentFieldId
              inputType = currentInputType
            }
          },
        )
      }

    val fakeHandle = FakeCommandRecognizerHandle()
    val audioMgr = AudioPreBufferManager(app)
    val speechEngine =
      OnDeviceSpeechEngine(
        context = app,
        audioPreBufferManager = audioMgr,
        recognizerFactory = SpeechRecognizerFactory { fakeHandle },
        mainHandler = Handler(Looper.getMainLooper()),
        restartDelayMs = 0L,
        cancelCooldownMs = 0L,
        commandReleaseDrainMs = 0L,
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

    fun runCommand(transcript: String) {
      controller.onCommandPressStart()
      shadowOf(Looper.getMainLooper()).idle()
      controller.onCommandPressEnd()
      shadowOf(Looper.getMainLooper()).idle()
      fakeHandle.listener.onResults(speechBundle(transcript))
      shadowOf(Looper.getMainLooper()).idle()
    }

    // 1. Dictate "Hello", tap Space, then dictate "My friend" -> continues lowercase ("Hello my friend")
    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onResults(speechBundle("Hello"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("Hello", controller.uiState.value.lastWordTyped)

    controller.onDictateTap() // Stop
    shadowOf(Looper.getMainLooper()).idle()
    controller.onSpaceSingle()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("Hello", controller.uiState.value.lastWordTyped)

    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onPartialResults(speechBundle("My"))
    shadowOf(Looper.getMainLooper()).idle()
    // Even partial uses lowercase continuation
    assertEquals("my", composingText)
    fakeHandle.listener.onResults(speechBundle("My friend"))
    shadowOf(Looper.getMainLooper()).idle()
    // Acronyms and pronoun "I" stay capitalized mid-sentence
    fakeHandle.listener.onResults(speechBundle("I know"))
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onResults(speechBundle("API details"))
    shadowOf(Looper.getMainLooper()).idle()
    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("Hello my friend I know API details", fieldText)
    assertEquals("details", controller.uiState.value.lastWordTyped)

    // 2. End sentence with Command "type ." -> next dictation stays capitalized!
    runCommand("type .")
    assertEquals("Hello my friend I know API details.", fieldText)
    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onResults(speechBundle("Next sentence"))
    shadowOf(Looper.getMainLooper()).idle()
    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("Hello my friend I know API details. Next sentence", fieldText)

    // 3. Coding symbols, capital letters, backspace, confused emoji, and Control+C
    fieldText = ""
    selStart = 0
    selEnd = 0
    currentFieldId = 11
    controller.onStartInput(restarting = false)

    runCommand("type less than")
    runCommand("capital k")
    runCommand("type ,")
    runCommand("type open bracket")
    runCommand("type open brace")
    runCommand("type a")
    assertEquals("<K,[{a", fieldText)

    runCommand("backspace")
    assertEquals("<K,[{", fieldText)

    runCommand("insert emoji confused")
    assertEquals("<K,[{😕", fieldText)

    runCommand("control c")
    val ctrlCDown =
      sentKeyEvents.any {
        it.keyCode == android.view.KeyEvent.KEYCODE_C &&
          it.action == android.view.KeyEvent.ACTION_DOWN &&
          it.isCtrlPressed
      }
    assertTrue(ctrlCDown)
    assertEquals("sent Ctrl+C", controller.uiState.value.commandDevState.outcome)

    // 4. Restricted TYPE_NULL field remembers last typed word and continues lowercase
    currentFieldId = 12
    currentInputType = InputType.TYPE_NULL
    fieldText = ""
    selStart = 0
    selEnd = 0
    controller.onStartInput(restarting = false)
    shadowOf(Looper.getMainLooper()).idle()

    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onResults(speechBundle("Start"))
    shadowOf(Looper.getMainLooper()).idle()
    controller.onDictateTap()
    controller.onSpaceSingle()
    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onResults(speechBundle("Continuing"))
    shadowOf(Looper.getMainLooper()).idle()
    controller.onDictateTap()
    assertEquals("Start continuing", fieldText)
    assertEquals("continuing", controller.uiState.value.lastWordTyped)

    // Type period in restricted mode -> next dictation capitalizes again
    runCommand("type period")
    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    fakeHandle.listener.onResults(speechBundle("Fresh"))
    shadowOf(Looper.getMainLooper()).idle()
    controller.onDictateTap()
    assertEquals("Start continuing. Fresh", fieldText)

    // 5. Fallback Control+C copies selection to clipboard
    controller.toggleFallbackExpanded()
    controller.onFallbackEditorChanged("copy this snippet", 5, 9) // "this"
    runCommand("ctrl c")
    val clipboard =
      app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    assertEquals("this", clipboard.primaryClip?.getItemAt(0)?.text?.toString())

    controller.onWindowHidden()
    controller.destroy()
  }

  @Test
  fun `command mode drains audio on release, recreates recognizer across mode switch, and retries NO_MATCH while held`() {
    val fieldBuffer = StringBuilder()
    val fakeIc =
      object : InputConnectionWrapper(null, true) {
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun getSelectedText(flags: Int): CharSequence = ""
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = fieldBuffer.toString()
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
              fieldId = 42
              inputType = InputType.TYPE_CLASS_TEXT
            }
          },
        )
      }

    val handles = mutableListOf<FakeCommandRecognizerHandle>()
    val audioMgr = AudioPreBufferManager(app)
    val speechEngine =
      OnDeviceSpeechEngine(
        context = app,
        audioPreBufferManager = audioMgr,
        recognizerFactory = SpeechRecognizerFactory { FakeCommandRecognizerHandle().also { handles.add(it) } },
        mainHandler = Handler(Looper.getMainLooper()),
        restartDelayMs = 20L,
        cancelCooldownMs = 140L,
        commandReleaseDrainMs = 200L,
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
    assertEquals(1, handles.size)

    // 1. Start Dictate (segmented) then switch to Command (non-segmented) -> recreates handle cleanly after cooldown
    controller.onDictateTap()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, handles.single().startCalls)
    controller.onDictateTap() // Stop Dictate (triggers cancelCooldownMs = 140ms)
    shadowOf(Looper.getMainLooper()).idle()

    // Immediately press Command before cooldown expires and release quickly (short utterance)
    controller.onCommandPressStart()
    controller.onCommandPressEnd()
    // Advance past 140ms cooldown -> creates new non-segmented handle #2 and starts listening
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(160))
    assertEquals(2, handles.size)
    val cmdHandle1 = handles.last()
    assertEquals(1, cmdHandle1.startCalls)
    // stopListening NOT called prematurely before onReadyForSpeech + 200ms drain
    assertEquals(0, cmdHandle1.stopCalls)

    cmdHandle1.listener.onReadyForSpeech(Bundle.EMPTY)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    assertEquals(0, cmdHandle1.stopCalls) // still draining audio

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
    assertEquals(1, cmdHandle1.stopCalls) // now stopListening fired after 200ms drain!

    cmdHandle1.listener.onResults(speechBundle("insert emoji fire"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("🔥", fieldBuffer.toString())

    // 2. While STILL holding Command, ERROR_NO_MATCH restarts seamlessly without failing
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    controller.onCommandPressStart()
    shadowOf(Looper.getMainLooper()).idle()
    cmdHandle1.listener.onReadyForSpeech(Bundle.EMPTY)
    cmdHandle1.listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
    // Still recording and restarted!
    assertTrue(controller.uiState.value.isCommandRecording)
    assertEquals(3, cmdHandle1.startCalls)

    // Now release and deliver final -> inserts once
    controller.onCommandPressEnd()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(220))
    cmdHandle1.listener.onResults(speechBundle("type period"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("🔥.", fieldBuffer.toString())

    // 3. ERROR_NO_MATCH AFTER release does NOT insert partial, but surfaces partial in dev diagnostics
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    controller.onCommandPressStart()
    shadowOf(Looper.getMainLooper()).idle()
    cmdHandle1.listener.onReadyForSpeech(Bundle.EMPTY)
    cmdHandle1.listener.onPartialResults(speechBundle("insert emoji wink"))
    shadowOf(Looper.getMainLooper()).idle()
    controller.onCommandPressEnd()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(220))
    cmdHandle1.listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals("🔥.", fieldBuffer.toString())
    assertEquals("no speech", controller.uiState.value.commandDevState.outcome)
    assertEquals("— (partial: insert emoji wink)", controller.uiState.value.commandDevState.heard)

    controller.onWindowHidden()
    controller.destroy()
  }

  @Test
  fun `continuous tap-to-toggle command mode survives breaks, executes indefinite type streams, and never leaks partials`() {
    val fieldBuffer = StringBuilder()
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
              fieldId = 77
              inputType = InputType.TYPE_CLASS_TEXT
            }
          },
        )
      }

    val handles = mutableListOf<FakeCommandRecognizerHandle>()
    val audioMgr = AudioPreBufferManager(app)
    val speechEngine =
      OnDeviceSpeechEngine(
        context = app,
        audioPreBufferManager = audioMgr,
        recognizerFactory = SpeechRecognizerFactory { FakeCommandRecognizerHandle().also { handles.add(it) } },
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

    // Tap Command in default TAP_TO_TOGGLE mode
    controller.onCommandTap()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(controller.uiState.value.isCommandRecording)
    val handle = handles.single()
    handle.listener.onReadyForSpeech(Bundle.EMPTY)
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(controller.uiState.value.isSpeechReady)

    // Partial transcripts never insert text
    handle.listener.onPartialResults(speechBundle("insert emoji fire"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("", fieldBuffer.toString())

    // Segment 1: emoji command -> stays in continuous Command mode!
    handle.listener.onSegmentResults(speechBundle("insert emoji fire"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("🔥", fieldBuffer.toString())
    assertTrue(controller.uiState.value.isCommandRecording)

    // Break / silence timeout does NOT turn off continuous Command mode
    handle.listener.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    assertTrue(controller.uiState.value.isCommandRecording)

    // Wake after break & issue "Type FM capital M" -> "fmM" + starts sticky type stream
    handles.last().listener.onReadyForSpeech(Bundle.EMPTY)
    handles.last().listener.onSegmentResults(speechBundle("Type FM capital M"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("🔥fmM", fieldBuffer.toString())

    // Another break, then continuation without repeating "type": "question mark" and "Type?"
    handles.last().listener.onSegmentResults(speechBundle("question mark"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("🔥fmM?", fieldBuffer.toString())

    // Stop typing -> unprefixed "Fire" is ignored again
    handles.last().listener.onSegmentResults(speechBundle("stop typing"))
    handles.last().listener.onSegmentResults(speechBundle("Fire"))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("🔥fmM?", fieldBuffer.toString())
    assertEquals("UnknownCommand", controller.uiState.value.commandDevState.parsed)

    // Tap Command again -> stops continuous Command mode cleanly
    controller.onCommandTap()
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(controller.uiState.value.isCommandRecording)

    controller.onWindowHidden()
    controller.destroy()
  }
}
