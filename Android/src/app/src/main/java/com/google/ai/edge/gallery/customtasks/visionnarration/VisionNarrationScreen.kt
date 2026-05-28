/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.customtasks.visionnarration

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private val VISION_INTERVALS = listOf(1, 2, 3, 5, 10)

@Composable
fun VisionNarrationScreen(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  viewModel: VisionNarrationViewModel = hiltViewModel(),
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val uiState by viewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val canUseModel =
    model.name.isNotEmpty() &&
      model.llmSupportImage &&
      modelManagerUiState.isModelInitialized(model = model)
  val previewLabel = stringResource(R.string.vision_narration_preview_label)
  val statusLabel = stringResource(R.string.vision_narration_status_label)
  val noModelStatus = stringResource(R.string.vision_narration_status_no_model)
  val latestPlaceholder = stringResource(R.string.vision_narration_latest_placeholder)
  val latestTitle = stringResource(R.string.vision_narration_latest_title)
  val historyTitle = stringResource(R.string.vision_narration_history_title)
  val historyEmpty = stringResource(R.string.vision_narration_history_empty)
  val latestDescriptionText =
    uiState.latestStreamingDescription.ifBlank {
      uiState.latestCompletedDescription.ifBlank { latestPlaceholder }
    }
  val statusText = if (canUseModel) uiState.statusText else noModelStatus
  val statusContentDescription =
    stringResource(R.string.vision_narration_status_content_description, statusText)
  val latestContentDescription =
    stringResource(R.string.vision_narration_latest_content_description, latestDescriptionText)
  val settingsLocked = uiState.autoRunning || uiState.inProgress || uiState.isSpeaking

  var showPromptManager by rememberSaveable { mutableStateOf(false) }
  var promptDialogVisible by rememberSaveable { mutableStateOf(false) }
  var promptDialogEditingId by rememberSaveable { mutableStateOf<String?>(null) }
  var draftPromptTitle by rememberSaveable { mutableStateOf("") }
  var draftPromptBody by rememberSaveable { mutableStateOf("") }
  var promptToDelete by rememberSaveable { mutableStateOf<String?>(null) }

  LaunchedEffect(uiState.autoRunning, uiState.inProgress, uiState.isSpeaking) {
    setAppBarControlsDisabled(uiState.autoRunning || uiState.inProgress || uiState.isSpeaking)
  }

  DisposableEffect(model.name) {
    onDispose {
      viewModel.stopNarration(model.takeIf { it.name.isNotEmpty() })
      setAppBarControlsDisabled(false)
    }
  }

  if (promptDialogVisible) {
    val isEditing = promptDialogEditingId != null
    AlertDialog(
      onDismissRequest = {
        promptDialogVisible = false
        promptDialogEditingId = null
      },
      confirmButton = {
        FilledTonalButton(
          onClick = {
            if (isEditing) {
              viewModel.updatePromptPreset(
                id = requireNotNull(promptDialogEditingId),
                title = draftPromptTitle,
                prompt = draftPromptBody,
              )
            } else {
              viewModel.addPromptPreset(title = draftPromptTitle, prompt = draftPromptBody)
            }
            promptDialogVisible = false
            promptDialogEditingId = null
          },
          enabled = draftPromptTitle.isNotBlank() && draftPromptBody.isNotBlank(),
        ) {
          Text(
            text =
              if (isEditing) {
                stringResource(R.string.vision_narration_prompt_save)
              } else {
                stringResource(R.string.vision_narration_prompt_create)
              }
          )
        }
      },
      dismissButton = {
        OutlinedButton(
          onClick = {
            promptDialogVisible = false
            promptDialogEditingId = null
          }
        ) {
          Text(stringResource(R.string.common_cancel))
        }
      },
      title = {
        Text(
          text =
            if (isEditing) {
              stringResource(R.string.vision_narration_prompt_edit_title)
            } else {
              stringResource(R.string.vision_narration_prompt_create_title)
            }
        )
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(
            value = draftPromptTitle,
            onValueChange = { draftPromptTitle = it },
            label = { Text(stringResource(R.string.vision_narration_prompt_name_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
          )
          OutlinedTextField(
            value = draftPromptBody,
            onValueChange = { draftPromptBody = it },
            label = { Text(stringResource(R.string.vision_narration_prompt_content_label)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
          )
        }
      },
    )
  }

  if (promptToDelete != null) {
    AlertDialog(
      onDismissRequest = { promptToDelete = null },
      confirmButton = {
        FilledTonalButton(
          onClick = {
            viewModel.deletePromptPreset(requireNotNull(promptToDelete))
            promptToDelete = null
          }
        ) {
          Text(stringResource(R.string.common_delete))
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { promptToDelete = null }) {
          Text(stringResource(R.string.common_cancel))
        }
      },
      title = { Text(stringResource(R.string.vision_narration_prompt_delete_title)) },
      text = { Text(stringResource(R.string.vision_narration_prompt_delete_message)) },
    )
  }

  if (showPromptManager) {
    VisionPromptManagerPage(
      bottomPadding = bottomPadding,
      uiState = uiState,
      selectedPromptDisplayName = viewModel.getSelectedPromptDisplayName(),
      defaultPrompt = viewModel.getDefaultPrompt(),
      onBack = { showPromptManager = false },
      onUseDefault = { viewModel.selectPromptPreset(null) },
      onSelectPrompt = { presetId -> viewModel.selectPromptPreset(presetId) },
      onCreatePrompt = {
        promptDialogEditingId = null
        draftPromptTitle = ""
        draftPromptBody = ""
        promptDialogVisible = true
      },
      onEditPrompt = { preset ->
        promptDialogEditingId = preset.id
        draftPromptTitle = preset.title
        draftPromptBody = preset.prompt
        promptDialogVisible = true
      },
      onDeletePrompt = { preset -> promptToDelete = preset.id },
    )
    return
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp)
        .padding(top = 12.dp, bottom = bottomPadding + 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
      text = stringResource(R.string.vision_narration_page_intro),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Card(
      modifier =
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
          contentDescription = previewLabel
        },
      shape = RoundedCornerShape(24.dp),
    ) {
      LiveCameraView(
        onBitmap = { bitmap, imageProxy ->
          val accepted = viewModel.canCaptureFrame()
          if (accepted) {
            val snapshot = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            imageProxy.close()
            viewModel.onCapturedFrame(model = model, bitmap = snapshot)
          } else {
            imageProxy.close()
          }
        },
        modifier = Modifier.fillMaxWidth().height(300.dp),
        preferredSize = 512,
        outputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888,
        renderPreview = true,
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
      )
    }

    Card(
      modifier =
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
          liveRegion = LiveRegionMode.Polite
          contentDescription = statusContentDescription
        },
      shape = RoundedCornerShape(20.dp),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = statusLabel,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = statusText,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
        )
      }
    }

    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = stringResource(R.string.vision_narration_prompt_section_title),
          style = MaterialTheme.typography.labelLarge,
        )
        Text(
          text =
            stringResource(
              R.string.vision_narration_prompt_selected_summary,
              viewModel.getSelectedPromptDisplayName(),
            ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text =
            viewModel.getSelectedPromptText().ifBlank { viewModel.getDefaultPrompt() },
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 4,
          overflow = TextOverflow.Ellipsis,
        )
        FilledTonalButton(
          onClick = { showPromptManager = true },
          enabled = !settingsLocked,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Outlined.Settings, contentDescription = null)
          Text(
            text = stringResource(R.string.vision_narration_prompt_manage_button),
            modifier = Modifier.padding(start = 8.dp),
          )
        }
      }
    }

    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.vision_narration_tts_title),
              style = MaterialTheme.typography.labelLarge,
            )
            Text(
              text = stringResource(R.string.vision_narration_tts_helper),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = uiState.autoSpeakEnabled,
            onCheckedChange = viewModel::updateAutoSpeakEnabled,
            enabled = !settingsLocked,
            modifier =
              Modifier.semantics {
                contentDescription = stringResource(R.string.vision_narration_tts_toggle)
              },
          )
        }
        if (uiState.autoSpeakEnabled) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            VisionSpeechMode.entries.forEach { speechMode ->
              FilterChip(
                selected = uiState.speechMode == speechMode,
                onClick = { viewModel.updateSpeechMode(speechMode) },
                enabled = !settingsLocked,
                label = {
                  Text(
                    text =
                      if (speechMode == VisionSpeechMode.AFTER_COMPLETE) {
                        stringResource(R.string.vision_narration_tts_mode_after_complete)
                      } else {
                        stringResource(R.string.vision_narration_tts_mode_during_generation)
                      }
                  )
                },
              )
            }
          }
        }
      }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = stringResource(R.string.vision_narration_interval_label),
        style = MaterialTheme.typography.labelLarge,
      )
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        for (seconds in VISION_INTERVALS) {
          FilterChip(
            selected = uiState.intervalSeconds == seconds,
            onClick = { viewModel.updateIntervalSeconds(seconds) },
            label = {
              Text(stringResource(R.string.vision_narration_interval_seconds, seconds))
            },
          )
        }
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      FilledTonalButton(
        onClick = {
          if (uiState.autoRunning || uiState.inProgress || uiState.isSpeaking) {
            viewModel.stopNarration(model.takeIf { it.name.isNotEmpty() })
          } else {
            viewModel.startAutoNarration()
          }
        },
        enabled = canUseModel,
        modifier = Modifier.weight(1f),
      ) {
        Text(
          if (uiState.autoRunning || uiState.inProgress || uiState.isSpeaking) {
            stringResource(R.string.vision_narration_stop)
          } else {
            stringResource(R.string.vision_narration_start)
          }
        )
      }

      OutlinedButton(
        onClick = viewModel::requestSingleCapture,
        enabled = canUseModel && !uiState.inProgress && !uiState.isSpeaking,
        modifier = Modifier.weight(1f),
      ) {
        Text(stringResource(R.string.vision_narration_single_capture))
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(
        onClick = viewModel::exportHistory,
        enabled = uiState.history.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Outlined.FileUpload, contentDescription = null)
        Text(
          text = stringResource(R.string.vision_narration_export_button),
          modifier = Modifier.padding(start = 8.dp),
        )
      }
    }

    Card(
      modifier =
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
          liveRegion = LiveRegionMode.Polite
          contentDescription = latestContentDescription
        },
      shape = RoundedCornerShape(20.dp),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = latestTitle,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
          Text(
            text = latestDescriptionText,
            style = MaterialTheme.typography.bodyLarge,
          )
        }
      }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        text = historyTitle,
        style = MaterialTheme.typography.titleMedium,
      )
      if (uiState.history.isEmpty()) {
        Text(
          text = historyEmpty,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        uiState.history.forEach { entry ->
          val timeText = viewModel.formatTimestamp(entry.timestampMs)
          val modeText =
            if (entry.mode == VisionCaptureMode.AUTO) {
              stringResource(R.string.vision_narration_capture_mode_auto)
            } else {
              stringResource(R.string.vision_narration_capture_mode_manual)
            }
          val historyContentDescription =
            stringResource(
              R.string.vision_narration_history_content_description,
              timeText,
              modeText,
              entry.description,
            )
          Card(
            modifier =
              Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = historyContentDescription
              },
            shape = RoundedCornerShape(18.dp),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = "$timeText  $modeText",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              SelectionContainer {
                Text(text = entry.description, style = MaterialTheme.typography.bodyLarge)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun VisionPromptManagerPage(
  bottomPadding: Dp,
  uiState: VisionNarrationUiState,
  selectedPromptDisplayName: String,
  defaultPrompt: String,
  onBack: () -> Unit,
  onUseDefault: () -> Unit,
  onSelectPrompt: (String) -> Unit,
  onCreatePrompt: () -> Unit,
  onEditPrompt: (VisionPromptPreset) -> Unit,
  onDeletePrompt: (VisionPromptPreset) -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp)
        .padding(top = 12.dp, bottom = bottomPadding + 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      OutlinedButton(onClick = onBack) {
        Icon(Icons.Outlined.ArrowBack, contentDescription = null)
        Text(
          text = stringResource(R.string.vision_narration_prompt_back),
          modifier = Modifier.padding(start = 8.dp),
        )
      }
      Text(
        text = stringResource(R.string.vision_narration_prompt_manager_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
    }

    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = stringResource(R.string.vision_narration_prompt_current_title),
          style = MaterialTheme.typography.labelLarge,
        )
        Text(
          text =
            stringResource(
              R.string.vision_narration_prompt_selected_summary,
              selectedPromptDisplayName,
            ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = stringResource(R.string.vision_narration_default_prompt_name),
          style = MaterialTheme.typography.labelLarge,
        )
        Text(text = defaultPrompt, style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = onUseDefault, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.vision_narration_prompt_use_default))
        }
      }
    }

    FilledTonalButton(onClick = onCreatePrompt, modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Outlined.Add, contentDescription = null)
      Text(
        text = stringResource(R.string.vision_narration_prompt_add_button),
        modifier = Modifier.padding(start = 8.dp),
      )
    }

    if (uiState.promptPresets.isEmpty()) {
      Text(
        text = stringResource(R.string.vision_narration_prompt_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      uiState.promptPresets.forEach { preset ->
        val selected = preset.id == uiState.selectedPromptId
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              text = preset.title,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Text(text = preset.prompt, style = MaterialTheme.typography.bodyMedium)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              FilledTonalButton(
                onClick = { onSelectPrompt(preset.id) },
                modifier = Modifier.weight(1f),
              ) {
                Text(
                  text =
                    if (selected) {
                      stringResource(R.string.vision_narration_prompt_selected)
                    } else {
                      stringResource(R.string.vision_narration_prompt_select)
                    }
                )
              }
              OutlinedButton(
                onClick = { onEditPrompt(preset) },
                modifier = Modifier.weight(1f),
              ) {
                Icon(Icons.Outlined.Edit, contentDescription = null)
                Text(
                  text = stringResource(R.string.common_edit),
                  modifier = Modifier.padding(start = 8.dp),
                )
              }
            }
            OutlinedButton(
              onClick = { onDeletePrompt(preset) },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Icon(Icons.Outlined.Delete, contentDescription = null)
              Text(
                text = stringResource(R.string.common_delete),
                modifier = Modifier.padding(start = 8.dp),
              )
            }
          }
        }
      }
    }
  }
}
