package com.example.ui.setup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Simulated terminal / restricted-input Android View modeled on terminal emulators (e.g. Termux).
 * Uses InputType.TYPE_NULL and denies text extraction / surrounding-text queries so the
 * Import failure & Fallback Apply workflows can be verified directly inside the prototype.
 */
class RestrictedTerminalView(context: Context) : View(context) {

  private val lines = mutableListOf("$ ")
  private var cursorCol = 2
  private var textColorInt: Int = 0xFF1A1917.toInt()
  private var promptColorInt: Int = 0xFFE85A1B.toInt()
  private var onTextUpdated: ((String) -> Unit)? = null

  private val textPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.MONOSPACE
      textSize = 38f
    }

  init {
    isFocusable = true
    isFocusableInTouchMode = true
    contentDescription = "Restricted terminal input sandbox"
  }

  fun setPaletteColors(inkColor: Int, accentColor: Int) {
    textColorInt = inkColor
    promptColorInt = accentColor
    invalidate()
  }

  fun setOnTerminalChangedListener(listener: (String) -> Unit) {
    onTextUpdated = listener
  }

  fun clearTerminal() {
    lines.clear()
    lines.add("$ ")
    cursorCol = 2
    onTextUpdated?.invoke(lines.joinToString("\n"))
    invalidate()
  }

  override fun onCheckIsTextEditor(): Boolean = true

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (event.action == MotionEvent.ACTION_UP) {
      requestFocus()
      val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
      imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
      performClick()
    }
    return true
  }

  override fun performClick(): Boolean = super.performClick()

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    outAttrs.inputType = InputType.TYPE_NULL
    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
    return object : BaseInputConnection(this, false) {
      override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val str = text?.toString().orEmpty()
        appendText(str)
        return true
      }

      override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        return true
      }

      override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = null

      override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null

      override fun getSelectedText(flags: Int): CharSequence? = null

      override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null

      override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        backspaceChar()
        return true
      }

      override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
          when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> backspaceChar()
            KeyEvent.KEYCODE_ENTER -> insertNewPromptLine()
            KeyEvent.KEYCODE_DPAD_LEFT -> {
              if (cursorCol > 2) cursorCol--
              invalidate()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
              val lineLen = lines.lastOrNull()?.length ?: 0
              if (cursorCol < lineLen) cursorCol++
              invalidate()
            }
          }
        }
        return true
      }
    }
  }

  private fun appendText(str: String) {
    if (str.isEmpty()) return
    for (ch in str) {
      if (ch == '\n') {
        insertNewPromptLine()
      } else {
        val lastIdx = lines.lastIndex
        val line = lines[lastIdx]
        val pos = cursorCol.coerceIn(0, line.length)
        lines[lastIdx] = line.substring(0, pos) + ch + line.substring(pos)
        cursorCol = pos + 1
      }
    }
    onTextUpdated?.invoke(lines.joinToString("\n"))
    invalidate()
  }

  private fun backspaceChar() {
    val lastIdx = lines.lastIndex
    val line = lines[lastIdx]
    if (cursorCol > 2 && line.length > 2) {
      val pos = cursorCol.coerceIn(2, line.length)
      lines[lastIdx] = line.removeRange(pos - 1, pos)
      cursorCol = pos - 1
      onTextUpdated?.invoke(lines.joinToString("\n"))
      invalidate()
    }
  }

  private fun insertNewPromptLine() {
    lines.add("$ ")
    if (lines.size > 6) {
      lines.removeAt(0)
    }
    cursorCol = 2
    onTextUpdated?.invoke(lines.joinToString("\n"))
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    textPaint.textSize = resources.displayMetrics.scaledDensity * 13.5f
    val lineHeight = textPaint.fontSpacing + 6f
    var y = paddingTop + lineHeight
    val visibleLines = lines.takeLast(5)
    visibleLines.forEachIndexed { idx, line ->
      textPaint.color = textColorInt
      canvas.drawText(line, paddingStart.toFloat(), y, textPaint)
      if (idx == visibleLines.lastIndex) {
        val cursorX =
          paddingStart + textPaint.measureText(line.substring(0, cursorCol.coerceIn(0, line.length)))
        textPaint.color = promptColorInt
        canvas.drawRect(cursorX, y - textPaint.textSize * 0.85f, cursorX + 4f, y + 4f, textPaint)
      }
      y += lineHeight
    }
  }
}
