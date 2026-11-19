package com.example.ime

import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

enum class ImeActionType {
  NEWLINE,
  NEXT,
  DONE,
  SEARCH,
  SEND,
  GO,
}

enum class NavDirection {
  LEFT,
  RIGHT,
  UP,
  DOWN,
}

data class TargetSignature(
  val packageName: String?,
  val fieldId: Int,
  val inputType: Int,
)

data class ImportedTargetSnapshot(
  val signature: TargetSignature,
  val importedWholeField: Boolean,
  val selectionStart: Int,
  val selectionEnd: Int,
  val originalTextLength: Int,
)

sealed interface ImportResult {
  data class Success(
    val text: String,
    val snapshot: ImportedTargetSnapshot,
  ) : ImportResult

  data object EmptyField : ImportResult

  data object Unavailable : ImportResult
}

sealed interface ApplyResult {
  data object Applied : ApplyResult

  data object TargetChanged : ApplyResult

  data object NoTarget : ApplyResult
}

class ImeTargetController {

  private var currentInputConnectionProvider: (() -> InputConnection?) = { null }
  private var currentEditorInfoProvider: (() -> EditorInfo?) = { null }
  private var sendDownUpKeyProvider: ((Int) -> Boolean)? = null

  var selectionStart: Int = -1
    private set

  var selectionEnd: Int = -1
    private set

  private var selectionAnchor: Int = -1

  fun attachProviders(
    icProvider: () -> InputConnection?,
    eiProvider: () -> EditorInfo?,
    sendDownUpKey: ((Int) -> Boolean)? = null,
  ) {
    currentInputConnectionProvider = icProvider
    currentEditorInfoProvider = eiProvider
    sendDownUpKeyProvider = sendDownUpKey
  }

  fun updateSelection(newSelStart: Int, newSelEnd: Int) {
    selectionStart = newSelStart
    selectionEnd = newSelEnd
  }

  fun resetSelectionAnchor() {
    selectionAnchor = -1
  }

  fun currentTargetSignature(): TargetSignature? {
    val ei = currentEditorInfoProvider() ?: return null
    return TargetSignature(
      packageName = ei.packageName,
      fieldId = ei.fieldId,
      inputType = ei.inputType,
    )
  }

  fun resolveImeAction(): ImeActionType {
    val ei = currentEditorInfoProvider() ?: return ImeActionType.NEWLINE
    val isMultiLine =
      (ei.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 &&
        (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
    val action = ei.imeOptions and EditorInfo.IME_MASK_ACTION
    return when {
      isMultiLine && (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ->
        ImeActionType.NEWLINE
      action == EditorInfo.IME_ACTION_SEND -> ImeActionType.SEND
      action == EditorInfo.IME_ACTION_SEARCH -> ImeActionType.SEARCH
      action == EditorInfo.IME_ACTION_NEXT -> ImeActionType.NEXT
      action == EditorInfo.IME_ACTION_GO -> ImeActionType.GO
      action == EditorInfo.IME_ACTION_DONE -> ImeActionType.DONE
      isMultiLine -> ImeActionType.NEWLINE
      else -> ImeActionType.NEWLINE
    }
  }

  fun isRestrictedInput(): Boolean {
    val ei = currentEditorInfoProvider() ?: return true
    return ei.inputType == InputType.TYPE_NULL
  }

  fun getContextBeforeCursor(maxChars: Int = 64): String? {
    val ic = currentInputConnectionProvider() ?: return null
    return ic.getTextBeforeCursor(maxChars, 0)?.toString()
  }

  fun shouldInsertLeadingSpace(): Boolean {
    val ic = currentInputConnectionProvider() ?: return false
    val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
    if (before.isEmpty()) return false
    return !before.last().isWhitespace()
  }

  fun setComposingPartial(partial: String, prependSpace: Boolean = shouldInsertLeadingSpace()) {
    val ic = currentInputConnectionProvider() ?: return
    val clean = partial.trim()
    if (clean.isEmpty()) return
    val textToCompose = if (prependSpace) " $clean" else clean
    ic.setComposingText(textToCompose, 1)
  }

  fun commitDictatedSegment(finalText: String, prependSpace: Boolean = shouldInsertLeadingSpace()) {
    val ic = currentInputConnectionProvider() ?: return
    val trimmed = finalText.trim()
    if (trimmed.isEmpty()) {
      ic.finishComposingText()
      return
    }
    val textToCommit = if (prependSpace) " $trimmed" else trimmed
    ic.commitText(textToCommit, 1)
  }

  fun finishComposingText() {
    currentInputConnectionProvider()?.finishComposingText()
  }

  fun cancelComposingText() {
    val ic = currentInputConnectionProvider() ?: return
    ic.setComposingText("", 1)
    ic.finishComposingText()
  }

  fun commitText(text: String) {
    val ic = currentInputConnectionProvider() ?: return
    ic.commitText(text, 1)
  }

  fun deleteSelectionOrBackwardGrapheme() {
    val ic = currentInputConnectionProvider() ?: return
    // Ensure any active composing span is committed before deleting cleanly
    ic.finishComposingText()

    val selected = ic.getSelectedText(0)
    if (!selected.isNullOrEmpty()) {
      if (ic.commitText("", 1)) return
    }

    val before = ic.getTextBeforeCursor(32, 0)?.toString()
    if (before.isNullOrEmpty()) {
      // Restricted / TYPE_NULL / WebView / start-of-composing fallback: send DEL key event
      sendDeleteKeyEvent(ic)
      return
    }

    val count = GraphemeUtils.lastGraphemeLength(before)
    if (count > 0) {
      val deleted = ic.deleteSurroundingText(count, 0)
      if (!deleted) {
        sendDeleteKeyEvent(ic)
      }
    } else {
      sendDeleteKeyEvent(ic)
    }
  }

  fun performEnterOrAction() {
    val ic = currentInputConnectionProvider() ?: return
    val ei = currentEditorInfoProvider()
    val actionType = resolveImeAction()
    val actionCode =
      when (actionType) {
        ImeActionType.SEND -> EditorInfo.IME_ACTION_SEND
        ImeActionType.SEARCH -> EditorInfo.IME_ACTION_SEARCH
        ImeActionType.NEXT -> EditorInfo.IME_ACTION_NEXT
        ImeActionType.GO -> EditorInfo.IME_ACTION_GO
        ImeActionType.DONE -> EditorInfo.IME_ACTION_DONE
        ImeActionType.NEWLINE -> EditorInfo.IME_ACTION_NONE
      }
    if (actionCode != EditorInfo.IME_ACTION_NONE && ei != null) {
      if (ic.performEditorAction(actionCode)) return
    }
    if (sendDownUpKeyProvider?.invoke(KeyEvent.KEYCODE_ENTER) != true) {
      sendKeyPress(ic, KeyEvent.KEYCODE_ENTER)
    }
  }

  fun navigate(direction: NavDirection, extendSelection: Boolean) {
    val ic = currentInputConnectionProvider() ?: return

    if ((direction == NavDirection.LEFT || direction == NavDirection.RIGHT) && !isRestrictedInput()) {
      val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
      if (extracted?.text != null) {
        val text = extracted.text.toString()
        val curStart = extracted.selectionStart.coerceIn(0, text.length)
        val curEnd = extracted.selectionEnd.coerceIn(0, text.length)
        if (extendSelection) {
          if (selectionAnchor !in 0..text.length) {
            selectionAnchor = curStart
          }
          val activeCursor = if (curStart == selectionAnchor) curEnd else curStart
          val nextPos =
            if (direction == NavDirection.LEFT) {
              GraphemeUtils.prevGraphemeBoundary(text, activeCursor)
            } else {
              GraphemeUtils.nextGraphemeBoundary(text, activeCursor)
            }
          ic.setSelection(selectionAnchor, nextPos)
          return
        } else {
          selectionAnchor = -1
          val basePos =
            if (curStart != curEnd) {
              if (direction == NavDirection.LEFT) minOf(curStart, curEnd) else maxOf(curStart, curEnd)
            } else {
              if (direction == NavDirection.LEFT) {
                GraphemeUtils.prevGraphemeBoundary(text, curStart)
              } else {
                GraphemeUtils.nextGraphemeBoundary(text, curEnd)
              }
            }
          ic.setSelection(basePos, basePos)
          return
        }
      }
    }

    val keyCode =
      when (direction) {
        NavDirection.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        NavDirection.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        NavDirection.UP -> KeyEvent.KEYCODE_DPAD_UP
        NavDirection.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
      }
    val metaState = if (extendSelection) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
    sendKeyPress(ic, keyCode, metaState)
  }

  fun moveToEdge(toEnd: Boolean, extendSelection: Boolean) {
    val ic = currentInputConnectionProvider() ?: return
    val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
    if (extracted?.text != null) {
      val text = extracted.text.toString()
      val target = if (toEnd) text.length else 0
      if (extendSelection) {
        if (selectionAnchor !in 0..text.length) {
          selectionAnchor = extracted.selectionStart.coerceIn(0, text.length)
        }
        ic.setSelection(selectionAnchor, target)
      } else {
        selectionAnchor = -1
        ic.setSelection(target, target)
      }
      return
    }
    val keyCode = if (toEnd) KeyEvent.KEYCODE_MOVE_END else KeyEvent.KEYCODE_MOVE_HOME
    val metaState = if (extendSelection) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
    sendKeyPress(ic, keyCode, metaState)
  }

  fun sendControlC(): Boolean {
    val ic = currentInputConnectionProvider() ?: return false
    return sendKeyPress(
      ic,
      KeyEvent.KEYCODE_C,
      KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
    )
  }

  fun delegateUndo(): Boolean {
    val ic = currentInputConnectionProvider() ?: return false
    return sendKeyPress(
      ic,
      KeyEvent.KEYCODE_Z,
      KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
    )
  }

  fun delegateRedo(): Boolean {
    val ic = currentInputConnectionProvider() ?: return false
    return sendKeyPress(
      ic,
      KeyEvent.KEYCODE_Z,
      KeyEvent.META_CTRL_ON or
        KeyEvent.META_CTRL_LEFT_ON or
        KeyEvent.META_SHIFT_ON or
        KeyEvent.META_SHIFT_LEFT_ON,
    )
  }

  fun importText(): ImportResult {
    val ic = currentInputConnectionProvider() ?: return ImportResult.Unavailable
    val signature = currentTargetSignature() ?: return ImportResult.Unavailable
    if (signature.inputType == InputType.TYPE_NULL) {
      return ImportResult.Unavailable
    }

    val selected = ic.getSelectedText(0)?.toString()
    val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)

    if (!selected.isNullOrEmpty()) {
      val start = extracted?.selectionStart ?: selectionStart
      val end = extracted?.selectionEnd ?: selectionEnd
      val fullLen = extracted?.text?.length ?: selected.length
      return ImportResult.Success(
        text = selected,
        snapshot =
          ImportedTargetSnapshot(
            signature = signature,
            importedWholeField = false,
            selectionStart = minOf(start, end),
            selectionEnd = maxOf(start, end),
            originalTextLength = fullLen,
          ),
      )
    }

    if (extracted?.text != null) {
      val fullText = extracted.text.toString()
      if (fullText.isEmpty()) return ImportResult.EmptyField
      return ImportResult.Success(
        text = fullText,
        snapshot =
          ImportedTargetSnapshot(
            signature = signature,
            importedWholeField = true,
            selectionStart = 0,
            selectionEnd = fullText.length,
            originalTextLength = fullText.length,
          ),
      )
    }

    val before = ic.getTextBeforeCursor(4000, 0)?.toString()
    val after = ic.getTextAfterCursor(4000, 0)?.toString()
    if (before == null && after == null) {
      return ImportResult.Unavailable
    }
    val combined = (before ?: "") + (after ?: "")
    if (combined.isEmpty()) return ImportResult.EmptyField
    return ImportResult.Success(
      text = combined,
      snapshot =
        ImportedTargetSnapshot(
          signature = signature,
          importedWholeField = true,
          selectionStart = 0,
          selectionEnd = combined.length,
          originalTextLength = combined.length,
        ),
    )
  }

  fun applyFallbackText(
    textToApply: String,
    importedSnapshot: ImportedTargetSnapshot?,
  ): ApplyResult {
    val ic = currentInputConnectionProvider() ?: return ApplyResult.NoTarget
    val currentSig = currentTargetSignature() ?: return ApplyResult.NoTarget

    if (importedSnapshot != null && importedSnapshot.signature != currentSig) {
      return ApplyResult.TargetChanged
    }

    ic.beginBatchEdit()
    try {
      val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
      if (importedSnapshot != null && importedSnapshot.importedWholeField) {
        val currentFullLen = extracted?.text?.length
        if (currentFullLen != null) {
          ic.setSelection(0, currentFullLen)
          ic.commitText(textToApply, 1)
          ic.setSelection(0, textToApply.length)
        } else {
          val beforeLen = ic.getTextBeforeCursor(4000, 0)?.length ?: 0
          val afterLen = ic.getTextAfterCursor(4000, 0)?.length ?: 0
          if (beforeLen > 0 || afterLen > 0) {
            ic.deleteSurroundingText(beforeLen, afterLen)
          }
          ic.commitText(textToApply, 1)
          if (ic.getTextBeforeCursor(textToApply.length, 0)?.length == textToApply.length) {
            ic.setSelection(0, textToApply.length)
          }
        }
        return ApplyResult.Applied
      }

      if (importedSnapshot != null && importedSnapshot.selectionStart >= 0 && importedSnapshot.selectionEnd >= importedSnapshot.selectionStart) {
        val start = importedSnapshot.selectionStart
        val end = importedSnapshot.selectionEnd
        ic.setSelection(start, end)
        ic.commitText(textToApply, 1)
        ic.setSelection(start, start + textToApply.length)
        return ApplyResult.Applied
      }

      val curStart =
        when {
          extracted != null && extracted.selectionStart >= 0 ->
            minOf(extracted.selectionStart, extracted.selectionEnd)
          selectionStart >= 0 -> minOf(selectionStart, selectionEnd)
          else -> -1
        }
      ic.commitText(textToApply, 1)
      if (curStart >= 0 && !isRestrictedInput()) {
        ic.setSelection(curStart, curStart + textToApply.length)
      }
      return ApplyResult.Applied
    } finally {
      ic.endBatchEdit()
    }
  }

  private fun sendDeleteKeyEvent(ic: InputConnection) {
    if (sendDownUpKeyProvider?.invoke(KeyEvent.KEYCODE_DEL) == true) return
    sendKeyPress(ic, KeyEvent.KEYCODE_DEL)
  }

  private fun sendKeyPress(ic: InputConnection, keyCode: Int, metaState: Int = 0): Boolean {
    val now = SystemClock.uptimeMillis()
    val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
    val down =
      KeyEvent(
        now,
        now,
        KeyEvent.ACTION_DOWN,
        keyCode,
        0,
        metaState,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        0,
        flags,
      )
    val up =
      KeyEvent(
        now,
        now,
        KeyEvent.ACTION_UP,
        keyCode,
        0,
        metaState,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        0,
        flags,
      )
    return ic.sendKeyEvent(down) && ic.sendKeyEvent(up)
  }
}
