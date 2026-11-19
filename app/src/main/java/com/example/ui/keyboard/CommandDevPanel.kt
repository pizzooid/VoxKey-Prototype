package com.example.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ime.CommandDevPanelState
import com.example.ui.theme.VoxKeyThemeTokens

/**
 * Temporary developer readout for testing hold-to-command recognition, parsing, execution,
 * and release -> final transcript -> insertion timing on device.
 */
@Composable
fun CommandDevPanel(
  devState: CommandDevPanelState,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val palette = VoxKeyThemeTokens.palette
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 6.dp, vertical = 3.dp)
        .background(palette.traySurface, RoundedCornerShape(10.dp))
        .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp)
        .testTag("command_dev_panel"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(end = 26.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      DevRow(label = "Heard:", value = devState.heard)
      DevRow(label = "Parsed:", value = devState.parsed)
      DevRow(label = "Outcome:", value = devState.outcome)
      DevRow(label = "Timing:", value = devState.timing)
    }

    Box(
      modifier =
        Modifier.align(Alignment.TopEnd)
          .size(22.dp)
          .clip(CircleShape)
          .clickable(role = Role.Button, onClick = onDismiss)
          .semantics { contentDescription = "Hide command developer panel" }
          .testTag("command_dev_dismiss_button"),
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

@Composable
private fun DevRow(
  label: String,
  value: String,
) {
  val palette = VoxKeyThemeTokens.palette
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = palette.inkSecondary,
      modifier = Modifier.width(64.dp),
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium,
      color = palette.inkPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}
