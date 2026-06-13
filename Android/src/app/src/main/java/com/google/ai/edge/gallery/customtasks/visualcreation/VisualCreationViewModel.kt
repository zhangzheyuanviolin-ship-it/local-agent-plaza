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

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VisualCreationUiState(
  val prompt: String = "",
  val negativePrompt: String = "",
  val settings: ImageGenerationSettings = ImageGenerationSettings.default(),
  val selectedImageGenerationModelId: String? =
    ImageGenerationModelRegistry.recommendedModels.firstOrNull()?.modelId,
  val status: VisualCreationStatus = VisualCreationStatus.IMAGE_MODEL_MISSING,
  val statusText: String = "请选择或导入图像生成模型",
  val submittedPrompt: String = "",
  val submittedNegativePrompt: String = "",
  val generationStartedAtMs: Long = 0L,
  val generationProgressStep: Int = 0,
  val generationProgressSteps: Int = 0,
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

  fun syncSelectedImageGenerationModel(model: Model) {
    if (_uiState.value.selectedImageGenerationModelId == model.name) {
      return
    }
    val modelInfo = ImageGenerationModelRegistry.findModel(model.name)
    val nextSettings =
      if (modelInfo?.family == "Stable Diffusion 1.5") {
        ImageGenerationSettings.fastCpuVerification()
      } else {
        modelInfo?.let { info ->
          _uiState.value.settings.copy(width = info.recommendedWidth, height = info.recommendedHeight)
        } ?: _uiState.value.settings
      }
    _uiState.update {
      it.copy(
        selectedImageGenerationModelId = model.name,
        status = VisualCreationStatus.IDLE,
        statusText = "当前图像生成模型：${model.displayName.ifBlank { model.name }}",
        settings = nextSettings,
      )
    }
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

  fun generateImage(context: Context, model: Model) {
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

    val modelInfo = ImageGenerationModelRegistry.findModel(model.name)
    if (modelInfo?.family != "Stable Diffusion 1.5") {
      _uiState.update {
        it.copy(
          status = VisualCreationStatus.ERROR,
          statusText = "本版本真实推理先支持 Stable Diffusion 1.5 单文件 GGUF。请在模型列表选择并下载 SD1.5 Q4_0、Q5_0 或 Q8_0 后生成。",
        )
      }
      return
    }

    val modelPath = model.getPath(context)
    if (!File(modelPath).exists()) {
      _uiState.update {
        it.copy(
          status = VisualCreationStatus.ERROR,
          statusText = "未找到已下载模型文件：$modelPath",
        )
      }
      return
    }

    val settings = uiState.value.settings
    val seed = settings.resolveSeed()
    val negativePrompt = uiState.value.negativePrompt.trim()
    val startedAtMs = System.currentTimeMillis()
    _uiState.update {
      it.copy(
        prompt = "",
        status = VisualCreationStatus.GENERATING_IMAGE,
        statusText =
          buildLoadingStatusText(
            modelName = model.displayName.ifBlank { model.name },
            prompt = prompt,
            elapsedSeconds = 0,
          ),
        submittedPrompt = prompt,
        submittedNegativePrompt = negativePrompt,
        generationStartedAtMs = startedAtMs,
        generationProgressStep = 0,
        generationProgressSteps = settings.steps,
      )
    }

    viewModelScope.launch(Dispatchers.Default) {
      val heartbeatJob =
        launch {
          while (true) {
            delay(10_000)
            _uiState.update { current ->
              if (current.status != VisualCreationStatus.GENERATING_IMAGE) {
                current
              } else if (current.generationProgressStep > 0) {
                current.copy(
                  statusText =
                    buildSamplingStatusText(
                      modelName = model.displayName.ifBlank { model.name },
                      step = current.generationProgressStep,
                      steps = current.generationProgressSteps,
                      prompt = current.submittedPrompt,
                    )
                )
              } else {
                current.copy(
                  statusText =
                    buildLoadingStatusText(
                      modelName = model.displayName.ifBlank { model.name },
                      prompt = current.submittedPrompt,
                      elapsedSeconds =
                        ((System.currentTimeMillis() - current.generationStartedAtMs) / 1000L)
                          .coerceAtLeast(0L),
                    )
                )
              }
            }
          }
        }
      try {
        val rgbBytes =
          NativeImageGenerationBridge.generateSd15Image(
            modelPath = modelPath,
            prompt = prompt,
            negativePrompt = negativePrompt,
            width = settings.width,
            height = settings.height,
            steps = settings.steps,
            cfgScale = settings.cfgScale,
            seed = seed,
            threadCount = settings.threadCount,
            progressListener =
              NativeImageGenerationBridge.ProgressListener { step, steps, _ ->
                _uiState.update { current ->
                  if (current.status == VisualCreationStatus.GENERATING_IMAGE) {
                    current.copy(
                      generationProgressStep = step.coerceAtLeast(0),
                      generationProgressSteps = steps.coerceAtLeast(settings.steps),
                      statusText =
                        buildSamplingStatusText(
                          modelName = model.displayName.ifBlank { model.name },
                          step = step,
                          steps = steps,
                          prompt = current.submittedPrompt,
                        ),
                    )
                  } else {
                    current
                  }
                }
              },
          )
        val outputPath =
          saveRgbImageAsPng(
            context = context,
            rgbBytes = rgbBytes,
            width = settings.width,
            height = settings.height,
          )
        _uiState.update {
          it.copy(
            status = VisualCreationStatus.IMAGE_GENERATED,
            statusText = "图片生成完成：$outputPath",
            generatedImagePath = outputPath,
          )
        }
      } catch (e: Throwable) {
        _uiState.update {
          it.copy(
            status = VisualCreationStatus.ERROR,
            statusText = "图片生成失败：${e.message ?: e::class.java.simpleName}",
          )
        }
      } finally {
        heartbeatJob.cancel()
      }
    }
  }

  private fun buildLoadingStatusText(
    modelName: String,
    prompt: String,
    elapsedSeconds: Long,
  ): String =
    "已提交提示词：${prompt.take(80)}。正在加载 $modelName 并初始化推理引擎，已等待 ${elapsedSeconds} 秒；首次加载模型会比较慢。"

  private fun buildSamplingStatusText(
    modelName: String,
    step: Int,
    steps: Int,
    prompt: String,
  ): String =
    "已提交提示词：${prompt.take(80)}。$modelName 正在采样，第 ${step.coerceAtLeast(0)} / ${steps.coerceAtLeast(1)} 步。"

  private fun saveRgbImageAsPng(
    context: Context,
    rgbBytes: ByteArray,
    width: Int,
    height: Int,
  ): String {
    val pixelCount = width * height
    val channelCount = rgbBytes.size / pixelCount
    require(channelCount >= 3) { "Invalid RGB buffer size: ${rgbBytes.size}" }

    val pixels = IntArray(pixelCount)
    for (i in 0 until pixelCount) {
      val base = i * channelCount
      val red = rgbBytes[base].toInt() and 0xff
      val green = rgbBytes[base + 1].toInt() and 0xff
      val blue = rgbBytes[base + 2].toInt() and 0xff
      pixels[i] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

    val outputDir = File(context.getExternalFilesDir(null), "visual_creation_outputs")
    outputDir.mkdirs()
    val outputFile = File(outputDir, "visual_creation_${System.currentTimeMillis()}.png")
    FileOutputStream(outputFile).use { outputStream ->
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
    }
    bitmap.recycle()
    return outputFile.absolutePath
  }
}
