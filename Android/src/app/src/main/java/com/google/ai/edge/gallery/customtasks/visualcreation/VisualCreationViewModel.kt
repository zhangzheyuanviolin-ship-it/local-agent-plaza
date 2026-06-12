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

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VisualCreationUiState(
  val prompt: String = "",
  val negativePrompt: String = "",
  val settings: ImageGenerationSettings = ImageGenerationSettings.default(),
  val selectedImageGenerationModelId: String? =
    ImageGenerationModelRegistry.recommendedModels.firstOrNull()?.modelId,
  val status: VisualCreationStatus = VisualCreationStatus.IMAGE_MODEL_MISSING,
  val statusText: String = "请选择或导入图像生成模型",
  val generatedImagePath: String? = null,
  val selectedVisualProcessMode: VisualProcessMode = VisualProcessMode.DESCRIBE_IMAGE,
  val selectedVlmModelName: String? = null,
  val visualProcessResult: String = "",
)

@HiltViewModel
class VisualCreationViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(VisualCreationUiState())
  val uiState = _uiState.asStateFlow()

  fun updatePrompt(prompt: String) {
    _uiState.update { it.copy(prompt = prompt) }
  }

  fun updateNegativePrompt(negativePrompt: String) {
    _uiState.update { it.copy(negativePrompt = negativePrompt) }
  }

  fun selectImageGenerationModel(modelId: String) {
    val model = ImageGenerationModelRegistry.findModel(modelId) ?: return
    _uiState.update {
      it.copy(
        selectedImageGenerationModelId = model.modelId,
        status = VisualCreationStatus.IDLE,
        statusText = "已选择图像生成模型：${model.displayName}",
      )
    }
  }

  fun updateVisualProcessMode(mode: VisualProcessMode) {
    _uiState.update { it.copy(selectedVisualProcessMode = mode) }
  }

  fun generatePlaceholder() {
    val prompt = uiState.value.prompt.trim()
    if (prompt.isEmpty()) {
      _uiState.update {
        it.copy(
          status = VisualCreationStatus.ERROR,
          statusText = "请先输入提示词",
        )
      }
      return
    }
    _uiState.update {
      it.copy(
        status = VisualCreationStatus.ERROR,
        statusText = "真实图像生成推理引擎尚未接入。本版本先用于验收真实模型包下载、模型加载入口和创作工作台页面。",
      )
    }
  }
}
