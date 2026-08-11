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
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import java.io.File
import java.util.Random
import kotlin.math.exp
import kotlin.math.roundToInt

private const val TAG = "SoundGenEngine"
private const val SAMPLE_RATE = 44_100
private const val BASIC_TEXT_TOKEN_COUNT = 128
private const val HD_TEXT_TOKEN_COUNT = 256
private const val BASIC_LATENT_SIZE = 16_384
private const val HD_LATENT_SCALE = 256
private const val HD_DECODER_SCALE = 257
private const val HD_AUDIO_SCALE = 4096
private const val DIFFUSION_STEPS = 8

interface MusicGenerationEngine : Closeable {
  suspend fun generate(
    context: Context,
    prompt: String,
    durationSeconds: Float,
    seed: Long = System.nanoTime(),
    onProgress: (Float) -> Unit,
  ): GeneratedMusicFile
}

class BasicSoundGenEngine(private val model: Model, context: Context) : MusicGenerationEngine {
  private val textModel = createModelWithGpuFallback(model.getPath(context, "sg_text.litert"))
  private val coreModel = createModelWithGpuFallback(model.getPath(context, "sg_core.litert"))
  private val decodeModel = createModelWithGpuFallback(model.getPath(context, "sg_decode.litert"))
  private val tokenizer =
    SoundGenTokenizer.fromBytes(File(model.getPath(context, "sg_vocab.spm")).readBytes())

  override suspend fun generate(
    context: Context,
    prompt: String,
    durationSeconds: Float,
    seed: Long,
    onProgress: (Float) -> Unit,
  ): GeneratedMusicFile {
    val tokens = tokenizer.encode(prompt)
    val tokenIds = LongArray(BASIC_TEXT_TOKEN_COUNT)
    val attentionMask = LongArray(BASIC_TEXT_TOKEN_COUNT)
    for (index in 0 until minOf(tokens.size, BASIC_TEXT_TOKEN_COUNT)) {
      tokenIds[index] = tokens[index].toLong()
      attentionMask[index] = 1L
    }

    val textInputs = textModel.createInputBuffers()
    val textOutputs = textModel.createOutputBuffers()
    var textBuffersClosed = false
    try {
      textInputs[0].writeLong(tokenIds)
      textInputs[1].writeLong(attentionMask)
      textInputs[2].writeFloat(floatArrayOf(durationSeconds))
      textModel.run(textInputs, textOutputs)
      val textEmbedding = textOutputs[0].readFloat()
      val textCondition = textOutputs[2].readFloat()
      onProgress(0.1f)
      closeBuffers(textInputs)
      closeBuffers(textOutputs)
      textBuffersClosed = true

      val sigmas = basicSigmas()
      var latent = gaussianArray(BASIC_LATENT_SIZE, seed)
      val coreInputs = coreModel.createInputBuffers()
      val coreOutputs = coreModel.createOutputBuffers()
      try {
        coreInputs[0].writeFloat(textEmbedding)
        coreInputs[1].writeFloat(textCondition)
        for (step in 0 until DIFFUSION_STEPS) {
          val sigma = sigmas[step]
          val nextSigma = sigmas[step + 1]
          coreInputs[2].writeFloat(latent)
          coreInputs[3].writeFloat(floatArrayOf(sigma))
          coreModel.run(coreInputs, coreOutputs)
          val predictedNoise = coreOutputs[0].readFloat()
          val nextNoise = gaussianArray(BASIC_LATENT_SIZE, seed + step + 4564)
          latent =
            FloatArray(BASIC_LATENT_SIZE) { index ->
              (nextNoise[index] * nextSigma) +
                ((1f - nextSigma) * (latent[index] - (predictedNoise[index] * sigma)))
            }
          onProgress(0.1f + ((step + 1) * 0.8f / DIFFUSION_STEPS))
        }
      } finally {
        closeBuffers(coreInputs)
        closeBuffers(coreOutputs)
      }

      val decodeInputs = decodeModel.createInputBuffers()
      val decodeOutputs = decodeModel.createOutputBuffers()
      try {
        decodeInputs[0].writeFloat(latent)
        decodeModel.run(decodeInputs, decodeOutputs)
        val waveform = decodeOutputs[0].readFloat()
        onProgress(0.95f)
        val samples = requestedSamples(durationSeconds, model.musicGenerationSpec()?.maxOutputSamplesPerChannel ?: 524_288)
        val output =
          writeStereoFloatWav(
            file =
              File(
                File(context.cacheDir, "soundgen"),
                "soundgen_${System.currentTimeMillis()}.wav",
              ),
            interleavedChannels = waveform,
            samplesPerChannel = samples,
            firstRightChannelIndex = 524_288,
            normalize = false,
          )
        onProgress(1f)
        return output
      } finally {
        closeBuffers(decodeInputs)
        closeBuffers(decodeOutputs)
      }
    } catch (e: Throwable) {
      if (!textBuffersClosed) {
        closeBuffers(textInputs)
        closeBuffers(textOutputs)
      }
      throw e
    }
  }

  override fun close() {
    listOf(textModel, coreModel, decodeModel).forEach { model ->
      try {
        model.close()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to close SoundGen model", e)
      }
    }
  }
}

class HdSoundGenEngine(private val model: Model, context: Context, private val blockCount: Int) :
  MusicGenerationEngine {
  private val latentSize = blockCount * HD_LATENT_SCALE
  private val decoderConditionSize = blockCount * HD_DECODER_SCALE
  private val maxOutputSamplesPerChannel = blockCount * HD_AUDIO_SCALE
  private val textModel = createCpuModel(model.getPath(context, "sghd_text.litert"))
  private val coreModel = createCpuModel(model.getPath(context, "sghd_core.litert"))
  private val decodeModel = createCpuModel(model.getPath(context, "sghd_decode.litert"))
  private val tokenizer =
    SoundGenHdTokenizer.fromBytes(File(model.getPath(context, "sghd_vocab.spm")).readBytes())

  override suspend fun generate(
    context: Context,
    prompt: String,
    durationSeconds: Float,
    seed: Long,
    onProgress: (Float) -> Unit,
  ): GeneratedMusicFile {
    val tokens = tokenizer.encode(prompt)
    val tokenIds = LongArray(HD_TEXT_TOKEN_COUNT)
    val attentionMask = LongArray(HD_TEXT_TOKEN_COUNT)
    for (index in 0 until minOf(tokens.size, HD_TEXT_TOKEN_COUNT)) {
      tokenIds[index] = tokens[index].toLong()
      attentionMask[index] = 1L
    }

    val textInputs = textModel.createInputBuffers()
    val textOutputs = textModel.createOutputBuffers()
    var textBuffersClosed = false
    try {
      textInputs[0].writeLong(tokenIds)
      textInputs[1].writeLong(attentionMask)
      textModel.run(textInputs, textOutputs)
      val textEmbedding = textOutputs[0].readFloat()
      val textMask = createHdTextMask(attentionMask)
      onProgress(0.05f)
      closeBuffers(textInputs)
      closeBuffers(textOutputs)
      textBuffersClosed = true

      val sigmas = hdSigmas(blockCount)
      var latent = gaussianArray(latentSize, seed)
      val decoderCondition = FloatArray(decoderConditionSize)
      val coreInputs = coreModel.createInputBuffers()
      val coreOutputs = coreModel.createOutputBuffers()
      try {
        coreInputs[2].writeFloat(textEmbedding)
        coreInputs[3].writeFloat(textMask)
        coreInputs[4].writeFloat(floatArrayOf(durationSeconds))
        coreInputs[5].writeFloat(decoderCondition)
        for (step in 0 until DIFFUSION_STEPS) {
          val sigma = sigmas[step]
          val nextSigma = sigmas[step + 1]
          coreInputs[0].writeFloat(latent)
          coreInputs[1].writeFloat(floatArrayOf(sigma))
          coreModel.run(coreInputs, coreOutputs)
          val predictedNoise = coreOutputs[0].readFloat()
          val nextNoise =
            if (step < DIFFUSION_STEPS - 1) gaussianArray(latentSize, seed + step + 1) else null
          latent =
            FloatArray(latentSize) { index ->
              val denoised = latent[index] - (predictedNoise[index] * sigma)
              if (nextNoise != null) {
                (nextNoise[index] * nextSigma) + ((1f - nextSigma) * denoised)
              } else {
                denoised
              }
            }
          onProgress(0.05f + ((step + 1) * 0.8f / DIFFUSION_STEPS))
        }
      } finally {
        closeBuffers(coreInputs)
        closeBuffers(coreOutputs)
      }

      val decodeInputs = decodeModel.createInputBuffers()
      val decodeOutputs = decodeModel.createOutputBuffers()
      try {
        decodeInputs[0].writeFloat(latent)
        decodeModel.run(decodeInputs, decodeOutputs)
        val waveform = decodeOutputs[0].readFloat()
        onProgress(0.97f)
        val samples = requestedSamples(durationSeconds, maxOutputSamplesPerChannel)
        val output =
          writeStereoFloatWav(
            file =
              File(
                File(context.cacheDir, "soundgenhd"),
                "soundgenhd_${System.currentTimeMillis()}.wav",
              ),
            interleavedChannels = waveform,
            samplesPerChannel = samples,
            firstRightChannelIndex = maxOutputSamplesPerChannel,
            normalize = true,
          )
        onProgress(1f)
        return output
      } finally {
        closeBuffers(decodeInputs)
        closeBuffers(decodeOutputs)
      }
    } catch (e: Throwable) {
      if (!textBuffersClosed) {
        closeBuffers(textInputs)
        closeBuffers(textOutputs)
      }
      throw e
    }
  }

  override fun close() {
    listOf(textModel, coreModel, decodeModel).forEach { model ->
      try {
        model.close()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to close SoundGen HD model", e)
      }
    }
  }
}

fun createMusicEngine(context: Context, model: Model): MusicGenerationEngine {
  return when (model.musicGenerationSpec()?.kind) {
    MusicGenerationKind.SOUNDGEN -> BasicSoundGenEngine(model, context)
    MusicGenerationKind.SOUNDGEN_HD -> HdSoundGenEngine(model, context, blockCount = 256)
    MusicGenerationKind.SOUNDGEN_HD_LONG -> HdSoundGenEngine(model, context, blockCount = 2048)
    null -> error("Unsupported music model: ${model.name}")
  }
}

private fun createModelWithGpuFallback(path: String): CompiledModel {
  return try {
    CompiledModel.create(path, CompiledModel.Options(Accelerator.GPU, Accelerator.CPU))
  } catch (e: Throwable) {
    Log.w(TAG, "GPU load failed for $path, using CPU fallback", e)
    createCpuModel(path)
  }
}

private fun createCpuModel(path: String): CompiledModel {
  return CompiledModel.create(path, CompiledModel.Options(Accelerator.CPU))
}

private fun closeBuffers(buffers: List<TensorBuffer>) {
  buffers.forEach { buffer ->
    try {
      buffer.close()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to close LiteRT tensor buffer", e)
    }
  }
}

private fun gaussianArray(size: Int, seed: Long): FloatArray {
  val random = Random(seed)
  return FloatArray(size) { random.nextGaussian().toFloat() }
}

internal fun createHdTextMask(attentionMask: LongArray): FloatArray {
  return FloatArray(HD_TEXT_TOKEN_COUNT) { index ->
    if (attentionMask.getOrNull(index) == 1L) 1f else 0f
  }
}

private fun basicSigmas(): FloatArray {
  val values = FloatArray(DIFFUSION_STEPS + 1)
  for (index in values.indices) {
    values[index] = if (index == 0) -6f else values[index - 1] + 1f
  }
  values[DIFFUSION_STEPS] = 2f
  for (index in values.indices) {
    values[index] = 1f / (exp(values[index].toDouble()).toFloat() + 1f)
  }
  values[0] = 1f
  values[DIFFUSION_STEPS] = 0f
  return values
}

private fun hdSigmas(blockCount: Int): FloatArray {
  val expFactor =
    exp(
        -((((blockCount.coerceIn(HD_TEXT_TOKEN_COUNT, 4096) - HD_TEXT_TOKEN_COUNT) * 0.65f) /
            3840f) + 0.5f)
          .toDouble()
      )
      .toFloat()
  return FloatArray(DIFFUSION_STEPS + 1) { index ->
      val t = 1f - (index / DIFFUSION_STEPS.toFloat())
      if (t >= 1f) {
        1f
      } else if (t <= 0f) {
        0f
      } else {
        1f - (expFactor / (((1f / (1f - t)) - 1f) + expFactor))
      }
    }
    .also { it[0] = 1f }
}

private fun requestedSamples(durationSeconds: Float, maxSamples: Int): Int {
  return (durationSeconds * SAMPLE_RATE).roundToInt().coerceAtLeast(1).coerceAtMost(maxSamples)
}
