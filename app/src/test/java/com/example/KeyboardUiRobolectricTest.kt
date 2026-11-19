package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioPreBufferManager
import com.example.data.EmojiRepository
import com.example.data.RecordingMode
import com.example.data.SettingsRepository
import com.example.ime.ImeTargetController
import com.example.ime.KeyboardController
import com.example.speech.OnDeviceSpeechEngine
import com.example.speech.SpeechRecognizerFactory
import com.example.speech.SpeechRecognizerHandle
import com.example.ui.keyboard.VoxKeyKeyboardRoot
import com.example.ui.theme.VoxKeyTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeyboardUiRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun `compact layout, navigation toggle, fallback preservation, backspace, and emoji picker flows`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val settingsRepo = SettingsRepository.getInstance(context)
    val emojiRepo = EmojiRepository.getInstance(context)
    val buffer = StringBuilder("abc")
    var delKeyEvents = 0

    val fakeIc =
      object : InputConnectionWrapper(null, true) {
        override fun finishComposingText(): Boolean = true

        override fun getSelectedText(flags: Int): CharSequence = ""

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = buffer.toString()

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
          val del = beforeLength.coerceAtMost(buffer.length)
          buffer.delete(buffer.length - del, buffer.length)
          return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
          buffer.append(text?.toString().orEmpty())
          return true
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
          if (event?.keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
            delKeyEvents++
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
              packageName = "com.example.test"
              fieldId = 1
              inputType = InputType.TYPE_CLASS_TEXT
            }
          },
        )
      }
    val audioMgr = AudioPreBufferManager(context)
    val speechEngine = OnDeviceSpeechEngine(context, audioMgr)
    val controller =
      KeyboardController(
        context = context,
        settingsRepository = settingsRepo,
        emojiRepository = emojiRepo,
        targetController = targetController,
        audioPreBufferManager = audioMgr,
        speechEngine = speechEngine,
        onSwitchToNextKeyboard = {},
        onOpenSettings = {},
      )

    composeTestRule.setContent {
      val state by controller.uiState.collectAsState()
      VoxKeyTheme { VoxKeyKeyboardRoot(state = state, controller = controller) }
    }
    composeTestRule.waitForIdle()

    // Verify Backspace & Space buttons edit app input on click
    composeTestRule.onNodeWithTag("main_delete_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals("ab", buffer.toString())

    composeTestRule.onNodeWithTag("main_space_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals("ab ", buffer.toString())

    // Main 7-button row (including Command & Space), toolbar, and developer panel present; Navigation strip & fallback hidden by default
    composeTestRule.onNodeWithTag("command_dev_panel").assertIsDisplayed()
    composeTestRule.onNodeWithTag("main_dictate_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("main_command_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("main_navigate_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("main_delete_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("main_space_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("main_enter_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("main_apply_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("nav_left_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("fallback_editor_input").assertDoesNotExist()

    // Toggle Navigate mode -> reveals Navigation row
    composeTestRule.onNodeWithTag("main_navigate_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("nav_left_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("nav_select_toggle").assertIsDisplayed()

    // Exit Navigate mode -> hides Navigation row
    composeTestRule.onNodeWithTag("main_navigate_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("nav_left_button").assertDoesNotExist()

    // Expand fallback editor, edit text, test Backspace & Space in fallback editor, collapse & preserve
    composeTestRule.onNodeWithTag("toolbar_fallback_toggle_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("fallback_editor_input").assertIsDisplayed()

    composeTestRule.runOnIdle { controller.onFallbackEditorChanged("draft cmd", 9, 9) }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("main_delete_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals("draft cm", controller.uiState.value.fallbackText)

    composeTestRule.onNodeWithTag("main_space_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals("draft cm ", controller.uiState.value.fallbackText)

    composeTestRule.onNodeWithTag("toolbar_fallback_toggle_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("fallback_editor_input").assertDoesNotExist()
    assertEquals("draft cm ", controller.uiState.value.fallbackText)

    // Test Emoji picker expansion, custom alias persistence, and insertion
    composeTestRule.onNodeWithTag("toolbar_emoji_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("emoji_search_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("emoji_cell_😀").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("emoji_cell_😀").performTouchInput { longClick() }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("emoji_alias_input").assertIsDisplayed()
    composeTestRule.runOnIdle {
      controller.updateAliasDraft("deployjoy")
      controller.saveCustomAlias()
    }
    composeTestRule.waitForIdle()

    composeTestRule.runOnIdle { controller.setEmojiSearchQuery("deployjoy") }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("emoji_cell_😀").assertIsDisplayed()

    composeTestRule.onNodeWithTag("emoji_close_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("main_dictate_button").assertIsDisplayed()

    runBlocking {
      val items = controller.uiState.value.emojiItems
      val grinning = items.firstOrNull { it.emoji == "😀" }
      assertTrue((grinning?.usageCount ?: 0) >= 1)
      assertEquals("deployjoy", grinning?.customAlias)
    }
    assertTrue(buffer.toString().contains("😀"))
    controller.destroy()
  }

  @Test
  fun `command button hold stays active across thumb drift until pointer release`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

    val buffer = StringBuilder()
    val fakeIc =
      object : InputConnectionWrapper(null, true) {
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun getSelectedText(flags: Int): CharSequence = ""
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = buffer.toString()
        override fun finishComposingText(): Boolean = true
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
          buffer.append(text?.toString().orEmpty())
          return true
        }
      }

    val targetController =
      ImeTargetController().apply {
        attachProviders(
          icProvider = { fakeIc },
          eiProvider = {
            EditorInfo().apply {
              packageName = "com.example.test"
              fieldId = 2
              inputType = InputType.TYPE_CLASS_TEXT
            }
          },
        )
      }

    lateinit var attachedListener: RecognitionListener
    val fakeHandle =
      object : SpeechRecognizerHandle {
        override fun setRecognitionListener(listener: RecognitionListener) {
          attachedListener = listener
        }
        override fun startListening(intent: Intent) = Unit
        override fun stopListening() = Unit
        override fun cancel() = Unit
        override fun destroy() = Unit
      }

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

    val settingsRepo = SettingsRepository.getInstance(app)
    runBlocking { settingsRepo.setRecordingMode(RecordingMode.HOLD_TO_RECORD) }

    val controller =
      KeyboardController(
        context = app,
        settingsRepository = settingsRepo,
        emojiRepository = EmojiRepository.getInstance(app),
        targetController = targetController,
        audioPreBufferManager = audioMgr,
        speechEngine = speechEngine,
        onSwitchToNextKeyboard = {},
        onOpenSettings = {},
      )

    composeTestRule.setContent {
      val state by controller.uiState.collectAsState()
      VoxKeyTheme { VoxKeyKeyboardRoot(state = state, controller = controller) }
    }
    composeTestRule.waitForIdle()

    // Press down on Command button, then drift finger well outside button bounds (would fail with waitForUpOrCancellation)
    composeTestRule.onNodeWithTag("main_command_button").performTouchInput {
      down(center)
      moveBy(Offset(0f, -120f))
    }
    composeTestRule.waitForIdle()
    assertTrue(controller.uiState.value.isCommandRecording)
    assertFalse(controller.uiState.value.isCommandPendingFinal)

    // Now lift finger -> transitions to pending final
    composeTestRule.onNodeWithTag("main_command_button").performTouchInput { up() }
    composeTestRule.waitForIdle()
    assertFalse(controller.uiState.value.isCommandRecording)
    assertTrue(controller.uiState.value.isCommandPendingFinal)

    // Deliver final transcript -> inserts emoji
    composeTestRule.runOnIdle {
      val bundle =
        Bundle().apply {
          putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("insert emoji fire"))
        }
      attachedListener.onResults(bundle)
    }
    composeTestRule.waitForIdle()
    assertEquals("🔥", buffer.toString())

    // Switch back to TAP_TO_TOGGLE and verify Command button toggles continuous recording
    runBlocking { settingsRepo.setRecordingMode(RecordingMode.TAP_TO_TOGGLE) }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("main_command_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(controller.uiState.value.isCommandRecording)

    composeTestRule.runOnIdle {
      val bundle =
        Bundle().apply {
          putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Type FM capital M"))
        }
      attachedListener.onSegmentResults(bundle)
    }
    composeTestRule.waitForIdle()
    assertEquals("🔥fmM", buffer.toString())
    assertTrue(controller.uiState.value.isCommandRecording)

    composeTestRule.onNodeWithTag("main_command_button").performClick()
    composeTestRule.waitForIdle()
    assertFalse(controller.uiState.value.isCommandRecording)

    controller.destroy()
  }
}
