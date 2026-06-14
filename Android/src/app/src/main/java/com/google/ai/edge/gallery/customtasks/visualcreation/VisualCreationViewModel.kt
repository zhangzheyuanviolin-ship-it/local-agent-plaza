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

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
  val generatedImageWidth: Int = 0,
  val generatedImageHeight: Int = 0,
  val savedGalleryUri: String? = null,
  val savedLocalFileUri: String? = null,
  val selectedVisualProcessMode: VisualProcessMode = VisualProcessMode.DESCRIBE_IMAGE,
  val selectedVlmModelName: String? = null,
  val visualProcessResult: String = "",
  val selectedPromptOptimizerModelName: String? = null,
  val promptOptimizationMode: PromptOptimizationMode = PromptOptimizationMode.ENGLISH_DEFAULT,
  val customPromptOptimizerSystemPrompt: String =
    defaultPromptOptimizerSystemPrompt(PromptOptimizationMode.ENGLISH_DEFAULT),
  val promptOptimizationStatusText: String = "中文提示词优化未运行",
  val isOptimizingPrompt: Boolean = false,
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
    val modelInfo = ImageGenerationModelRegistry.findModel(model.name)
    val nextSettings = defaultSettingsForModel(modelInfo, _uiState.value.settings)
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
        settings = defaultSettingsForModel(model, it.settings),
      )
    }
  }

  fun selectPromptOptimizerModel(modelName: String?) {
    _uiState.update { it.copy(selectedPromptOptimizerModelName = modelName) }
  }

  fun updatePromptOptimizationMode(mode: PromptOptimizationMode) {
    _uiState.update {
      val nextPrompt =
        if (
          mode == PromptOptimizationMode.ENGLISH_CUSTOM &&
            it.customPromptOptimizerSystemPrompt.isNotBlank()
        ) {
          it.customPromptOptimizerSystemPrompt
        } else {
          defaultPromptOptimizerSystemPrompt(mode)
        }
      it.copy(promptOptimizationMode = mode, customPromptOptimizerSystemPrompt = nextPrompt)
    }
  }

  fun updateCustomPromptOptimizerSystemPrompt(systemPrompt: String) {
    _uiState.update { it.copy(customPromptOptimizerSystemPrompt = systemPrompt) }
  }

  fun updateImageWidth(width: Int) {
    _uiState.update { it.copy(settings = it.settings.copy(width = sanitizeGenerationDimension(width))) }
  }

  fun updateImageHeight(height: Int) {
    _uiState.update { it.copy(settings = it.settings.copy(height = sanitizeGenerationDimension(height))) }
  }

  fun updateGenerationSteps(steps: Int) {
    _uiState.update { it.copy(settings = it.settings.copy(steps = sanitizeGenerationSteps(steps))) }
  }

  fun updateCfgScale(cfgScale: Float) {
    _uiState.update { it.copy(settings = it.settings.copy(cfgScale = sanitizeCfgScale(cfgScale))) }
  }

  fun updateRandomSeed(randomSeed: Boolean) {
    _uiState.update { it.copy(settings = it.settings.copy(randomSeed = randomSeed)) }
  }

  fun updateSeed(seed: String) {
    val parsed = seed.trim().toLongOrNull() ?: return
    _uiState.update { it.copy(settings = it.settings.copy(seed = parsed.coerceAtLeast(0L))) }
  }

  fun updateThreadCount(threadCount: Int) {
    _uiState.update { it.copy(settings = it.settings.copy(threadCount = sanitizeThreadCount(threadCount))) }
  }

  fun updateVisualProcessMode(mode: VisualProcessMode) {
    _uiState.update { it.copy(selectedVisualProcessMode = mode) }
  }

  fun optimizePromptWithLocalLlm(context: Context, model: Model?) {
    val sourcePrompt = uiState.value.prompt.trim()
    val mode = uiState.value.promptOptimizationMode
    if (sourcePrompt.isEmpty()) {
      _uiState.update {
        it.copy(
          status = VisualCreationStatus.ERROR,
          statusText = "请先输入中文或自然语言图片描述，再进行提示词优化",
          promptOptimizationStatusText = "提示词优化失败：没有可优化的提示词",
        )
      }
      return
    }
    if (mode == PromptOptimizationMode.ORIGINAL) {
      _uiState.update {
        it.copy(
          status = VisualCreationStatus.IDLE,
          promptOptimizationStatusText = "已选择原文直发：生成图片时会直接使用当前提示词",
          statusText = "原文直发模式不调用文本模型",
        )
      }
      return
    }
    if (model == null || !model.isLlm) {
      _uiState.update {
        it.copy(
          status = VisualCreationStatus.ERROR,
          statusText = "请先选择一个已下载的本地文本模型用于中文提示词翻译优化",
          promptOptimizationStatusText = "提示词优化未运行：没有可用文本模型",
        )
      }
      return
    }

    viewModelScope.launch(Dispatchers.Default) {
      val systemPrompt =
        uiState.value.customPromptOptimizerSystemPrompt.ifBlank {
          defaultPromptOptimizerSystemPrompt(mode)
        }
      _uiState.update {
        it.copy(
          isOptimizingPrompt = true,
          promptOptimizationStatusText = "正在使用 ${model.displayName.ifBlank { model.name }} 翻译并优化提示词",
          statusText = "正在调用本地文本模型优化图片提示词",
        )
      }
      var cleanedUp = false
      val cleanUpModel = {
        if (!cleanedUp) {
          cleanedUp = true
          model.runtimeHelper.cleanUp(model = model, onDone = {})
        }
      }
      try {
        val initError =
          initializePromptOptimizer(context = context, model = model, systemPrompt = systemPrompt)
        if (!initError.isNullOrBlank()) {
          _uiState.update {
            it.copy(
              isOptimizingPrompt = false,
              status = VisualCreationStatus.ERROR,
              statusText = "本地提示词优化模型初始化失败：$initError",
              promptOptimizationStatusText = "提示词优化失败：$initError",
            )
          }
          cleanUpModel()
          return@launch
        }

        model.runtimeHelper.resetConversation(
          model = model,
          supportImage = false,
          supportAudio = false,
          systemInstruction = Contents.of(systemPrompt),
        )

        val optimized =
          runPromptOptimizer(model = model, sourcePrompt = sourcePrompt, systemPrompt = systemPrompt)
        if (optimized.isBlank()) {
          _uiState.update {
            it.copy(
              isOptimizingPrompt = false,
              status = VisualCreationStatus.ERROR,
              statusText = "本地提示词优化失败：模型没有返回有效英文提示词",
              promptOptimizationStatusText = "提示词优化失败：空结果",
            )
          }
        } else {
          _uiState.update {
            it.copy(
              prompt = optimized,
              isOptimizingPrompt = false,
              status = VisualCreationStatus.IDLE,
              statusText = "提示词已优化为英文，可直接生成图片",
              promptOptimizationStatusText = "已生成英文提示词：$optimized",
            )
          }
        }
      } catch (e: Throwable) {
        _uiState.update {
          it.copy(
            isOptimizingPrompt = false,
            status = VisualCreationStatus.ERROR,
            statusText = "本地提示词优化失败：${e.message ?: e::class.java.simpleName}",
            promptOptimizationStatusText = "提示词优化失败：${e.message ?: e::class.java.simpleName}",
          )
        }
      } finally {
        cleanUpModel()
      }
    }
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
    if (modelInfo == null || !modelInfo.supportsTextToImage) {
      _uiState.update {
        it.copy(
          status = VisualCreationStatus.ERROR,
          statusText = "当前模型暂未声明支持文生图：${model.displayName.ifBlank { model.name }}",
        )
      }
      return
    }

    val nativeFiles =
      if (modelInfo.backend == ImageGenerationBackend.STABLE_DIFFUSION_CPP) {
        resolveNativeImageGenerationFiles(context = context, model = model, modelInfo = modelInfo)
      } else {
        NativeImageGenerationFiles(modelPath = model.getPath(context), diffusionModelPath = "", vaePath = "", llmPath = "")
      }
    val missingFiles =
      listOf(
          nativeFiles.modelPath,
          nativeFiles.diffusionModelPath,
          nativeFiles.vaePath,
          nativeFiles.llmPath,
        )
        .filter { it.isNotBlank() && !File(it).exists() }
    if (missingFiles.isNotEmpty()) {
      _uiState.update {
        it.copy(
          status = VisualCreationStatus.ERROR,
          statusText = "未找到已下载模型文件：${missingFiles.joinToString()}",
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
              } else if (
                current.generationProgressSteps > 0 &&
                  current.generationProgressStep >= current.generationProgressSteps
              ) {
                current.copy(
                  statusText =
                    buildDecodingStatusText(
                      modelName = model.displayName.ifBlank { model.name },
                      prompt = current.submittedPrompt,
                    )
                )
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
        val nativeResult =
          if (modelInfo.backend == ImageGenerationBackend.LOCAL_DREAM_QNN_MNN) {
            LocalDreamImageGenerationClient(context)
              .generateImage(
                modelPath = nativeFiles.modelPath,
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = settings.width,
                height = settings.height,
                steps = settings.steps,
                cfgScale = settings.cfgScale,
                seed = seed,
              )
          } else {
            NativeImageGenerationBridge.generateImage(
              modelPath = nativeFiles.modelPath,
              diffusionModelPath = nativeFiles.diffusionModelPath,
              vaePath = nativeFiles.vaePath,
              llmPath = nativeFiles.llmPath,
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
                      val sampleProgress =
                        normalizeSamplingProgress(
                          callbackStep = step,
                          callbackSteps = steps,
                          expectedSteps = settings.steps,
                        )
                      if (sampleProgress == null) {
                        current.copy(
                          statusText =
                            buildDecodingStatusText(
                              modelName = model.displayName.ifBlank { model.name },
                              prompt = current.submittedPrompt,
                            )
                        )
                      } else {
                        current.copy(
                          generationProgressStep = sampleProgress.step,
                          generationProgressSteps = sampleProgress.steps,
                          statusText =
                            if (sampleProgress.isComplete) {
                              buildDecodingStatusText(
                                modelName = model.displayName.ifBlank { model.name },
                                prompt = current.submittedPrompt,
                              )
                            } else {
                              buildSamplingStatusText(
                                modelName = model.displayName.ifBlank { model.name },
                                step = sampleProgress.step,
                                steps = sampleProgress.steps,
                                prompt = current.submittedPrompt,
                              )
                            },
                        )
                      }
                    } else {
                      current
                    }
                  }
                },
            )
          }
        val outputPath =
          saveRgbImageAsPng(
            context = context,
            result = nativeResult,
          )
        _uiState.update {
          it.copy(
            status = VisualCreationStatus.IMAGE_GENERATED,
            statusText =
              "图片生成完成：$outputPath，实际尺寸 ${nativeResult.width} x ${nativeResult.height}",
            generatedImagePath = outputPath,
            generatedImageWidth = nativeResult.width,
            generatedImageHeight = nativeResult.height,
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

  fun saveGeneratedImageToGallery(context: Context) {
    val imagePath = uiState.value.generatedImagePath
    if (imagePath.isNullOrBlank()) {
      _uiState.update { it.copy(status = VisualCreationStatus.ERROR, statusText = "当前没有可保存到相册的图片") }
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val savedUri = copyPngToMediaStore(
          context = context,
          sourcePath = imagePath,
          collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
          relativePath = Environment.DIRECTORY_PICTURES + "/Local Visual Creation",
        )
        _uiState.update {
          it.copy(
            status = VisualCreationStatus.IMAGE_SAVED,
            statusText = "图片已保存到系统相册：$savedUri",
            savedGalleryUri = savedUri,
          )
        }
      } catch (e: Throwable) {
        _uiState.update {
          it.copy(status = VisualCreationStatus.ERROR, statusText = "保存到系统相册失败：${e.message ?: e::class.java.simpleName}")
        }
      }
    }
  }

  fun saveGeneratedImageToLocalFolder(context: Context) {
    val imagePath = uiState.value.generatedImagePath
    if (imagePath.isNullOrBlank()) {
      _uiState.update { it.copy(status = VisualCreationStatus.ERROR, statusText = "当前没有可保存到本地文件夹的图片") }
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val savedUri = copyPngToMediaStore(
          context = context,
          sourcePath = imagePath,
          collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
          relativePath = Environment.DIRECTORY_DOWNLOADS + "/local-visual-creation/generated-images",
        )
        _uiState.update {
          it.copy(
            status = VisualCreationStatus.IMAGE_SAVED,
            statusText = "图片已保存到下载管理文件夹：$savedUri",
            savedLocalFileUri = savedUri,
          )
        }
      } catch (e: Throwable) {
        _uiState.update {
          it.copy(status = VisualCreationStatus.ERROR, statusText = "保存到本地文件夹失败：${e.message ?: e::class.java.simpleName}")
        }
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

  private fun buildDecodingStatusText(
    modelName: String,
    prompt: String,
  ): String = "已提交提示词：${prompt.take(80)}。$modelName 采样已完成，正在解码并保存图片。"

  private fun saveRgbImageAsPng(
    context: Context,
    result: NativeImageGenerationResult,
  ): String {
    val width = result.width
    val height = result.height
    val pixels = nativeRgbToArgbPixels(result)

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

  private fun copyPngToMediaStore(
    context: Context,
    sourcePath: String,
    collection: android.net.Uri,
    relativePath: String,
  ): String {
    val sourceFile = File(sourcePath)
    require(sourceFile.exists()) { "源图片不存在：$sourcePath" }

    val fileName = sourceFile.name.ifBlank { "visual_creation_${System.currentTimeMillis()}.png" }
    val values =
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
    val resolver = context.contentResolver
    val uri = resolver.insert(collection, values) ?: error("系统媒体库拒绝创建目标文件")
    try {
      resolver.openOutputStream(uri)?.use { outputStream ->
        FileInputStream(sourceFile).use { inputStream -> inputStream.copyTo(outputStream) }
      } ?: error("无法打开目标文件输出流")
      values.clear()
      values.put(MediaStore.MediaColumns.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
      return uri.toString()
    } catch (e: Throwable) {
      resolver.delete(uri, null, null)
      throw e
    }
  }

  private suspend fun initializePromptOptimizer(
    context: Context,
    model: Model,
    systemPrompt: String,
  ): String? {
    if (model.instance != null) {
      return null
    }
    val initResult = CompletableDeferred<String>()
    model.runtimeHelper.initialize(
      context = context,
      model = model,
      taskId = TASK_ID_LOCAL_VISUAL_CREATION,
      supportImage = false,
      supportAudio = false,
      systemInstruction = Contents.of(systemPrompt),
      coroutineScope = viewModelScope,
      onDone = { message ->
        if (!initResult.isCompleted) {
          initResult.complete(message)
        }
      },
    )
    return withTimeoutOrNull(180_000L) { initResult.await() }
      ?: "初始化本地文本模型超时"
  }

  private suspend fun runPromptOptimizer(
    model: Model,
    sourcePrompt: String,
    systemPrompt: String,
  ): String {
    val result = CompletableDeferred<Result<String>>()
    val rawResponse = StringBuilder()
    val optimizerInput =
      """
      $systemPrompt

      User idea:
      $sourcePrompt
      """
        .trimIndent()

    model.runtimeHelper.runInference(
      model = model,
      input = optimizerInput,
      resultListener = { partialResult, done, _ ->
        rawResponse.append(partialResult)
        if (done && !result.isCompleted) {
          result.complete(Result.success(sanitizePromptOptimizerOutput(rawResponse.toString())))
        }
      },
      cleanUpListener = {},
      onError = { message ->
        if (!result.isCompleted) {
          result.complete(Result.failure(IllegalStateException(message)))
        }
      },
      coroutineScope = viewModelScope,
    )

    return withTimeoutOrNull(180_000L) { result.await().getOrThrow() }
      ?: error("本地文本模型生成提示词超时")
  }
}

private data class NativeImageGenerationFiles(
  val modelPath: String,
  val diffusionModelPath: String,
  val vaePath: String,
  val llmPath: String,
)

private fun defaultSettingsForModel(
  modelInfo: ImageGenerationModelInfo?,
  currentSettings: ImageGenerationSettings,
): ImageGenerationSettings {
  if (modelInfo == null) {
    return currentSettings
  }
  val base =
    currentSettings.copy(
      width = sanitizeGenerationDimension(modelInfo.recommendedWidth),
      height = sanitizeGenerationDimension(modelInfo.recommendedHeight),
      backend = modelInfo.backend,
      vaeTiling = false,
    )
  return when (modelInfo.family) {
    "Z-Image" -> base.copy(steps = 8, cfgScale = 1.0f)
    "Stable Diffusion 1.5" -> base.copy(steps = 28, cfgScale = 7.0f)
    "Absolute Reality SD1.5" -> base.copy(steps = 28, cfgScale = 7.0f)
    else -> base
  }
}

private fun resolveNativeImageGenerationFiles(
  context: Context,
  model: Model,
  modelInfo: ImageGenerationModelInfo,
): NativeImageGenerationFiles {
  val fileNames = resolveNativeImageGenerationFileNames(modelInfo)
  return NativeImageGenerationFiles(
    modelPath = fileNames.modelFileName.takeIf { it.isNotBlank() }?.let { model.getPath(context, it) } ?: "",
    diffusionModelPath =
      fileNames.diffusionModelFileName.takeIf { it.isNotBlank() }?.let { model.getPath(context, it) }
        ?: "",
    vaePath = fileNames.vaeFileName.takeIf { it.isNotBlank() }?.let { model.getPath(context, it) } ?: "",
    llmPath = fileNames.llmFileName.takeIf { it.isNotBlank() }?.let { model.getPath(context, it) } ?: "",
  )
}

private fun sanitizePromptOptimizerOutput(raw: String): String {
  return processLlmResponse(raw)
    .replace("```", "")
    .lineSequence()
    .map { line ->
      line
        .trim()
        .removePrefix("English prompt:")
        .removePrefix("Prompt:")
        .removePrefix("Final prompt:")
        .trim()
        .trim('"', '\'', '“', '”')
    }
    .filter { it.isNotBlank() }
    .joinToString(" ")
    .replace(Regex("\\s+"), " ")
    .trim()
}

internal fun nativeRgbToArgbPixels(result: NativeImageGenerationResult): IntArray {
  require(result.width > 0 && result.height > 0) {
    "Invalid image size: ${result.width} x ${result.height}"
  }
  require(result.channels >= 3) { "Invalid channel count: ${result.channels}" }
  val pixelCount = result.width * result.height
  require(result.bytes.size == pixelCount * result.channels) {
    "Invalid RGB buffer size: ${result.bytes.size}, expected ${pixelCount * result.channels}"
  }

  val pixels = IntArray(pixelCount)
  for (i in 0 until pixelCount) {
    val base = i * result.channels
    val red = result.bytes[base].toInt() and 0xff
    val green = result.bytes[base + 1].toInt() and 0xff
    val blue = result.bytes[base + 2].toInt() and 0xff
    pixels[i] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
  }
  return pixels
}

internal data class SamplingProgress(val step: Int, val steps: Int) {
  val isComplete: Boolean
    get() = step >= steps
}

internal fun normalizeSamplingProgress(
  callbackStep: Int,
  callbackSteps: Int,
  expectedSteps: Int,
): SamplingProgress? {
  val totalSteps = expectedSteps.coerceAtLeast(1)
  if (callbackSteps != totalSteps) {
    return null
  }
  return SamplingProgress(
    step = callbackStep.coerceIn(0, totalSteps),
    steps = totalSteps,
  )
}
