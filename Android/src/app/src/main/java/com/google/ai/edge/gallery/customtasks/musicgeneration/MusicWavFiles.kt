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

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.google.ai.edge.gallery.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "MusicWavFiles"
private const val WAV_SAMPLE_RATE = 44_100
private const val WAV_CHANNELS = 2
private const val WAV_BITS_PER_SAMPLE = 16

data class GeneratedMusicFile(val file: File, val durationSeconds: Float)

fun writeStereoFloatWav(
  file: File,
  interleavedChannels: FloatArray,
  samplesPerChannel: Int,
  firstRightChannelIndex: Int,
  normalize: Boolean,
): GeneratedMusicFile {
  file.parentFile?.mkdirs()
  val actualSamples = samplesPerChannel.coerceAtLeast(1)
  val scale =
    if (normalize) {
      val maxAmplitude = interleavedChannels.fold(0f) { current, value -> maxOf(current, kotlin.math.abs(value)) }
      if (maxAmplitude > 1.0e-6f) 1f / maxAmplitude else 1f
    } else {
      1f
    }
  val dataSize = actualSamples * WAV_CHANNELS * WAV_BITS_PER_SAMPLE / 8
  RandomAccessFile(file, "rw").use { wav ->
    wav.setLength(0)
    wav.write(
      ByteBuffer.allocate(44)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray(Charsets.US_ASCII))
        .putInt(dataSize + 36)
        .put("WAVE".toByteArray(Charsets.US_ASCII))
        .put("fmt ".toByteArray(Charsets.US_ASCII))
        .putInt(16)
        .putShort(1.toShort())
        .putShort(WAV_CHANNELS.toShort())
        .putInt(WAV_SAMPLE_RATE)
        .putInt(WAV_SAMPLE_RATE * WAV_CHANNELS * WAV_BITS_PER_SAMPLE / 8)
        .putShort((WAV_CHANNELS * WAV_BITS_PER_SAMPLE / 8).toShort())
        .putShort(WAV_BITS_PER_SAMPLE.toShort())
        .put("data".toByteArray(Charsets.US_ASCII))
        .putInt(dataSize)
        .array()
    )
    val samples = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
    for (index in 0 until actualSamples) {
      samples.putShort(toPcm16(interleavedChannels[index] * scale))
      samples.putShort(toPcm16(interleavedChannels[firstRightChannelIndex + index] * scale))
    }
    wav.write(samples.array())
  }
  return GeneratedMusicFile(file = file, durationSeconds = actualSamples.toFloat() / WAV_SAMPLE_RATE)
}

fun saveMusicFileToMediaStore(context: Context, source: File, displayPrefix: String): Boolean {
  val resolver = context.contentResolver
  val values =
    ContentValues().apply {
      put(MediaStore.Audio.Media.DISPLAY_NAME, "${displayPrefix}_${System.currentTimeMillis()}.wav")
      put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
      put(
        MediaStore.Audio.Media.RELATIVE_PATH,
        "${Environment.DIRECTORY_MUSIC}/SoundGen",
      )
    }
  val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
  return try {
    resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
    true
  } catch (e: Exception) {
    Log.e(TAG, "Failed to save generated audio", e)
    false
  }
}

fun shareMusicFile(context: Context, file: File) {
  val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)
  val chooser =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
          type = "audio/wav"
          putExtra(Intent.EXTRA_STREAM, uri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        "分享生成音频",
      )
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  context.startActivity(chooser)
}

private fun toPcm16(value: Float): Short {
  val clipped = value.coerceIn(-1f, 1f)
  return (clipped * 32767f).toInt().toShort()
}
