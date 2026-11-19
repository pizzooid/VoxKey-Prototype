package com.example.command

import com.example.data.EmojiItem
import java.util.Locale

sealed interface ParsedCommand {
  data class InsertEmoji(
    val emoji: String,
    val matchedName: String,
  ) : ParsedCommand

  data class TypeText(
    val text: String,
    val label: String,
    val startsOrContinuesTypeStream: Boolean = true,
  ) : ParsedCommand

  data object EnterTypeStream : ParsedCommand

  data object StopTypeStream : ParsedCommand

  data object Backspace : ParsedCommand

  data object ControlC : ParsedCommand

  data class UnknownCommand(
    val normalizedTranscript: String,
    val reason: String,
  ) : ParsedCommand
}

/**
 * Pure deterministic parser for voice commands.
 * Kept completely independent from speech recognition and IME target state.
 *
 * Supports:
 * - Emoji commands: "insert emoji <name>"
 * - Indefinite type streams: "type FM capital M" -> "fmM", "type?" -> "?", "type <...>"
 * - Coding & punctuation symbols: "type questionmark", "type?", "type less than", "symbol fat arrow"
 * - Standalone capitals: "capital a", "uppercase z"
 * - Action commands: "backspace", "delete", "control c" / "ctrl+c"
 */
object VoiceCommandParser {
  private const val EMOJI_PREFIX = "insert emoji"
  private const val TYPE_PREFIX = "type"
  private const val INSERT_SYMBOL_PREFIX = "insert symbol"
  private const val SYMBOL_PREFIX = "symbol"

  private enum class CaseModifier {
    NONE,
    LOWER,
    UPPER,
  }

  private val BACKSPACE_COMMANDS =
    setOf("backspace", "back space", "back-space", "press backspace", "delete", "press delete")

  private val CONTROL_C_COMMANDS =
    setOf(
      "control c",
      "ctrl c",
      "control-c",
      "ctrl-c",
      "control+c",
      "ctrl+c",
      "control + c",
      "ctrl + c",
      "control see",
      "ctrl see",
      "control sea",
      "ctrl sea",
      "press control c",
      "press ctrl c",
    )

  private val STOP_TYPE_STREAM_COMMANDS =
    setOf("stop typing", "exit type", "end type", "stop type", "exit typing", "done typing")

  private val DIGIT_WORDS =
    mapOf(
      "zero" to "0",
      "one" to "1",
      "two" to "2",
      "three" to "3",
      "four" to "4",
      "five" to "5",
      "six" to "6",
      "seven" to "7",
      "eight" to "8",
      "nine" to "9",
    )

  private val LITERAL_SYMBOLS =
    setOf(
      ".", ",", "<", ">", "[", "]", "{", "}", "(", ")", ":", ";",
      "'", "\"", "`", "/", "\\", "|", "-", "_", "+", "=", "*", "&",
      "%", "$", "#", "@", "!", "?", "~", "^", "->", "=>", "...", "…",
    )

  private val SINGLE_CHAR_LITERAL_SYMBOLS =
    setOf(
      '.', ',', '<', '>', '[', ']', '{', '}', '(', ')', ':', ';',
      '\'', '"', '`', '/', '\\', '|', '-', '_', '+', '=', '*', '&',
      '%', '$', '#', '@', '!', '?', '~', '^', '…', '—', '–',
    )

  private val SPOKEN_SYMBOLS =
    mapOf(
      "period" to ".",
      "dot" to ".",
      "full stop" to ".",
      "fullstop" to ".",
      "point" to ".",
      "ellipsis" to "...",
      "three dots" to "...",
      "dot dot dot" to "...",
      "comma" to ",",
      "less than" to "<",
      "less than sign" to "<",
      "left angle bracket" to "<",
      "open angle bracket" to "<",
      "opening angle bracket" to "<",
      "angle bracket" to "<",
      "left chevron" to "<",
      "open chevron" to "<",
      "greater than" to ">",
      "greater than sign" to ">",
      "right angle bracket" to ">",
      "close angle bracket" to ">",
      "closing angle bracket" to ">",
      "right chevron" to ">",
      "close chevron" to ">",
      "left bracket" to "[",
      "open bracket" to "[",
      "opening bracket" to "[",
      "left square bracket" to "[",
      "open square bracket" to "[",
      "opening square bracket" to "[",
      "square bracket" to "[",
      "right bracket" to "]",
      "close bracket" to "]",
      "closing bracket" to "]",
      "right square bracket" to "]",
      "close square bracket" to "]",
      "closing square bracket" to "]",
      "left brace" to "{",
      "open brace" to "{",
      "opening brace" to "{",
      "left curly" to "{",
      "open curly" to "{",
      "left curly brace" to "{",
      "open curly brace" to "{",
      "opening curly brace" to "{",
      "curly brace" to "{",
      "left curly bracket" to "{",
      "open curly bracket" to "{",
      "opening curly bracket" to "{",
      "curly bracket" to "{",
      "right brace" to "}",
      "close brace" to "}",
      "closing brace" to "}",
      "right curly" to "}",
      "close curly" to "}",
      "right curly brace" to "}",
      "close curly brace" to "}",
      "closing curly brace" to "}",
      "right curly bracket" to "}",
      "close curly bracket" to "}",
      "closing curly bracket" to "}",
      "left paren" to "(",
      "open paren" to "(",
      "opening paren" to "(",
      "left parenthesis" to "(",
      "open parenthesis" to "(",
      "opening parenthesis" to "(",
      "parenthesis" to "(",
      "right paren" to ")",
      "close paren" to ")",
      "closing paren" to ")",
      "right parenthesis" to ")",
      "close parenthesis" to ")",
      "closing parenthesis" to ")",
      "colon" to ":",
      "semicolon" to ";",
      "semi colon" to ";",
      "apostrophe" to "'",
      "single quote" to "'",
      "quote" to "'",
      "double quote" to "\"",
      "quotation mark" to "\"",
      "quotationmark" to "\"",
      "quotes" to "\"",
      "backtick" to "`",
      "back quote" to "`",
      "grave" to "`",
      "grave accent" to "`",
      "slash" to "/",
      "forward slash" to "/",
      "forwardslash" to "/",
      "backslash" to "\\",
      "back slash" to "\\",
      "pipe" to "|",
      "vertical bar" to "|",
      "dash" to "-",
      "dash sign" to "-",
      "hyphen" to "-",
      "minus" to "-",
      "minus sign" to "-",
      "underscore" to "_",
      "underscore sign" to "_",
      "under score" to "_",
      "plus" to "+",
      "plus sign" to "+",
      "equals" to "=",
      "equal" to "=",
      "equal sign" to "=",
      "equals sign" to "=",
      "asterisk" to "*",
      "star" to "*",
      "multiply" to "*",
      "ampersand" to "&",
      "ampersand sign" to "&",
      "and sign" to "&",
      "percent" to "%",
      "percent sign" to "%",
      "percent symbol" to "%",
      "modulo" to "%",
      "dollar" to "$",
      "dollar sign" to "$",
      "dollar symbol" to "$",
      "hash" to "#",
      "hash sign" to "#",
      "hash symbol" to "#",
      "hashtag" to "#",
      "pound" to "#",
      "pound sign" to "#",
      "pound symbol" to "#",
      "number sign" to "#",
      "number symbol" to "#",
      "at" to "@",
      "at sign" to "@",
      "at symbol" to "@",
      "exclamation" to "!",
      "exclamation mark" to "!",
      "exclamationmark" to "!",
      "exclamation point" to "!",
      "exclamationpoint" to "!",
      "bang" to "!",
      "question" to "?",
      "question mark" to "?",
      "questionmark" to "?",
      "tilde" to "~",
      "caret" to "^",
      "hat" to "^",
      "space" to " ",
      "space bar" to " ",
      "spacebar" to " ",
      "tab" to "\t",
      "newline" to "\n",
      "new line" to "\n",
      "enter" to "\n",
      "arrow" to "->",
      "thin arrow" to "->",
      "fat arrow" to "=>",
    )

  fun normalize(raw: String): String {
    val collapsed = raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    return collapsed.trimEnd { ch -> ch in ".!?,;:…\"')]" }.trim()
  }

  fun parse(
    transcript: String,
    emojiDictionary: List<EmojiItem>,
    allowTypeContinuation: Boolean = false,
  ): ParsedCommand {
    val rawTrimmed = transcript.trim()
    if (rawTrimmed.isEmpty()) {
      return ParsedCommand.UnknownCommand("", "empty transcript")
    }

    val rawLowerCollapsed = rawTrimmed.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    val normalized = normalize(rawTrimmed)

    // 1. Direct action commands: Backspace, Control+C, & Stop Type Stream
    if (normalized in BACKSPACE_COMMANDS) {
      return ParsedCommand.Backspace
    }
    if (normalized in CONTROL_C_COMMANDS) {
      return ParsedCommand.ControlC
    }
    if (allowTypeContinuation && normalized in STOP_TYPE_STREAM_COMMANDS) {
      return ParsedCommand.StopTypeStream
    }

    // 2. Standalone capital letter commands ("capital a", "uppercase z", "capital letter b")
    parseCapitalLetterArg(normalized)?.let { upper ->
      return ParsedCommand.TypeText(
        text = upper,
        label = "capital $upper",
        startsOrContinuesTypeStream = allowTypeContinuation,
      )
    }

    // 3. Symbol commands ("insert symbol ...", "symbol ...", "symbol?")
    extractPrefixedRaw(rawLowerCollapsed, INSERT_SYMBOL_PREFIX)?.let { rawArg ->
      return parseSymbolOnly(rawArg, normalized)
    }
    extractPrefixedRaw(rawLowerCollapsed, SYMBOL_PREFIX)?.let { rawArg ->
      return parseSymbolOnly(rawArg, normalized)
    }

    // 4. Indefinite type stream ("type ...", "type?", "type.", "type FM capital M")
    extractPrefixedRaw(rawLowerCollapsed, TYPE_PREFIX)?.let { rawArg ->
      if (rawArg.isEmpty() && allowTypeContinuation) {
        return ParsedCommand.EnterTypeStream
      }
      return parseTypeCommand(rawArg, normalized)
    }

    // 5. Emoji insertion ("insert emoji ...")
    if (normalized.startsWith(EMOJI_PREFIX)) {
      val afterPrefix = normalized.removePrefix(EMOJI_PREFIX)
      if (afterPrefix.isNotEmpty() && !afterPrefix[0].isWhitespace() && afterPrefix[0] !in ":,-") {
        return ParsedCommand.UnknownCommand(normalized, "missing command prefix")
      }
      val targetName = afterPrefix.trimStart { it.isWhitespace() || it in ":,-" }.let(::normalize)
      if (targetName.isEmpty()) {
        return ParsedCommand.UnknownCommand(normalized, "missing emoji name")
      }

      // 5a. Saved custom aliases (e.g. renaming 🌿 to "calm") take precedence
      emojiDictionary
        .firstOrNull { item ->
          val alias = item.customAlias?.let(::normalize)
          alias != null && alias == targetName
        }
        ?.let {
          return ParsedCommand.InsertEmoji(it.emoji, targetName)
        }

      // 5b. Default emoji names (e.g. "fire", "hugging face", "confused face")
      emojiDictionary
        .firstOrNull { item -> normalize(item.defaultName) == targetName }
        ?.let {
          return ParsedCommand.InsertEmoji(it.emoji, targetName)
        }

      // 5c. Exact normalized keyword aliases (e.g. "wink" -> 😉, "confused" -> 😕)
      emojiDictionary
        .firstOrNull { item -> item.keywords.any { normalize(it) == targetName } }
        ?.let {
          return ParsedCommand.InsertEmoji(it.emoji, targetName)
        }

      return ParsedCommand.UnknownCommand(normalized, "unknown emoji \"$targetName\"")
    }

    // 6. Sticky type-stream continuation across breaks in continuous Command mode
    if (allowTypeContinuation) {
      return parseTypeCommand(rawLowerCollapsed, normalized)
    }

    return ParsedCommand.UnknownCommand(normalized, "unknown command")
  }

  fun isBareTypeCommand(transcript: String): Boolean = normalize(transcript) == TYPE_PREFIX

  /**
   * Matches `"prefix ..."` as well as ASR-glued punctuation directly after the prefix
   * (e.g., `"type?"`, `"type."`, `"type,"`, `"type!"`, `"type:"`, `"type;"`, `"type..."`)
   * while rejecting unrelated inflected words like `"typed"` or `"types"`.
   */
  private fun extractPrefixedRaw(rawLowerCollapsed: String, prefix: String): String? {
    if (!rawLowerCollapsed.startsWith(prefix)) return null
    val rest = rawLowerCollapsed.removePrefix(prefix)
    if (rest.isEmpty()) return ""
    if (rest[0].isWhitespace()) return rest.trim()
    if (!rest[0].isLetterOrDigit()) {
      return rest.trim()
    }
    return null
  }

  private fun normalizeSingleLiteral(token: String): String? {
    val clean =
      when (token) {
        "…" -> "..."
        "—", "–" -> "-"
        else -> token
      }
    return clean.takeIf { it in LITERAL_SYMBOLS }
  }

  private fun resolveLiteralSymbol(rawArg: String): String? {
    normalizeSingleLiteral(rawArg)?.let { return it }
    // If ASR added a trailing period after a non-period symbol (e.g. ",." or "{."), match the symbol
    if (rawArg.length >= 2 && rawArg.endsWith('.')) {
      val withoutPeriod = rawArg.dropLast(1).trimEnd()
      normalizeSingleLiteral(withoutPeriod)?.let { return it }
    }
    return null
  }

  private fun parseSymbolOnly(rawArg: String, normalizedFull: String): ParsedCommand {
    if (rawArg.isEmpty()) {
      return ParsedCommand.UnknownCommand(normalizedFull, "missing symbol")
    }
    resolveLiteralSymbol(rawArg)?.let { sym ->
      return ParsedCommand.TypeText(sym, sym, startsOrContinuesTypeStream = false)
    }
    val arg = normalize(rawArg)
    SPOKEN_SYMBOLS[arg]?.let { sym ->
      return ParsedCommand.TypeText(sym, arg, startsOrContinuesTypeStream = false)
    }
    return ParsedCommand.UnknownCommand(normalizedFull, "unknown symbol \"$arg\"")
  }

  private fun parseTypeCommand(rawArg: String, normalizedFull: String): ParsedCommand {
    if (rawArg.isEmpty()) {
      return ParsedCommand.UnknownCommand(normalizedFull, "missing type target")
    }

    // Fast path for single literal symbol ("type .", "type?", "type,", "type...", etc.)
    resolveLiteralSymbol(rawArg)?.let { sym ->
      return ParsedCommand.TypeText(sym, sym, startsOrContinuesTypeStream = true)
    }

    // Fast path for single spoken phrase ("type less than", "type semicolon.", "type capital m")
    val singleNormalized = normalize(rawArg)
    if (singleNormalized.isNotEmpty()) {
      parseCapitalLetterArg(singleNormalized)?.let { upper ->
        return ParsedCommand.TypeText(upper, "capital $upper", startsOrContinuesTypeStream = true)
      }
      SPOKEN_SYMBOLS[singleNormalized]?.let { sym ->
        return ParsedCommand.TypeText(sym, singleNormalized, startsOrContinuesTypeStream = true)
      }
      DIGIT_WORDS[singleNormalized]?.let { digit ->
        return ParsedCommand.TypeText(digit, singleNormalized, startsOrContinuesTypeStream = true)
      }
    }

    // General indefinite stream tokenizer (handles e.g. "FM capital M" -> "fmM",
    // "less than capital k comma open bracket open brace a" -> "<K,[{a", multi-word coding text)
    val tokens = tokenizeTypeStream(rawArg)
    if (tokens.isEmpty()) {
      return ParsedCommand.UnknownCommand(normalizedFull, "missing type target")
    }

    val out = StringBuilder()
    var i = 0
    var pendingCase = CaseModifier.NONE
    var matchedMeaningfulToken = false

    while (i < tokens.size) {
      val t = tokens[i]

      if (i + 1 < tokens.size && (t == "capital" || t == "uppercase") && tokens[i + 1] == "letter") {
        pendingCase = CaseModifier.UPPER
        i += 2
        continue
      }
      if (t == "capital" || t == "uppercase") {
        pendingCase = CaseModifier.UPPER
        i += 1
        continue
      }
      if (i + 2 < tokens.size && t == "lower" && tokens[i + 1] == "case" && tokens[i + 2] == "letter") {
        pendingCase = CaseModifier.LOWER
        i += 3
        continue
      }
      if (
        i + 1 < tokens.size &&
          ((t == "lowercase" && tokens[i + 1] == "letter") || (t == "lower" && tokens[i + 1] == "case"))
      ) {
        pendingCase = CaseModifier.LOWER
        i += 2
        continue
      }
      if (t == "lowercase" || t == "letter") {
        pendingCase = CaseModifier.LOWER
        i += 1
        continue
      }

      // Longest spoken symbol phrase match (3 tokens down to 1)
      var matchedPhraseLen = 0
      var matchedSymbol: String? = null
      for (len in minOf(3, tokens.size - i) downTo 1) {
        val phrase = tokens.subList(i, i + len).joinToString(" ")
        val sym = SPOKEN_SYMBOLS[phrase]
        if (sym != null) {
          matchedPhraseLen = len
          matchedSymbol = sym
          break
        }
      }
      if (matchedSymbol != null) {
        out.append(matchedSymbol)
        pendingCase = CaseModifier.NONE
        matchedMeaningfulToken = true
        i += matchedPhraseLen
        continue
      }

      val literal = normalizeSingleLiteral(t)
      if (literal != null) {
        out.append(literal)
        pendingCase = CaseModifier.NONE
        matchedMeaningfulToken = true
        i += 1
        continue
      }

      val digit = DIGIT_WORDS[t]
      if (digit != null) {
        out.append(digit)
        pendingCase = CaseModifier.NONE
        matchedMeaningfulToken = true
        i += 1
        continue
      }

      if (t.any { it.isLetterOrDigit() }) {
        val formatted =
          when (pendingCase) {
            CaseModifier.UPPER -> t.uppercase(Locale.ROOT)
            CaseModifier.LOWER, CaseModifier.NONE -> t.lowercase(Locale.ROOT)
          }
        out.append(formatted)
        pendingCase = CaseModifier.NONE
        matchedMeaningfulToken = true
        i += 1
        continue
      }

      i += 1
    }

    val result = out.toString()
    if (!matchedMeaningfulToken || result.isEmpty()) {
      return ParsedCommand.UnknownCommand(normalizedFull, "unknown type target \"$singleNormalized\"")
    }
    return ParsedCommand.TypeText(
      text = result,
      label = result,
      startsOrContinuesTypeStream = true,
    )
  }

  private fun tokenizeTypeStream(rawArg: String): List<String> {
    val tokens = mutableListOf<String>()
    val chunks = rawArg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    for (chunk in chunks) {
      // If ASR appended sentence punctuation to a known spoken-symbol keyword (e.g., "semicolon."),
      // treat it as that spoken keyword instead of emitting both ";" and ".".
      val strippedKeyword = chunk.trimEnd { it in ".!?,;:" }
      if (strippedKeyword.length >= 2 && strippedKeyword != chunk && strippedKeyword in SPOKEN_SYMBOLS) {
        tokens.add(strippedKeyword)
        continue
      }

      var idx = 0
      while (idx < chunk.length) {
        when {
          chunk.startsWith("...", idx) -> {
            tokens.add("...")
            idx += 3
          }
          chunk.startsWith("->", idx) -> {
            tokens.add("->")
            idx += 2
          }
          chunk.startsWith("=>", idx) -> {
            tokens.add("=>")
            idx += 2
          }
          chunk[idx] == '…' -> {
            tokens.add("...")
            idx += 1
          }
          chunk[idx] == '—' || chunk[idx] == '–' -> {
            tokens.add("-")
            idx += 1
          }
          chunk[idx] in SINGLE_CHAR_LITERAL_SYMBOLS -> {
            tokens.add(chunk[idx].toString())
            idx += 1
          }
          else -> {
            val start = idx
            while (
              idx < chunk.length &&
                !chunk.startsWith("...", idx) &&
                !chunk.startsWith("->", idx) &&
                !chunk.startsWith("=>", idx) &&
                chunk[idx] !in SINGLE_CHAR_LITERAL_SYMBOLS &&
                chunk[idx] != '—' &&
                chunk[idx] != '–'
            ) {
              idx++
            }
            val word = chunk.substring(start, idx)
            if (word.isNotEmpty()) {
              tokens.add(word)
            }
          }
        }
      }
    }
    return tokens
  }

  private fun parseCapitalLetterArg(normalizedArg: String): String? {
    val prefixes = listOf("capital letter ", "uppercase letter ", "capital ", "uppercase ")
    for (p in prefixes) {
      if (normalizedArg.startsWith(p)) {
        val letter = normalizedArg.removePrefix(p).trim()
        if (letter.length == 1 && letter[0] in 'a'..'z') {
          return letter.uppercase(Locale.ROOT)
        }
      }
    }
    return null
  }
}
