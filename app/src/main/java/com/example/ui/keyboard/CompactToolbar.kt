package com.example.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ime.KeyboardController
import com.example.ime.KeyboardUiState
import com.example.speech.SpeechLanguages
import com.example.ui.theme.VoxKeyThemeTokens

@Composable
fun CompactToolbar(
  state: KeyboardUiState,
  controller: KeyboardController,
  modifier: Modifier = Modifier,
) {
  val palette = VoxKeyThemeTokens.palette
  val activeLangName = SpeechLanguages.find(state.settings.activeLanguage).displayName
  val undoEnabled = if (state.isTargetingFallback) state.canFallbackUndo else true
  val redoEnabled = if (state.isTargetingFallback) state.canFallbackRedo else true

  Row(
    modifier = modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // 1. Language switcher
    ToolbarIconButton(
      onClick = controller::onCycleLanguage,
      tooltip = "Language: $activeLangName",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_switch_language),
      testTag = "toolbar_language_button",
    ) {
      Box(
        modifier =
          Modifier.background(palette.keySurface, RoundedCornerShape(7.dp))
            .border(1.dp, palette.keyBorder, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = state.activeLanguageShort,
          style = MaterialTheme.typography.labelMedium,
          color = palette.inkPrimary,
        )
      }
    }

    // 2. Switch to user's normal keyboard
    ToolbarIconButton(
      onClick = controller::onSwitchKeyboard,
      tooltip = "Switch keyboard",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_switch_keyboard),
      testTag = "toolbar_switch_ime_button",
    ) {
      Icon(
        imageVector = Icons.Rounded.Keyboard,
        contentDescription = null,
        tint = palette.inkSecondary,
        modifier = Modifier.size(19.dp),
      )
    }

    // 3. Emoji picker
    ToolbarIconButton(
      onClick = controller::toggleEmojiPicker,
      tooltip = "Emoji picker",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_emoji_picker),
      testTag = "toolbar_emoji_button",
      isActive = state.isEmojiPickerExpanded,
      activeBg = palette.navigateActiveBg,
    ) {
      Icon(
        imageVector = Icons.Outlined.EmojiEmotions,
        contentDescription = null,
        tint = if (state.isEmojiPickerExpanded) palette.navigateAccent else palette.inkSecondary,
        modifier = Modifier.size(19.dp),
      )
    }

    // 4. Import text from app (downward arrow)
    ToolbarIconButton(
      onClick = controller::onImportFromApp,
      tooltip = "Import selection or field",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_import_text),
      testTag = "toolbar_import_button",
      isActive = state.hasImportedSnapshot,
      activeBg = palette.dictateActiveBg,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Rounded.ArrowDownward,
          contentDescription = null,
          tint = if (state.hasImportedSnapshot) palette.dictateAccent else palette.inkSecondary,
          modifier = Modifier.size(19.dp),
        )
        if (state.hasImportedSnapshot) {
          Box(
            modifier =
              Modifier.align(Alignment.TopEnd)
                .offset(x = 3.dp, y = (-2).dp)
                .size(6.dp)
                .background(palette.dictateAccent, CircleShape)
          )
        }
      }
    }

    // 5. Undo
    ToolbarIconButton(
      onClick = controller::onUndo,
      tooltip = if (state.isTargetingFallback) "Undo draft edit" else "Undo (Ctrl+Z)",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_undo),
      testTag = "toolbar_undo_button",
      enabled = undoEnabled,
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Rounded.Undo,
        contentDescription = null,
        tint = if (undoEnabled) palette.inkSecondary else palette.inkSecondary.copy(alpha = 0.32f),
        modifier = Modifier.size(19.dp),
      )
    }

    // 6. Redo
    ToolbarIconButton(
      onClick = controller::onRedo,
      tooltip = if (state.isTargetingFallback) "Redo draft edit" else "Redo (Ctrl+Shift+Z)",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_redo),
      testTag = "toolbar_redo_button",
      enabled = redoEnabled,
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Rounded.Redo,
        contentDescription = null,
        tint = if (redoEnabled) palette.inkSecondary else palette.inkSecondary.copy(alpha = 0.32f),
        modifier = Modifier.size(19.dp),
      )
    }

    // 7. Expand / collapse fallback editor
    ToolbarIconButton(
      onClick = controller::toggleFallbackExpanded,
      tooltip = if (state.isFallbackExpanded) "Collapse fallback editor" else "Expand fallback editor",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_toggle_fallback),
      testTag = "toolbar_fallback_toggle_button",
      isActive = state.isFallbackExpanded,
      activeBg = palette.keyElevated,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Rounded.EditNote,
          contentDescription = null,
          tint = if (state.isFallbackExpanded) palette.inkPrimary else palette.inkSecondary,
          modifier = Modifier.size(21.dp),
        )
        if (!state.isFallbackExpanded && state.fallbackText.isNotEmpty()) {
          Box(
            modifier =
              Modifier.align(Alignment.TopEnd)
                .offset(x = 2.dp, y = (-1).dp)
                .size(6.dp)
                .background(palette.dictateAccent, CircleShape)
          )
        }
      }
    }

    // 8. Settings
    ToolbarIconButton(
      onClick = controller::onOpenSettings,
      tooltip = "Settings & models",
      onShowTooltip = controller::showTooltip,
      contentDescription = stringResource(R.string.cd_open_settings),
      testTag = "toolbar_settings_button",
    ) {
      Icon(
        imageVector = Icons.Outlined.Settings,
        contentDescription = null,
        tint = palette.inkSecondary,
        modifier = Modifier.size(19.dp),
      )
    }
  }
}
