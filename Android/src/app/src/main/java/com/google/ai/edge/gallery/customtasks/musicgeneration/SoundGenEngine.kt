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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.Random

private const val TAG = "SoundGenEngine"
private const val SOUNDGEN_SEED = 42L

enum class MusicAccelerationMode {
  AUTO,
  CPU,
  GPU,
}

interface MusicGenerationEngine : Closeable {
  suspend fun generate(
    context: Context,
    prompt: String,
    durationSeconds: Float,
    accelerationMode: MusicAccelerationMode,
    onProgress: (Float) -> Unit,
  ): GeneratedMusicFile

  fun accelerationReport(): String
}

private class Box049SoundGenEngine(
  private val model: Model,
  private val kind: MusicGenerationKind,
) : MusicGenerationEngine {
  @Volatile private var lastAccelerationReport: String = ""

  override fun accelerationReport(): String = lastAccelerationReport

  override suspend fun generate(
    context: Context,
    prompt: String,
    durationSeconds: Float,
    accelerationMode: MusicAccelerationMode,
    onProgress: (Float) -> Unit,
  ): GeneratedMusicFile {
    lastAccelerationReport = ""
    if (accelerationMode == MusicAccelerationMode.GPU) {
      appendAccelerationReport("GPU 模式：Text 固定 CPU；Core/Decoder 使用 0.4.9 稳定 GPU+CPU 混合策略")
    }
    return when (kind) {
      MusicGenerationKind.SOUNDGEN ->
        generateBasic(context, prompt, durationSeconds, accelerationMode, onProgress)
      MusicGenerationKind.SOUNDGEN_HD ->
        generateHd(context, prompt, durationSeconds, accelerationMode, longMode = false, onProgress)
      MusicGenerationKind.SOUNDGEN_HD_LONG ->
        generateHd(context, prompt, durationSeconds, accelerationMode, longMode = true, onProgress)
    }
  }

  private fun generateBasic(
    context: Context,
    prompt: String,
    duration: Float,
    accelerationMode: MusicAccelerationMode,
    onProgress: (Float) -> Unit,
  ): GeneratedMusicFile {
    val tokenizer =
      SoundGenTokenizer.fromBytes(File(model.getPath(context, "sg_vocab.spm")).readBytes())
    val ids = tokenizer.encode(prompt)
    val tokenIds = LongArray(BoxSoundGenCore.BASIC_TEXT_TOKEN_COUNT)
    val attentionMask = LongArray(BoxSoundGenCore.BASIC_TEXT_TOKEN_COUNT)
    for (index in 0 until minOf(ids.size, BoxSoundGenCore.BASIC_TEXT_TOKEN_COUNT)) {
      tokenIds[index] = ids[index].toLong()
      attentionMask[index] = 1L
    }

    val textResult =
      withModel(
        path = model.getPath(context, "sg_text.litert"),
        mode = accelerationMode,
        component = "text",
        autoGpuPreferred = true,
        quantizedHint = false,
      ) { text ->
        val inputs = text.createInputBuffers()
        val outputs = text.createOutputBuffers()
        try {
          requireBufferCount(inputs, 3, "SoundGen text inputs")
          requireBufferCount(outputs, 3, "SoundGen text outputs")
          inputs[0].writeLong(tokenIds)
          inputs[1].writeLong(attentionMask)
          inputs[2].writeFloat(floatArrayOf(duration))
          runExact(text, inputs, outputs)
          Pair(outputs[0].readFloat(), outputs[2].readFloat())
        } finally {
          closeBuffers(inputs)
          closeBuffers(outputs)
        }
      }
    onProgress(0.10f)

    var latent = gaussian(SOUNDGEN_SEED, BoxSoundGenCore.BASIC_LATENT_SIZE)
    val sigmas = BoxSoundGenCore.basicSigmas()
    withModel(
      path = model.getPath(context, "sg_core.litert"),
      mode = accelerationMode,
      component = "core",
      autoGpuPreferred = true,
      quantizedHint = false,
    ) { core ->
      val inputs = core.createInputBuffers()
      val outputs = core.createOutputBuffers()
      try {
        requireBufferCount(inputs, 4, "SoundGen core inputs")
        requireBufferCount(outputs, 1, "SoundGen core outputs")
        inputs[0].writeFloat(textResult.first)
        inputs[1].writeFloat(textResult.second)
        for (step in 0 until BoxSoundGenCore.DIFFUSION_STEPS) {
          val current = sigmas[step]
          val next = sigmas[step + 1]
          inputs[2].writeFloat(latent)
          inputs[3].writeFloat(floatArrayOf(current))
          runExact(core, inputs, outputs)
          val velocity = outputs[0].readFloat()
          require(velocity.size == latent.size) {
            "SoundGen core output size ${velocity.size}, expected ${latent.size}"
          }
          val noise = gaussian(SOUNDGEN_SEED + step + 4564L, latent.size)
          latent =
            FloatArray(latent.size) { index ->
              val denoised = latent[index] - velocity[index] * current
              noise[index] * next + (1f - next) * denoised
            }
          onProgress(0.10f + ((step + 1) * 0.8f / BoxSoundGenCore.DIFFUSION_STEPS))
        }
      } finally {
        closeBuffers(inputs)
        closeBuffers(outputs)
      }
    }

    val waveform =
      withModel(
        path = model.getPath(context, "sg_decode.litert"),
        mode = accelerationMode,
        component = "decoder",
        autoGpuPreferred = true,
        quantizedHint = false,
      ) { decoder ->
        val inputs = decoder.createInputBuffers()
        val outputs = decoder.createOutputBuffers()
        try {
          requireBufferCount(inputs, 1, "SoundGen decoder inputs")
          requireBufferCount(outputs, 1, "SoundGen decoder outputs")
          inputs[0].writeFloat(latent)
          runExact(decoder, inputs, outputs)
          outputs[0].readFloat()
        } finally {
          closeBuffers(inputs)
          closeBuffers(outputs)
        }
      }
    onProgress(0.95f)

    val plane = 524_288
    require(waveform.size >= plane * 2) {
      "SoundGen decoder output size ${waveform.size}, expected at least ${plane * 2}"
    }
    val frames = BoxSoundGenCore.requestedSamples(duration, plane)
    val output =
      writeStereoFloatWav(
        file = File(File(context.cacheDir, "soundgen"), "soundgen_${System.currentTimeMillis()}.wav"),
        interleavedChannels = waveform,
        samplesPerChannel = frames,
        firstRightChannelIndex = plane,
        normalize = false,
      )
    onProgress(1f)
    return output
  }

  private fun generateHd(
    context: Context,
    prompt: String,
    duration: Float,
    accelerationMode: MusicAccelerationMode,
    longMode: Boolean,
    onProgress: (Float) -> Unit,
  ): GeneratedMusicFile {
    val blockCount = if (longMode) 2048 else 256
    val shape = BoxSoundGenCore.hdShape(blockCount)
    val tokenizer =
      SoundGenHdTokenizer.fromBytes(File(model.getPath(context, "sghd_vocab.spm")).readBytes())
    val ids = tokenizer.encode(prompt)
    val tokenIds = LongArray(BoxSoundGenCore.HD_TEXT_TOKEN_COUNT)
    val maskLong = LongArray(BoxSoundGenCore.HD_TEXT_TOKEN_COUNT)
    for (index in 0 until minOf(ids.size, BoxSoundGenCore.HD_TEXT_TOKEN_COUNT)) {
      tokenIds[index] = ids[index].toLong()
      maskLong[index] = 1L
    }
    val mask = createHdTextMask(maskLong)

    var hidden =
      withModel(
        path = model.getPath(context, "sghd_text.litert"),
        mode = accelerationMode,
        component = "text",
        autoGpuPreferred = false,
        quantizedHint = true,
      ) { text ->
        val inputs = text.createInputBuffers()
        val outputs = text.createOutputBuffers()
        try {
          requireBufferCount(inputs, 2, "SoundGen HD text inputs")
          requireBufferCount(outputs, 1, "SoundGen HD text outputs")
          inputs[0].writeLong(tokenIds)
          inputs[1].writeLong(maskLong)
          runExact(text, inputs, outputs)
          outputs[0].readFloat()
        } finally {
          closeBuffers(inputs)
          closeBuffers(outputs)
        }
      }
    if (longMode) System.gc()
    onProgress(0.05f)

    val sigmas = BoxSoundGenCore.hdSigmas(blockCount)
    var latent = gaussian(SOUNDGEN_SEED, shape.latentSize)
    withModel(
      path = model.getPath(context, "sghd_core.litert"),
      mode = accelerationMode,
      component = "core",
      autoGpuPreferred = false,
      quantizedHint = true,
    ) { core ->
      val inputs = core.createInputBuffers()
      val outputs = core.createOutputBuffers()
      try {
        requireBufferCount(inputs, 6, "SoundGen HD core inputs")
        requireBufferCount(outputs, 1, "SoundGen HD core outputs")
        inputs[2].writeFloat(hidden)
        inputs[3].writeFloat(mask)
        inputs[4].writeFloat(floatArrayOf(duration))
        inputs[5].writeFloat(FloatArray(shape.decoderConditionSize))
        hidden = FloatArray(0)

        for (step in 0 until BoxSoundGenCore.DIFFUSION_STEPS) {
          val current = sigmas[step]
          val next = sigmas[step + 1]
          inputs[0].writeFloat(latent)
          inputs[1].writeFloat(floatArrayOf(current))
          runExact(core, inputs, outputs)
          val velocity = outputs[0].readFloat()
          require(velocity.size == latent.size) {
            "SoundGen HD core output size ${velocity.size}, expected ${latent.size}"
          }
          val updated = FloatArray(latent.size)
          if (step < BoxSoundGenCore.DIFFUSION_STEPS - 1) {
            val noise = Random(SOUNDGEN_SEED + step + 1L)
            for (index in latent.indices) {
              val denoised = latent[index] - velocity[index] * current
              updated[index] = noise.nextGaussian().toFloat() * next + (1f - next) * denoised
            }
          } else {
            for (index in latent.indices) {
              updated[index] = latent[index] - velocity[index] * current
            }
          }
          latent = updated
          onProgress(0.05f + ((step + 1) * 0.8f / BoxSoundGenCore.DIFFUSION_STEPS))
        }
      } finally {
        closeBuffers(inputs)
        closeBuffers(outputs)
      }
    }
    if (longMode) System.gc()

    val decoderMode =
      if (longMode && accelerationMode == MusicAccelerationMode.GPU) {
        appendAccelerationReport("decoder=CPU（Long 稳定策略）")
        MusicAccelerationMode.CPU
      } else {
        accelerationMode
      }

    val waveform =
      withModel(
        path = model.getPath(context, "sghd_decode.litert"),
        mode = decoderMode,
        component = "decoder",
        autoGpuPreferred = false,
        quantizedHint = true,
      ) { decoder ->
        val inputs = decoder.createInputBuffers()
        val outputs = decoder.createOutputBuffers()
        try {
          requireBufferCount(inputs, 1, "SoundGen HD decoder inputs")
          requireBufferCount(outputs, 1, "SoundGen HD decoder outputs")
          inputs[0].writeFloat(latent)
          if (longMode) {
            latent = FloatArray(0)
            System.gc()
          }
          runExact(decoder, inputs, outputs)
          outputs[0].readFloat()
        } finally {
          closeBuffers(inputs)
          closeBuffers(outputs)
        }
      }
    if (longMode) System.gc()
    onProgress(0.97f)

    require(waveform.size >= shape.maxOutputSamplesPerChannel * 2) {
      "SoundGen HD decoder output size ${waveform.size}, expected at least ${shape.maxOutputSamplesPerChannel * 2}"
    }
    val frames = BoxSoundGenCore.requestedSamples(duration, shape.maxOutputSamplesPerChannel)
    val outputFile =
      File(
        File(context.cacheDir, if (longMode) "soundgenhdlong" else "soundgenhd"),
        "${if (longMode) "soundgenhdlong" else "soundgenhd"}_${System.currentTimeMillis()}.wav",
      )
    val output =
      if (longMode) {
        writeStereoFloatWavStreaming(
          file = outputFile,
          interleavedChannels = waveform,
          samplesPerChannel = frames,
          firstRightChannelIndex = shape.maxOutputSamplesPerChannel,
          normalize = true,
        )
      } else {
        writeStereoFloatWav(
          file = outputFile,
          interleavedChannels = waveform,
          samplesPerChannel = frames,
          firstRightChannelIndex = shape.maxOutputSamplesPerChannel,
          normalize = true,
        )
      }
    onProgress(1f)
    return output
  }

  private inline fun <T> withModel(
    path: String,
    mode: MusicAccelerationMode,
    component: String,
    autoGpuPreferred: Boolean,
    quantizedHint: Boolean,
    block: (CompiledModel) -> T,
  ): T {
    val compiled =
      openModel(
        path = path,
        mode = mode,
        component = component,
        autoGpuPreferred = autoGpuPreferred,
        quantizedHint = quantizedHint,
      )
    return try {
      block(compiled)
    } finally {
      try {
        compiled.close()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to close $component model", e)
      }
    }
  }

  private fun openModel(
    path: String,
    mode: MusicAccelerationMode,
    component: String,
    autoGpuPreferred: Boolean,
    quantizedHint: Boolean,
  ): CompiledModel {
    if (mode == MusicAccelerationMode.CPU) {
      return createCpuModel(path)
    }

    if (mode == MusicAccelerationMode.AUTO) {
      if (!autoGpuPreferred) {
        return createCpuModel(path)
      }
      return try {
        CompiledModel.create(path, CompiledModel.Options(Accelerator.GPU, Accelerator.CPU))
      } catch (gpuError: Throwable) {
        Log.w(TAG, "AUTO GPU/CPU load failed for $component, using CPU", gpuError)
        createCpuModel(path)
      }
    }

    if (component == "text") {
      appendAccelerationReport("Text=CPU")
      return createCpuModel(path)
    }

    val profiles =
      listOf(
        "AUTOMATIC" to "DEFAULT",
        "OPENCL" to "FP16",
        "OPENCL" to "FP32",
        "OPENGL" to "FP16",
        "OPENGL" to "FP32",
      )
    val failures = mutableListOf<String>()
    for ((backend, precision) in profiles) {
      try {
        val options = CompiledModel.Options(Accelerator.GPU, Accelerator.CPU)
        configureGpuOptions(options, backend, precision, quantizedHint)
        val compiled = CompiledModel.create(path, options)
        appendAccelerationReport(
          "$component=GPU+CPU 混合编译成功($backend/$precision)；实际 GPU 分区需结合耗时判断"
        )
        return compiled
      } catch (error: Throwable) {
        val cause = rootCause(error)
        failures += "$backend/$precision: ${cause.javaClass.simpleName}: ${cause.message.orEmpty()}"
        System.gc()
      }
    }

    appendAccelerationReport("$component=GPU+CPU 五种 profile 均失败，已安全回退 CPU")
    Log.w(TAG, "GPU profiles failed for $component: ${failures.joinToString(" | ")}")
    return createCpuModel(path)
  }

  private fun configureGpuOptions(
    options: CompiledModel.Options,
    backendName: String,
    precisionName: String,
    quantizedHint: Boolean,
  ) {
    val gpuOptionsClass = Class.forName("com.google.ai.edge.litert.CompiledModel\$GpuOptions")
    val backendClass = Class.forName("com.google.ai.edge.litert.CompiledModel\$GpuOptions\$Backend")
    val precisionClass = Class.forName("com.google.ai.edge.litert.CompiledModel\$GpuOptions\$Precision")
    val backend = backendClass.enumConstants.first { (it as Enum<*>).name == backendName }
    val precision = precisionClass.enumConstants.first { (it as Enum<*>).name == precisionName }

    val ctor =
      gpuOptionsClass.constructors.firstOrNull { it.parameterCount == 15 }
        ?: throw NoSuchMethodException("LiteRT 2.1.6 GpuOptions 15-arg constructor not found")
    val args = arrayOfNulls<Any>(15)
    args[2] = if (quantizedHint) true else null
    args[3] = precision
    args[12] = backend
    val gpuOptions = ctor.newInstance(*args)

    val setter =
      options.javaClass.methods.firstOrNull {
        it.name == "setGpuOptions" &&
          it.parameterCount == 1 &&
          it.parameterTypes[0].isInstance(gpuOptions)
      }
    if (setter != null) {
      setter.invoke(options, gpuOptions)
      return
    }
    val field =
      try {
        options.javaClass.getDeclaredField("gpuOptions")
      } catch (_: NoSuchFieldException) {
        null
      }
    if (field != null) {
      field.isAccessible = true
      field.set(options, gpuOptions)
      return
    }
    throw NoSuchMethodException("LiteRT Options.setGpuOptions not found")
  }

  private fun appendAccelerationReport(line: String) {
    if (line.isBlank()) return
    lastAccelerationReport =
      if (lastAccelerationReport.isBlank()) line else "$lastAccelerationReport；$line"
  }

  override fun close() {
    // 0.4.9 integration keeps only lightweight configuration in model.instance.
  }
}

fun createMusicEngine(context: Context, model: Model): MusicGenerationEngine {
  val kind = model.musicGenerationSpec()?.kind ?: error("Unsupported music model: ${model.name}")
  return Box049SoundGenEngine(model = model, kind = kind)
}

private fun createCpuModel(path: String): CompiledModel {
  return CompiledModel.create(path, CompiledModel.Options(Accelerator.CPU))
}

private fun runExact(
  model: CompiledModel,
  inputs: List<TensorBuffer>,
  outputs: List<TensorBuffer>,
) {
  val methods = model.javaClass.methods
  try {
    val threeArg =
      methods.firstOrNull {
        it.name == "run" &&
          it.parameterCount == 3 &&
          List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
          List::class.java.isAssignableFrom(it.parameterTypes[1]) &&
          it.parameterTypes[2] == Int::class.javaPrimitiveType
      }
    if (threeArg != null) {
      threeArg.invoke(model, inputs, outputs, 0)
      return
    }

    val twoArg =
      methods.firstOrNull {
        it.name == "run" &&
          it.parameterCount == 2 &&
          List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
          List::class.java.isAssignableFrom(it.parameterTypes[1])
      }
    if (twoArg != null) {
      twoArg.invoke(model, inputs, outputs)
      return
    }

    val defaultRun =
      methods.firstOrNull {
        it.name == "run\$default" &&
          Modifier.isStatic(it.modifiers) &&
          it.parameterCount >= 5
      }
    if (defaultRun != null) {
      val parameterTypes = defaultRun.parameterTypes
      val args = arrayOfNulls<Any>(parameterTypes.size)
      args[0] = model
      args[1] = inputs
      args[2] = outputs
      for (index in 3 until parameterTypes.size) {
        args[index] =
          if (parameterTypes[index] == Int::class.javaPrimitiveType) {
            if (index == 3) 0 else 4
          } else {
            null
          }
      }
      defaultRun.invoke(null, *args)
      return
    }
  } catch (e: InvocationTargetException) {
    throw RuntimeException("LiteRT run failed", e.targetException ?: e)
  }
  throw NoSuchMethodException("No compatible LiteRT run overload")
}

private fun requireBufferCount(buffers: List<TensorBuffer>, expected: Int, label: String) {
  require(buffers.size == expected) { "$label count ${buffers.size}, expected $expected" }
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

private fun gaussian(seed: Long, size: Int): FloatArray {
  val random = Random(seed)
  return FloatArray(size) { random.nextGaussian().toFloat() }
}

internal fun createHdTextMask(attentionMask: LongArray): FloatArray {
  return FloatArray(BoxSoundGenCore.HD_TEXT_TOKEN_COUNT) { index ->
    if (attentionMask.getOrNull(index) == 1L) 1f else 0f
  }
}

private fun rootCause(error: Throwable): Throwable {
  var current = error
  while (current is InvocationTargetException && current.targetException != null) {
    current = current.targetException
  }
  while (current.cause != null && current.cause !== current) {
    current = current.cause!!
  }
  return current
}
