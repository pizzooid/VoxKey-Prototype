package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

data class LanguageOption(
  val tag: String,
  val shortCode: String,
  val displayName: String,
)

sealed interface LanguageAvailability {
  data object AvailableOnDevice : LanguageAvailability
  data object DownloadRequired : LanguageAvailability
  data object Downloading : LanguageAvailability
  data object Unavailable : LanguageAvailability
  data object LegacyOfflineFallback : LanguageAvailability
  data object Checking : LanguageAvailability
}

object SpeechLanguages {
  val catalog: List<LanguageOption> =
    listOf(
      LanguageOption("en-US", "EN", "English (US)"),
      LanguageOption("en-GB", "EN-GB", "English (UK)"),
      LanguageOption("es-ES", "ES", "Spanish"),
      LanguageOption("fr-FR", "FR", "French"),
      LanguageOption("de-DE", "DE", "German"),
      LanguageOption("it-IT", "IT", "Italian"),
      LanguageOption("pt-BR", "PT", "Portuguese (BR)"),
      LanguageOption("nl-NL", "NL", "Dutch"),
      LanguageOption("pl-PL", "PL", "Polish"),
      LanguageOption("tr-TR", "TR", "Turkish"),
      LanguageOption("ja-JP", "JA", "Japanese"),
      LanguageOption("ko-KR", "KO", "Korean"),
      LanguageOption("zh-CN", "ZH", "Chinese (Mandarin)"),
      LanguageOption("hi-IN", "HI", "Hindi"),
    )

  fun find(tag: String): LanguageOption =
    catalog.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
      ?: LanguageOption(tag, tag.substringBefore("-").uppercase(), tag)
}

class SpeechCapabilityInspector(private val context: Context) {

  fun isOnDeviceApiSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
      SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

  fun isAudioPipeSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isOnDeviceApiSupported()

  fun checkLanguageSupport(
    languageTags: List<String>,
    onResult: (Map<String, LanguageAvailability>) -> Unit,
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      val avail =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
          LanguageAvailability.LegacyOfflineFallback
        } else {
          LanguageAvailability.Unavailable
        }
      onResult(languageTags.associateWith { avail })
      return
    }

    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
      onResult(languageTags.associateWith { LanguageAvailability.Unavailable })
      return
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      onResult(languageTags.associateWith { LanguageAvailability.AvailableOnDevice })
      return
    }

    val recognizer =
      try {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
      } catch (_: Exception) {
        onResult(languageTags.associateWith { LanguageAvailability.Unavailable })
        return
      }

    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      }

    try {
      recognizer.checkRecognitionSupport(
        intent,
        ContextCompat.getMainExecutor(context),
        object : RecognitionSupportCallback {
          override fun onSupportResult(recognitionSupport: RecognitionSupport) {
            val installed =
              (recognitionSupport.installedOnDeviceLanguages +
                  recognitionSupport.onlineLanguages.filter { false })
                .map { normalizeTag(it) }
                .toSet()
            val pending =
              recognitionSupport.pendingOnDeviceLanguages.map { normalizeTag(it) }.toSet()
            val supported =
              recognitionSupport.supportedOnDeviceLanguages.map { normalizeTag(it) }.toSet()

            val mapped =
              languageTags.associateWith { tag ->
                val norm = normalizeTag(tag)
                val base = norm.substringBefore("-")
                when {
                  installed.contains(norm) || installed.any { it.substringBefore("-") == base } ->
                    LanguageAvailability.AvailableOnDevice
                  pending.contains(norm) || pending.any { it.substringBefore("-") == base } ->
                    LanguageAvailability.Downloading
                  supported.contains(norm) || supported.any { it.substringBefore("-") == base } ->
                    LanguageAvailability.DownloadRequired
                  else -> LanguageAvailability.Unavailable
                }
              }
            runCatching { recognizer.destroy() }
            onResult(mapped)
          }

          override fun onError(error: Int) {
            runCatching { recognizer.destroy() }
            onResult(languageTags.associateWith { LanguageAvailability.Unavailable })
          }
        },
      )
    } catch (_: Exception) {
      runCatching { recognizer.destroy() }
      onResult(languageTags.associateWith { LanguageAvailability.Unavailable })
    }
  }

  fun triggerModelDownload(languageTag: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return false
    return try {
      val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
      val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        }
      recognizer.triggerModelDownload(intent)
      // Give the system recognizer a brief window before destroying the caller handle
      android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
        { runCatching { recognizer.destroy() } },
        2000L,
      )
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun normalizeTag(tag: String): String = tag.replace('_', '-').lowercase()
}
