package com.example.ime

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.example.MainActivity
import com.example.audio.AudioPreBufferManager
import com.example.data.EmojiRepository
import com.example.data.SettingsRepository
import com.example.speech.OnDeviceSpeechEngine
import com.example.ui.keyboard.VoxKeyKeyboardRoot
import com.example.ui.theme.VoxKeyTheme

class VoiceImeService : InputMethodService() {

  private val lifecycleOwner = ImeLifecycleOwner()
  private lateinit var targetController: ImeTargetController
  private lateinit var audioPreBufferManager: AudioPreBufferManager
  private lateinit var speechEngine: OnDeviceSpeechEngine
  private lateinit var controller: KeyboardController

  override fun onCreate() {
    super.onCreate()
    lifecycleOwner.onCreate()

    targetController =
      ImeTargetController().apply {
        attachProviders(
          icProvider = { currentInputConnection },
          eiProvider = { currentInputEditorInfo },
          sendDownUpKey = { keyCode ->
            sendDownUpKeyEvents(keyCode)
            true
          },
        )
      }

    audioPreBufferManager = AudioPreBufferManager(applicationContext)
    speechEngine = OnDeviceSpeechEngine(applicationContext, audioPreBufferManager)

    controller =
      KeyboardController(
        context = applicationContext,
        settingsRepository = SettingsRepository.getInstance(applicationContext),
        emojiRepository = EmojiRepository.getInstance(applicationContext),
        targetController = targetController,
        audioPreBufferManager = audioPreBufferManager,
        speechEngine = speechEngine,
        onSwitchToNextKeyboard = { switchKeyboard() },
        onOpenSettings = { openSettingsActivity() },
      )
  }

  override fun onCreateInputView(): View {
    lifecycleOwner.attachToDecorView(window?.window?.decorView)
    return ComposeView(this).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        val state by controller.uiState.collectAsState()
        VoxKeyTheme(themeMode = state.settings.themeMode) {
          VoxKeyKeyboardRoot(
            state = state,
            controller = controller,
          )
        }
      }
    }
  }

  override fun onWindowShown() {
    super.onWindowShown()
    lifecycleOwner.onStart()
    controller.onWindowShown()
  }

  override fun onWindowHidden() {
    super.onWindowHidden()
    controller.onWindowHidden()
    lifecycleOwner.onStop()
  }

  override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
    super.onStartInput(attribute, restarting)
    controller.onStartInput(restarting)
  }

  override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    controller.onStartInput(restarting)
  }

  override fun onFinishInput() {
    super.onFinishInput()
    controller.onFinishInput()
  }

  override fun onUpdateSelection(
    oldSelStart: Int,
    oldSelEnd: Int,
    newSelStart: Int,
    newSelEnd: Int,
    candidatesStart: Int,
    candidatesEnd: Int,
  ) {
    super.onUpdateSelection(
      oldSelStart,
      oldSelEnd,
      newSelStart,
      newSelEnd,
      candidatesStart,
      candidatesEnd,
    )
    controller.onUpdateSelection(newSelStart, newSelEnd)
  }

  override fun onDestroy() {
    super.onDestroy()
    controller.destroy()
    lifecycleOwner.onDestroy()
  }

  private fun switchKeyboard() {
    val switched =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        switchToNextInputMethod(false)
      } else {
        @Suppress("DEPRECATION")
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.switchToNextInputMethod(window?.window?.attributes?.token, false) ?: false
      }
    if (!switched) {
      val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
      imm?.showInputMethodPicker()
    }
  }

  private fun openSettingsActivity() {
    val intent =
      Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      }
    startActivity(intent)
  }
}
