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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

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
  val isModelReady =
    model.name.isNotEmpty() && modelManagerUiState.isModelInitialized(model = model)
  val durationSeconds = parseMusicDurationSeconds(uiState.durationInput)
  val canGenerate =
    isModelReady &&
      !uiState.isGenerating &&
      uiState.prompt.isNotBlank() &&
      durationSeconds != null

  LaunchedEffect(uiState.isGenerating) { setAppBarControlsDisabled(uiState.isGenerating) }
  DisposableEffect(Unit) {
    onDispose {
      viewModel.stopPlayback()
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
    OutlinedTextField(
      value = uiState.prompt,
      onValueChange = viewModel::updatePrompt,
      enabled = !uiState.isGenerating,
      label = { Text("声音或音乐描述") },
      minLines = 4,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = uiState.durationInput,
      onValueChange = viewModel::updateDurationInput,
      enabled = !uiState.isGenerating,
      label = { Text("生成时长，单位秒") },
      supportingText = {
        Text(
          if (durationSeconds == null) {
            "请输入大于0的数字。"
          } else {
            "将请求生成 ${formatMusicDurationSeconds(durationSeconds)} 秒音频；页面不设置固定上限。"
          }
        )
      },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Button(
      onClick = { viewModel.generate(model) },
      enabled = canGenerate,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(if (uiState.isGenerating) "正在生成" else "生成音频")
    }
    if (uiState.isGenerating) {
      LinearProgressIndicator(progress = uiState.progress, modifier = Modifier.fillMaxWidth())
    }
    if (uiState.statusText.isNotBlank()) {
      Text(
        text = uiState.statusText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    uiState.result?.let { result ->
      Text(
        text = "已生成 ${formatMusicDurationSeconds(result.durationSeconds)} 秒WAV音频。",
        style = MaterialTheme.typography.titleSmall,
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        FilledTonalButton(onClick = viewModel::togglePlayback, enabled = !uiState.isGenerating) {
          Icon(
            if (uiState.isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            contentDescription = null,
          )
          Text(if (uiState.isPlaying) "停止" else "播放", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
          onClick = viewModel::saveResult,
          enabled = !uiState.isGenerating,
          modifier = Modifier.width(96.dp),
        ) {
          Icon(Icons.Outlined.Save, contentDescription = null)
          Text("保存", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
          onClick = viewModel::shareResult,
          enabled = !uiState.isGenerating,
          modifier = Modifier.width(96.dp),
        ) {
          Icon(Icons.Outlined.Share, contentDescription = null)
          Text("分享", modifier = Modifier.padding(start = 8.dp))
        }
      }
    }
    if (!isModelReady) {
      Text("当前模型还未加载完成。", style = MaterialTheme.typography.bodyMedium)
    }
  }
}
