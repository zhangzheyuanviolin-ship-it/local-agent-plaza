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

package com.google.ai.edge.gallery.customtasks.aikeyboard

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardVoice
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

const val TASK_ID_AI_KEYBOARD = "local_ai_keyboard"

const val AI_KEYBOARD_SETTINGS_MODEL = "ai-keyboard-settings"

class AiKeyboardSettingsInstance

fun createAiKeyboardSettingsModel(): Model {
  return Model(
    name = AI_KEYBOARD_SETTINGS_MODEL,
    displayName = "AI 键盘设置",
    info = "管理离线语音输入模型，启用系统输入法，并测试按需加载的本地语音输入链路。",
    localFileRelativeDirPathOverride = "ai_keyboard_settings",
    showBenchmarkButton = false,
    showRunAgainButton = false,
  )
}

class AiKeyboardTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = TASK_ID_AI_KEYBOARD,
      label = "AI 键盘",
      category = Category.LLM,
      icon = Icons.Outlined.KeyboardVoice,
      models = mutableListOf(createAiKeyboardSettingsModel()),
      description = "把本地智能体广场作为系统输入法使用，第一阶段支持离线语音输入和中英文 Vosk 模型管理。",
      shortDescription = "本地语音输入法",
      docUrl = "https://github.com/zhangzheyuanviolin-ship-it/offline-voice-ime",
      sourceCodeUrl = "https://github.com/zhangzheyuanviolin-ship-it/offline-voice-ime",
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
    model.instance = AiKeyboardSettingsInstance()
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
    AiKeyboardScreen(bottomPadding = customTaskData.bottomPadding)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AiKeyboardTaskModule {
  @Provides
  @IntoSet
  fun provideAiKeyboardTask(): CustomTask {
    return AiKeyboardTask()
  }
}
