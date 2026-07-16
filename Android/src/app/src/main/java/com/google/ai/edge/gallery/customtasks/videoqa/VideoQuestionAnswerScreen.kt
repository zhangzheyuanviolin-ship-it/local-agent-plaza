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

package com.google.ai.edge.gallery.customtasks.videoqa

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.util.Locale

@Composable
fun VideoQuestionAnswerScreen(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  viewModel: VideoQuestionAnswerViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val videoState by viewModel.videoUiState.collectAsState()
  val chatState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val downloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]?.status
  val modelReady =
    downloadStatus == ModelDownloadStatusType.SUCCEEDED &&
      modelManagerUiState.isModelInitialized(selectedModel)
  val controlsLocked = videoState.processingVideo || chatState.inProgress || chatState.preparing

  LaunchedEffect(controlsLocked) { setAppBarControlsDisabled(controlsLocked) }

  val galleryVideoPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      uri?.let {
        viewModel.processVideo(context = context, uri = it, label = videoLabel(it))
      }
    }
  val fileVideoPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let {
        runCatching {
          context.contentResolver.takePersistableUriPermission(
            it,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        }
        viewModel.processVideo(context = context, uri = it, label = videoLabel(it))
      }
    }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .imePadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp)
        .padding(top = 12.dp, bottom = bottomPadding + 24.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    VideoSettingsCard(videoState = videoState, viewModel = viewModel, controlsLocked = controlsLocked)

    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("视频来源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          "请选择 MP4 视频。相册入口适合系统媒体库中的视频，文件入口适合下载目录、网盘同步目录或其他文件夹中的视频。",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          Button(
            onClick = {
              galleryVideoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
              )
            },
            enabled = !controlsLocked,
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
            Text("相册视频", modifier = Modifier.padding(start = 6.dp))
          }
          OutlinedButton(
            onClick = { fileVideoPicker.launch(arrayOf("video/mp4", "video/*")) },
            enabled = !controlsLocked,
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Text("文件视频", modifier = Modifier.padding(start = 6.dp))
          }
        }
      }
    }

    StatusCard(videoState = videoState)
    FramePreviewCard(videoState = videoState)

    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("提问", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
          value = videoState.currentPrompt,
          onValueChange = viewModel::updatePrompt,
          modifier = Modifier.fillMaxWidth(),
          minLines = 3,
          label = { Text("输入您想询问视频的问题") },
          enabled = !controlsLocked,
        )
        Button(
          onClick = { viewModel.askVideo(selectedModel) },
          enabled = modelReady && !controlsLocked && videoState.frames.isNotEmpty(),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Outlined.VideoLibrary, contentDescription = null)
          Text("发送给本地视觉模型", modifier = Modifier.padding(start = 8.dp))
        }
        if (!modelReady) {
          Text(
            "当前模型仍在加载或未下载完成，模型初始化完成后才能发送。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    if (chatState.preparing || chatState.inProgress) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("本地模型正在分析视频画面帧。", style = MaterialTheme.typography.bodyMedium)
      }
    }

    if (videoState.currentAnswer.isNotBlank()) {
      Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("模型回答", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(videoState.currentAnswer, style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
  }
}

@Composable
private fun VideoSettingsCard(
  videoState: VideoQuestionAnswerUiState,
  viewModel: VideoQuestionAnswerViewModel,
  controlsLocked: Boolean,
) {
  Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Settings, contentDescription = null)
        Text("视频问答设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      }
      Text(
        "完整视频描述会按视频时长均匀抽帧；关键帧问答会按您输入的时间点抽帧。时间点支持纯秒数、小数秒和 mm:ss，例如 12、12.5、01:05。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = videoState.mode == VideoQaMode.COMPLETE_VIDEO,
          onClick = { viewModel.updateMode(VideoQaMode.COMPLETE_VIDEO) },
          enabled = !controlsLocked,
          label = { Text("完整视频描述") },
        )
        FilterChip(
          selected = videoState.mode == VideoQaMode.KEY_FRAMES,
          onClick = { viewModel.updateMode(VideoQaMode.KEY_FRAMES) },
          enabled = !controlsLocked,
          label = { Text("关键帧问答") },
        )
      }
      Text("抽帧分辨率上限：${videoState.frameSize}px")
      Slider(
        value = when (videoState.frameSize) {
          384 -> 0f
          512 -> 1f
          768 -> 2f
          else -> 3f
        },
        onValueChange = { index ->
          val sizes = listOf(384, 512, 768, 1024)
          viewModel.updateFrameSize(sizes[index.toInt().coerceIn(0, sizes.lastIndex)])
        },
        valueRange = 0f..3f,
        steps = 2,
        enabled = !controlsLocked,
      )
      if (videoState.mode == VideoQaMode.COMPLETE_VIDEO) {
        Text("抽取画面帧数量：${videoState.frameCount} 张")
        Slider(
          value = videoState.frameCount.toFloat(),
          onValueChange = { viewModel.updateFrameCount(it.toInt()) },
          valueRange = 1f..20f,
          steps = 18,
          enabled = !controlsLocked,
        )
        if (videoState.frameCount > 10 || videoState.frameSize >= 768) {
          Text(
            "当前设置可能明显增加内存占用。高内存设备可以尝试，普通设备建议保持 10 张以内和 512px。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      } else {
        Text(
          "请最多填写 5 个时间点。示例：输入 3 表示第 3 秒，输入 3.5 表示第 3.5 秒，输入 01:05 表示第 1 分 5 秒。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        videoState.keyFrameInputs.forEachIndexed { index, value ->
          OutlinedTextField(
            value = value,
            onValueChange = { viewModel.updateKeyFrameInput(index, it) },
            enabled = !controlsLocked,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            label = { Text("第 ${index + 1} 个时间点") },
            placeholder = { Text(if (index == 0) "例如 12.5 或 01:05" else "可留空") },
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
  }
}

@Composable
private fun StatusCard(videoState: VideoQuestionAnswerUiState) {
  Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (videoState.processingVideo) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
      }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(videoState.statusText, style = MaterialTheme.typography.bodyMedium)
        if (videoState.selectedVideoLabel.isNotBlank()) {
          Text(
            "当前视频：${videoState.selectedVideoLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (videoState.durationMs > 0L) {
          Text(
            "视频时长：${formatDuration(videoState.durationMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (videoState.errorText.isNotBlank()) {
          Text(videoState.errorText, color = MaterialTheme.colorScheme.error)
        }
      }
    }
  }
}

@Composable
private fun FramePreviewCard(videoState: VideoQuestionAnswerUiState) {
  if (videoState.frames.isEmpty()) return
  Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("已抽取画面帧", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        videoState.frames.forEachIndexed { index, frame ->
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
              bitmap = frame.bitmap.asImageBitmap(),
              contentDescription = "第 ${index + 1} 张视频画面帧",
              modifier = Modifier.height(96.dp),
            )
            Text(
              "${index + 1}: ${formatFrameTime(frame.timeUs)}s",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

private fun videoLabel(uri: Uri): String {
  return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: uri.toString()
}

private fun formatDuration(durationMs: Long): String {
  val totalSeconds = durationMs / 1000L
  val minutes = totalSeconds / 60L
  val seconds = totalSeconds % 60L
  return "%d:%02d".format(Locale.US, minutes, seconds)
}

private fun formatFrameTime(timeUs: Long): String {
  return String.format(Locale.US, "%.1f", timeUs / 1_000_000.0)
}
