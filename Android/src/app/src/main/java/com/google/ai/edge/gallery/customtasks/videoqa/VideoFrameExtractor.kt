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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToLong

private const val US_PER_MS = 1_000L
private const val US_PER_SECOND = 1_000_000L
const val VIDEO_QA_MAX_KEYFRAME_INPUTS = 5
const val VIDEO_QA_DEFAULT_FRAME_COUNT = 10
const val VIDEO_QA_DEFAULT_FRAME_SIZE = 512

enum class VideoQaMode {
  COMPLETE_VIDEO,
  KEY_FRAMES,
}

data class VideoFrame(
  val bitmap: Bitmap,
  val timeUs: Long,
)

data class VideoFrameExtractionResult(
  val frames: List<VideoFrame>,
  val durationMs: Long,
)

data class ParsedKeyFrameTimes(
  val timesUs: List<Long>,
  val invalidInputs: List<String>,
)

fun planEvenFrameTimesUs(durationMs: Long, frameCount: Int): List<Long> {
  val safeFrameCount = frameCount.coerceAtLeast(1)
  val durationUs = durationMs.coerceAtLeast(0L) * US_PER_MS
  if (safeFrameCount == 1) {
    return listOf(durationUs / 2L)
  }
  return List(safeFrameCount) { index ->
    ((durationUs.toDouble() * (index.toDouble() + 0.5)) / safeFrameCount.toDouble()).roundToLong()
  }
}

fun parseKeyFrameTimesUs(inputs: List<String>, durationMs: Long): List<Long> {
  return parseKeyFrameTimes(inputs = inputs, durationMs = durationMs).timesUs
}

fun parseKeyFrameTimes(inputs: List<String>, durationMs: Long): ParsedKeyFrameTimes {
  val durationUs = durationMs.coerceAtLeast(0L) * US_PER_MS
  val times = mutableListOf<Long>()
  val invalidInputs = mutableListOf<String>()
  for (input in inputs.take(VIDEO_QA_MAX_KEYFRAME_INPUTS)) {
    val cleaned = input.trim()
    if (cleaned.isBlank()) continue
    val seconds = parseTimeSeconds(cleaned)
    if (seconds == null) {
      invalidInputs += cleaned
      continue
    }
    times += (seconds * US_PER_SECOND).roundToLong().coerceIn(0L, durationUs)
  }
  return ParsedKeyFrameTimes(timesUs = times, invalidInputs = invalidInputs)
}

fun validateKeyFrameInputs(inputs: List<String>): List<String> {
  return inputs
    .take(VIDEO_QA_MAX_KEYFRAME_INPUTS)
    .map { it.trim() }
    .filter { it.isNotBlank() && parseTimeSeconds(it) == null }
}

private fun parseTimeSeconds(value: String): Double? {
  if (!value.contains(":")) {
    return value.toDoubleOrNull()?.takeIf { it >= 0.0 }
  }
  val parts = value.split(":")
  if (parts.size !in 2..3) return null
  val parsed = parts.map { part -> part.trim().toDoubleOrNull() ?: return null }
  if (parsed.any { it < 0.0 }) return null
  return if (parsed.size == 2) {
    parsed[0] * 60.0 + parsed[1]
  } else {
    parsed[0] * 3600.0 + parsed[1] * 60.0 + parsed[2]
  }
}

fun extractVideoFrames(
  context: Context,
  uri: Uri,
  mode: VideoQaMode,
  frameCount: Int,
  frameSize: Int,
  keyFrameInputs: List<String>,
): VideoFrameExtractionResult {
  val retriever = MediaMetadataRetriever()
  try {
    retriever.setDataSource(context, uri)
    val durationMs =
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        ?: 0L
    val width =
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        ?: frameSize
    val height =
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        ?: frameSize
    val rotation =
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        ?: 0
    val scaledSize =
      calculateScaledFrameSize(
        width = if (rotation == 90 || rotation == 270) height else width,
        height = if (rotation == 90 || rotation == 270) width else height,
        maxSide = frameSize,
      )
    val times =
      if (mode == VideoQaMode.COMPLETE_VIDEO) {
        planEvenFrameTimesUs(durationMs = durationMs, frameCount = frameCount)
      } else {
        parseKeyFrameTimesUs(inputs = keyFrameInputs, durationMs = durationMs)
      }
    val frames =
      times.mapNotNull { timeUs ->
        val bitmap =
          retriever.getScaledFrameAtTime(
            timeUs,
            MediaMetadataRetriever.OPTION_CLOSEST,
            scaledSize.first,
            scaledSize.second,
          )
        bitmap?.let { VideoFrame(bitmap = it, timeUs = timeUs) }
      }
    return VideoFrameExtractionResult(frames = frames, durationMs = durationMs)
  } finally {
    retriever.release()
  }
}

fun prepareVideoFrameBitmapsForModel(frames: List<VideoFrame>): List<Bitmap> {
  return frames.mapIndexed { index, frame -> frame.bitmap.withFrameLabel(index, frame.timeUs) }
}

fun formatVideoFrameTimeSeconds(timeUs: Long): String {
  return String.format(Locale.US, "%.2f", timeUs / US_PER_SECOND.toDouble())
}

private fun calculateScaledFrameSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
  val safeWidth = width.coerceAtLeast(1)
  val safeHeight = height.coerceAtLeast(1)
  val safeMaxSide = maxSide.coerceAtLeast(1)
  if (safeWidth >= safeHeight) {
    val scaledHeight = (safeMaxSide * safeHeight.toDouble() / safeWidth.toDouble()).roundToLong()
    return safeMaxSide to scaledHeight.toInt().coerceAtLeast(1)
  }
  val scaledWidth = (safeMaxSide * safeWidth.toDouble() / safeHeight.toDouble()).roundToLong()
  return scaledWidth.toInt().coerceAtLeast(1) to safeMaxSide
}

private fun Bitmap.withFrameLabel(index: Int, timeUs: Long): Bitmap {
  val labeled = copy(Bitmap.Config.ARGB_8888, true)
  val canvas = Canvas(labeled)
  val label = "Frame ${index + 1}  ${formatVideoFrameTimeSeconds(timeUs)}s"
  val textSize = (min(labeled.width, labeled.height) * 0.055f).coerceIn(20f, 44f)
  val padding = (textSize * 0.35f).roundToLong().toFloat()
  val textPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      this.textSize = textSize
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
  val backgroundPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.argb(205, 0, 0, 0)
      style = Paint.Style.FILL
    }
  val textWidth = textPaint.measureText(label)
  val height = textSize + padding * 2.2f
  val rect = RectF(0f, 0f, (textWidth + padding * 2f).coerceAtMost(labeled.width.toFloat()), height)
  canvas.drawRect(rect, backgroundPaint)
  canvas.drawText(label, padding, padding + textSize, textPaint)
  return labeled
}
