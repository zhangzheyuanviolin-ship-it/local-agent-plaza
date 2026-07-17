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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.DataStoreRepository

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgnesOmniConfigDialog(
  dataStoreRepository: DataStoreRepository,
  onDismiss: () -> Unit,
) {
  val initial = readAgnesOmniConfig(dataStoreRepository)
  var imageModel by remember { mutableStateOf(initial.imageModel) }
  var imageSize by remember { mutableStateOf(initial.imageSize) }
  var imageRatio by remember { mutableStateOf(initial.imageRatio) }
  var videoDuration by remember { mutableStateOf(initial.videoDuration) }
  var videoResolution by remember { mutableStateOf(initial.videoResolution) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Agnes 全模态工具箱设置") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          "模型调用时只需要传入提示词；图片模型、尺寸、比例、视频时长和分辨率由这里统一控制。",
          style = MaterialTheme.typography.bodySmall,
        )
        AgnesChoiceSection(
          title = "图片模型",
          selectedValue = imageModel.value,
          options = AgnesImageModel.entries.map { it.value to it.label },
          onSelected = { selected -> imageModel = AgnesImageModel.entries.first { it.value == selected } },
        )
        AgnesChoiceSection(
          title = "图片尺寸",
          selectedValue = imageSize.value,
          options = AgnesImageSize.entries.map { it.value to it.label },
          onSelected = { selected -> imageSize = AgnesImageSize.entries.first { it.value == selected } },
        )
        AgnesChoiceSection(
          title = "图片比例",
          selectedValue = imageRatio.value,
          options = AgnesImageRatio.entries.map { it.value to it.label },
          onSelected = { selected -> imageRatio = AgnesImageRatio.entries.first { it.value == selected } },
        )
        AgnesChoiceSection(
          title = "视频时长",
          selectedValue = videoDuration.value,
          options = AgnesVideoDuration.entries.map { it.value to it.label },
          onSelected = { selected ->
            videoDuration = AgnesVideoDuration.entries.first { it.value == selected }
          },
        )
        AgnesChoiceSection(
          title = "视频分辨率",
          selectedValue = videoResolution.value,
          options = AgnesVideoResolution.entries.map { it.value to it.label },
          onSelected = { selected ->
            videoResolution = AgnesVideoResolution.entries.first { it.value == selected }
          },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          saveAgnesOmniConfig(
            dataStoreRepository,
            AgnesOmniConfig(
              imageModel = imageModel,
              imageSize = imageSize,
              imageRatio = imageRatio,
              videoDuration = videoDuration,
              videoResolution = videoResolution,
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
private fun AgnesChoiceSection(
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
