package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thread-safe fixed-capacity circular PCM byte buffer with monotonic byte cursor tracking.
 * Supports non-destructive cursor reads (`snapshotSince`) so failed ASR cycles can replay
 * unconfirmed speech without losing audio.
 */
class CircularAudioBuffer(initialCapacityBytes: Int) {
  private var buffer = ByteArray(initialCapacityBytes.coerceAtLeast(1))
  private var writePos = 0
  private var size = 0
  private var totalWrittenBytes = 0L

  @Synchronized
  fun resize(newCapacityBytes: Int) {
    val targetCap = newCapacityBytes.coerceAtLeast(1)
    if (targetCap == buffer.size) return
    val current = snapshotInternal()
    buffer.fill(0)
    buffer = ByteArray(targetCap)
    val toCopy = minOf(current.size, targetCap)
    val startOffset = current.size - toCopy
    System.arraycopy(current, startOffset, buffer, 0, toCopy)
    writePos = toCopy % targetCap
    size = toCopy
  }

  @Synchronized
  fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
    if (length <= 0) return
    totalWrittenBytes += length.toLong()
    val cap = buffer.size
    if (length >= cap) {
      val tailOffset = offset + (length - cap)
      System.arraycopy(data, tailOffset, buffer, 0, cap)
      writePos = 0
      size = cap
      return
    }
    val firstPart = minOf(length, cap - writePos)
    System.arraycopy(data, offset, buffer, writePos, firstPart)
    val remaining = length - firstPart
    if (remaining > 0) {
      System.arraycopy(data, offset + firstPart, buffer, 0, remaining)
    }
    writePos = (writePos + length) % cap
    size = minOf(cap, size + length)
  }

  @Synchronized
  fun drain(): ByteArray {
    val result = snapshotInternal()
    clearInternal()
    return result
  }

  @Synchronized
  fun snapshot(): ByteArray = snapshotInternal()

  @Synchronized
  fun totalBytes(): Long = totalWrittenBytes

  /**
   * Non-destructively returns bytes written since [fromByte] (clamped to currently retained audio
   * and at most [maxBytes]), alongside the current total byte cursor.
   */
  @Synchronized
  fun snapshotSince(fromByte: Long, maxBytes: Int = buffer.size): Pair<ByteArray, Long> {
    val endByte = totalWrittenBytes
    val oldestRetained = endByte - size.toLong()
    val clampedMax = maxBytes.coerceAtLeast(0).toLong()
    val earliestAllowed = maxOf(oldestRetained, endByte - clampedMax)
    val startByte = fromByte.coerceIn(earliestAllowed, endByte)
    val byteCount = (endByte - startByte).toInt()
    if (byteCount <= 0) return ByteArray(0) to endByte

    val cap = buffer.size
    val offsetFromStartOfRetained = (startByte - oldestRetained).toInt()
    val retainedStartIdx = (writePos - size + cap) % cap
    val startIdx = (retainedStartIdx + offsetFromStartOfRetained) % cap

    val out = ByteArray(byteCount)
    val firstPart = minOf(byteCount, cap - startIdx)
    System.arraycopy(buffer, startIdx, out, 0, firstPart)
    val remaining = byteCount - firstPart
    if (remaining > 0) {
      System.arraycopy(buffer, 0, out, firstPart, remaining)
    }
    return out to endByte
  }

  @Synchronized
  fun availableBytes(): Int = size

  @Synchronized
  fun clear() {
    clearInternal()
  }

  private fun snapshotInternal(): ByteArray {
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    val cap = buffer.size
    val start = (writePos - size + cap) % cap
    val firstPart = minOf(size, cap - start)
    System.arraycopy(buffer, start, out, 0, firstPart)
    val remaining = size - firstPart
    if (remaining > 0) {
      System.arraycopy(buffer, 0, out, firstPart, remaining)
    }
    return out
  }

  private fun clearInternal() {
    buffer.fill(0)
    writePos = 0
    size = 0
  }
}

/**
 * Lightweight adaptive voice-activity detector over 16-bit little-endian PCM chunks.
 * Tracks an adaptive ambient noise floor and fires after consecutive active frames (or a strong
 * onset peak) so pauses don't starve SpeechRecognizer while speech onset wakes recognition quickly.
 */
class PcmSpeechActivityDetector(
  private val minSpeechRms: Float = 115f,
  private val minSpeechPeak: Int = 550,
  private val noiseMultiplier: Float = 2.1f,
) {
  private var noiseFloorRms = 90f
  private var consecutiveActiveFrames = 0

  @Synchronized
  fun reset() {
    noiseFloorRms = 90f
    consecutiveActiveFrames = 0
  }

  @Synchronized
  fun resetBurstCounter() {
    consecutiveActiveFrames = 0
  }

  @Synchronized
  fun processPcmChunk(data: ByteArray, length: Int): Boolean {
    val sampleCount = length / 2
    if (sampleCount <= 0) return false

    var sumSq = 0.0
    var maxAbs = 0
    var i = 0
    while (i + 1 < length) {
      val low = data[i].toInt() and 0xFF
      val high = data[i + 1].toInt()
      val sample = (high shl 8) or low
      val absSample = abs(sample)
      if (absSample > maxAbs) maxAbs = absSample
      sumSq += sample.toDouble() * sample.toDouble()
      i += 2
    }

    val rms = sqrt(sumSq / sampleCount).toFloat()
    val rmsThreshold = maxOf(minSpeechRms, noiseFloorRms * noiseMultiplier)
    val isFrameActive = rms >= rmsThreshold || maxAbs >= minSpeechPeak

    if (isFrameActive) {
      consecutiveActiveFrames++
      return consecutiveActiveFrames >= 2 || rms >= rmsThreshold * 1.35f
    } else {
      consecutiveActiveFrames = 0
      val alpha = if (rms < noiseFloorRms) 0.08f else 0.02f
      noiseFloorRms = ((noiseFloorRms * (1f - alpha)) + (rms * alpha)).coerceIn(50f, 350f)
      return false
    }
  }
}

/**
 * Maintains a rolling in-memory PCM pre-buffer while the IME is visible and mic permission
 * is granted. Keeps recording into the ring buffer even while streaming to a recognizer pipe so
 * failed/timed-out recognizer cycles can replay unconfirmed audio seamlessly, and detects speech
 * onset during silent standby pauses.
 */
class AudioPreBufferManager(
  private val context: Context,
  private val nowMsProvider: () -> Long = { SystemClock.elapsedRealtime() },
) {

  companion object {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val BYTES_PER_SECOND = SAMPLE_RATE_HZ * 2 // 16-bit mono = 32,000 B/s
    private const val READ_CHUNK_BYTES = 1024 // 32ms per chunk for responsive VAD
    private const val MIN_RING_RETENTION_MS = 2500
    private const val MAX_PIPE_PRELOAD_BYTES = 56_000 // 1750ms (< 64KB Linux pipe limit)
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val ringBuffer = CircularAudioBuffer(capacityForMs(MIN_RING_RETENTION_MS))
  private val vad = PcmSpeechActivityDetector()
  private val isCapturing = AtomicBoolean(false)
  private val lastSpeechMs = AtomicLong(0L)

  @Volatile private var captureJob: Job? = null
  @Volatile private var audioRecord: AudioRecord? = null
  @Volatile private var activePipeOutput: ParcelFileDescriptor.AutoCloseOutputStream? = null
  @Volatile private var unconfirmedFromByte: Long = 0L
  @Volatile private var speechTriggerCallback: (() -> Unit)? = null

  private val pipeLock = Any()
  private val recorderLock = Any()

  fun hasRecordPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED

  fun isCapturingActive(): Boolean = isCapturing.get()

  fun hasRecentSpeech(withinMs: Long): Boolean {
    val last = lastSpeechMs.get()
    if (last <= 0L) return false
    return (nowMsProvider() - last) <= withinMs
  }

  fun markAudioConfirmedNow() {
    synchronized(pipeLock) {
      unconfirmedFromByte = ringBuffer.totalBytes()
    }
  }

  fun armSpeechTrigger(onSpeechDetected: () -> Unit) {
    vad.resetBurstCounter()
    speechTriggerCallback = onSpeechDetected
  }

  fun disarmSpeechTrigger() {
    speechTriggerCallback = null
  }

  fun updatePreBufferDuration(ms: Int) {
    val targetMs = maxOf(ms.coerceIn(500, 1000), MIN_RING_RETENTION_MS)
    ringBuffer.resize(capacityForMs(targetMs))
  }

  fun startPreBuffering(preBufferMs: Int) {
    updatePreBufferDuration(preBufferMs)
    if (!hasRecordPermission()) return
    if (isCapturing.get()) return

    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_ENCODING)
    if (minBuf <= 0) return
    val audioRecordBufSize = maxOf(minBuf * 4, 16_384) // >= 512ms OS ring headroom

    synchronized(recorderLock) {
      if (isCapturing.get()) return
      val recorder =
        try {
          AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_ENCODING,
            audioRecordBufSize,
          )
        } catch (_: SecurityException) {
          return
        } catch (_: IllegalArgumentException) {
          return
        }

      if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release()
        return
      }

      try {
        recorder.startRecording()
      } catch (_: IllegalStateException) {
        recorder.release()
        return
      }

      isCapturing.set(true)
      audioRecord = recorder
      vad.reset()

      captureJob =
        scope.launch {
          val chunk = ByteArray(READ_CHUNK_BYTES)
          try {
            while (isActive && isCapturing.get()) {
              val read =
                try {
                  recorder.read(chunk, 0, chunk.size)
                } catch (_: IllegalStateException) {
                  break
                }
              if (read > 0) {
                processCapturedChunk(chunk, read)
              } else if (read < 0) {
                break
              }
            }
          } finally {
            isCapturing.set(false)
            chunk.fill(0)
          }
        }
    }
  }

  internal fun processCapturedChunk(data: ByteArray, length: Int) {
    if (length <= 0) return
    val speechDetected = vad.processPcmChunk(data, length)
    if (speechDetected) {
      lastSpeechMs.set(nowMsProvider())
    }
    synchronized(pipeLock) {
      // Always retain recent PCM in circular buffer so failed pipes never drop speech
      ringBuffer.write(data, 0, length)
      val stream = activePipeOutput
      if (stream != null) {
        try {
          stream.write(data, 0, length)
          stream.flush()
        } catch (_: IOException) {
          runCatching { stream.close() }
          activePipeOutput = null
        }
      }
    }
    if (speechDetected) {
      val cb = speechTriggerCallback
      if (cb != null) {
        speechTriggerCallback = null
        cb()
      }
    }
  }

  /**
   * Opens a ParcelFileDescriptor pipe, flushes pre-buffered (or unconfirmed replay) PCM audio into
   * it without blocking, and continues streaming live AudioRecord frames into the pipe.
   */
  fun attachLivePipe(
    preBufferMs: Int = 800,
    replayUnconfirmed: Boolean = false,
  ): ParcelFileDescriptor? {
    if (!isCapturing.get()) return null
    return try {
      val pipe = ParcelFileDescriptor.createPipe()
      val readSide = pipe[0]
      val writeSide = pipe[1]
      val outStream = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
      synchronized(pipeLock) {
        activePipeOutput?.let { runCatching { it.close() } }
        val totalNow = ringBuffer.totalBytes()
        val preBufBytes = capacityForMs(preBufferMs.coerceIn(500, 1000)).toLong()
        val desiredStart =
          if (replayUnconfirmed && unconfirmedFromByte > 0L) {
            minOf(unconfirmedFromByte, (totalNow - preBufBytes).coerceAtLeast(0L))
          } else {
            (totalNow - preBufBytes).coerceAtLeast(0L)
          }
        unconfirmedFromByte = desiredStart
        val (preAudio, _) = ringBuffer.snapshotSince(desiredStart, MAX_PIPE_PRELOAD_BYTES)
        if (preAudio.isNotEmpty()) {
          outStream.write(preAudio)
          outStream.flush()
          preAudio.fill(0)
        }
        activePipeOutput = outStream
      }
      readSide
    } catch (_: IOException) {
      closePipeOnly()
      null
    }
  }

  fun closePipeOnly() {
    synchronized(pipeLock) {
      activePipeOutput?.let { runCatching { it.close() } }
      activePipeOutput = null
    }
  }

  /**
   * Stops capture and zeroes/discards any unused pre-buffered audio immediately.
   */
  fun stopAndDiscard() {
    isCapturing.set(false)
    speechTriggerCallback = null
    closePipeOnly()
    captureJob?.cancel()
    captureJob = null
    synchronized(recorderLock) {
      audioRecord?.let { rec ->
        runCatching { rec.stop() }
        runCatching { rec.release() }
      }
      audioRecord = null
    }
    ringBuffer.clear()
    unconfirmedFromByte = 0L
    lastSpeechMs.set(0L)
  }

  fun bufferedDurationMs(): Int =
    ((ringBuffer.availableBytes().toLong() * 1000L) / BYTES_PER_SECOND).toInt()

  private fun capacityForMs(ms: Int): Int =
    ((ms.toLong() * BYTES_PER_SECOND) / 1000L).toInt().coerceAtLeast(3200)
}
