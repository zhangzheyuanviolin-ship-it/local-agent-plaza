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

class VisualCreationWorkbenchInstance

/**
 * The model allowlist refresh in ModelManagerViewModel removes every non-imported custom-task model
 * before restoring the built-in local model sets. Bonsai is app-owned and must remain reachable
 * through that refresh. This list behaves normally for all user/model operations, while the
 * allowlist cleanup iterator deliberately keeps the Bonsai entry alive.
 */
private class BonsaiPreservingModelList(models: List<Model>) : AbstractMutableList<Model>() {
  private val delegate = models.toMutableList()

  override val size: Int
    get() = delegate.size

  override fun get(index: Int): Model = delegate[index]

  override fun set(index: Int, element: Model): Model = delegate.set(index, element)

  override fun add(index: Int, element: Model) {
    delegate.add(index, element)
  }

  override fun removeAt(index: Int): Model = delegate.removeAt(index)

  override fun clear() {
    delegate.removeAll { it.name != BONSAI_IMAGE_MODEL_ID }
  }

  override fun iterator(): MutableIterator<Model> {
    return object : MutableIterator<Model> {
      private var cursor = 0
      private var lastReturned = -1

      override fun hasNext(): Boolean = cursor < delegate.size

      override fun next(): Model {
        if (!hasNext()) throw NoSuchElementException()
        lastReturned = cursor
        return delegate[cursor++]
      }

      override fun remove() {
        check(lastReturned >= 0) { "next() must be called before remove()" }
        if (delegate[lastReturned].name != BONSAI_IMAGE_MODEL_ID) {
          delegate.removeAt(lastReturned)
          if (lastReturned < cursor) cursor--
        }
        lastReturned = -1
      }
    }
  }
}

private fun bonsaiVisualModels(): MutableList<Model> =
  BonsaiPreservingModelList(listOf(createBonsaiImageModel()) + createVisualCreationImageModels())

private fun bonsaiOnlyModels(): MutableList<Model> =
  BonsaiPreservingModelList(listOf(createBonsaiImageModel()))

class VisualCreationTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = TASK_ID_LOCAL_VISUAL_CREATION,
      label = "本地视觉创作",
      category = Category.LLM,
      icon = Icons.Outlined.Image,
      models = bonsaiVisualModels(),
      description = "在设备本地生成图片，并把生成结果继续交给本地视觉语言模型进行描述、评审、分析和文本创作。包含 Bonsai Image 4B LiteRT。",
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

@Module
@InstallIn(SingletonComponent::class)
internal object VisualCreationTaskModule {
  @Provides
  @IntoSet
  fun provideVisualCreationTask(): CustomTask = VisualCreationTask()

  @Provides
  @IntoSet
  fun provideBonsaiImageTask(): CustomTask = BonsaiImageTask()
}
