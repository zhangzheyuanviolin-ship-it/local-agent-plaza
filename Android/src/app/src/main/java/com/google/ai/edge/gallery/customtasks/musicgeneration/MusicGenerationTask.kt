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

package com.google.ai.edge.gallery.customtasks.musicgeneration

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
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

class MusicGenerationTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = TASK_ID_LOCAL_MUSIC_GENERATION,
      label = "本地音乐生成",
      category = Category.LLM,
      icon = Icons.Outlined.GraphicEq,
      models = createMusicGenerationModels().toMutableList(),
      description = "使用设备本地LiteRT音乐生成模型，根据文字描述离线生成声音或音乐，并支持播放、保存和分享WAV音频。",
      shortDescription = "离线生成声音和音乐",
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
    try {
      // Use the exact effective standalone Box 0.4.9 runtime generated from the pinned golden
      // commit. This removes the hand-translated Kotlin inference implementation from production.
      model.instance = GoldenBox049RuntimeEngine(context = context, model = model)
      onDone("")
    } catch (e: Exception) {
      model.instance = null
      onDone(e.message ?: "Failed to load music generation model")
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    (model.instance as? MusicGenerationEngine)?.close()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customTaskData = data as CustomTaskData
    MusicGenerationScreen(
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      bottomPadding = customTaskData.bottomPadding,
      setAppBarControlsDisabled = customTaskData.setAppBarControlsDisabled,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object MusicGenerationTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return MusicGenerationTask()
  }
}
