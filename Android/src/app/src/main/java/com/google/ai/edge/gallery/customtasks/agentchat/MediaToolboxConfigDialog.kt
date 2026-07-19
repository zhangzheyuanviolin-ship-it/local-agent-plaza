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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.DataStoreRepository

@Composable
fun MediaToolboxConfigDialog(
  dataStoreRepository: DataStoreRepository,
  onDismiss: () -> Unit,
) {
  val initial = readMediaToolboxConfig(dataStoreRepository)
  var imageModeEnabled by remember { mutableStateOf(initial.imageModeEnabled) }
  var audioModeEnabled by remember { mutableStateOf(initial.audioModeEnabled) }
  var videoModeEnabled by remember { mutableStateOf(initial.videoModeEnabled) }
  var overwriteOutputs by remember { mutableStateOf(initial.overwriteOutputs) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("多媒体工具箱设置") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          "开启哪个模式，模型才会看到并调用对应的多媒体工具。所有输出默认保存到工作区 media 文件夹。",
          style = MaterialTheme.typography.bodySmall,
        )
        MediaToolboxSwitchRow(
          title = "图片模式",
          description = "图片信息、缩放、格式转换、图片转 5 秒视频。",
          checked = imageModeEnabled,
          onCheckedChange = { imageModeEnabled = it },
        )
        MediaToolboxSwitchRow(
          title = "音频模式",
          description = "音频信息、格式转换、拼接、裁切、混音和背景音乐循环。",
          checked = audioModeEnabled,
          onCheckedChange = { audioModeEnabled = it },
        )
        MediaToolboxSwitchRow(
          title = "视频模式",
          description = "视频信息、格式转换、拼接、裁切、提取音轨、静音和添加音频。",
          checked = videoModeEnabled,
          onCheckedChange = { videoModeEnabled = it },
        )
        MediaToolboxSwitchRow(
          title = "允许覆盖同名输出",
          description = "关闭时，若输出文件已存在，会自动追加时间戳生成新文件。",
          checked = overwriteOutputs,
          onCheckedChange = { overwriteOutputs = it },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          saveMediaToolboxConfig(
            dataStoreRepository,
            MediaToolboxConfig(
              imageModeEnabled = imageModeEnabled,
              audioModeEnabled = audioModeEnabled,
              videoModeEnabled = videoModeEnabled,
              overwriteOutputs = overwriteOutputs,
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

@Composable
private fun MediaToolboxSwitchRow(
  title: String,
  description: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}
