package com.example.ime

import java.text.BreakIterator

/**
 * Extended grapheme cluster utilities so emoji ZWJ sequences, skin tones, flags,
 * and combining diacritics are navigated and deleted atomically.
 */
object GraphemeUtils {

  fun lastGraphemeLength(textBeforeCursor: String): Int {
    if (textBeforeCursor.isEmpty()) return 0
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(textBeforeCursor)
    val end = iterator.last()
    val prev = iterator.previous()
    return if (prev != BreakIterator.DONE && end > prev) {
      end - prev
    } else {
      Character.charCount(textBeforeCursor.codePointBefore(textBeforeCursor.length))
    }
  }

  fun prevGraphemeBoundary(text: String, offset: Int): Int {
    val clamped = offset.coerceIn(0, text.length)
    if (clamped == 0) return 0
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val prev = iterator.preceding(clamped)
    return if (prev != BreakIterator.DONE) prev else 0
  }

  fun nextGraphemeBoundary(text: String, offset: Int): Int {
    val clamped = offset.coerceIn(0, text.length)
    if (clamped >= text.length) return text.length
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val next = iterator.following(clamped)
    return if (next != BreakIterator.DONE) next else text.length
  }

  fun deleteSelectionOrBackward(
    text: String,
    selStart: Int,
    selEnd: Int,
  ): Triple<String, Int, Int> {
    val start = minOf(selStart, selEnd).coerceIn(0, text.length)
    val end = maxOf(selStart, selEnd).coerceIn(0, text.length)
    if (end > start) {
      val nextText = text.removeRange(start, end)
      return Triple(nextText, start, start)
    }
    if (start == 0) return Triple(text, 0, 0)
    val prev = prevGraphemeBoundary(text, start)
    val nextText = text.removeRange(prev, start)
    return Triple(nextText, prev, prev)
  }

  fun insertOrReplace(
    text: String,
    selStart: Int,
    selEnd: Int,
    insertion: String,
  ): Triple<String, Int, Int> {
    val start = minOf(selStart, selEnd).coerceIn(0, text.length)
    val end = maxOf(selStart, selEnd).coerceIn(0, text.length)
    val nextText = buildString {
      append(text, 0, start)
      append(insertion)
      append(text, end, text.length)
    }
    val newCursor = start + insertion.length
    return Triple(nextText, newCursor, newCursor)
  }
}
