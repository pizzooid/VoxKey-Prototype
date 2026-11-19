package com.example.ui.keyboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.EmojiCategory
import com.example.data.EmojiItem
import com.example.ime.EmojiInputTarget
import com.example.ime.KeyboardController
import com.example.ime.KeyboardUiState
import com.example.ui.theme.VoxKeyThemeTokens

@Composable
fun EmojiPickerPanel(
  state: KeyboardUiState,
  controller: KeyboardController,
  modifier: Modifier = Modifier,
) {
  val palette = VoxKeyThemeTokens.palette
  val mostUsed =
    remember(state.emojiItems) {
      state.emojiItems
        .filter { it.usageCount > 0 }
        .sortedWith(compareByDescending<EmojiItem> { it.usageCount }.thenByDescending { it.lastUsedAt })
        .take(12)
    }

  val isSearching = state.emojiSearchQuery.isNotBlank()
  val visibleEmojis =
    remember(state.emojiItems, state.emojiSearchQuery, state.selectedEmojiCategory) {
      if (isSearching) {
        state.emojiItems.filter { it.matches(state.emojiSearchQuery) }
      } else {
        state.emojiItems.filter { it.category == state.selectedEmojiCategory }
      }
    }

  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    // 1. Search bar + close
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier =
          Modifier.weight(1f)
            .height(42.dp)
            .background(palette.keySurface, RoundedCornerShape(10.dp))
            .border(
              1.dp,
              if (state.activeEmojiTextTarget == EmojiInputTarget.SEARCH) palette.navigateAccent
              else palette.keyBorder,
              RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.Search,
          contentDescription = null,
          tint = palette.inkSecondary,
          modifier = Modifier.size(18.dp),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
          if (state.emojiSearchQuery.isEmpty()) {
            Text(
              text = "Search emoji or alias",
              style = MaterialTheme.typography.bodyMedium,
              color = palette.inkSecondary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          CompositionLocalProvider(LocalTextInputService provides null) {
            BasicTextField(
              value = state.emojiSearchQuery,
              onValueChange = controller::setEmojiSearchQuery,
              singleLine = true,
              textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.inkPrimary),
              cursorBrush = SolidColor(palette.navigateAccent),
              modifier =
                Modifier.fillMaxWidth()
                  .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                      controller.setEmojiTextTarget(EmojiInputTarget.SEARCH)
                    }
                  }
                  .testTag("emoji_search_input"),
            )
          }
        }
        if (state.emojiSearchQuery.isNotEmpty()) {
          Box(
            modifier =
              Modifier.size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = { controller.setEmojiSearchQuery("") })
                .semantics { contentDescription = "Clear search" }
                .testTag("emoji_search_clear"),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = null,
              tint = palette.inkSecondary,
              modifier = Modifier.size(15.dp),
            )
          }
        }
        Box(
          modifier =
            Modifier.size(30.dp)
              .clip(CircleShape)
              .background(
                if (state.isListening && state.activeEmojiTextTarget == EmojiInputTarget.SEARCH)
                  palette.dictateAccent
                else palette.traySurface,
                CircleShape,
              )
              .clickable { controller.startDictateForEmojiInput(EmojiInputTarget.SEARCH) }
              .semantics { contentDescription = "Dictate emoji search" }
              .testTag("emoji_search_mic"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = null,
            tint =
              if (state.isListening && state.activeEmojiTextTarget == EmojiInputTarget.SEARCH)
                palette.onDictateAccent
              else palette.inkSecondary,
            modifier = Modifier.size(16.dp),
          )
        }
      }

      Box(
        modifier =
          Modifier.size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.keySurface, RoundedCornerShape(10.dp))
            .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = controller::closeEmojiPicker)
            .semantics { contentDescription = "Close emoji picker" }
            .testTag("emoji_close_button"),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Close,
          contentDescription = stringResource(R.string.cd_close_emoji),
          tint = palette.inkPrimary,
          modifier = Modifier.size(19.dp),
        )
      }
    }

    // Inline custom alias editor when user long-pressed an emoji
    state.editingAliasEmoji?.let { item ->
      InlineAliasEditor(
        item = item,
        draft = state.aliasDraft,
        isListening = state.isListening && state.activeEmojiTextTarget == EmojiInputTarget.ALIAS,
        onDraftChange = controller::updateAliasDraft,
        onFocusAlias = { controller.setEmojiTextTarget(EmojiInputTarget.ALIAS) },
        onDictateAlias = { controller.startDictateForEmojiInput(EmojiInputTarget.ALIAS) },
        onSave = controller::saveCustomAlias,
        onReset = controller::resetCustomAlias,
        onClose = controller::closeAliasEditor,
      )
    }

    // 2. Most-used emojis
    if (!isSearching && mostUsed.isNotEmpty()) {
      LazyRow(
        modifier =
          Modifier.fillMaxWidth()
            .background(palette.traySurface, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        item {
          Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = "Most used",
            tint = palette.dictateAccent,
            modifier = Modifier.padding(horizontal = 4.dp).size(15.dp),
          )
        }
        items(mostUsed, key = { "recent_${it.emoji}" }) { item ->
          EmojiCell(
            item = item,
            onTap = { controller.onEmojiTapped(item) },
            onLongPress = { controller.openAliasEditor(item) },
          )
        }
      }
    }

    // 3. Categories + Grid
    if (!isSearching) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        EmojiCategory.entries.forEach { category ->
          val selected = category == state.selectedEmojiCategory
          Box(
            modifier =
              Modifier.height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                  if (selected) palette.navigateActiveBg else palette.keySurface,
                  RoundedCornerShape(8.dp),
                )
                .border(
                  1.dp,
                  if (selected) palette.navigateAccent else palette.keyBorder,
                  RoundedCornerShape(8.dp),
                )
                .clickable { controller.selectEmojiCategory(category) }
                .padding(horizontal = 10.dp)
                .testTag("emoji_cat_${category.name.lowercase()}"),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "${category.iconEmoji} ${category.label}",
              style = MaterialTheme.typography.labelSmall,
              color = if (selected) palette.navigateAccent else palette.inkPrimary,
            )
          }
        }
      }
    }

    val gridHeight = if (state.activeEmojiTextTarget != null) 118.dp else 178.dp
    if (visibleEmojis.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxWidth().height(gridHeight),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "No matching emojis",
          style = MaterialTheme.typography.bodyMedium,
          color = palette.inkSecondary,
        )
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        modifier = Modifier.fillMaxWidth().height(gridHeight),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        items(visibleEmojis, key = { it.emoji }) { item ->
          EmojiCell(
            item = item,
            onTap = { controller.onEmojiTapped(item) },
            onLongPress = { controller.openAliasEditor(item) },
          )
        }
      }
    }

    // Built-in compact QWERTY pad when typing into Emoji Search or Custom Alias inside the IME
    if (state.activeEmojiTextTarget != null) {
      MiniQwertyPad(
        onChar = controller::appendToEmojiTextInput,
        onBackspace = controller::backspaceEmojiTextInput,
        onDone = {
          if (state.activeEmojiTextTarget == EmojiInputTarget.ALIAS) {
            controller.saveCustomAlias()
          } else {
            controller.setEmojiTextTarget(null)
          }
        },
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(
  item: EmojiItem,
  onTap: () -> Unit,
  onLongPress: () -> Unit,
) {
  val palette = VoxKeyThemeTokens.palette
  val haptics = LocalHapticFeedback.current
  val hasAlias = !item.customAlias.isNullOrBlank()

  Box(
    modifier =
      Modifier.defaultMinSize(minWidth = 42.dp, minHeight = 42.dp)
        .clip(RoundedCornerShape(8.dp))
        .then(
          if (hasAlias) {
            Modifier.border(1.dp, palette.dictateAccent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
          } else {
            Modifier
          },
        )
        .combinedClickable(
          role = Role.Button,
          onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onTap()
          },
          onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongPress()
          },
        )
        .semantics {
          contentDescription = item.customAlias?.let { "${item.defaultName}, $it" } ?: item.defaultName
        }
        .testTag("emoji_cell_${item.emoji}"),
    contentAlignment = Alignment.Center,
  ) {
    Text(text = item.emoji, fontSize = 22.sp)
  }
}

@Composable
private fun InlineAliasEditor(
  item: EmojiItem,
  draft: String,
  isListening: Boolean,
  onDraftChange: (String) -> Unit,
  onFocusAlias: () -> Unit,
  onDictateAlias: () -> Unit,
  onSave: () -> Unit,
  onReset: () -> Unit,
  onClose: () -> Unit,
) {
  val palette = VoxKeyThemeTokens.palette
  val shape = RoundedCornerShape(10.dp)

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .background(palette.traySurface, shape)
        .border(1.dp, palette.keyBorder, shape)
        .padding(8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(text = item.emoji, fontSize = 22.sp)
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.defaultName,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
          color = palette.inkPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Box(
        modifier =
          Modifier.size(30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClose)
            .semantics { contentDescription = "Cancel alias edit" },
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Close,
          contentDescription = null,
          tint = palette.inkSecondary,
          modifier = Modifier.size(16.dp),
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier =
          Modifier.weight(1f)
            .height(38.dp)
            .background(palette.keySurface, RoundedCornerShape(8.dp))
            .border(1.dp, palette.navigateAccent, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
          if (draft.isEmpty()) {
            Text(
              text = "Custom name...",
              style = MaterialTheme.typography.bodyMedium,
              color = palette.inkSecondary,
            )
          }
          CompositionLocalProvider(LocalTextInputService provides null) {
            BasicTextField(
              value = draft,
              onValueChange = onDraftChange,
              singleLine = true,
              textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.inkPrimary),
              cursorBrush = SolidColor(palette.navigateAccent),
              modifier =
                Modifier.fillMaxWidth()
                  .onFocusChanged { if (it.isFocused) onFocusAlias() }
                  .testTag("emoji_alias_input"),
            )
          }
        }
        Box(
          modifier =
            Modifier.size(28.dp)
              .clip(CircleShape)
              .background(
                if (isListening) palette.dictateAccent else palette.traySurface,
                CircleShape,
              )
              .clickable(onClick = onDictateAlias)
              .semantics { contentDescription = "Dictate custom alias" },
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = null,
            tint = if (isListening) palette.onDictateAccent else palette.inkSecondary,
            modifier = Modifier.size(15.dp),
          )
        }
      }

      if (!item.customAlias.isNullOrBlank()) {
        Box(
          modifier =
            Modifier.size(38.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(palette.keySurface, RoundedCornerShape(8.dp))
              .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
              .clickable(onClick = onReset)
              .semantics { contentDescription = "Reset custom alias" }
              .testTag("emoji_alias_reset"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.RestartAlt,
            contentDescription = null,
            tint = palette.inkSecondary,
            modifier = Modifier.size(18.dp),
          )
        }
      }

      Box(
        modifier =
          Modifier.size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.applyAccent, RoundedCornerShape(8.dp))
            .clickable(onClick = onSave)
            .semantics { contentDescription = "Save custom alias" }
            .testTag("emoji_alias_save"),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Check,
          contentDescription = null,
          tint = palette.onApplyAccent,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

@Composable
private fun MiniQwertyPad(
  onChar: (String) -> Unit,
  onBackspace: () -> Unit,
  onDone: () -> Unit,
) {
  val palette = VoxKeyThemeTokens.palette
  val rows =
    listOf(
      listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
      listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
      listOf("z", "x", "c", "v", "b", "n", "m"),
    )

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .background(palette.traySurface, RoundedCornerShape(10.dp))
        .padding(4.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    rows.forEachIndexed { idx, row ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (idx == 1) Spacer(Modifier.width(12.dp))
        if (idx == 2) Spacer(Modifier.width(4.dp))
        row.forEach { ch ->
          Box(
            modifier =
              Modifier.weight(1f)
                .height(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(palette.keySurface, RoundedCornerShape(6.dp))
                .clickable { onChar(ch) },
            contentAlignment = Alignment.Center,
          ) {
            Text(text = ch, style = MaterialTheme.typography.bodyMedium, color = palette.inkPrimary)
          }
        }
        if (idx == 1) Spacer(Modifier.width(12.dp))
        if (idx == 2) {
          Box(
            modifier =
              Modifier.weight(1.4f)
                .height(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(palette.keyElevated, RoundedCornerShape(6.dp))
                .clickable(onClick = onBackspace)
                .semantics { contentDescription = "Mini keyboard backspace" },
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.Backspace,
              contentDescription = null,
              tint = palette.inkPrimary,
              modifier = Modifier.size(16.dp),
            )
          }
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Box(
        modifier =
          Modifier.weight(3f)
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.keySurface, RoundedCornerShape(6.dp))
            .clickable { onChar(" ") }
            .semantics { contentDescription = "Space" },
        contentAlignment = Alignment.Center,
      ) {
        Text(text = "space", style = MaterialTheme.typography.labelSmall, color = palette.inkSecondary)
      }
      Box(
        modifier =
          Modifier.weight(1f)
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.applyAccent, RoundedCornerShape(6.dp))
            .clickable(onClick = onDone)
            .semantics { contentDescription = "Done typing" },
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Check,
          contentDescription = null,
          tint = palette.onApplyAccent,
          modifier = Modifier.size(16.dp),
        )
      }
    }
  }
}
