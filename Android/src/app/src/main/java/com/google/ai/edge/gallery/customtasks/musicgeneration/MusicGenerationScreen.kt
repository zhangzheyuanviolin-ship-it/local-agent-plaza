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

package com.google.ai.edge.gallery.customtasks.musicgeneration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlin.math.roundToInt

@Composable
fun MusicGenerationScreen(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  viewModel: MusicGenerationViewModel = hiltViewModel(),
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val uiState by viewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val spec = model.musicGenerationSpec()
  val isModelReady =
    model.name.isNotEmpty() && modelManagerUiState.isModelInitialized(model = model)
  val canGenerate =
    isModelReady && spec != null && !uiState.isGenerating && uiState.prompt.isNotBlank()

  LaunchedEffect(model.name) {
    if (model.name.isNotEmpty()) viewModel.ensureModelDefaults(model)
  }
  LaunchedEffect(uiState.isGenerating) { setAppBarControlsDisabled(uiState.isGenerating) }
  DisposableEffect(Unit) {
    onDispose {
      viewModel.stopPlayback(updateStatus = false)
      setAppBarControlsDisabled(false)
    }
  }

  Column(
    verticalArrangement = Arrangement.spacedBy(16.dp),
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding + 16.dp),
  ) {
    Text(
      text = model.displayName.ifBlank { "Box 本地音乐" },
      style = MaterialTheme.typography.headlineSmall,
    )
    if (model.info.isNotBlank()) {
      Text(text = model.info, style = MaterialTheme.typography.bodyMedium)
    }

    OutlinedTextField(
      value = uiState.prompt,
      onValueChange = viewModel::updatePrompt,
      enabled = !uiState.isGenerating,
      label = { Text("描述您想生成的音乐") },
      minLines = 4,
      modifier = Modifier.fillMaxWidth(),
    )

    if (spec != null) {
      Text(
        text = "生成时长：${formatMusicDurationSeconds(uiState.durationSeconds)} 秒",
        style = MaterialTheme.typography.titleSmall,
      )
      Slider(
        value = uiState.durationSeconds,
        onValueChange = { value -> viewModel.updateDurationSeconds(value.roundToInt().toFloat()) },
        valueRange = spec.minDurationSeconds..spec.maxDurationSeconds,
        steps =
          (spec.maxDurationSeconds.roundToInt() - spec.minDurationSeconds.roundToInt() - 1)
            .coerceAtLeast(0),
        enabled = !uiState.isGenerating,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    Text(text = "加速模式", style = MaterialTheme.typography.titleSmall)
    AccelerationChoice(
      label = "自动（推荐）",
      selected = uiState.accelerationMode == MusicAccelerationMode.AUTO,
      enabled = !uiState.isGenerating,
      onClick = { viewModel.updateAccelerationMode(MusicAccelerationMode.AUTO) },
    )
    AccelerationChoice(
      label = "CPU",
      selected = uiState.accelerationMode == MusicAccelerationMode.CPU,
      enabled = !uiState.isGenerating,
      onClick = { viewModel.updateAccelerationMode(MusicAccelerationMode.CPU) },
    )
    AccelerationChoice(
      label = "GPU",
      selected = uiState.accelerationMode == MusicAccelerationMode.GPU,
      enabled = !uiState.isGenerating,
      onClick = { viewModel.updateAccelerationMode(MusicAccelerationMode.GPU) },
    )

    Button(
      onClick = { viewModel.generate(model) },
      enabled = canGenerate,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(if (uiState.isGenerating) "正在生成" else "生成音乐")
    }

    if (uiState.isGenerating) {
      LinearProgressIndicator(progress = { uiState.progress }, modifier = Modifier.fillMaxWidth())
    }

    if (uiState.statusText.isNotBlank()) {
      Text(
        text = uiState.statusText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (uiState.result != null) {
      FilledTonalButton(
        onClick = viewModel::togglePlayback,
        enabled = !uiState.isGenerating,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(
          imageVector = if (uiState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
          contentDescription = null,
        )
        Text(
          text =
            when {
              uiState.isPlaying -> "暂停"
              uiState.isPaused -> "继续播放"
              else -> "播放"
            },
          modifier = Modifier.padding(start = 8.dp),
        )
      }

      OutlinedButton(
        onClick = viewModel::exportResult,
        enabled = !uiState.isGenerating,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Outlined.Save, contentDescription = null)
        Text("导出音频", modifier = Modifier.padding(start = 8.dp))
      }

      OutlinedButton(
        onClick = viewModel::shareResult,
        enabled = !uiState.isGenerating,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Outlined.Share, contentDescription = null)
        Text("分享音频", modifier = Modifier.padding(start = 8.dp))
      }
    }

    if (!isModelReady) {
      Text(
        text = "当前模型还未加载完成。请先在本地智能体广场的模型管理中下载并加载该模型。",
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Composable
private fun AccelerationChoice(
  label: String,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .clickable(enabled = enabled, onClick = onClick)
        .padding(vertical = 2.dp),
  ) {
    RadioButton(selected = selected, onClick = onClick, enabled = enabled)
    Text(text = label, modifier = Modifier.padding(start = 8.dp))
  }
}
