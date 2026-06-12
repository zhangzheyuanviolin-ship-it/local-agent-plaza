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

package com.google.ai.edge.gallery.customtasks.visualcreation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@Composable
fun VisualCreationScreen(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  viewModel: VisualCreationViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val selectedModel =
    ImageGenerationModelRegistry.findModel(uiState.selectedImageGenerationModelId.orEmpty())

  LaunchedEffect(uiState.status) {
    setAppBarControlsDisabled(uiState.status == VisualCreationStatus.GENERATING_IMAGE)
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
      text = "本地视觉创作",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      text = "在本机生成图片，并把生成结果继续交给本地视觉语言模型进行描述、评审、分析和文本创作。第一阶段先启用本地图像生成能力，后续处理入口从一开始保留在同一工作流中。",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("图像生成模型")
        Text(
          text = "当前模型：${selectedModel?.displayName ?: "未选择"}",
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          text = "推理后端：${selectedModel?.backend ?: ImageGenerationBackend.STABLE_DIFFUSION_CPP}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ImageGenerationModelRegistry.recommendedModels.forEach { model ->
            FilterChip(
              selected = model.modelId == uiState.selectedImageGenerationModelId,
              onClick = { viewModel.selectImageGenerationModel(model.modelId) },
              label = { Text(model.displayName) },
            )
          }
        }
        OutlinedButton(onClick = {}) { Text("图像生成模型管理") }
      }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("提示词")
        OutlinedTextField(
          value = uiState.prompt,
          onValueChange = viewModel::updatePrompt,
          label = { Text("提示词") },
          placeholder = { Text("例如：一位小提琴家站在雨夜街灯下，电影感，写实风格") },
          modifier = Modifier.fillMaxWidth(),
          minLines = 3,
        )
        OutlinedTextField(
          value = uiState.negativePrompt,
          onValueChange = viewModel::updateNegativePrompt,
          label = { Text("负面提示词") },
          placeholder = { Text("例如：模糊、畸形、乱码文字、低质量、手指错误") },
          modifier = Modifier.fillMaxWidth(),
          minLines = 2,
        )
      }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("生成参数")
        SettingsLine("尺寸", "${uiState.settings.width} x ${uiState.settings.height}")
        SettingsLine("采样步数", "${uiState.settings.steps}")
        SettingsLine("CFG（提示词引导强度）", "${uiState.settings.cfgScale}")
        SettingsLine("Sampler（采样器）", uiState.settings.sampler)
        SettingsLine("低内存模式", if (uiState.settings.lowMemoryMode) "开启" else "关闭")
        SettingsLine("VAE tiling", if (uiState.settings.vaeTiling) "开启" else "关闭")
        Button(
          onClick = viewModel::generatePlaceholder,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("生成图片")
        }
      }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("生成结果")
        Text(
          text = uiState.generatedImagePath ?: "还没有生成图片。生成完成后，图片会显示在这里，并可保存、重新生成或发送给视觉模型处理。",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier =
            Modifier.semantics {
              contentDescription = "已生成图片。可以保存、重新生成，或发送给视觉语言模型进行描述、评审和创作。"
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = {}) { Text("保存到相册") }
          OutlinedButton(onClick = {}) { Text("保存到本地文件夹") }
        }
        OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
          Text("发送给视觉模型处理")
        }
      }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("图片后续处理")
        Text("当前视觉语言模型：${uiState.selectedVlmModelName ?: "未选择"}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf(
              VisualProcessMode.DESCRIBE_IMAGE,
              VisualProcessMode.REVIEW_IMAGE,
              VisualProcessMode.EXPAND_TO_STORY,
            )
            .forEach { mode ->
              AssistChip(
                onClick = { viewModel.updateVisualProcessMode(mode) },
                label = { Text(mode.label) },
              )
            }
        }
        OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
          Text("选择视觉语言模型")
        }
        Text(
          text = uiState.visualProcessResult.ifBlank { "生成图片后，可在后续阶段把图片发送给本地视觉语言模型进行描述、评审或文本创作。" },
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }

    Text(
      text = uiState.statusText,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier =
        Modifier.semantics {
          liveRegion = LiveRegionMode.Polite
          contentDescription = "状态：${uiState.statusText}"
        },
    )
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.SemiBold,
  )
}

@Composable
private fun SettingsLine(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
  }
}
