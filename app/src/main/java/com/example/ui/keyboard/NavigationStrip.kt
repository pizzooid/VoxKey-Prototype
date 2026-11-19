package com.example.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LastPage
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ime.KeyboardController
import com.example.ime.KeyboardUiState
import com.example.ime.NavDirection
import com.example.ui.theme.VoxKeyThemeTokens

@Composable
fun NavigationStrip(
  state: KeyboardUiState,
  controller: KeyboardController,
  modifier: Modifier = Modifier,
) {
  val palette = VoxKeyThemeTokens.palette
  val targetColor = if (state.isTargetingFallback) palette.dictateAccent else palette.navigateAccent
  val stripShape = RoundedCornerShape(12.dp)

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 6.dp, vertical = 3.dp)
        .background(palette.traySurface, stripShape)
        .border(1.dp, targetColor.copy(alpha = 0.5f), stripShape)
        .padding(horizontal = 4.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Visual target indicator icon (App vs Fallback editor)
    Box(
      modifier =
        Modifier.size(width = 24.dp, height = 40.dp)
          .background(targetColor.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
          .semantics {
            contentDescription =
              if (state.isTargetingFallback) "Navigating fallback editor" else "Navigating app field"
          }
          .testTag("nav_target_indicator"),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector =
          if (state.isTargetingFallback) Icons.Rounded.EditNote else Icons.Rounded.PhoneAndroid,
        contentDescription = null,
        tint = targetColor,
        modifier = Modifier.size(16.dp),
      )
    }

    // Start of input
    TactileKeyButton(
      onClick = { controller.navigateToEdge(toEnd = false) },
      onLongClickTooltip = "Jump to start",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_nav_start),
      testTag = "nav_start_button",
      cornerRadius = 9.dp,
      minHeight = 42.dp,
      modifier = Modifier.weight(1f).height(42.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.FirstPage,
        contentDescription = null,
        tint = palette.inkPrimary,
        modifier = Modifier.size(19.dp),
      )
    }

    // Left
    RepeatableNavKey(
      icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
      contentDescription = stringResource(R.string.cd_nav_left),
      testTag = "nav_left_button",
      onPressStart = { controller.startRepeatNavigate(NavDirection.LEFT) },
      onPressEnd = controller::stopRepeatingAction,
      modifier = Modifier.weight(1f),
    )

    // Down
    RepeatableNavKey(
      icon = Icons.Rounded.KeyboardArrowDown,
      contentDescription = stringResource(R.string.cd_nav_down),
      testTag = "nav_down_button",
      onPressStart = { controller.startRepeatNavigate(NavDirection.DOWN) },
      onPressEnd = controller::stopRepeatingAction,
      modifier = Modifier.weight(1f),
    )

    // Up
    RepeatableNavKey(
      icon = Icons.Rounded.KeyboardArrowUp,
      contentDescription = stringResource(R.string.cd_nav_up),
      testTag = "nav_up_button",
      onPressStart = { controller.startRepeatNavigate(NavDirection.UP) },
      onPressEnd = controller::stopRepeatingAction,
      modifier = Modifier.weight(1f),
    )

    // Right
    RepeatableNavKey(
      icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
      contentDescription = stringResource(R.string.cd_nav_right),
      testTag = "nav_right_button",
      onPressStart = { controller.startRepeatNavigate(NavDirection.RIGHT) },
      onPressEnd = controller::stopRepeatingAction,
      modifier = Modifier.weight(1f),
    )

    // End of input
    TactileKeyButton(
      onClick = { controller.navigateToEdge(toEnd = true) },
      onLongClickTooltip = "Jump to end",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_nav_end),
      testTag = "nav_end_button",
      cornerRadius = 9.dp,
      minHeight = 42.dp,
      modifier = Modifier.weight(1f).height(42.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.LastPage,
        contentDescription = null,
        tint = palette.inkPrimary,
        modifier = Modifier.size(19.dp),
      )
    }

    // Select toggle
    TactileKeyButton(
      onClick = controller::toggleSelectMode,
      onLongClickTooltip = if (state.isSelectEnabled) "Selection extension ON" else "Extend selection",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_nav_select),
      testTag = "nav_select_toggle",
      backgroundColor = if (state.isSelectEnabled) targetColor else palette.keySurface,
      borderColor = if (state.isSelectEnabled) targetColor else palette.keyBorder,
      cornerRadius = 9.dp,
      minHeight = 42.dp,
      modifier = Modifier.weight(1.05f).height(42.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.SelectAll,
        contentDescription = null,
        tint = if (state.isSelectEnabled) palette.onNavigateAccent else palette.inkPrimary,
        modifier = Modifier.size(19.dp),
      )
    }
  }
}

@Composable
private fun RepeatableNavKey(
  icon: ImageVector,
  contentDescription: String,
  testTag: String,
  onPressStart: () -> Unit,
  onPressEnd: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val palette = VoxKeyThemeTokens.palette
  val haptics = LocalHapticFeedback.current
  val shape = RoundedCornerShape(9.dp)
  var isPressed by remember { mutableStateOf(false) }

  Box(
    modifier =
      modifier
        .height(42.dp)
        .defaultMinSize(minWidth = 36.dp, minHeight = 42.dp)
        .clip(shape)
        .background(if (isPressed) palette.traySurface else palette.keySurface, shape)
        .border(1.dp, palette.keyBorder, shape)
        .pointerInput(testTag) {
          awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            isPressed = true
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onPressStart()
            try {
              waitForUpOrCancellation()
            } finally {
              isPressed = false
              onPressEnd()
            }
          }
        }
        .semantics { this.contentDescription = contentDescription }
        .testTag(testTag),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = palette.inkPrimary,
      modifier = Modifier.size(20.dp),
    )
  }
}
