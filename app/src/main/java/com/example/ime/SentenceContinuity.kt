package com.example.ime

/**
 * Pure deterministic helpers for tracking the last typed word and deciding whether
 * incoming dictation should continue in lowercase when the previous sentence has not ended.
 */
object SentenceContinuity {

  private val sentenceEndChars = setOf('.', '!', '?', '…', '\n', '\r')
  private val singleLetterKeepUpper = setOf("I")

  fun shouldContinueLowercase(contextBeforeCursor: String): Boolean {
    if (contextBeforeCursor.isEmpty()) return false
    var idx = contextBeforeCursor.length - 1
    while (idx >= 0 && (contextBeforeCursor[idx] == ' ' || contextBeforeCursor[idx] == '\t')) {
      idx--
    }
    if (idx < 0) return false
    val lastChar = contextBeforeCursor[idx]
    return lastChar !in sentenceEndChars
  }

  fun extractLastWord(contextBeforeCursor: String): String {
    if (contextBeforeCursor.isEmpty()) return ""
    var end = contextBeforeCursor.length
    while (end > 0 && (contextBeforeCursor[end - 1] == ' ' || contextBeforeCursor[end - 1] == '\t')) {
      end--
    }
    if (end == 0) return ""
    if (contextBeforeCursor[end - 1] == '\n' || contextBeforeCursor[end - 1] == '\r') return ""
    var start = end
    while (start > 0 && !contextBeforeCursor[start - 1].isWhitespace()) {
      start--
    }
    return contextBeforeCursor.substring(start, end)
  }

  fun applyContinuityCasing(incoming: String, continueLowercase: Boolean): String {
    val clean = incoming.trim()
    if (!continueLowercase || clean.isEmpty()) return clean

    val firstChar = clean[0]
    if (!firstChar.isUpperCase()) return clean

    // Inspect the leading token to preserve "I", "I'm"/"I've", and ALL-CAPS acronyms like "API"
    var tokenEnd = 0
    while (tokenEnd < clean.length && (clean[tokenEnd].isLetterOrDigit() || clean[tokenEnd] == '\'')) {
      tokenEnd++
    }
    val firstToken = clean.substring(0, tokenEnd)
    if (firstToken in singleLetterKeepUpper || firstToken.startsWith("I'")) {
      return clean
    }
    if (firstToken.length >= 2 && firstToken[0].isUpperCase() && firstToken[1].isUpperCase()) {
      return clean
    }

    return firstChar.lowercaseChar() + clean.substring(1)
  }
}
