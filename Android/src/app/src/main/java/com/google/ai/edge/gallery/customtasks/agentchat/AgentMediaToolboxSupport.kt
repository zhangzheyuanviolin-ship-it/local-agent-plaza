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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

object AgentMediaToolboxSupport {
  private const val MAX_CONCAT_INPUTS = 5
  private const val DEFAULT_IMAGE_VIDEO_DURATION_SECONDS = 5.0
  private val timestampFormatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

  enum class Mode(val value: String) {
    IMAGE("image"),
    AUDIO("audio"),
    VIDEO("video"),
  }

  fun run(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    operation: String,
    parameters: JSONObject,
  ): JSONObject {
    val normalizedOperation = operation.trim().lowercase(Locale.US)
    return when (normalizedOperation) {
      "media_image_info" -> {
        requireMode(config, Mode.IMAGE)
        imageInfo(context, workspaceConfig, parameters.requirePath("input_path", "path", "image_path"))
      }
      "media_image_resize" -> {
        requireMode(config, Mode.IMAGE)
        imageResize(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          inputPath = parameters.requirePath("input_path", "path", "image_path"),
          outputPath = parameters.optString("output_path"),
          target = parameters.optString("target").ifBlank { parameters.optString("size") },
          width = parameters.optIntOrNull("width"),
          height = parameters.optIntOrNull("height"),
        )
      }
      "media_image_convert" -> {
        requireMode(config, Mode.IMAGE)
        imageConvert(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          inputPath = parameters.requirePath("input_path", "path", "image_path"),
          outputPath = parameters.optString("output_path"),
          targetFormat = parameters.optString("target_format").ifBlank { parameters.optString("format") },
        )
      }
      "media_image_to_video" -> {
        requireMode(config, Mode.IMAGE)
        imageToVideo(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          inputPath = parameters.requirePath("input_path", "path", "image_path"),
          outputPath = parameters.optString("output_path"),
          durationSeconds =
            parameters.optDouble("duration_seconds", DEFAULT_IMAGE_VIDEO_DURATION_SECONDS)
              .coerceIn(1.0, 60.0),
        )
      }
      "media_audio_info" -> {
        requireMode(config, Mode.AUDIO)
        audioInfo(context, workspaceConfig, parameters.requirePath("input_path", "path", "audio_path"))
      }
      "media_audio_convert" -> {
        requireMode(config, Mode.AUDIO)
        audioConvert(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          inputPath = parameters.requirePath("input_path", "path", "audio_path"),
          outputPath = parameters.optString("output_path"),
          targetFormat = parameters.optString("target_format").ifBlank { parameters.optString("format") },
        )
      }
      "media_audio_concat" -> {
        requireMode(config, Mode.AUDIO)
        audioConcat(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          inputPaths = parameters.optStringArray("input_paths", "paths", "audio_paths"),
          outputPath = parameters.optString("output_path"),
          targetFormat = parameters.optString("target_format").ifBlank { parameters.optString("format") },
        )
      }
      "media_audio_trim" -> {
        requireMode(config, Mode.AUDIO)
        audioTrim(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          inputPath = parameters.requirePath("input_path", "path", "audio_path"),
          outputPath = parameters.optString("output_path"),
          start = parameters.optString("start").ifBlank { parameters.optString("start_time") },
          end = parameters.optString("end").ifBlank { parameters.optString("end_time") },
        )
      }
      "media_audio_mix" -> {
        requireMode(config, Mode.AUDIO)
        audioMix(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          primaryPath = parameters.requirePath("primary_path", "input_path", "path"),
          secondaryPath = parameters.requirePath("secondary_path", "overlay_path", "background_path"),
          outputPath = parameters.optString("output_path"),
          primaryVolume = parameters.optDouble("primary_volume", 1.0).coerceIn(0.0, 5.0),
          secondaryVolume = parameters.optDouble("secondary_volume", 1.0).coerceIn(0.0, 5.0),
          secondaryStart = parameters.optString("secondary_start").ifBlank { parameters.optString("start_time") },
          loopSecondary = parameters.optBoolean("loop_secondary", false),
          targetFormat = parameters.optString("target_format").ifBlank { parameters.optString("format") },
        )
      }
      "media_video_info" -> {
        requireMode(config, Mode.VIDEO)
        videoInfo(context, workspaceConfig, parameters.requirePath("input_path", "path", "video_path"))
      }
      "media_video_convert" -> {
        requireMode(config, Mode.VIDEO)
        videoConvert(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          inputPath = parameters.requirePath("input_path", "path", "video_path"),
          outputPath = parameters.optString("output_path"),
          targetFormat = parameters.optString("target_format").ifBlank { parameters.optString("format") },
        )
      }
      "media_video_concat" -> {
        requireMode(config, Mode.VIDEO)
        videoConcat(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          inputPaths = parameters.optStringArray("input_paths", "paths", "video_paths"),
          outputPath = parameters.optString("output_path"),
        )
      }
      "media_video_trim" -> {
        requireMode(config, Mode.VIDEO)
        videoTrim(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          inputPath = parameters.requirePath("input_path", "path", "video_path"),
          outputPath = parameters.optString("output_path"),
          start = parameters.optString("start").ifBlank { parameters.optString("start_time") },
          end = parameters.optString("end").ifBlank { parameters.optString("end_time") },
        )
      }
      "media_video_extract_audio" -> {
        requireMode(config, Mode.VIDEO)
        videoExtractAudio(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          inputPath = parameters.requirePath("input_path", "path", "video_path"),
          outputPath = parameters.optString("output_path"),
          targetFormat = parameters.optString("target_format").ifBlank { parameters.optString("format") },
        )
      }
      "media_video_mute" -> {
        requireMode(config, Mode.VIDEO)
        videoMute(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          inputPath = parameters.requirePath("input_path", "path", "video_path"),
          outputPath = parameters.optString("output_path"),
        )
      }
      "media_video_add_audio" -> {
        requireMode(config, Mode.VIDEO)
        videoAddAudio(
          context = context,
          workspaceConfig = workspaceConfig,
          config = config,
          videoPath = parameters.requirePath("video_path", "input_path", "path"),
          audioPath = parameters.requirePath("audio_path", "overlay_path", "music_path"),
          outputPath = parameters.optString("output_path"),
          audioVolume = parameters.optDouble("audio_volume", 1.0).coerceIn(0.0, 5.0),
          originalVolume = parameters.optDouble("original_volume", 1.0).coerceIn(0.0, 5.0),
        )
      }
      else ->
        JSONObject()
          .put("status", "failed")
          .put("operation", normalizedOperation)
          .put("error", "Unsupported media toolbox operation: $operation")
    }
  }

  private fun imageInfo(context: Context, workspaceConfig: FileWorkspaceConfig, inputPath: String): JSONObject {
    val root = workspaceRoot(context, workspaceConfig)
    val document = resolveWorkspaceFile(root, inputPath)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val inputStream =
      context.contentResolver.openInputStream(document.uri)
        ?: throw IOException("Failed to open image file stream: $inputPath")
    inputStream.use { input ->
      BitmapFactory.decodeStream(input, null, options)
    }
    if (options.outWidth <= 0 || options.outHeight <= 0) {
      throw IOException("Unsupported or unreadable image file: $inputPath")
    }
    return baseResult("media_image_info")
      .put("path", inputPath)
      .put("mime_type", options.outMimeType.orEmpty())
      .put("width", options.outWidth)
      .put("height", options.outHeight)
      .put("resolution", "${options.outWidth}x${options.outHeight}")
      .put("file_bytes", document.length())
  }

  private fun imageResize(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    inputPath: String,
    outputPath: String,
    target: String,
    width: Int?,
    height: Int?,
  ): JSONObject {
    val bitmap = decodeWorkspaceBitmap(context, workspaceConfig, inputPath)
    val targetSize = resolveImageTarget(bitmap.width, bitmap.height, target, width, height)
    val resized = Bitmap.createScaledBitmap(bitmap, targetSize.first, targetSize.second, true)
    val ext = inputPath.extensionOr("jpg")
    val targetPath = ensureMediaOutputPath(outputPath, "resized-image", ext)
    val bytesWritten = writeBitmap(context, workspaceConfig, config, targetPath, resized, ext)
    return baseResult("media_image_resize")
      .put("path", targetPath)
      .put("input_path", inputPath)
      .put("width", targetSize.first)
      .put("height", targetSize.second)
      .put("bytes_written", bytesWritten)
  }

  private fun imageConvert(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    inputPath: String,
    outputPath: String,
    targetFormat: String,
  ): JSONObject {
    val format = normalizeImageFormat(targetFormat)
    val bitmap = decodeWorkspaceBitmap(context, workspaceConfig, inputPath)
    val targetPath = ensureMediaOutputPath(outputPath, "converted-image", format)
    val bytesWritten = writeBitmap(context, workspaceConfig, config, targetPath, bitmap, format)
    return baseResult("media_image_convert")
      .put("path", targetPath)
      .put("input_path", inputPath)
      .put("format", format)
      .put("bytes_written", bytesWritten)
  }

  private fun imageToVideo(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    inputPath: String,
    outputPath: String,
    durationSeconds: Double,
  ): JSONObject {
    val workDir = newWorkDir(context)
    val input = copyWorkspaceToTemp(context, workspaceConfig, inputPath, workDir)
    val output = File(workDir, "image-video.mp4")
    runFfmpeg(
      arrayOf(
        "-y",
        "-loop",
        "1",
        "-t",
        formatSeconds(durationSeconds),
        "-i",
        input.absolutePath,
        "-vf",
        "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p",
        "-r",
        "30",
        "-c:v",
        "mpeg4",
        "-q:v",
        "4",
        output.absolutePath,
      )
    )
    val targetPath = ensureMediaOutputPath(outputPath, "image-video", "mp4")
    val bytesWritten = copyTempToWorkspace(context, workspaceConfig, config, output, targetPath)
    return baseResult("media_image_to_video")
      .put("path", targetPath)
      .put("input_path", inputPath)
      .put("duration_seconds", durationSeconds)
      .put("bytes_written", bytesWritten)
  }

  private fun audioInfo(context: Context, workspaceConfig: FileWorkspaceConfig, inputPath: String): JSONObject {
    val temp = copyWorkspaceToTemp(context, workspaceConfig, inputPath, newWorkDir(context))
    val metadata = readMediaMetadata(temp.absolutePath)
    return baseResult("media_audio_info")
      .put("path", inputPath)
      .put("duration_ms", metadata.durationMs)
      .put("duration_seconds", metadata.durationMs / 1000.0)
      .put("mime_type", metadata.mimeType)
      .put("bitrate", metadata.bitrate)
      .put("has_audio", hasTrack(temp.absolutePath, audio = true))
      .put("file_bytes", temp.length())
  }

  private fun audioConvert(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    inputPath: String,
    outputPath: String,
    targetFormat: String,
  ): JSONObject {
    val format = normalizeAudioFormat(targetFormat)
    val workDir = newWorkDir(context)
    val input = copyWorkspaceToTemp(context, workspaceConfig, inputPath, workDir)
    val output = File(workDir, "converted-audio.$format")
    runFfmpeg(arrayOf("-y", "-i", input.absolutePath, "-vn") + audioCodecArgs(format) + arrayOf(output.absolutePath))
    val targetPath = ensureMediaOutputPath(outputPath, "converted-audio", format)
    val bytesWritten = copyTempToWorkspace(context, workspaceConfig, config, output, targetPath)
    return baseResult("media_audio_convert")
      .put("path", targetPath)
      .put("input_path", inputPath)
      .put("format", format)
      .put("bytes_written", bytesWritten)
  }

  private fun audioConcat(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    inputPaths: List<String>,
    outputPath: String,
    targetFormat: String,
  ): JSONObject {
    val paths = inputPaths.filter { it.isNotBlank() }.take(MAX_CONCAT_INPUTS)
    if (paths.size < 2) throw IOException("At least two audio input paths are required.")
    val format = normalizeAudioFormat(targetFormat.ifBlank { outputPath.extensionOr("mp3") })
    val workDir = newWorkDir(context)
    val inputs = paths.map { copyWorkspaceToTemp(context, workspaceConfig, it, workDir) }
    val output = File(workDir, "concat-audio.$format")
    val args = mutableListOf("-y")
    inputs.forEach { args += listOf("-i", it.absolutePath) }
    val labels = inputs.indices.joinToString("") { "[$it:a]" }
    args += listOf("-filter_complex", "${labels}concat=n=${inputs.size}:v=0:a=1[a]", "-map", "[a]")
    args += audioCodecArgs(format)
    args += output.absolutePath
    runFfmpeg(args.toTypedArray())
    val targetPath = ensureMediaOutputPath(outputPath, "concat-audio", format)
    val bytesWritten = copyTempToWorkspace(context, workspaceConfig, config, output, targetPath)
    return baseResult("media_audio_concat")
      .put("path", targetPath)
      .put("input_count", paths.size)
      .put("format", format)
      .put("bytes_written", bytesWritten)
  }

  private fun audioTrim(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    inputPath: String,
    outputPath: String,
    start: String,
    end: String,
  ): JSONObject {
    val safeStart = requireTime(start, "start")
    val safeEnd = requireTime(end, "end")
    val format = outputPath.extensionOr(inputPath.extensionOr("mp3")).let(::normalizeAudioFormat)
    val workDir = newWorkDir(context)
    val input = copyWorkspaceToTemp(context, workspaceConfig, inputPath, workDir)
    val output = File(workDir, "trim-audio.$format")
    runFfmpeg(arrayOf("-y", "-ss", safeStart, "-to", safeEnd, "-i", input.absolutePath, "-vn") + audioCodecArgs(format) + arrayOf(output.absolutePath))
    val targetPath = ensureMediaOutputPath(outputPath, "trim-audio", format)
    val bytesWritten = copyTempToWorkspace(context, workspaceConfig, config, output, targetPath)
    return baseResult("media_audio_trim")
      .put("path", targetPath)
      .put("input_path", inputPath)
      .put("start", safeStart)
      .put("end", safeEnd)
      .put("bytes_written", bytesWritten)
  }

  private fun audioMix(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    primaryPath: String,
    secondaryPath: String,
    outputPath: String,
    primaryVolume: Double,
    secondaryVolume: Double,
    secondaryStart: String,
    loopSecondary: Boolean,
    targetFormat: String,
  ): JSONObject {
    val format = normalizeAudioFormat(targetFormat.ifBlank { outputPath.extensionOr("mp3") })
    val workDir = newWorkDir(context)
    val primary = copyWorkspaceToTemp(context, workspaceConfig, primaryPath, workDir)
    val secondary = copyWorkspaceToTemp(context, workspaceConfig, secondaryPath, workDir)
    val output = File(workDir, "mix-audio.$format")
    val args = mutableListOf("-y", "-i", primary.absolutePath)
    if (loopSecondary) args += listOf("-stream_loop", "-1")
    args += listOf("-i", secondary.absolutePath)
    val delayMs = (parseTimeSeconds(secondaryStart.ifBlank { "0" }) * 1000.0).roundToInt().coerceAtLeast(0)
    args += listOf(
      "-filter_complex",
      "[0:a]volume=$primaryVolume[a0];[1:a]volume=$secondaryVolume,adelay=$delayMs|$delayMs[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=0[a]",
      "-map",
      "[a]",
    )
    args += audioCodecArgs(format)
    args += output.absolutePath
    runFfmpeg(args.toTypedArray())
    val targetPath = ensureMediaOutputPath(outputPath, "mix-audio", format)
    val bytesWritten = copyTempToWorkspace(context, workspaceConfig, config, output, targetPath)
    return baseResult("media_audio_mix")
      .put("path", targetPath)
      .put("primary_path", primaryPath)
      .put("secondary_path", secondaryPath)
      .put("loop_secondary", loopSecondary)
      .put("bytes_written", bytesWritten)
  }

  private fun videoInfo(context: Context, workspaceConfig: FileWorkspaceConfig, inputPath: String): JSONObject {
    val temp = copyWorkspaceToTemp(context, workspaceConfig, inputPath, newWorkDir(context))
    val metadata = readMediaMetadata(temp.absolutePath)
    return baseResult("media_video_info")
      .put("path", inputPath)
      .put("duration_ms", metadata.durationMs)
      .put("duration_seconds", metadata.durationMs / 1000.0)
      .put("width", metadata.width)
      .put("height", metadata.height)
      .put("rotation", metadata.rotation)
      .put("mime_type", metadata.mimeType)
      .put("bitrate", metadata.bitrate)
      .put("has_video", hasTrack(temp.absolutePath, audio = false))
      .put("has_audio", hasTrack(temp.absolutePath, audio = true))
      .put("file_bytes", temp.length())
  }

  private fun videoConvert(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    inputPath: String,
    outputPath: String,
    targetFormat: String,
  ): JSONObject {
    val format = normalizeVideoFormat(targetFormat)
    val workDir = newWorkDir(context)
    val input = copyWorkspaceToTemp(context, workspaceConfig, inputPath, workDir)
    val output = File(workDir, "converted-video.$format")
    runFfmpeg(arrayOf("-y", "-i", input.absolutePath) + videoCodecArgs(format) + arrayOf(output.absolutePath))
    val targetPath = ensureMediaOutputPath(outputPath, "converted-video", format)
    val bytesWritten = copyTempToWorkspace(context, workspaceConfig, config, output, targetPath)
    return baseResult("media_video_convert")
      .put("path", targetPath)
      .put("input_path", inputPath)
      .put("format", format)
      .put("bytes_written", bytesWritten)
  }

  private fun videoConcat(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    inputPaths: List<String>,
    outputPath: String,
  ): JSONObject {
    val paths = inputPaths.filter { it.isNotBlank() }.take(MAX_CONCAT_INPUTS)
    if (paths.size < 2) throw IOException("At least two video input paths are required.")
    val workDir = newWorkDir(context)
    val inputs = paths.map { copyWorkspaceToTemp(context, workspaceConfig, it, workDir) }
    val listFile = File(workDir, "concat-list.txt")
    listFile.writeText(inputs.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\\\''")}'" })
    val output = File(workDir, "concat-video.mp4")
    runFfmpeg(arrayOf("-y", "-f", "concat", "-safe", "0", "-i", listFile.absolutePath, "-c", "copy", output.absolutePath))
    val targetPath = ensureMediaOutputPath(outputPath, "concat-video", "mp4")
    val bytesWritten = copyTempToWorkspace(context, workspaceConfig, config, output, targetPath)
    return baseResult("media_video_concat")
      .put("path", targetPath)
      .put("input_count", paths.size)
      .put("bytes_written", bytesWritten)
  }

  private fun videoTrim(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    inputPath: String,
    outputPath: String,
    start: String,
    end: String,
  ): JSONObject {
    val safeStart = requireTime(start, "start")
    val safeEnd = requireTime(end, "end")
    val format = outputPath.extensionOr(inputPath.extensionOr("mp4")).let(::normalizeVideoFormat)
    val workDir = newWorkDir(context)
    val input = copyWorkspaceToTemp(context, workspaceConfig, inputPath, workDir)
    val output = File(workDir, "trim-video.$format")
    runFfmpeg(arrayOf("-y", "-ss", safeStart, "-to", safeEnd, "-i", input.absolutePath, "-c", "copy", output.absolutePath))
    val targetPath = ensureMediaOutputPath(outputPath, "trim-video", format)
    val bytesWritten = copyTempToWorkspace(context, workspaceConfig, config, output, targetPath)
    return baseResult("media_video_trim")
      .put("path", targetPath)
      .put("input_path", inputPath)
      .put("start", safeStart)
      .put("end", safeEnd)
      .put("bytes_written", bytesWritten)
  }

  private fun videoExtractAudio(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    inputPath: String,
    outputPath: String,
    targetFormat: String,
  ): JSONObject {
    val format = normalizeAudioFormat(targetFormat.ifBlank { outputPath.extensionOr("mp3") })
    val workDir = newWorkDir(context)
    val input = copyWorkspaceToTemp(context, workspaceConfig, inputPath, workDir)
    if (!hasTrack(input.absolutePath, audio = true)) {
      throw IOException("Video does not contain an audio track.")
    }
    val output = File(workDir, "video-audio.$format")
    runFfmpeg(arrayOf("-y", "-i", input.absolutePath, "-vn") + audioCodecArgs(format) + arrayOf(output.absolutePath))
    val targetPath = ensureMediaOutputPath(outputPath, "video-audio", format)
    val bytesWritten = copyTempToWorkspace(context, workspaceConfig, config, output, targetPath)
    return baseResult("media_video_extract_audio")
      .put("path", targetPath)
      .put("input_path", inputPath)
      .put("format", format)
      .put("bytes_written", bytesWritten)
  }

  private fun videoMute(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    inputPath: String,
    outputPath: String,
  ): JSONObject {
    val workDir = newWorkDir(context)
    val input = copyWorkspaceToTemp(context, workspaceConfig, inputPath, workDir)
    val output = File(workDir, "muted-video.mp4")
    runFfmpeg(arrayOf("-y", "-i", input.absolutePath, "-c:v", "copy", "-an", output.absolutePath))
    val targetPath = ensureMediaOutputPath(outputPath, "muted-video", "mp4")
    val bytesWritten = copyTempToWorkspace(context, workspaceConfig, config, output, targetPath)
    return baseResult("media_video_mute")
      .put("path", targetPath)
      .put("input_path", inputPath)
      .put("bytes_written", bytesWritten)
  }

  private fun videoAddAudio(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    videoPath: String,
    audioPath: String,
    outputPath: String,
    audioVolume: Double,
    originalVolume: Double,
  ): JSONObject {
    val workDir = newWorkDir(context)
    val video = copyWorkspaceToTemp(context, workspaceConfig, videoPath, workDir)
    val audio = copyWorkspaceToTemp(context, workspaceConfig, audioPath, workDir)
    val output = File(workDir, "video-with-audio.mp4")
    val args =
      if (hasTrack(video.absolutePath, audio = true)) {
        arrayOf(
          "-y",
          "-i",
          video.absolutePath,
          "-i",
          audio.absolutePath,
          "-filter_complex",
          "[0:a]volume=$originalVolume[a0];[1:a]volume=$audioVolume[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=0[a]",
          "-map",
          "0:v:0",
          "-map",
          "[a]",
          "-c:v",
          "copy",
          "-c:a",
          "aac",
          "-shortest",
          output.absolutePath,
        )
      } else {
        arrayOf(
          "-y",
          "-i",
          video.absolutePath,
          "-i",
          audio.absolutePath,
          "-map",
          "0:v:0",
          "-map",
          "1:a:0",
          "-c:v",
          "copy",
          "-c:a",
          "aac",
          "-shortest",
          output.absolutePath,
        )
      }
    runFfmpeg(args)
    val targetPath = ensureMediaOutputPath(outputPath, "video-with-audio", "mp4")
    val bytesWritten = copyTempToWorkspace(context, workspaceConfig, config, output, targetPath)
    return baseResult("media_video_add_audio")
      .put("path", targetPath)
      .put("video_path", videoPath)
      .put("audio_path", audioPath)
      .put("bytes_written", bytesWritten)
  }

  private fun requireMode(config: MediaToolboxConfig, mode: Mode) {
    val enabled =
      when (mode) {
        Mode.IMAGE -> config.imageModeEnabled
        Mode.AUDIO -> config.audioModeEnabled
        Mode.VIDEO -> config.videoModeEnabled
      }
    if (!enabled) {
      throw IOException("${mode.value} mode is disabled in Media Toolbox settings.")
    }
  }

  private fun decodeWorkspaceBitmap(context: Context, workspaceConfig: FileWorkspaceConfig, inputPath: String): Bitmap {
    val document = resolveWorkspaceFile(workspaceRoot(context, workspaceConfig), inputPath)
    return context.contentResolver.openInputStream(document.uri)?.use { input ->
      BitmapFactory.decodeStream(input)
    } ?: throw IOException("Unsupported or unreadable image file: $inputPath")
  }

  private fun writeBitmap(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    path: String,
    bitmap: Bitmap,
    format: String,
  ): Long {
    val target = ensureWritableFile(workspaceRoot(context, workspaceConfig), path, mimeTypeForPath(path), config.overwriteOutputs)
    context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
      val compressFormat =
        when (format.lowercase(Locale.US)) {
          "png" -> Bitmap.CompressFormat.PNG
          "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
          else -> Bitmap.CompressFormat.JPEG
        }
      if (!bitmap.compress(compressFormat, 92, output)) {
        throw IOException("Failed to encode image.")
      }
    } ?: throw IOException("Failed to open output file: $path")
    return target.length()
  }

  private fun copyWorkspaceToTemp(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    path: String,
    workDir: File,
  ): File {
    val document = resolveWorkspaceFile(workspaceRoot(context, workspaceConfig), path)
    if (!document.isFile) throw IOException("Workspace path is not a file: $path")
    val cleanExt = path.extensionOr("bin")
    val file = File(workDir, "input-${workDir.list()?.size ?: 0}.$cleanExt")
    context.contentResolver.openInputStream(document.uri)?.use { input ->
      FileOutputStream(file).use { output -> input.copyTo(output) }
    } ?: throw IOException("Failed to open workspace file: $path")
    return file
  }

  private fun copyTempToWorkspace(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    config: MediaToolboxConfig,
    source: File,
    path: String,
  ): Long {
    if (!source.isFile || source.length() <= 0L) {
      throw IOException("Media operation produced no output file.")
    }
    val target = ensureWritableFile(workspaceRoot(context, workspaceConfig), path, mimeTypeForPath(path), config.overwriteOutputs)
    context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
      source.inputStream().use { input -> input.copyTo(output) }
    } ?: throw IOException("Failed to open output file: $path")
    return source.length()
  }

  private fun workspaceRoot(context: Context, workspaceConfig: FileWorkspaceConfig): DocumentFile {
    if (workspaceConfig.treeUri.isBlank()) {
      throw IOException("No workspace folder is configured.")
    }
    return DocumentFile.fromTreeUri(context, workspaceConfig.treeUri.toUri())
      ?: throw IOException("Workspace folder is not available.")
  }

  private fun resolveWorkspaceFile(root: DocumentFile, path: String): DocumentFile {
    val clean = path.replace('\\', '/').trim('/')
    if (clean.isBlank()) throw IOException("Workspace file path is required.")
    var cur = root
    clean.split('/').filter { it.isNotBlank() }.forEach { part ->
      cur = cur.findFile(part) ?: throw IOException("Workspace file not found: $path")
    }
    return cur
  }

  private fun ensureWritableFile(
    root: DocumentFile,
    path: String,
    mimeType: String,
    overwrite: Boolean,
  ): DocumentFile {
    val clean = path.replace('\\', '/').trim('/').ifBlank { throw IOException("Output path is empty.") }
    val parts = clean.split('/').filter { it.isNotBlank() }
    var dir = root
    for (part in parts.dropLast(1)) {
      dir = dir.findFile(part) ?: dir.createDirectory(part)
        ?: throw IOException("Failed to create workspace directory: $part")
      if (!dir.isDirectory) throw IOException("Workspace path component is not a directory: $part")
    }
    val requestedName = parts.last()
    val finalName =
      if (overwrite || dir.findFile(requestedName) == null) {
        requestedName
      } else {
        val base = requestedName.substringBeforeLast('.', requestedName)
        val ext = requestedName.substringAfterLast('.', "")
        if (ext.isBlank()) "$base-${timestamp()}" else "$base-${timestamp()}.$ext"
      }
    if (overwrite) {
      dir.findFile(finalName)?.delete()
    }
    return dir.createFile(mimeType, finalName)
      ?: throw IOException("Failed to create workspace file: $clean")
  }

  private fun ensureMediaOutputPath(path: String, defaultPrefix: String, extension: String): String {
    val cleanExt = extension.trimStart('.').lowercase(Locale.US).ifBlank { "bin" }
    val normalized = path.replace('\\', '/').trim()
    val fallback = "media/$defaultPrefix-${timestamp()}.$cleanExt"
    if (normalized.isBlank()) return fallback
    val withExt =
      if (normalized.substringAfterLast('/', normalized).contains('.')) normalized
      else "$normalized.$cleanExt"
    return if ('/' in withExt) withExt else "media/$withExt"
  }

  private fun runFfmpeg(args: Array<String>) {
    val session = FFmpegKit.executeWithArguments(args)
    val returnCode = session.returnCode
    if (!ReturnCode.isSuccess(returnCode)) {
      val output = session.allLogsAsString.orEmpty().takeLast(3000)
      throw IOException("FFmpeg failed with code ${returnCode?.value}: $output")
    }
  }

  private fun readMediaMetadata(path: String): MediaMetadata {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(path)
      return MediaMetadata(
        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
        rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
        bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L,
        mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty(),
      )
    } finally {
      retriever.release()
    }
  }

  private fun hasTrack(path: String, audio: Boolean): Boolean {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(path)
      for (i in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
        if (audio && mime.startsWith("audio/")) return true
        if (!audio && mime.startsWith("video/")) return true
      }
      return false
    } finally {
      extractor.release()
    }
  }

  private fun newWorkDir(context: Context): File {
    val dir = File(context.cacheDir, "media-toolbox/${timestamp()}-${System.nanoTime()}")
    if (!dir.mkdirs()) throw IOException("Failed to create media toolbox cache directory.")
    return dir
  }

  private fun resolveImageTarget(
    sourceWidth: Int,
    sourceHeight: Int,
    target: String,
    width: Int?,
    height: Int?,
  ): Pair<Int, Int> {
    if (width != null && width > 0 && height != null && height > 0) {
      return width.coerceIn(1, 4096) to height.coerceIn(1, 4096)
    }
    val maxSide =
      when (target.trim().lowercase(Locale.US)) {
        "512", "512p" -> 512
        "720", "720p" -> 720
        "1080", "1080p" -> 1080
        "2k", "1440", "1440p" -> 1440
        "4k", "2160", "2160p" -> 3840
        else -> width?.takeIf { it > 0 } ?: height?.takeIf { it > 0 } ?: 1080
      }.coerceIn(1, 4096)
    val ratio = maxSide.toDouble() / maxOf(sourceWidth, sourceHeight).coerceAtLeast(1).toDouble()
    return (sourceWidth * ratio).roundToInt().coerceAtLeast(1) to
      (sourceHeight * ratio).roundToInt().coerceAtLeast(1)
  }

  private fun audioCodecArgs(format: String): Array<String> {
    return when (format.lowercase(Locale.US)) {
      "wav" -> arrayOf("-c:a", "pcm_s16le")
      "m4a", "aac" -> arrayOf("-c:a", "aac", "-b:a", "192k")
      "ogg" -> arrayOf("-c:a", "libvorbis", "-q:a", "4")
      "flac" -> arrayOf("-c:a", "flac")
      else -> arrayOf("-c:a", "libmp3lame", "-b:a", "192k")
    }
  }

  private fun videoCodecArgs(format: String): Array<String> {
    return when (format.lowercase(Locale.US)) {
      "webm" -> arrayOf("-c:v", "libvpx-vp9", "-b:v", "1800k", "-c:a", "libopus", "-b:a", "128k")
      "mkv" -> arrayOf("-c:v", "mpeg4", "-q:v", "4", "-c:a", "aac", "-b:a", "192k")
      else -> arrayOf("-c:v", "mpeg4", "-q:v", "4", "-c:a", "aac", "-b:a", "192k", "-pix_fmt", "yuv420p")
    }
  }

  private fun normalizeImageFormat(value: String): String {
    return when (value.trim().trimStart('.').lowercase(Locale.US)) {
      "png" -> "png"
      "webp" -> "webp"
      else -> "jpg"
    }
  }

  private fun normalizeAudioFormat(value: String): String {
    return when (value.trim().trimStart('.').lowercase(Locale.US)) {
      "wav" -> "wav"
      "m4a" -> "m4a"
      "aac" -> "aac"
      "ogg" -> "ogg"
      "flac" -> "flac"
      else -> "mp3"
    }
  }

  private fun normalizeVideoFormat(value: String): String {
    return when (value.trim().trimStart('.').lowercase(Locale.US)) {
      "mov" -> "mov"
      "mkv" -> "mkv"
      "webm" -> "webm"
      else -> "mp4"
    }
  }

  private fun requireTime(value: String, label: String): String {
    val cleaned = value.trim()
    if (cleaned.isBlank()) throw IOException("$label time is required.")
    parseTimeSeconds(cleaned)
    return cleaned
  }

  private fun parseTimeSeconds(value: String): Double {
    val cleaned = value.trim()
    if (!cleaned.contains(":")) {
      return cleaned.toDoubleOrNull()?.takeIf { it >= 0.0 }
        ?: throw IOException("Invalid time value: $value")
    }
    val parts = cleaned.split(":").map { it.trim().toDoubleOrNull() ?: throw IOException("Invalid time value: $value") }
    if (parts.size !in 2..3 || parts.any { it < 0.0 }) throw IOException("Invalid time value: $value")
    return if (parts.size == 2) parts[0] * 60.0 + parts[1] else parts[0] * 3600.0 + parts[1] * 60.0 + parts[2]
  }

  private fun formatSeconds(value: Double): String = String.format(Locale.US, "%.3f", value)

  private fun JSONObject.requirePath(vararg names: String): String {
    for (name in names) {
      val value = optString(name).trim()
      if (value.isNotBlank()) return value
    }
    throw IOException("Workspace input path is required. Expected one of: ${names.joinToString(", ")}")
  }

  private fun JSONObject.optStringArray(vararg names: String): List<String> {
    for (name in names) {
      val array = optJSONArray(name)
      if (array != null) {
        val values = mutableListOf<String>()
        for (i in 0 until array.length()) {
          values += array.optString(i)
        }
        return values
      }
      val csv = optString(name)
      if (csv.isNotBlank()) {
        return csv.split(',', '\n', ';').map { it.trim() }.filter { it.isNotBlank() }
      }
    }
    return emptyList()
  }

  private fun JSONObject.optIntOrNull(name: String): Int? {
    return if (has(name)) optInt(name) else null
  }

  private fun String.extensionOr(default: String): String {
    val ext = substringAfterLast('.', "").lowercase(Locale.US)
    return if (ext.length in 2..5) ext else default
  }

  private fun mimeTypeForPath(path: String): String {
    return when (path.extensionOr("bin")) {
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "webp" -> "image/webp"
      "gif" -> "image/gif"
      "mp3" -> "audio/mpeg"
      "wav" -> "audio/wav"
      "m4a", "aac" -> "audio/mp4"
      "ogg" -> "audio/ogg"
      "flac" -> "audio/flac"
      "mp4" -> "video/mp4"
      "mov" -> "video/quicktime"
      "webm" -> "video/webm"
      "mkv" -> "video/x-matroska"
      else -> "application/octet-stream"
    }
  }

  private fun baseResult(operation: String): JSONObject {
    return JSONObject().put("status", "succeeded").put("operation", operation)
  }

  private fun timestamp(): String = timestampFormatter.format(Date())

  private data class MediaMetadata(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val bitrate: Long,
    val mimeType: String,
  )
}
