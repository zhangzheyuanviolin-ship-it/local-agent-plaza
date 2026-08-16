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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

const val TASK_ID_LOCAL_VISUAL_CREATION = "llm_local_visual_creation"
const val TASK_ID_BONSAI_IMAGE = "llm_bonsai_image"
const val TASK_ID_FLUX_KLEIN_IMAGE = "llm_flux_klein_image"

class VisualCreationWorkbenchInstance

/**
 * App-owned image models use ordinary mutable lists. ModelManagerViewModel explicitly restores
 * them after bootstrap and allowlist refresh so dedicated tasks remain canonical and single-model.
 */
private fun visualCreationModels(): MutableList<Model> =
  (listOf(createBonsaiImageModel(), createFluxKleinImageModel()) + createVisualCreationImageModels())
    .toMutableList()

private fun bonsaiOnlyModels(): MutableList<Model> = mutableListOf(createBonsaiImageModel())

private fun fluxOnlyModels(): MutableList<Model> = mutableListOf(createFluxKleinImageModel())

class VisualCreationTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = TASK_ID_LOCAL_VISUAL_CREATION,
      label = "本地视觉创作",
      category = Category.LLM,
      icon = Icons.Outlined.Image,
      models = visualCreationModels(),
      description = "在设备本地生成图片，并把生成结果继续交给本地视觉语言模型进行描述、评审、分析和文本创作。包含 Bonsai Image 4B 与 FLUX.2 Klein 4B LiteRT。",
      shortDescription = "生成图片、理解图片，并基于图片继续创作",
      docUrl = "https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza",
      sourceCodeUrl = "",
      handleModelConfigChangesInTask = true,
      newFeature = true,
      useThemeColor = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    model.instance = VisualCreationWorkbenchInstance()
    onDone("")
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customTaskData = data as CustomTaskData
    VisualCreationScreen(
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      bottomPadding = customTaskData.bottomPadding,
      setAppBarControlsDisabled = customTaskData.setAppBarControlsDisabled,
    )
  }
}

/** A dedicated, one-model home-screen entry so Bonsai is discoverable without the 32-model list. */
class BonsaiImageTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = TASK_ID_BONSAI_IMAGE,
      label = "Bonsai 图像生成",
      category = Category.LLM,
      icon = Icons.Outlined.Image,
      models = bonsaiOnlyModels(),
      description = "Bonsai Image 4B LiteRT 本地图像生成。模型约 4.3 GB，固定 512 × 512，默认 4 步，在设备 CPU/XNNPACK 上运行。",
      shortDescription = "下载 Bonsai 4B，在本机直接生成 512 × 512 图片",
      docUrl = "https://huggingface.co/litert-community/Bonsai-Image-ternary-4B",
      sourceCodeUrl = "",
      handleModelConfigChangesInTask = true,
      newFeature = true,
      useThemeColor = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    model.instance = VisualCreationWorkbenchInstance()
    onDone("")
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customTaskData = data as CustomTaskData
    VisualCreationScreen(
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      bottomPadding = customTaskData.bottomPadding,
      setAppBarControlsDisabled = customTaskData.setAppBarControlsDisabled,
    )
  }
}

/** Dedicated FLUX.2 Klein entry with one directly downloadable model. */
class FluxKleinImageTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = TASK_ID_FLUX_KLEIN_IMAGE,
      label = "FLUX.2 Klein 图像生成",
      category = Category.LLM,
      icon = Icons.Outlined.Image,
      models = fluxOnlyModels(),
      description = "FLUX.2 Klein 4B LiteRT 本地图像生成。模型约 7.45 GB，固定 256 × 256、4 步，优先使用 LiteRT GPU CompiledModel FP32。",
      shortDescription = "下载 FLUX.2 Klein 4B，在手机 GPU 本地生成图片",
      docUrl = "https://huggingface.co/litert-community/FLUX.2-klein-4B-LiteRT",
      sourceCodeUrl = "",
      handleModelConfigChangesInTask = true,
      newFeature = true,
      useThemeColor = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    model.instance = VisualCreationWorkbenchInstance()
    onDone("")
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customTaskData = data as CustomTaskData
    VisualCreationScreen(
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      bottomPadding = customTaskData.bottomPadding,
      setAppBarControlsDisabled = customTaskData.setAppBarControlsDisabled,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object VisualCreationTaskModule {
  @Provides
  @IntoSet
  fun provideVisualCreationTask(): CustomTask = VisualCreationTask()

  @Provides
  @IntoSet
  fun provideBonsaiImageTask(): CustomTask = BonsaiImageTask()

  @Provides
  @IntoSet
  fun provideFluxKleinImageTask(): CustomTask = FluxKleinImageTask()
}
