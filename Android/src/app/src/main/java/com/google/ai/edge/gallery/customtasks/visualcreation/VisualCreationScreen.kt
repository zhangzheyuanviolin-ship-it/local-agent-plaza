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

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.util.Locale

@Composable
fun VisualCreationScreen(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  viewModel: VisualCreationViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedAppModel = modelManagerUiState.selectedModel
  val selectedModelInfo = ImageGenerationModelRegistry.findModel(selectedAppModel.name)
  val promptOptimizerModels =
    modelManagerUiState.tasks
      .firstOrNull { it.id == BuiltInTaskId.LLM_CHAT }
      ?.models
      ?.filter { model ->
        model.isLlm &&
          modelManagerUiState.modelDownloadStatus[model.name]?.status ==
            ModelDownloadStatusType.SUCCEEDED
      }
      ?: emptyList()
  val selectedPromptOptimizerModel =
    promptOptimizerModels.firstOrNull { it.name == uiState.selectedPromptOptimizerModelName }
      ?: promptOptimizerModels.firstOrNull()
  var showAdvancedSettings by remember { mutableStateOf(false) }
  var showVisualProcessing by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { setAppBarControlsDisabled(false) }

  LaunchedEffect(selectedAppModel.name) { viewModel.syncSelectedImageGenerationModel(selectedAppModel) }

  LaunchedEffect(selectedPromptOptimizerModel?.name) {
    if (
      uiState.selectedPromptOptimizerModelName == null &&
        selectedPromptOptimizerModel?.name != null
    ) {
      viewModel.selectPromptOptimizerModel(selectedPromptOptimizerModel.name)
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
      text = "本地视觉创作",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      text = "在本机生成图片，并把生成结果继续交给本地视觉语言模型进行描述、评审、分析和文本创作。",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("图像生成模型")
        Text(
          text = "当前模型：${selectedAppModel.displayName.ifBlank { selectedAppModel.name }}",
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          text = "推理后端：${selectedModelInfo?.backend ?: ImageGenerationBackend.STABLE_DIFFUSION_CPP}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = "切换、下载或删除图像生成模型请返回上一层模型列表。本工作台只使用您进入时选择的模型。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        SectionTitle("中文提示词优化")
        Text(
          text = uiState.promptOptimizationStatusText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (promptOptimizerModels.isEmpty()) {
          Text(
            text = "没有检测到已下载的本地文本模型。请先在文本聊天任务下载一个支持多语言的文本模型。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            promptOptimizerModels.take(6).forEach { model ->
              AssistChip(
                onClick = { viewModel.selectPromptOptimizerModel(model.name) },
                label = { Text(model.displayName.ifBlank { model.name }) },
              )
            }
          }
          Text(
            text =
              "当前优化模型：${
                selectedPromptOptimizerModel?.let { it.displayName.ifBlank { it.name } } ?: "未选择"
              }",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        OutlinedButton(
          onClick = { viewModel.optimizePromptWithLocalLlm(context, selectedPromptOptimizerModel) },
          enabled =
            promptOptimizerModels.isNotEmpty() &&
              uiState.prompt.isNotBlank() &&
              !uiState.isOptimizingPrompt &&
              uiState.status != VisualCreationStatus.GENERATING_IMAGE,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(if (uiState.isOptimizingPrompt) "正在优化提示词" else "翻译并优化为英文提示词")
        }
      }
    }

    Button(
      onClick = { viewModel.generateImage(context = context, model = selectedAppModel) },
      enabled = uiState.status != VisualCreationStatus.GENERATING_IMAGE,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        if (uiState.status == VisualCreationStatus.GENERATING_IMAGE) {
          if (uiState.generationProgressStep > 0) {
            "正在生成图片，第 ${uiState.generationProgressStep} / ${uiState.generationProgressSteps} 步"
          } else {
            "正在加载模型"
          }
        } else {
          "生成图片"
        }
      )
    }

    if (uiState.submittedPrompt.isNotBlank()) {
      ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          SectionTitle("已提交的生成请求")
          Text(
            text = "提示词：${uiState.submittedPrompt}",
            style = MaterialTheme.typography.bodyMedium,
          )
          if (uiState.submittedNegativePrompt.isNotBlank()) {
            Text(
              text = "负面提示词：${uiState.submittedNegativePrompt}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (uiState.status == VisualCreationStatus.GENERATING_IMAGE) {
            Text(
              text =
                if (uiState.generationProgressStep > 0) {
                  "采样进度：第 ${uiState.generationProgressStep} / ${uiState.generationProgressSteps} 步"
                } else {
                  "进度：正在加载模型和初始化推理引擎"
                },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          SectionTitle("高级参数")
          OutlinedButton(onClick = { showAdvancedSettings = !showAdvancedSettings }) {
            Text(if (showAdvancedSettings) "收起" else "展开")
          }
        }
        if (showAdvancedSettings) {
          ParameterSlider(
            label = "宽度",
            value = uiState.settings.width,
            range = 128f..1024f,
            enabled = uiState.status != VisualCreationStatus.GENERATING_IMAGE,
            onValueChange = viewModel::updateImageWidth,
          )
          ParameterSlider(
            label = "高度",
            value = uiState.settings.height,
            range = 128f..1024f,
            enabled = uiState.status != VisualCreationStatus.GENERATING_IMAGE,
            onValueChange = viewModel::updateImageHeight,
          )
          ParameterSlider(
            label = "采样步数",
            value = uiState.settings.steps,
            range = 1f..50f,
            enabled = uiState.status != VisualCreationStatus.GENERATING_IMAGE,
            onValueChange = viewModel::updateGenerationSteps,
          )
          FloatParameterSlider(
            label = "CFG（提示词引导强度）",
            value = uiState.settings.cfgScale,
            range = 1f..12f,
            enabled = uiState.status != VisualCreationStatus.GENERATING_IMAGE,
            onValueChange = viewModel::updateCfgScale,
          )
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("随机种子", style = MaterialTheme.typography.bodyMedium)
            Switch(
              checked = uiState.settings.randomSeed,
              onCheckedChange = viewModel::updateRandomSeed,
              enabled = uiState.status != VisualCreationStatus.GENERATING_IMAGE,
            )
          }
          if (!uiState.settings.randomSeed) {
            OutlinedTextField(
              value = uiState.settings.seed.toString(),
              onValueChange = viewModel::updateSeed,
              label = { Text("固定种子") },
              singleLine = true,
              enabled = uiState.status != VisualCreationStatus.GENERATING_IMAGE,
              modifier = Modifier.fillMaxWidth(),
            )
          }
          ParameterSlider(
            label = "线程数",
            value = uiState.settings.threadCount,
            range = 1f..8f,
            enabled = uiState.status != VisualCreationStatus.GENERATING_IMAGE,
            onValueChange = viewModel::updateThreadCount,
          )
          SettingsLine("Sampler（采样器）", uiState.settings.sampler)
          SettingsLine("低内存模式", if (uiState.settings.lowMemoryMode) "开启" else "关闭")
          SettingsLine("VAE tiling", if (uiState.settings.vaeTiling) "开启" else "关闭")
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
        uiState.generatedImagePath?.let { path ->
          val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
          bitmap?.let {
            Image(
              bitmap = it.asImageBitmap(),
              contentDescription = "生成的图片",
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = { viewModel.saveGeneratedImageToGallery(context) },
            enabled =
              !uiState.generatedImagePath.isNullOrBlank() &&
                uiState.status != VisualCreationStatus.GENERATING_IMAGE,
          ) {
            Text("保存到相册")
          }
          OutlinedButton(
            onClick = { viewModel.saveGeneratedImageToLocalFolder(context) },
            enabled =
              !uiState.generatedImagePath.isNullOrBlank() &&
                uiState.status != VisualCreationStatus.GENERATING_IMAGE,
          ) {
            Text("保存到本地文件夹")
          }
        }
        uiState.savedGalleryUri?.let { uri ->
          Text(
            text = "系统相册保存位置：$uri",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        uiState.savedLocalFileUri?.let { uri ->
          Text(
            text = "本地文件夹保存位置：$uri",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
          Text("发送给视觉模型处理")
        }
      }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          SectionTitle("图片后续处理")
          OutlinedButton(onClick = { showVisualProcessing = !showVisualProcessing }) {
            Text(if (showVisualProcessing) "收起" else "展开")
          }
        }
        if (showVisualProcessing) {
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
          OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text("选择视觉语言模型")
          }
          Text(
            text = uiState.visualProcessResult.ifBlank { "生成图片后，可在后续阶段把图片发送给本地视觉语言模型进行描述、评审或文本创作。" },
            style = MaterialTheme.typography.bodyMedium,
          )
        }
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
private fun ParameterSlider(
  label: String,
  value: Int,
  range: ClosedFloatingPointRange<Float>,
  enabled: Boolean,
  onValueChange: (Int) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("$label：$value", style = MaterialTheme.typography.bodyMedium)
    Slider(
      value = value.toFloat(),
      onValueChange = { onValueChange(it.toInt()) },
      valueRange = range,
      enabled = enabled,
    )
  }
}

@Composable
private fun FloatParameterSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  enabled: Boolean,
  onValueChange: (Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("$label：${String.format(Locale.US, "%.1f", value)}", style = MaterialTheme.typography.bodyMedium)
    Slider(
      value = value,
      onValueChange = onValueChange,
      valueRange = range,
      enabled = enabled,
    )
  }
}

@Composable
private fun SettingsLine(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
  }
}
