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
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "MusicGenerationViewModel"

data class MusicGenerationUiState(
  val prompt: String = "",
  val durationInput: String = formatMusicDurationSeconds(DEFAULT_MUSIC_DURATION_SECONDS),
  val isGenerating: Boolean = false,
  val isPlaying: Boolean = false,
  val progress: Float = 0f,
  val result: GeneratedMusicFile? = null,
  val statusText: String = "",
)

@HiltViewModel
class MusicGenerationViewModel
@Inject
constructor(@ApplicationContext private val context: Context) : ViewModel() {
  private val _uiState = MutableStateFlow(MusicGenerationUiState())
  val uiState: StateFlow<MusicGenerationUiState> = _uiState.asStateFlow()
  private var mediaPlayer: MediaPlayer? = null

  fun updatePrompt(prompt: String) {
    _uiState.update { it.copy(prompt = prompt) }
  }

  fun updateDurationInput(input: String) {
    _uiState.update { it.copy(durationInput = input) }
  }

  fun generate(model: Model) {
    val state = uiState.value
    val prompt = state.prompt.trim()
    val seconds = parseMusicDurationSeconds(state.durationInput)
    if (prompt.isBlank()) {
      _uiState.update { it.copy(statusText = "请输入要生成的声音或音乐描述。") }
      return
    }
    if (seconds == null) {
      _uiState.update { it.copy(statusText = "请输入大于0的秒数。") }
      return
    }
    val engine = model.instance as? MusicGenerationEngine
    if (engine == null) {
      _uiState.update { it.copy(statusText = "模型尚未加载完成。") }
      return
    }
    stopPlayback()
    viewModelScope.launch(Dispatchers.Default) {
      _uiState.update {
        it.copy(isGenerating = true, progress = 0f, statusText = "正在生成音频...", result = null)
      }
      try {
        val result =
          engine.generate(context = context, prompt = prompt, durationSeconds = seconds) { progress ->
            _uiState.update { it.copy(progress = progress.coerceIn(0f, 1f)) }
          }
        _uiState.update {
          it.copy(
            isGenerating = false,
            progress = 1f,
            result = result,
            statusText = "生成完成，实际音频长度 ${formatMusicDurationSeconds(result.durationSeconds)} 秒。",
          )
        }
      } catch (e: Exception) {
        Log.e(TAG, "Music generation failed", e)
        _uiState.update {
          it.copy(
            isGenerating = false,
            progress = 0f,
            statusText = "生成失败：${e.message ?: "未知错误"}",
          )
        }
      }
    }
  }

  fun togglePlayback() {
    val file = uiState.value.result?.file ?: return
    if (uiState.value.isPlaying) {
      stopPlayback()
      return
    }
    try {
      mediaPlayer =
        MediaPlayer().apply {
          setDataSource(file.absolutePath)
          setOnCompletionListener {
            it.release()
            if (mediaPlayer == it) {
              mediaPlayer = null
            }
            _uiState.update { state -> state.copy(isPlaying = false) }
          }
          prepare()
          start()
        }
      _uiState.update { it.copy(isPlaying = true, statusText = "正在播放生成音频。") }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to play generated audio", e)
      stopPlayback()
      _uiState.update { it.copy(statusText = "播放失败：${e.message ?: "未知错误"}") }
    }
  }

  fun saveResult() {
    val result = uiState.value.result ?: return
    val prefix =
      if (result.file.name.startsWith("soundgenhd")) {
        "SoundGenHD"
      } else {
        "SoundGen"
      }
    val saved = saveMusicFileToMediaStore(context, result.file, prefix)
    _uiState.update {
      it.copy(statusText = if (saved) "已保存到Music/SoundGen。" else "保存失败。")
    }
  }

  fun shareResult() {
    val result = uiState.value.result ?: return
    try {
      shareMusicFile(context, result.file)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to share generated audio", e)
      _uiState.update { it.copy(statusText = "分享失败：${e.message ?: "未知错误"}") }
    }
  }

  fun stopPlayback() {
    mediaPlayer?.run {
      try {
        stop()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to stop generated audio playback", e)
      }
      release()
    }
    mediaPlayer = null
    _uiState.update { it.copy(isPlaying = false) }
  }

  override fun onCleared() {
    stopPlayback()
    super.onCleared()
  }
}
