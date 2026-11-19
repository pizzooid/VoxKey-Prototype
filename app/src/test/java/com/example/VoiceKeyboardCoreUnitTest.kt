package com.example

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.example.audio.CircularAudioBuffer
import com.example.audio.PcmSpeechActivityDetector
import com.example.ime.ApplyResult
import com.example.ime.EditGroupKind
import com.example.ime.GraphemeUtils
import com.example.ime.ImeActionType
import com.example.ime.ImeTargetController
import com.example.ime.ImportResult
import com.example.ime.TextSnapshot
import com.example.ime.UndoHistoryManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceKeyboardCoreUnitTest {

  @Test
  fun `grapheme deletion removes complex emojis and combining marks cleanly`() {
    val family = "hi 👨‍👩‍👧‍👦"
    val (afterFamily, start1, end1) =
      GraphemeUtils.deleteSelectionOrBackward(family, family.length, family.length)
    assertEquals("hi ", afterFamily)
    assertEquals(3, start1)
    assertEquals(3, end1)

    val skinTone = "ok 👍🏽"
    val (afterSkin, _, _) =
      GraphemeUtils.deleteSelectionOrBackward(skinTone, skinTone.length, skinTone.length)
    assertEquals("ok ", afterSkin)

    val flag = "go 🇮🇹"
    val (afterFlag, _, _) =
      GraphemeUtils.deleteSelectionOrBackward(flag, flag.length, flag.length)
    assertEquals("go ", afterFlag)

    val combining = "cafe\u0301"
    val (afterCombining, _, _) =
      GraphemeUtils.deleteSelectionOrBackward(combining, combining.length, combining.length)
    assertEquals("caf", afterCombining)
  }

  @Test
  fun `circular audio buffer wraps, drains FIFO, and discards cleanly`() {
    val ring = CircularAudioBuffer(initialCapacityBytes = 5)
    ring.write(byteArrayOf(1, 2, 3))
    ring.write(byteArrayOf(4, 5, 6, 7))
    assertEquals(5, ring.availableBytes())
    val drained = ring.drain()
    assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), drained)
    assertEquals(0, ring.availableBytes())

    ring.write(byteArrayOf(8, 9))
    ring.clear()
    assertEquals(0, ring.availableBytes())
    assertArrayEquals(byteArrayOf(), ring.drain())
  }

  @Test
  fun `circular audio buffer snapshotSince replays unconfirmed bytes without consuming them`() {
    val ring = CircularAudioBuffer(initialCapacityBytes = 8)
    ring.write(byteArrayOf(10, 11, 12, 13))
    val markAfterFirst = ring.totalBytes() // 4L
    ring.write(byteArrayOf(14, 15, 16))

    // Non-destructive snapshot from markAfterFirst returns [14, 15, 16] twice
    val (snap1, cursor1) = ring.snapshotSince(markAfterFirst)
    val (snap2, cursor2) = ring.snapshotSince(markAfterFirst)
    assertEquals(7L, cursor1)
    assertEquals(7L, cursor2)
    assertArrayEquals(byteArrayOf(14, 15, 16), snap1)
    assertArrayEquals(byteArrayOf(14, 15, 16), snap2)

    // Overwrite past capacity -> clamps cleanly to oldest retained byte
    ring.write(byteArrayOf(17, 18, 19, 20, 21, 22, 23))
    val (wrappedSnap, cursor3) = ring.snapshotSince(markAfterFirst)
    assertEquals(14L, cursor3)
    assertArrayEquals(byteArrayOf(16, 17, 18, 19, 20, 21, 22, 23), wrappedSnap)
  }

  @Test
  fun `pcm speech activity detector stays quiet on low noise and triggers on speech energy`() {
    val vad = PcmSpeechActivityDetector()

    fun makePcmChunk(amplitude: Int, samples: Int = 256): ByteArray {
      val out = ByteArray(samples * 2)
      for (i in 0 until samples) {
        val v = if (i % 2 == 0) amplitude else -amplitude
        out[i * 2] = (v and 0xFF).toByte()
        out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
      }
      return out
    }

    val quietNoise = makePcmChunk(amplitude = 40)
    repeat(10) {
      assertFalse(vad.processPcmChunk(quietNoise, quietNoise.size))
    }

    val spokenWord = makePcmChunk(amplitude = 1400)
    val frame1 = vad.processPcmChunk(spokenWord, spokenWord.size)
    val frame2 = vad.processPcmChunk(spokenWord, spokenWord.size)
    assertTrue(frame1 || frame2)
  }

  @Test
  fun `undo history groups manual typing and isolates dictation segments`() {
    val history = UndoHistoryManager()
    val s0 = TextSnapshot("", 0, 0)
    val s1 = TextSnapshot("a", 1, 1)
    val s2 = TextSnapshot("ab", 2, 2)
    history.recordChange(s0, s1, EditGroupKind.MANUAL_INSERT, nowMs = 1000L)
    history.recordChange(s1, s2, EditGroupKind.MANUAL_INSERT, nowMs = 1200L)

    val s3 = TextSnapshot("ab hello world", 14, 14)
    history.recordChange(s2, s3, EditGroupKind.DICTATION_SEGMENT, nowMs = 1400L)

    val undo1 = history.undo(s3)
    assertEquals(s2, undo1)
    val undo2 = history.undo(s2)
    assertEquals(s0, undo2)
    assertFalse(history.canUndo)

    val redo1 = history.redo(s0)
    assertEquals(s2, redo1)
    val redo2 = history.redo(s2)
    assertEquals(s3, redo2)
  }

  @Test
  fun `import and apply respect target signature and never emit Enter`() {
    var fieldText = "hello world"
    var selStart = 6
    var selEnd = 11
    var sentEnter = false
    var currentPkg = "com.example.notes"
    var currentFieldId = 42
    var currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    var currentImeOptions = EditorInfo.IME_ACTION_SEND

    val fakeIc =
      object : InputConnectionWrapper(null, true) {
        override fun beginBatchEdit(): Boolean = true

        override fun endBatchEdit(): Boolean = true

        override fun getSelectedText(flags: Int): CharSequence? =
          if (selEnd > selStart) fieldText.substring(selStart, selEnd) else ""

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
          ExtractedText().apply {
            text = fieldText
            selectionStart = selStart
            selectionEnd = selEnd
          }

        override fun setSelection(start: Int, end: Int): Boolean {
          selStart = start
          selEnd = end
          return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
          val str = text?.toString().orEmpty()
          val min = minOf(selStart, selEnd).coerceIn(0, fieldText.length)
          val max = maxOf(selStart, selEnd).coerceIn(0, fieldText.length)
          fieldText = fieldText.substring(0, min) + str + fieldText.substring(max)
          selStart = min + str.length
          selEnd = selStart
          return true
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
          if (event?.keyCode == KeyEvent.KEYCODE_ENTER) sentEnter = true
          return true
        }
      }

    val controller = ImeTargetController()
    controller.attachProviders(
      icProvider = { fakeIc },
      eiProvider = {
        EditorInfo().apply {
          packageName = currentPkg
          fieldId = currentFieldId
          inputType = currentInputType
          imeOptions = currentImeOptions
        }
      },
    )

    assertEquals(ImeActionType.SEND, controller.resolveImeAction())

    val imported = controller.importText() as ImportResult.Success
    assertEquals("world", imported.text)

    // Switch target before apply -> must block blind overwrite!
    currentFieldId = 99
    val mismatch = controller.applyFallbackText("planet", imported.snapshot)
    assertEquals(ApplyResult.TargetChanged, mismatch)
    assertEquals("hello world", fieldText)

    // Switch back to original target -> replaces imported selection and keeps replacement selected
    currentFieldId = 42
    val applied = controller.applyFallbackText("planet", imported.snapshot)
    assertEquals(ApplyResult.Applied, applied)
    assertEquals("hello planet", fieldText)
    assertEquals(6, selStart)
    assertEquals(12, selEnd)
    assertFalse(sentEnter)

    // Restricted TYPE_NULL target -> Import unavailable
    currentInputType = InputType.TYPE_NULL
    assertTrue(controller.importText() is ImportResult.Unavailable)
  }

  @Test
  fun `delete falls back to KEYCODE_DEL when surrounding text is empty or rejected`() {
    var fallbackDelCalled = 0
    val emptySurroundingIc =
      object : InputConnectionWrapper(null, true) {
        override fun finishComposingText(): Boolean = true
        override fun getSelectedText(flags: Int): CharSequence = ""
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
      }
    val controller =
      ImeTargetController().apply {
        attachProviders(
          icProvider = { emptySurroundingIc },
          eiProvider = { EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT } },
          sendDownUpKey = { keyCode ->
            if (keyCode == KeyEvent.KEYCODE_DEL) fallbackDelCalled++
            true
          },
        )
      }

    controller.deleteSelectionOrBackwardGrapheme()
    assertEquals(1, fallbackDelCalled)

    val rejectingIc =
      object : InputConnectionWrapper(null, true) {
        override fun finishComposingText(): Boolean = true
        override fun getSelectedText(flags: Int): CharSequence = ""
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = "abc"
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = false
      }
    controller.attachProviders(
      icProvider = { rejectingIc },
      eiProvider = { EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT } },
      sendDownUpKey = { keyCode ->
        if (keyCode == KeyEvent.KEYCODE_DEL) fallbackDelCalled++
        true
      },
    )
    controller.deleteSelectionOrBackwardGrapheme()
    assertEquals(2, fallbackDelCalled)
  }
}
