package com.example.ui.keyboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ime.KeyboardController
import com.example.ime.KeyboardUiState
import com.example.ui.theme.VoxKeyThemeTokens

@Composable
fun VoxKeyKeyboardRoot(
  state: KeyboardUiState,
  controller: KeyboardController,
  modifier: Modifier = Modifier,
) {
  val palette = VoxKeyThemeTokens.palette

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .background(palette.keyboardBackground)
        .navigationBarsPadding()
        .padding(top = 4.dp, bottom = 6.dp),
  ) {
    HorizontalDivider(color = palette.divider, thickness = 1.dp)

    // First-use dismissible hint OR transient tooltip / status / restricted partial preview
    val bannerMessage =
      when {
        state.tooltipText != null -> state.tooltipText
        state.statusMessage != null -> state.statusMessage
        state.commandPartialFeedback.isNotEmpty() && !state.settings.showCommandDevPanel ->
          "⌘ ${state.commandPartialFeedback}"
        state.livePartialAppPreview.isNotEmpty() -> state.livePartialAppPreview
        else -> null
      }

    AnimatedVisibility(
      visible = bannerMessage != null,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      bannerMessage?.let { msg ->
        val bg =
          when {
            state.statusMessage != null && state.statusIsError -> palette.errorBg
            state.tooltipText != null -> palette.tooltipBg
            else -> palette.traySurface
          }
        val fg =
          when {
            state.statusMessage != null && state.statusIsError -> palette.errorAccent
            state.tooltipText != null -> palette.tooltipInk
            else -> palette.inkPrimary
          }
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .padding(horizontal = 8.dp, vertical = 3.dp)
              .background(bg, RoundedCornerShape(8.dp))
              .padding(horizontal = 10.dp, vertical = 5.dp)
              .testTag("keyboard_status_banner"),
        ) {
          Text(
            text = msg,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }

    if (bannerMessage == null && !state.settings.firstUseHintDismissed && !state.isEmojiPickerExpanded) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .background(palette.traySurface, RoundedCornerShape(8.dp))
            .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("first_use_hint_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.hint_first_use),
          style = MaterialTheme.typography.labelSmall,
          color = palette.inkSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Box(
          modifier =
            Modifier.size(24.dp)
              .clip(CircleShape)
              .clickable(onClick = controller::dismissFirstUseHint)
              .semantics { contentDescription = "Dismiss hint" },
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = null,
            tint = palette.inkSecondary,
            modifier = Modifier.size(14.dp),
          )
        }
      }
    }

    if (state.isEmojiPickerExpanded) {
      EmojiPickerPanel(state = state, controller = controller)
    } else {
      if (state.settings.showCommandDevPanel) {
        CommandDevPanel(
          devState = state.commandDevState,
          onDismiss = controller::dismissCommandDevPanel,
        )
      }

      // Optional Fallback Editor (collapsed by default)
      AnimatedVisibility(
        visible = state.isFallbackExpanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        FallbackEditorPanel(state = state, controller = controller)
      }

      // Compact Toolbar
      CompactToolbar(state = state, controller = controller)

      // Navigation Row (only visible in Navigate mode)
      AnimatedVisibility(
        visible = state.isNavigateMode,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        NavigationStrip(state = state, controller = controller)
      }

      // Main 5-Action Row
      MainControlRow(state = state, controller = controller)
    }
  }
}
