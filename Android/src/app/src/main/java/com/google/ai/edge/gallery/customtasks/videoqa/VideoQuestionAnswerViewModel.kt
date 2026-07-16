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

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.proto.UserData
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModelBase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val KEY_VIDEO_QA_MODE = "video_qa_mode"
private const val KEY_VIDEO_QA_FRAME_COUNT = "video_qa_frame_count"
private const val KEY_VIDEO_QA_FRAME_SIZE = "video_qa_frame_size"

data class VideoQuestionAnswerUiState(
  val mode: VideoQaMode = VideoQaMode.COMPLETE_VIDEO,
  val frameCount: Int = VIDEO_QA_DEFAULT_FRAME_COUNT,
  val frameSize: Int = VIDEO_QA_DEFAULT_FRAME_SIZE,
  val keyFrameInputs: List<String> = List(VIDEO_QA_MAX_KEYFRAME_INPUTS) { "" },
  val selectedVideoLabel: String = "",
  val durationMs: Long = 0L,
  val frames: List<VideoFrame> = emptyList(),
  val processingVideo: Boolean = false,
  val statusText: String = "请选择一个 MP4 视频，然后按当前设置抽取画面帧。",
  val errorText: String = "",
  val currentPrompt: String = "",
  val currentAnswer: String = "",
  val lastSubmittedPrompt: String = "",
)

@HiltViewModel
class VideoQuestionAnswerViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
  private val dataStoreRepository: DataStoreRepository,
) : LlmChatViewModelBase(systemPromptRepository, userDataDataStore) {
  private val _videoUiState = MutableStateFlow(createInitialState())
  val videoUiState = _videoUiState.asStateFlow()

  fun updateMode(mode: VideoQaMode) {
    _videoUiState.update { it.copy(mode = mode, errorText = "") }
    dataStoreRepository.saveSecret(KEY_VIDEO_QA_MODE, mode.name)
  }

  fun updateFrameCount(frameCount: Int) {
    val normalized = frameCount.coerceIn(1, 20)
    _videoUiState.update { it.copy(frameCount = normalized) }
    dataStoreRepository.saveSecret(KEY_VIDEO_QA_FRAME_COUNT, normalized.toString())
  }

  fun updateFrameSize(frameSize: Int) {
    val normalized = listOf(384, 512, 768, 1024).minBy { kotlin.math.abs(it - frameSize) }
    _videoUiState.update { it.copy(frameSize = normalized) }
    dataStoreRepository.saveSecret(KEY_VIDEO_QA_FRAME_SIZE, normalized.toString())
  }

  fun updateKeyFrameInput(index: Int, value: String) {
    _videoUiState.update { state ->
      if (index !in state.keyFrameInputs.indices) return@update state
      val inputs = state.keyFrameInputs.toMutableList()
      inputs[index] = value
      state.copy(keyFrameInputs = inputs, errorText = "")
    }
  }

  fun updatePrompt(prompt: String) {
    _videoUiState.update { it.copy(currentPrompt = prompt) }
  }

  fun processVideo(context: Context, uri: Uri, label: String) {
    val state = _videoUiState.value
    val invalidInputs =
      if (state.mode == VideoQaMode.KEY_FRAMES) validateKeyFrameInputs(state.keyFrameInputs)
      else emptyList()
    if (invalidInputs.isNotEmpty()) {
      _videoUiState.update {
        it.copy(errorText = "无法识别这些时间点：${invalidInputs.joinToString("，")}。请输入 12、12.5 或 01:05 这样的格式。")
      }
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      _videoUiState.update {
        it.copy(
          processingVideo = true,
          selectedVideoLabel = label,
          frames = emptyList(),
          durationMs = 0L,
          errorText = "",
          statusText = "正在处理视频并抽取画面帧。",
        )
      }
      try {
        val latest = _videoUiState.value
        val result =
          extractVideoFrames(
            context = context,
            uri = uri,
            mode = latest.mode,
            frameCount = latest.frameCount,
            frameSize = latest.frameSize,
            keyFrameInputs = latest.keyFrameInputs,
          )
        val status =
          if (result.frames.isNotEmpty()) {
            "视频处理完成，已抽取 ${result.frames.size} 张画面帧。"
          } else {
            "没有从视频中抽取到可用画面帧，请检查视频文件或时间点设置。"
          }
        _videoUiState.update {
          it.copy(
            processingVideo = false,
            frames = result.frames,
            durationMs = result.durationMs,
            statusText = status,
            errorText = if (result.frames.isEmpty()) status else "",
          )
        }
      } catch (e: Exception) {
        _videoUiState.update {
          it.copy(
            processingVideo = false,
            frames = emptyList(),
            errorText = "视频处理失败：${e.message ?: e::class.java.simpleName}",
            statusText = "视频处理失败。",
          )
        }
      }
    }
  }

  fun askVideo(model: Model) {
    val state = _videoUiState.value
    val prompt = state.currentPrompt.trim()
    if (state.frames.isEmpty()) {
      _videoUiState.update { it.copy(errorText = "请先上传视频并完成画面帧抽取。") }
      return
    }
    if (prompt.isBlank()) {
      _videoUiState.update { it.copy(errorText = "请输入要询问视频的问题。") }
      return
    }
    val bitmaps = prepareVideoFrameBitmapsForModel(state.frames)
    val runtimePrompt = buildRuntimePrompt(state = state, prompt = prompt)
    _videoUiState.update {
      it.copy(currentAnswer = "", lastSubmittedPrompt = prompt, errorText = "", statusText = "正在发送视频画面帧给本地模型。")
    }
    generateResponse(
      model = model,
      input = runtimePrompt,
      images = bitmaps,
      onDone = {
        _videoUiState.update {
          it.copy(statusText = "视频问答完成。")
        }
      },
      onError = { error ->
        _videoUiState.update {
          it.copy(errorText = error, statusText = "视频问答失败。")
        }
      },
      allowThinking = false,
      onInterceptPartialResult = { _, partialResult, done, _ ->
        if (partialResult.isNotEmpty()) {
          _videoUiState.update {
            it.copy(currentAnswer = processLlmResponse("${it.currentAnswer}$partialResult"))
          }
        }
        if (done) {
          _videoUiState.update { it.copy(statusText = "视频问答完成。") }
        }
        true
      },
    )
  }

  private fun buildRuntimePrompt(state: VideoQuestionAnswerUiState, prompt: String): String {
    val frameDescriptions =
      state.frames.mapIndexed { index, frame ->
        "Frame ${index + 1}：视频时间 ${formatVideoFrameTimeSeconds(frame.timeUs)} 秒"
      }
    val modeText =
      if (state.mode == VideoQaMode.COMPLETE_VIDEO) "完整视频描述模式" else "关键帧问答模式"
    return buildString {
      append("你正在分析一个视频。用户已经上传视频，系统从视频中抽取了多张画面帧并按时间顺序作为图片输入附加给你。\n")
      append("每张图片左上角都写有 Frame 编号和视频时间戳。请优先以图片内的 Frame 编号为顺序依据，不要自行重排图片。\n")
      append("当前模式：")
      append(modeText)
      append("。\n")
      append("帧时间轴：\n")
      append(frameDescriptions.joinToString("\n"))
      append("\n\n请严格根据这些画面帧进行分析。如果画面之间存在时间跳跃，请说明推断的不确定性。")
      append("用户问题：")
      append(prompt)
    }
  }

  private fun createInitialState(): VideoQuestionAnswerUiState {
    val mode =
      dataStoreRepository.readSecret(KEY_VIDEO_QA_MODE)
        ?.let { saved -> VideoQaMode.entries.firstOrNull { it.name == saved } }
        ?: VideoQaMode.COMPLETE_VIDEO
    val frameCount =
      dataStoreRepository.readSecret(KEY_VIDEO_QA_FRAME_COUNT)?.toIntOrNull()?.coerceIn(1, 20)
        ?: VIDEO_QA_DEFAULT_FRAME_COUNT
    val frameSize =
      dataStoreRepository.readSecret(KEY_VIDEO_QA_FRAME_SIZE)?.toIntOrNull()?.let { saved ->
        listOf(384, 512, 768, 1024).minBy { kotlin.math.abs(it - saved) }
      } ?: VIDEO_QA_DEFAULT_FRAME_SIZE
    return VideoQuestionAnswerUiState(mode = mode, frameCount = frameCount, frameSize = frameSize)
  }
}
