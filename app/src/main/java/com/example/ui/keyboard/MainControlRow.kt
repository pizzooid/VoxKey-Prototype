package com.example.ui.keyboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Publish
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.RecordingMode
import com.example.ime.ImeActionType
import com.example.ime.KeyboardController
import com.example.ime.KeyboardUiState
import com.example.ui.theme.VoxKeyThemeTokens

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainControlRow(
  state: KeyboardUiState,
  controller: KeyboardController,
  modifier: Modifier = Modifier,
) {
  val palette = VoxKeyThemeTokens.palette
  val haptics = LocalHapticFeedback.current

  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // 1. Dictate
    val dictateBg by
      animateColorAsState(
        targetValue =
          if (state.isListening) palette.dictateAccent else palette.dictateActiveBg,
        label = "dictateBg",
      )
    val dictateTint by
      animateColorAsState(
        targetValue =
          if (state.isListening) palette.onDictateAccent else palette.dictateAccent,
        label = "dictateTint",
      )
    val pulseScale by
      animateFloatAsState(
        targetValue =
          if ((state.isListening || state.isCommandRecording) && state.isSpeechReady) {
            1f + (state.rmsDb / 48f).coerceIn(0f, 0.25f)
          } else {
            1f
          },
        animationSpec = spring(stiffness = 450f),
        label = "rmsPulse",
      )

    val dictateShape = RoundedCornerShape(14.dp)
    val dictateCd =
      if (state.isListening) stringResource(R.string.cd_stop_dictate)
      else stringResource(R.string.cd_dictate)

    val dictateModifier =
      Modifier.weight(1.10f)
        .height(52.dp)
        .defaultMinSize(minHeight = 48.dp)
        .clip(dictateShape)
        .background(dictateBg, dictateShape)
        .border(
          width = if (state.isListening) 1.5.dp else 1.dp,
          color = palette.dictateAccent.copy(alpha = if (state.isListening) 1f else 0.35f),
          shape = dictateShape,
        )
        .then(
          if (state.settings.recordingMode == RecordingMode.HOLD_TO_RECORD) {
            Modifier.pointerInput(RecordingMode.HOLD_TO_RECORD) {
              awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                controller.onDictatePressStart()
                try {
                  awaitPointerRelease(down.id)
                } finally {
                  controller.onDictatePressEnd()
                }
              }
            }
          } else {
            Modifier.combinedClickable(
              role = Role.Button,
              onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                controller.onDictateTap()
              },
              onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                controller.showTooltip(
                  if (state.isListening) "Stop dictation" else "Tap to dictate (${state.activeLanguageShort})",
                )
              },
            )
          },
        )
        .semantics { contentDescription = dictateCd }
        .testTag("main_dictate_button")

    Box(modifier = dictateModifier, contentAlignment = Alignment.Center) {
      if (state.isListening) {
        if (state.isSpeechReady) {
          Box(
            modifier =
              Modifier.size(34.dp)
                .scale(pulseScale)
                .background(palette.onDictateAccent.copy(alpha = 0.18f), CircleShape)
          )
        }
        Icon(
          imageVector =
            when {
              !state.isSpeechReady -> Icons.Rounded.Mic
              state.settings.recordingMode == RecordingMode.TAP_TO_TOGGLE -> Icons.Rounded.Stop
              else -> Icons.Rounded.GraphicEq
            },
          contentDescription = null,
          tint = dictateTint.copy(alpha = if (state.isSpeechReady) 1f else 0.78f),
          modifier = Modifier.size(22.dp),
        )
      } else {
        Icon(
          imageVector = Icons.Rounded.Mic,
          contentDescription = null,
          tint = dictateTint,
          modifier = Modifier.size(22.dp),
        )
      }
    }

    // 2. Command (supports TAP_TO_TOGGLE continuous command stream & HOLD_TO_RECORD single utterance)
    val cmdActive = state.isCommandRecording
    val cmdPending = state.isCommandPendingFinal
    val cmdBg by
      animateColorAsState(
        targetValue =
          when {
            cmdActive -> palette.commandAccent
            else -> palette.commandActiveBg
          },
        label = "cmdBg",
      )
    val cmdTint by
      animateColorAsState(
        targetValue =
          when {
            cmdActive -> palette.onCommandAccent
            else -> palette.commandAccent
          },
        label = "cmdTint",
      )
    val cmdShape = RoundedCornerShape(14.dp)
    val cmdCd =
      when {
        cmdActive && state.settings.recordingMode == RecordingMode.TAP_TO_TOGGLE ->
          stringResource(R.string.cd_stop_command)
        state.settings.recordingMode == RecordingMode.TAP_TO_TOGGLE ->
          stringResource(R.string.cd_tap_command)
        else -> stringResource(R.string.cd_command)
      }

    Box(
      modifier =
        Modifier.weight(1.12f)
          .height(52.dp)
          .defaultMinSize(minHeight = 48.dp)
          .clip(cmdShape)
          .background(cmdBg, cmdShape)
          .border(
            width = if (cmdActive || cmdPending) 1.5.dp else 1.dp,
            color = palette.commandAccent.copy(alpha = if (cmdActive || cmdPending) 1f else 0.35f),
            shape = cmdShape,
          )
          .then(
            if (state.settings.recordingMode == RecordingMode.HOLD_TO_RECORD) {
              Modifier.pointerInput(RecordingMode.HOLD_TO_RECORD, controller) {
                awaitEachGesture {
                  val down = awaitFirstDown(requireUnconsumed = false)
                  haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                  controller.onCommandPressStart()
                  try {
                    awaitPointerRelease(down.id)
                  } finally {
                    controller.onCommandPressEnd()
                  }
                }
              }
            } else {
              Modifier.combinedClickable(
                role = Role.Button,
                onClick = {
                  haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  controller.onCommandTap()
                },
                onLongClick = {
                  haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                  controller.showTooltip(
                    if (cmdActive) "Stop command mode" else "Tap for voice commands (with breaks)",
                  )
                },
              )
            },
          )
          .semantics { contentDescription = cmdCd }
          .testTag("main_command_button"),
      contentAlignment = Alignment.Center,
    ) {
      if (cmdActive && state.isSpeechReady) {
        Box(
          modifier =
            Modifier.size(34.dp)
              .scale(pulseScale)
              .background(palette.onCommandAccent.copy(alpha = 0.18f), CircleShape)
        )
      }
      Icon(
        imageVector =
          when {
            cmdPending -> Icons.Rounded.HourglassTop
            cmdActive && !state.isSpeechReady -> Icons.Rounded.KeyboardCommandKey
            cmdActive && state.settings.recordingMode == RecordingMode.TAP_TO_TOGGLE -> Icons.Rounded.Stop
            cmdActive -> Icons.Rounded.GraphicEq
            else -> Icons.Rounded.KeyboardCommandKey
          },
        contentDescription = null,
        tint = cmdTint.copy(alpha = if (cmdActive && !state.isSpeechReady) 0.78f else 1f),
        modifier = Modifier.size(21.dp),
      )
    }

    // 3. Navigate
    val navBg by
      animateColorAsState(
        targetValue =
          if (state.isNavigateMode) palette.navigateAccent else palette.keySurface,
        label = "navBg",
      )
    val navTint by
      animateColorAsState(
        targetValue =
          if (state.isNavigateMode) palette.onNavigateAccent else palette.inkPrimary,
        label = "navTint",
      )
    TactileKeyButton(
      onClick = controller::toggleNavigateMode,
      onLongClickTooltip = if (state.isNavigateMode) "Exit navigate mode" else "Navigate & select",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_navigate),
      testTag = "main_navigate_button",
      backgroundColor = navBg,
      borderColor = if (state.isNavigateMode) palette.navigateAccent else palette.keyBorder,
      minWidth = 40.dp,
      modifier = Modifier.weight(0.92f).height(52.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.OpenWith,
        contentDescription = null,
        tint = navTint,
        modifier = Modifier.size(20.dp),
      )
    }

    // 4. Delete / backspace (supports tap, hold-to-repeat, and accessibility click)
    val deleteShape = RoundedCornerShape(12.dp)
    var deletePressed by remember { mutableStateOf(false) }
    Box(
      modifier =
        Modifier.weight(0.92f)
          .height(52.dp)
          .defaultMinSize(minHeight = 48.dp)
          .clip(deleteShape)
          .background(if (deletePressed) palette.traySurface else palette.keySurface, deleteShape)
          .border(1.dp, palette.keyBorder, deleteShape)
          .pointerInput(controller) {
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              deletePressed = true
              haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              controller.startRepeatDelete()
              try {
                awaitPointerRelease(down.id)
              } finally {
                deletePressed = false
                controller.stopRepeatingAction()
              }
            }
          }
          .semantics {
            contentDescription = "Delete"
            onClick {
              controller.onDeleteSingle()
              true
            }
          }
          .testTag("main_delete_button"),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Rounded.Backspace,
        contentDescription = null,
        tint = palette.inkPrimary,
        modifier = Modifier.size(19.dp),
      )
    }

    // 5. Space (supports tap, hold-to-repeat, and accessibility click)
    val spaceShape = RoundedCornerShape(12.dp)
    val spaceCd = stringResource(R.string.cd_space)
    var spacePressed by remember { mutableStateOf(false) }
    Box(
      modifier =
        Modifier.weight(1.02f)
          .height(52.dp)
          .defaultMinSize(minHeight = 48.dp)
          .clip(spaceShape)
          .background(if (spacePressed) palette.traySurface else palette.keySurface, spaceShape)
          .border(1.dp, palette.keyBorder, spaceShape)
          .pointerInput(controller) {
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              spacePressed = true
              haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              controller.startRepeatSpace()
              try {
                awaitPointerRelease(down.id)
              } finally {
                spacePressed = false
                controller.stopRepeatingAction()
              }
            }
          }
          .semantics {
            contentDescription = spaceCd
            onClick {
              controller.onSpaceSingle()
              true
            }
          }
          .testTag("main_space_button"),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Rounded.SpaceBar,
        contentDescription = null,
        tint = palette.inkPrimary,
        modifier = Modifier.size(20.dp),
      )
    }

    // 5. Enter / Continue
    val effectiveAction =
      if (state.isTargetingFallback) ImeActionType.NEWLINE else state.imeActionType
    val enterIcon =
      when (effectiveAction) {
        ImeActionType.SEND -> Icons.AutoMirrored.Rounded.Send
        ImeActionType.SEARCH -> Icons.Rounded.Search
        ImeActionType.NEXT -> Icons.AutoMirrored.Rounded.ArrowForward
        ImeActionType.GO -> Icons.Rounded.PlayArrow
        ImeActionType.DONE -> Icons.Rounded.Check
        ImeActionType.NEWLINE -> Icons.AutoMirrored.Rounded.KeyboardReturn
      }
    val enterTooltip =
      when (effectiveAction) {
        ImeActionType.SEND -> "Send"
        ImeActionType.SEARCH -> "Search"
        ImeActionType.NEXT -> "Next field"
        ImeActionType.GO -> "Go"
        ImeActionType.DONE -> "Done"
        ImeActionType.NEWLINE -> if (state.isTargetingFallback) "Insert newline in draft" else "Enter / Newline"
      }
    TactileKeyButton(
      onClick = controller::onEnterOrAction,
      onLongClickTooltip = enterTooltip,
      onShowTooltip = controller::showTooltip,
      contentDescription = enterTooltip,
      testTag = "main_enter_button",
      backgroundColor = palette.keySurface,
      borderColor = palette.keyBorder,
      minWidth = 40.dp,
      modifier = Modifier.weight(0.92f).height(52.dp),
    ) {
      Icon(
        imageVector = enterIcon,
        contentDescription = null,
        tint = palette.inkPrimary,
        modifier = Modifier.size(20.dp),
      )
    }

    // 7. Apply fallback editor's text
    val hasApplyPayload = state.fallbackText.isNotEmpty() || state.hasImportedSnapshot
    val applyBg = if (hasApplyPayload) palette.applyAccent else palette.keySurface
    val applyTint = if (hasApplyPayload) palette.onApplyAccent else palette.inkSecondary.copy(alpha = 0.6f)
    TactileKeyButton(
      onClick = controller::onApplyFallback,
      onLongClickTooltip = "Apply fallback text to app",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_apply_fallback),
      testTag = "main_apply_button",
      backgroundColor = applyBg,
      borderColor = if (hasApplyPayload) palette.applyAccent else palette.keyBorder,
      minWidth = 40.dp,
      modifier = Modifier.weight(0.92f).height(52.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.Publish,
        contentDescription = null,
        tint = applyTint,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

private suspend fun AwaitPointerEventScope.awaitPointerRelease(pointerId: PointerId) {
  while (true) {
    val event = awaitPointerEvent()
    val tracked = event.changes.firstOrNull { it.id == pointerId }
    if (tracked == null || !tracked.pressed) {
      break
    }
  }
}
