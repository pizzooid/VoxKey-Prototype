package com.example.ui.keyboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.VoxKeyThemeTokens

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TactileKeyButton(
  onClick: () -> Unit,
  onLongClickTooltip: String,
  onShowTooltip: (String) -> Unit,
  contentDescription: String,
  testTag: String,
  modifier: Modifier = Modifier,
  backgroundColor: Color = VoxKeyThemeTokens.palette.keySurface,
  borderColor: Color = VoxKeyThemeTokens.palette.keyBorder,
  cornerRadius: Dp = 12.dp,
  minWidth: Dp = 48.dp,
  minHeight: Dp = 48.dp,
  content: @Composable BoxScope.() -> Unit,
) {
  val haptics = LocalHapticFeedback.current
  val shape = RoundedCornerShape(cornerRadius)
  Box(
    modifier =
      modifier
        .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
        .clip(shape)
        .background(backgroundColor, shape)
        .border(1.dp, borderColor, shape)
        .combinedClickable(
          role = Role.Button,
          onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
          },
          onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onShowTooltip(onLongClickTooltip)
          },
        )
        .semantics { this.contentDescription = contentDescription }
        .testTag(testTag),
    contentAlignment = Alignment.Center,
    content = content,
  )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolbarIconButton(
  onClick: () -> Unit,
  tooltip: String,
  onShowTooltip: (String) -> Unit,
  contentDescription: String,
  testTag: String,
  modifier: Modifier = Modifier,
  isActive: Boolean = false,
  activeBg: Color = VoxKeyThemeTokens.palette.navigateActiveBg,
  enabled: Boolean = true,
  content: @Composable BoxScope.() -> Unit,
) {
  val haptics = LocalHapticFeedback.current
  val shape = RoundedCornerShape(10.dp)
  val bg = if (isActive) activeBg else Color.Transparent
  Box(
    modifier =
      modifier
        .defaultMinSize(minWidth = 40.dp, minHeight = 44.dp)
        .clip(shape)
        .background(bg, shape)
        .combinedClickable(
          enabled = enabled,
          role = Role.Button,
          onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
          },
          onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onShowTooltip(tooltip)
          },
        )
        .semantics { this.contentDescription = contentDescription }
        .testTag(testTag),
    contentAlignment = Alignment.Center,
    content = content,
  )
}
