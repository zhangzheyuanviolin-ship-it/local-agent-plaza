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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.mergeDescendants
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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

  LaunchedEffect(uiState.autoRunning, uiState.inProgress) {
    setAppBarControlsDisabled(uiState.autoRunning || uiState.inProgress)
  }

  DisposableEffect(model.name) {
    onDispose {
      viewModel.stopNarration(model.takeIf { it.name.isNotEmpty() })
      setAppBarControlsDisabled(false)
    }
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
      Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

    OutlinedTextField(
      value = uiState.prompt,
      onValueChange = viewModel::updatePrompt,
      modifier = Modifier.fillMaxWidth(),
      label = { Text(stringResource(R.string.vision_narration_prompt_label)) },
      placeholder = { Text(stringResource(R.string.vision_narration_prompt_placeholder)) },
      supportingText = {
        Text(stringResource(R.string.vision_narration_prompt_helper, viewModel.getDefaultPrompt()))
      },
      minLines = 3,
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = stringResource(R.string.vision_narration_interval_label),
        style = MaterialTheme.typography.labelLarge,
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
          if (uiState.autoRunning || uiState.inProgress) {
            viewModel.stopNarration(model.takeIf { it.name.isNotEmpty() })
          } else {
            viewModel.startAutoNarration()
          }
        },
        enabled = canUseModel,
        modifier = Modifier.weight(1f),
      ) {
        Text(
          if (uiState.autoRunning || uiState.inProgress) {
            stringResource(R.string.vision_narration_stop)
          } else {
            stringResource(R.string.vision_narration_start)
          }
        )
      }

      OutlinedButton(
        onClick = viewModel::requestSingleCapture,
        enabled = canUseModel && !uiState.inProgress,
        modifier = Modifier.weight(1f),
      ) {
        Text(stringResource(R.string.vision_narration_single_capture))
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
      Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
