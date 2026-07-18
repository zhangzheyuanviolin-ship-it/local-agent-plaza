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

package com.google.ai.edge.gallery.customtasks.agentchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.DataStoreRepository

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MiniMaxOmniConfigDialog(
  dataStoreRepository: DataStoreRepository,
  onDismiss: () -> Unit,
) {
  val initial = readMiniMaxOmniConfig(dataStoreRepository)
  var apiHost by remember { mutableStateOf(initial.apiHost) }
  var textModel by remember { mutableStateOf(initial.textModel) }
  var textMaxTokens by remember { mutableIntStateOf(initial.textMaxTokens) }
  var ttsModel by remember { mutableStateOf(initial.ttsModel) }
  var voice by remember { mutableStateOf(initial.voice) }
  var imageRatio by remember { mutableStateOf(initial.imageRatio) }
  var musicMode by remember { mutableStateOf(initial.musicMode) }
  var visionModel by remember { mutableStateOf(initial.visionModel) }
  var videoModel by remember { mutableStateOf(initial.videoModel) }
  var mediaLimit by remember { mutableStateOf(initial.mediaLimit) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("MiniMax 全模态工具箱设置") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          "模型调用时只传入提示词、查询词或工作区文件路径；模型、音色、比例、音乐模式和媒体大小上限由这里统一控制。",
          style = MaterialTheme.typography.bodySmall,
        )
        MiniMaxChoiceSection(
          title = "API 端点",
          selectedValue = apiHost.value,
          options = MiniMaxApiHost.entries.map { it.value to it.label },
          onSelected = { selected -> apiHost = MiniMaxApiHost.entries.first { it.value == selected } },
        )
        MiniMaxChoiceSection(
          title = "文本模型",
          selectedValue = textModel.value,
          options = MiniMaxTextModel.entries.map { it.value to it.label },
          onSelected = { selected -> textModel = MiniMaxTextModel.entries.first { it.value == selected } },
        )
        OutlinedTextField(
          value = textMaxTokens.toString(),
          onValueChange = { textMaxTokens = it.filter(Char::isDigit).toIntOrNull()?.coerceIn(128, 4096) ?: 1024 },
          label = { Text("文本最大输出 token") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
        )
        MiniMaxChoiceSection(
          title = "语音模型",
          selectedValue = ttsModel.value,
          options = MiniMaxTtsModel.entries.map { it.value to it.label },
          onSelected = { selected -> ttsModel = MiniMaxTtsModel.entries.first { it.value == selected } },
        )
        MiniMaxChoiceSection(
          title = "语音音色",
          selectedValue = voice.value,
          options = MiniMaxVoice.entries.map { it.value to it.label },
          onSelected = { selected -> voice = MiniMaxVoice.entries.first { it.value == selected } },
        )
        MiniMaxChoiceSection(
          title = "图片比例",
          selectedValue = imageRatio.value,
          options = MiniMaxImageRatio.entries.map { it.value to it.label },
          onSelected = { selected -> imageRatio = MiniMaxImageRatio.entries.first { it.value == selected } },
        )
        MiniMaxChoiceSection(
          title = "音乐模式",
          selectedValue = musicMode.value,
          options = MiniMaxMusicMode.entries.map { it.value to it.label },
          onSelected = { selected -> musicMode = MiniMaxMusicMode.entries.first { it.value == selected } },
        )
        MiniMaxChoiceSection(
          title = "图片分析模型",
          selectedValue = visionModel.value,
          options = MiniMaxTextModel.entries.map { it.value to it.label },
          onSelected = { selected -> visionModel = MiniMaxTextModel.entries.first { it.value == selected } },
        )
        MiniMaxChoiceSection(
          title = "视频分析模型",
          selectedValue = videoModel.value,
          options = MiniMaxTextModel.entries.map { it.value to it.label },
          onSelected = { selected -> videoModel = MiniMaxTextModel.entries.first { it.value == selected } },
        )
        MiniMaxChoiceSection(
          title = "本地媒体读取上限",
          selectedValue = mediaLimit.value,
          options = MiniMaxMediaLimit.entries.map { it.value to it.label },
          onSelected = { selected -> mediaLimit = MiniMaxMediaLimit.entries.first { it.value == selected } },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          saveMiniMaxOmniConfig(
            dataStoreRepository,
            MiniMaxOmniConfig(
              apiHost = apiHost,
              textModel = textModel,
              textMaxTokens = textMaxTokens.coerceIn(128, 4096),
              ttsModel = ttsModel,
              voice = voice,
              imageRatio = imageRatio,
              musicMode = musicMode,
              visionModel = visionModel,
              videoModel = videoModel,
              mediaLimit = mediaLimit,
            ),
          )
          onDismiss()
        }
      ) {
        Text("保存")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MiniMaxChoiceSection(
  title: String,
  selectedValue: String,
  options: List<Pair<String, String>>,
  onSelected: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      options.forEach { (value, label) ->
        FilterChip(
          selected = selectedValue == value,
          onClick = { onSelected(value) },
          label = { Text(label) },
        )
      }
    }
  }
}
