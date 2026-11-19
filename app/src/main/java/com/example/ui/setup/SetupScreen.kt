package com.example.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.RecordingMode
import com.example.data.ThemeMode
import com.example.speech.LanguageAvailability
import com.example.speech.SpeechLanguages
import com.example.ui.theme.VoxKeyThemeTokens
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(
  viewModel: SetupViewModel,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val palette = VoxKeyThemeTokens.palette

  val micPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      viewModel.refreshStatus()
    }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        viewModel.refreshStatus()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  var standardSandboxText by remember { mutableStateOf("") }
  var actionSandboxText by remember { mutableStateOf("") }
  var actionSandboxLog by remember { mutableStateOf("") }
  var terminalRef by remember { mutableStateOf<RestrictedTerminalView?>(null) }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = palette.keyboardBackground,
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 14.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "VoxKey",
              style = MaterialTheme.typography.headlineMedium,
              color = palette.inkPrimary,
            )
            Text(
              text = "On-device voice keyboard · local audio only",
              style = MaterialTheme.typography.bodyMedium,
              color = palette.inkSecondary,
            )
          }
          IconButton(
            onClick = { viewModel.refreshStatus() },
            modifier = Modifier.testTag("setup_refresh_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Refresh,
              contentDescription = "Refresh status",
              tint = palette.inkPrimary,
            )
          }
        }

        // 1. Activation Checklist
        SetupCard(title = "Keyboard & Permissions") {
          ChecklistRow(
            title = "1. Enable VoxKey in Android Settings",
            subtitle =
              if (state.systemStatus.isImeEnabled) "Enabled in system input methods"
              else "Required before selecting VoxKey",
            done = state.systemStatus.isImeEnabled,
            actionLabel = if (state.systemStatus.isImeEnabled) "Open" else "Enable",
            testTag = "setup_enable_ime_button",
            onAction = {
              context.startActivity(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              )
            },
          )

          HorizontalDivider(color = palette.divider)

          ChecklistRow(
            title = "2. Select VoxKey as Active Keyboard",
            subtitle =
              if (state.systemStatus.isImeSelected) "Active default input method"
              else "Switch to VoxKey in the system picker",
            done = state.systemStatus.isImeSelected,
            actionLabel = "Select",
            testTag = "setup_select_ime_button",
            onAction = {
              val imm =
                context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
              imm?.showInputMethodPicker()
            },
          )

          HorizontalDivider(color = palette.divider)

          ChecklistRow(
            title = "3. Microphone Permission",
            subtitle =
              if (state.systemStatus.hasMicPermission) "Granted for on-device recognition"
              else "Required for dictation & rolling pre-buffer",
            done = state.systemStatus.hasMicPermission,
            actionLabel = if (state.systemStatus.hasMicPermission) "Granted" else "Grant",
            testTag = "setup_mic_permission_button",
            onAction = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
          )
        }

        // 2. Recording & Display Preferences
        SetupCard(title = "Recording & Theme") {
          Text(
            text = "Dictation Activation",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = palette.inkPrimary,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            ModeChoiceChip(
              label = "Tap start / stop",
              selected = state.settings.recordingMode == RecordingMode.TAP_TO_TOGGLE,
              onClick = { viewModel.setRecordingMode(RecordingMode.TAP_TO_TOGGLE) },
              modifier = Modifier.weight(1f).testTag("pref_mode_tap"),
            )
            ModeChoiceChip(
              label = "Hold to record",
              selected = state.settings.recordingMode == RecordingMode.HOLD_TO_RECORD,
              onClick = { viewModel.setRecordingMode(RecordingMode.HOLD_TO_RECORD) },
              modifier = Modifier.weight(1f).testTag("pref_mode_hold"),
            )
          }

          Spacer(Modifier.height(4.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Audio Pre-Buffer",
              style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
              color = palette.inkPrimary,
            )
            Text(
              text = "${state.settings.preBufferMs} ms",
              style = MaterialTheme.typography.labelMedium,
              color = palette.dictateAccent,
            )
          }
          Slider(
            value = state.settings.preBufferMs.toFloat(),
            onValueChange = { raw ->
              val snapped = ((raw / 50f).roundToInt() * 50).coerceIn(500, 1000)
              viewModel.setPreBufferMs(snapped)
            },
            valueRange = 500f..1000f,
            steps = 9,
            colors =
              SliderDefaults.colors(
                thumbColor = palette.dictateAccent,
                activeTrackColor = palette.dictateAccent,
              ),
            modifier = Modifier.testTag("pref_prebuffer_slider"),
          )
          Text(
            text =
              if (state.systemStatus.isAudioPipeSupported) {
                "Android ${state.systemStatus.apiLevel}: Silent in-memory audio pre-buffer streams into on-device recognition on tap without idle recognition beeps."
              } else {
                "Device API ${state.systemStatus.apiLevel}: On-device recognition starts silently on tap without idle background beeps."
              },
            style = MaterialTheme.typography.labelSmall,
            color = palette.inkSecondary,
          )

          HorizontalDivider(color = palette.divider)

          Text(
            text = "Appearance",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = palette.inkPrimary,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            ThemeMode.entries.forEach { mode ->
              val label =
                when (mode) {
                  ThemeMode.SYSTEM -> "System"
                  ThemeMode.LIGHT -> "Light"
                  ThemeMode.DARK -> "Dark"
                }
              ModeChoiceChip(
                label = label,
                selected = state.settings.themeMode == mode,
                onClick = { viewModel.setThemeMode(mode) },
                modifier = Modifier.weight(1f).testTag("pref_theme_${mode.name.lowercase()}"),
              )
            }
          }

          HorizontalDivider(color = palette.divider)

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Command Developer Panel",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = palette.inkPrimary,
              )
              Text(
                text = "Shows Heard, Parsed, Outcome, and timing on the keyboard",
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkSecondary,
              )
            }
            Switch(
              checked = state.settings.showCommandDevPanel,
              onCheckedChange = { viewModel.setShowCommandDevPanel(it) },
              modifier = Modifier.testTag("pref_command_dev_panel_switch"),
            )
          }
        }

        // 3. On-Device Recognition Languages & Model Setup
        SetupCard(title = "On-Device Languages & Models") {
          if (!state.systemStatus.isOnDeviceApiSupported) {
            Text(
              text =
                if (state.systemStatus.apiLevel < 31)
                  "Android < 12 detected: uses EXTRA_PREFER_OFFLINE; strict on-device check requires Android 12+."
                else
                  "Device reports on-device SpeechRecognizer unavailable. Install or update Google Speech Services / offline packs in System Settings.",
              style = MaterialTheme.typography.bodyMedium,
              color = palette.errorAccent,
            )
          }

          state.modelFeedback?.let { msg ->
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .background(palette.navigateActiveBg, RoundedCornerShape(8.dp))
                  .padding(horizontal = 10.dp, vertical = 6.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = msg,
                style = MaterialTheme.typography.labelSmall,
                color = palette.navigateAccent,
                modifier = Modifier.weight(1f),
              )
              Text(
                text = "Dismiss",
                style = MaterialTheme.typography.labelSmall,
                color = palette.navigateAccent,
                modifier = Modifier.clickable { viewModel.clearFeedback() },
              )
            }
          }

          SpeechLanguages.catalog.forEach { lang ->
            val isEnabled = state.settings.enabledLanguages.contains(lang.tag)
            val isActive = state.settings.activeLanguage == lang.tag
            val availability =
              state.languageAvailability[lang.tag] ?: LanguageAvailability.Checking

            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Box(
                modifier =
                  Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (isActive) palette.dictateAccent else palette.traySurface)
                    .clickable { viewModel.setActiveLanguage(lang.tag) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
              ) {
                Text(
                  text = lang.shortCode,
                  style = MaterialTheme.typography.labelMedium,
                  color = if (isActive) palette.onDictateAccent else palette.inkPrimary,
                )
              }

              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = lang.displayName,
                  style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                  color = palette.inkPrimary,
                )
                val (statusLabel, statusColor) =
                  when (availability) {
                    LanguageAvailability.AvailableOnDevice ->
                      "Ready on device" to palette.navigateAccent
                    LanguageAvailability.DownloadRequired ->
                      "Download required" to palette.dictateAccent
                    LanguageAvailability.Downloading ->
                      "Downloading model..." to palette.dictateAccent
                    LanguageAvailability.Unavailable ->
                      "Unavailable on device" to palette.errorAccent
                    LanguageAvailability.LegacyOfflineFallback ->
                      "Legacy offline mode" to palette.inkSecondary
                    LanguageAvailability.Checking ->
                      "Checking support..." to palette.inkSecondary
                  }
                Text(
                  text = statusLabel,
                  style = MaterialTheme.typography.labelSmall,
                  color = statusColor,
                )
              }

              if (availability == LanguageAvailability.DownloadRequired) {
                IconButton(
                  onClick = { viewModel.triggerModelDownload(lang.tag) },
                  modifier = Modifier.testTag("download_lang_${lang.tag}"),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "Download ${lang.displayName} model",
                    tint = palette.dictateAccent,
                  )
                }
              }

              Switch(
                checked = isEnabled,
                onCheckedChange = { viewModel.toggleLanguageEnabled(lang.tag) },
              )
            }
          }
        }

        // 4. Built-in Dual Verification Sandbox
        SetupCard(title = "Prototype Verification Sandbox") {
          Text(
            text = "Standard Multiline Input",
            style = MaterialTheme.typography.labelSmall,
            color = palette.inkSecondary,
          )
          OutlinedTextField(
            value = standardSandboxText,
            onValueChange = { standardSandboxText = it },
            placeholder = { Text("Tap to test dictation, navigate, emojis, undo/redo, Import & Apply") },
            minLines = 3,
            maxLines = 5,
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.navigateAccent,
                unfocusedBorderColor = palette.keyBorder,
                focusedTextColor = palette.inkPrimary,
                unfocusedTextColor = palette.inkPrimary,
              ),
            modifier = Modifier.fillMaxWidth().testTag("sandbox_standard_field"),
          )

          Text(
            text = "Single-line Send Action Field",
            style = MaterialTheme.typography.labelSmall,
            color = palette.inkSecondary,
          )
          OutlinedTextField(
            value = actionSandboxText,
            onValueChange = { actionSandboxText = it },
            placeholder = { Text("Verifies Send icon on Enter key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions =
              KeyboardActions(
                onSend = {
                  if (actionSandboxText.isNotBlank()) {
                    actionSandboxLog = "Sent: $actionSandboxText"
                    actionSandboxText = ""
                  }
                },
              ),
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.navigateAccent,
                unfocusedBorderColor = palette.keyBorder,
                focusedTextColor = palette.inkPrimary,
                unfocusedTextColor = palette.inkPrimary,
              ),
            modifier = Modifier.fillMaxWidth().testTag("sandbox_send_field"),
          )
          if (actionSandboxLog.isNotEmpty()) {
            Text(
              text = actionSandboxLog,
              style = MaterialTheme.typography.labelSmall,
              color = palette.navigateAccent,
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Terminal,
                contentDescription = null,
                tint = palette.dictateAccent,
                modifier = Modifier.size(16.dp),
              )
              Text(
                text = "Restricted Terminal Sandbox (TYPE_NULL)",
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkSecondary,
              )
            }
            Text(
              text = "Clear",
              style = MaterialTheme.typography.labelSmall,
              color = palette.dictateAccent,
              modifier = Modifier.clickable { terminalRef?.clearTerminal() },
            )
          }

          val termInk = palette.inkPrimary.toArgb()
          val termAccent = palette.dictateAccent.toArgb()
          AndroidView(
            factory = { ctx ->
              RestrictedTerminalView(ctx).apply {
                setPaletteColors(termInk, termAccent)
                setPadding(24, 16, 24, 16)
                terminalRef = this
              }
            },
            update = { view -> view.setPaletteColors(termInk, termAccent) },
            modifier =
              Modifier.fillMaxWidth()
                .height(116.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.traySurface, RoundedCornerShape(10.dp))
                .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
                .testTag("sandbox_terminal_view"),
          )
        }

        Spacer(Modifier.height(16.dp))
      }
    }
  }
}

@Composable
private fun SetupCard(
  title: String,
  content: @Composable ColumnScope.() -> Unit,
) {
  val palette = VoxKeyThemeTokens.palette
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .background(palette.keySurface, RoundedCornerShape(16.dp))
        .border(1.dp, palette.keyBorder, RoundedCornerShape(16.dp))
        .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = palette.inkPrimary,
    )
    content()
  }
}

@Composable
private fun ChecklistRow(
  title: String,
  subtitle: String,
  done: Boolean,
  actionLabel: String,
  testTag: String,
  onAction: () -> Unit,
) {
  val palette = VoxKeyThemeTokens.palette
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
      imageVector = if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
      contentDescription = null,
      tint = if (done) palette.navigateAccent else palette.inkSecondary,
      modifier = Modifier.size(22.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = palette.inkPrimary,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = palette.inkSecondary,
      )
    }
    if (done && actionLabel == "Granted") {
      Box(
        modifier =
          Modifier.background(palette.navigateActiveBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag),
      ) {
        Text(
          text = actionLabel,
          style = MaterialTheme.typography.labelSmall,
          color = palette.navigateAccent,
        )
      }
    } else {
      Button(
        onClick = onAction,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = if (done) palette.traySurface else palette.applyAccent,
            contentColor = if (done) palette.inkPrimary else palette.onApplyAccent,
          ),
        modifier = Modifier.testTag(testTag),
      ) {
        Text(text = actionLabel, style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}

@Composable
private fun ModeChoiceChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val palette = VoxKeyThemeTokens.palette
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
      )
    },
    colors =
      FilterChipDefaults.filterChipColors(
        selectedContainerColor = palette.dictateActiveBg,
        selectedLabelColor = palette.dictateAccent,
        containerColor = palette.traySurface,
        labelColor = palette.inkSecondary,
      ),
    border =
      FilterChipDefaults.filterChipBorder(
        enabled = true,
        selected = selected,
        borderColor = palette.keyBorder,
        selectedBorderColor = palette.dictateAccent,
      ),
    modifier = modifier,
  )
}
