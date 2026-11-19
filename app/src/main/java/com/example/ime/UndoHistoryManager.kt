package com.example.ime

data class TextSnapshot(
  val text: String,
  val selStart: Int,
  val selEnd: Int,
)

enum class EditGroupKind {
  DICTATION_SEGMENT,
  MANUAL_INSERT,
  MANUAL_DELETE,
  EMOJI_INSERT,
  CLEAR,
  IMPORT,
  DISCRETE,
}

/**
 * Local undo/redo stack for the fallback editor. Groups contiguous manual edits
 * while keeping each completed dictation segment, emoji, import, or clear as its own step.
 */
class UndoHistoryManager(private val maxHistory: Int = 100) {
  private val undoStack = ArrayDeque<TextSnapshot>()
  private val redoStack = ArrayDeque<TextSnapshot>()
  private var lastGroupKind: EditGroupKind? = null
  private var lastEditTimeMs: Long = 0L

  val canUndo: Boolean
    get() = undoStack.isNotEmpty()

  val canRedo: Boolean
    get() = redoStack.isNotEmpty()

  fun recordChange(
    before: TextSnapshot,
    after: TextSnapshot,
    kind: EditGroupKind,
    nowMs: Long = System.currentTimeMillis(),
  ) {
    if (before.text == after.text) return

    val coalesceWithPrevious =
      (kind == EditGroupKind.MANUAL_INSERT || kind == EditGroupKind.MANUAL_DELETE) &&
        lastGroupKind == kind &&
        (nowMs - lastEditTimeMs) <= 900L &&
        undoStack.isNotEmpty()

    if (!coalesceWithPrevious) {
      undoStack.addLast(before)
      if (undoStack.size > maxHistory) {
        undoStack.removeFirst()
      }
    }
    redoStack.clear()
    lastGroupKind = kind
    lastEditTimeMs = nowMs
  }

  fun undo(current: TextSnapshot): TextSnapshot? {
    if (undoStack.isEmpty()) return null
    val previous = undoStack.removeLast()
    redoStack.addLast(current)
    lastGroupKind = null
    return previous
  }

  fun redo(current: TextSnapshot): TextSnapshot? {
    if (redoStack.isEmpty()) return null
    val next = redoStack.removeLast()
    undoStack.addLast(current)
    lastGroupKind = null
    return next
  }

  fun reset() {
    undoStack.clear()
    redoStack.clear()
    lastGroupKind = null
    lastEditTimeMs = 0L
  }
}
