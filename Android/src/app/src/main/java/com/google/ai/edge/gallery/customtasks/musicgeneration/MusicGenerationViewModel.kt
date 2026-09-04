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
import android.os.SystemClock
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
import kotlinx.coroutines.withContext

private const val TAG = "MusicGenerationViewModel"
private const val DEFAULT_PROMPT = "calm piano melody, warm harmony, cinematic, 90 bpm"

data class MusicGenerationUiState(
  val prompt: String = DEFAULT_PROMPT,
  val durationSeconds: Float = DEFAULT_MUSIC_DURATION_SECONDS,
  val accelerationMode: MusicAccelerationMode = MusicAccelerationMode.AUTO,
  val isGenerating: Boolean = false,
  val isPlaying: Boolean = false,
  val isPaused: Boolean = false,
  val progress: Float = 0f,
  val result: GeneratedMusicFile? = null,
  val generationElapsedMs: Long = -1L,
  val accelerationReport: String = "",
  val statusText: String = "就绪",
  val activeModelName: String = "",
)

@HiltViewModel
class MusicGenerationViewModel
@Inject
constructor(@ApplicationContext private val context: Context) : ViewModel() {
  private val _uiState = MutableStateFlow(MusicGenerationUiState())
  val uiState: StateFlow<MusicGenerationUiState> = _uiState.asStateFlow()
  private var mediaPlayer: MediaPlayer? = null

  fun ensureModelDefaults(model: Model) {
    if (uiState.value.activeModelName == model.name) return
    val spec = model.musicGenerationSpec() ?: return
    stopPlayback(updateStatus = false)
    _uiState.update {
      it.copy(
        durationSeconds = spec.defaultDurationSeconds,
        activeModelName = model.name,
        result = null,
        generationElapsedMs = -1L,
        accelerationReport = "",
        statusText = "就绪。模型文件已校验。",
      )
    }
  }

  fun updatePrompt(prompt: String) {
    _uiState.update { it.copy(prompt = prompt) }
  }

  fun updateDurationSeconds(seconds: Float) {
    _uiState.update { it.copy(durationSeconds = seconds) }
  }

  fun updateAccelerationMode(mode: MusicAccelerationMode) {
    if (uiState.value.isGenerating) return
    _uiState.update { it.copy(accelerationMode = mode) }
  }

  fun generate(model: Model) {
    val state = uiState.value
    val prompt = state.prompt.trim()
    val spec = model.musicGenerationSpec()
    if (prompt.isBlank()) {
      _uiState.update { it.copy(statusText = "音乐描述不能为空。") }
      return
    }
    if (spec == null) {
      _uiState.update { it.copy(statusText = "当前模型不支持本地音乐生成。") }
      return
    }
    val seconds = state.durationSeconds.coerceIn(spec.minDurationSeconds, spec.maxDurationSeconds)
    val engine = model.instance as? MusicGenerationEngine
    if (engine == null) {
      _uiState.update { it.copy(statusText = "模型尚未加载完成。") }
      return
    }

    stopPlayback(updateStatus = false)
    val mode = state.accelerationMode
    val startedAt = SystemClock.elapsedRealtime()
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isGenerating = true,
          isPlaying = false,
          isPaused = false,
          progress = 0f,
          result = null,
          generationElapsedMs = -1L,
          accelerationReport = "",
          statusText = loadingText(model, mode, seconds),
        )
      }
      try {
        val result =
          withContext(Dispatchers.Default) {
            engine.generate(
              context = context,
              prompt = prompt,
              durationSeconds = seconds,
              accelerationMode = mode,
            ) { progress ->
              _uiState.update {
                it.copy(
                  progress = progress.coerceIn(0f, 1f),
                  statusText =
                    "正在生成 ${model.displayName}：${(progress.coerceIn(0f, 1f) * 100f).toInt()}%",
                )
              }
            }
          }
        val elapsed = maxOf(0L, SystemClock.elapsedRealtime() - startedAt)
        val report = engine.accelerationReport()
        _uiState.update {
          it.copy(
            isGenerating = false,
            progress = 1f,
            result = result,
            generationElapsedMs = elapsed,
            accelerationReport = report,
            statusText = "音频生成完成：${result.file.absolutePath}${generationElapsedText(elapsed)}",
          )
        }
        playGenerated(result.file)
      } catch (e: Throwable) {
        Log.e(TAG, "Music generation failed", e)
        _uiState.update {
          it.copy(
            isGenerating = false,
            progress = 0f,
            statusText = "生成失败：${e.message ?: e.javaClass.simpleName}",
          )
        }
      }
    }
  }

  fun togglePlayback() {
    val result = uiState.value.result ?: run {
      _uiState.update { it.copy(statusText = "当前还没有可播放的生成音频。") }
      return
    }
    try {
      val player = mediaPlayer
      if (player != null) {
        if (player.isPlaying) {
          player.pause()
          _uiState.update {
            it.copy(
              isPlaying = false,
              isPaused = true,
              statusText = "已暂停：${result.file.name}${generationElapsedText(it.generationElapsedMs)}",
            )
          }
        } else {
          player.start()
          _uiState.update {
            it.copy(
              isPlaying = true,
              isPaused = false,
              statusText = playbackStatus("正在播放", result.file.name, it),
            )
          }
        }
        return
      }
      playGenerated(result.file)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to toggle generated audio playback", e)
      stopPlayback(updateStatus = false)
      _uiState.update { it.copy(statusText = "播放失败：${e.message ?: "未知错误"}") }
    }
  }

  fun exportResult() {
    val result = uiState.value.result ?: return
    val saved = saveMusicFileToMediaStore(context, result.file)
    _uiState.update {
      it.copy(statusText = if (saved) "音频已导出到 Music/BoxLocalMusic。" else "音频导出失败。")
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

  fun stopPlayback(updateStatus: Boolean = false) {
    mediaPlayer?.run {
      try {
        stop()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to stop generated audio playback", e)
      }
      release()
    }
    mediaPlayer = null
    _uiState.update {
      it.copy(
        isPlaying = false,
        isPaused = false,
        statusText = if (updateStatus) "播放已停止。" else it.statusText,
      )
    }
  }

  private fun playGenerated(file: java.io.File) {
    try {
      mediaPlayer?.release()
      val player =
        MediaPlayer().apply {
          setDataSource(file.absolutePath)
          setOnCompletionListener { completed ->
            try {
              completed.release()
            } catch (_: Throwable) {
            }
            if (mediaPlayer == completed) mediaPlayer = null
            _uiState.update {
              it.copy(
                isPlaying = false,
                isPaused = false,
                statusText = "播放结束：${file.name}${generationElapsedText(it.generationElapsedMs)}",
              )
            }
          }
          prepare()
          start()
        }
      mediaPlayer = player
      _uiState.update {
        it.copy(
          isPlaying = true,
          isPaused = false,
          statusText = playbackStatus("正在播放", file.name, it),
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to play generated audio", e)
      stopPlayback(updateStatus = false)
      _uiState.update { it.copy(statusText = "播放失败：${e.message ?: "未知错误"}") }
    }
  }

  private fun playbackStatus(prefix: String, fileName: String, state: MusicGenerationUiState): String {
    return buildString {
      append(prefix).append("：").append(fileName)
      append(generationElapsedText(state.generationElapsedMs))
      if (state.accelerationReport.isNotBlank()) {
        append("\n加速诊断：").append(state.accelerationReport)
      }
    }
  }

  private fun loadingText(model: Model, mode: MusicAccelerationMode, seconds: Float): String {
    val modeText =
      when (mode) {
        MusicAccelerationMode.AUTO ->
          if (model.name == "soundgen") "自动：SoundGen 优先 GPU/CPU 回退" else "自动：CPU"
        MusicAccelerationMode.CPU -> "强制 CPU"
        MusicAccelerationMode.GPU ->
          if (model.name == "soundgen_hd_long") {
            "GPU：Text=CPU，Core=GPU+CPU 混合，Decoder=CPU（Long 稳定策略）"
          } else {
            "GPU：Text=CPU，Core/Decoder=GPU+CPU 混合"
          }
      }
    return "正在加载 ${model.displayName}。加速模式：$modeText。请求时长：${formatMusicDurationSeconds(seconds)} 秒。"
  }

  private fun generationElapsedText(elapsedMs: Long): String {
    if (elapsedMs < 0L) return ""
    return "\n生成耗时：${String.format(java.util.Locale.US, "%.2f", elapsedMs / 1000.0)} 秒"
  }

  override fun onCleared() {
    stopPlayback(updateStatus = false)
    super.onCleared()
  }
}
