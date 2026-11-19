package com.example.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ime.KeyboardController
import com.example.ime.KeyboardUiState
import com.example.ui.theme.VoxKeyThemeTokens

/**
 * Compact escape-hatch editor without a heading. Uses LocalTextInputService = null
 * so tapping inside repositions selection without stealing the host app's InputConnection.
 */
@Composable
fun FallbackEditorPanel(
  state: KeyboardUiState,
  controller: KeyboardController,
  modifier: Modifier = Modifier,
) {
  val palette = VoxKeyThemeTokens.palette
  val borderColor =
    when {
      state.isCommandRecording || state.isCommandPendingFinal -> palette.commandAccent
      state.isListening -> palette.dictateAccent
      state.isNavigateMode -> palette.navigateAccent
      else -> palette.inkPrimary.copy(alpha = 0.35f)
    }
  val shape = RoundedCornerShape(12.dp)
  val textFieldValue =
    TextFieldValue(
      text = state.fallbackText,
      selection =
        TextRange(
          start = state.fallbackSelStart.coerceIn(0, state.fallbackText.length),
          end = state.fallbackSelEnd.coerceIn(0, state.fallbackText.length),
        ),
    )

  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 6.dp, vertical = 4.dp)
        .background(palette.keySurface, shape)
        .border(width = 1.5.dp, color = borderColor, shape = shape)
        .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
  ) {
    CompositionLocalProvider(LocalTextInputService provides null) {
      BasicTextField(
        value = textFieldValue,
        onValueChange = { updated ->
          controller.onFallbackEditorChanged(
            newText = updated.text,
            newSelStart = updated.selection.start,
            newSelEnd = updated.selection.end,
          )
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.inkPrimary),
        cursorBrush = SolidColor(if (state.isNavigateMode) palette.navigateAccent else palette.dictateAccent),
        modifier =
          Modifier.fillMaxWidth()
            .heightIn(min = 54.dp, max = 108.dp)
            .padding(end = 36.dp)
            .testTag("fallback_editor_input"),
      )
    }

    if (state.fallbackText.isNotEmpty()) {
      Box(
        modifier =
          Modifier.align(Alignment.TopEnd)
            .defaultMinSize(minWidth = 36.dp, minHeight = 36.dp)
            .clip(CircleShape)
            .background(palette.traySurface, CircleShape)
            .clickable(role = Role.Button, onClick = controller::clearFallbackText)
            .semantics { contentDescription = "Clear fallback text" }
            .testTag("fallback_clear_button"),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Close,
          contentDescription = stringResource(R.string.cd_clear_fallback),
          tint = palette.inkSecondary,
          modifier = Modifier.size(16.dp),
        )
      }
    }
  }
}
