/*
 * Z-Image Turbo LiteRT Android host pipeline.
 * Host execution mirrors the Apache-2.0 Box 3.3.3 Android implementation and the
 * litert-community Z-Image-Turbo-LiteRT graph split.
 */
package com.google.ai.edge.gallery.customtasks.visualcreation

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.Normalizer
import java.util.Random
import java.util.regex.Pattern
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

object ZImageTurboGenerationClient {
  fun generateImage(
    context: Context,
    modelPath: String,
    prompt: String,
    seed: Long,
    progressListener: ((ImageGenerationStageProgress) -> Unit)? = null,
  ): NativeImageGenerationResult {
    val primary = File(modelPath)
    require(primary.exists()) { "Z-Image 主模型文件不存在：$modelPath" }
    val dir = primary.parentFile ?: error("无法解析 Z-Image 模型目录")
    val engine = ZImageTurboEngine(context, dir, progressListener)
    return try {
      engine.generate(prompt, seed)
    } finally {
      engine.close()
    }
  }
}

private class ZImageTurboEngine(
  private val context: Context,
  private val dir: File,
  private val progress: ((ImageGenerationStageProgress) -> Unit)?,
) : AutoCloseable {
  private val graphNames =
    listOf(
      Z_QWEN_ENCODER,
      Z_EMBED_IMAGE,
      Z_REFINE_IMAGE,
      Z_EMBED_CAPTION,
      Z_REFINE_CAPTION,
      Z_MAIN_0,
      Z_MAIN_1,
      Z_MAIN_2,
      Z_MAIN_3,
      Z_MAIN_4,
      Z_MAIN_5,
      Z_FINAL,
      Z_VAE,
    )
  private val mainGraphs = listOf(Z_MAIN_0, Z_MAIN_1, Z_MAIN_2, Z_MAIN_3, Z_MAIN_4, Z_MAIN_5)
  private val environment: Environment
  private val tokenizer: ZQwenTokenizer
  private val embedFile: RandomAccessFile
  private val embedMap: MappedByteBuffer
  private val capPadToken: FloatArray
  private val tembMlp: FloatArray

  init {
    val required = graphNames + listOf(Z_QWEN_EMBED, Z_QWEN_VOCAB, Z_QWEN_MERGES, Z_QWEN_SPECIAL)
    val missing = required.filter { !File(dir, it).exists() }
    require(missing.isEmpty()) { "Z-Image 模型文件缺失：${missing.joinToString()}" }
    tokenizer =
      ZQwenTokenizer(
        File(dir, Z_QWEN_VOCAB),
        File(dir, Z_QWEN_MERGES),
        File(dir, Z_QWEN_SPECIAL),
      )
    embedFile = RandomAccessFile(File(dir, Z_QWEN_EMBED), "r")
    embedMap =
      embedFile.channel.map(FileChannel.MapMode.READ_ONLY, 0L, embedFile.length()).apply {
        order(ByteOrder.LITTLE_ENDIAN)
      }
    capPadToken = assetFloats("zimage_cap_pad_token.bin")
    tembMlp = assetFloats("zimage_temb_mlp.bin")
    require(capPadToken.size == 3840) { "Z-Image cap pad token 尺寸异常：${capPadToken.size}" }
    require(tembMlp.size == 525568) { "Z-Image timestep MLP 尺寸异常：${tembMlp.size}" }
    environment = Environment.create()
  }

  private fun assetFloats(name: String): FloatArray {
    val bytes = context.assets.open("zimage/$name").use { it.readBytes() }
    val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(fb.remaining()).also { fb.get(it) }
  }

  private fun runGraph(name: String, inputs: List<FloatArray>): List<FloatArray> {
    val path = File(dir, name).absolutePath
    val gpuOptions =
      CompiledModel.Options(Accelerator.GPU).apply {
        gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
      }
    val model =
      try {
        CompiledModel.create(path, gpuOptions, environment)
      } catch (gpuError: Throwable) {
        Log.w("ZImageTurboRuntime", "$name GPU compile failed; falling back to CPU", gpuError)
        progress?.invoke(ImageGenerationStageProgress("$name GPU 编译失败，正在回退 CPU"))
        CompiledModel.create(path, CompiledModel.Options(Accelerator.CPU), environment)
      }
    val inputBuffers = model.createInputBuffers()
    val outputBuffers = model.createOutputBuffers()
    try {
      require(inputBuffers.size == inputs.size) {
        "$name 输入数量不匹配：host=${inputs.size}, graph=${inputBuffers.size}"
      }
      inputs.forEachIndexed { index, values -> inputBuffers[index].writeFloat(values) }
      model.run(inputBuffers, outputBuffers)
      return outputBuffers.map { it.readFloat() }
    } finally {
      inputBuffers.forEach { runCatching { it.close() } }
      outputBuffers.forEach { runCatching { it.close() } }
      model.close()
    }
  }

  fun generate(promptText: String, seed: Long): NativeImageGenerationResult {
    val totalStart = System.currentTimeMillis()
    progress?.invoke(ImageGenerationStageProgress("Encoding prompt", totalSteps = 9))
    val promptEncoding = encodePrompt(promptText)
    var latents = FloatArray(16 * 32 * 32)
    Random(seed).let { random ->
      for (index in latents.indices) latents[index] = random.nextGaussian().toFloat()
    }
    val sigmas = buildSigmas()

    for (step in 0 until 9) {
      val displayStep = step + 1
      val temb = timestepEmbedding(1f - sigmas[step])
      progress?.invoke(
        ImageGenerationStageProgress(
          "Step $displayStep/9 · caption",
          elapsed(totalStart),
          displayStep,
          9,
        )
      )
      val velocity = forwardVelocity(latents, promptEncoding, temb, displayStep, totalStart)
      val deltaSigma = sigmas[step + 1] - sigmas[step]
      // Box Z-Image Turbo returns velocity with the opposite sign used by the scheduler.
      for (index in latents.indices) latents[index] += (-velocity[index]) * deltaSigma
    }

    progress?.invoke(ImageGenerationStageProgress("Decoding image", elapsed(totalStart), 9, 9))
    val vaeInput = FloatArray(latents.size) { index -> (latents[index] / 0.3611f) + 0.1159f }
    val decoded = runGraph(Z_VAE, listOf(vaeInput))[0]
    require(decoded.size >= 3 * 256 * 256) { "Z-Image VAE 输出尺寸异常：${decoded.size}" }
    val rgb = ByteArray(256 * 256 * 3)
    for (pixel in 0 until 256 * 256) {
      for (channel in 0 until 3) {
        val normalized = (decoded[channel * 65536 + pixel] / 2f + 0.5f).coerceIn(0f, 1f)
        rgb[pixel * 3 + channel] = round(normalized * 255f).toInt().toByte()
      }
    }
    progress?.invoke(ImageGenerationStageProgress("Done", elapsed(totalStart), 9, 9))
    return NativeImageGenerationResult(256, 256, 3, rgb)
  }

  private data class PromptEncoding(val hidden: FloatArray, val validTokens: Int)

  private fun encodePrompt(promptText: String): PromptEncoding {
    val formatted = "<|im_start|>user\n$promptText<|im_end|>\n<|im_start|>assistant\n"
    val rawIds = tokenizer.encode(formatted)
    val valid64 = minOf(rawIds.size, 64)
    val ids = IntArray(64) { index -> if (index < valid64) rawIds[index] else 151643 }
    val embedded = embed(ids)
    val encoded = runGraph(Z_QWEN_ENCODER, listOf(embedded))[0]
    require(encoded.size >= 64 * 2560) { "Z-Image Qwen encoder 输出尺寸异常：${encoded.size}" }
    val valid32 = minOf(valid64, 32)
    val fixed = FloatArray(32 * 2560)
    if (valid32 > 0) System.arraycopy(encoded, 0, fixed, 0, valid32 * 2560)
    return PromptEncoding(fixed, valid32)
  }

  private fun forwardVelocity(
    latents: FloatArray,
    promptEncoding: PromptEncoding,
    temb: FloatArray,
    step: Int,
    startMs: Long,
  ): FloatArray {
    val captionEmbedded = runGraph(Z_EMBED_CAPTION, listOf(promptEncoding.hidden))[0]
    require(captionEmbedded.size >= 32 * 3840) {
      "Z-Image caption embed 输出尺寸异常：${captionEmbedded.size}"
    }
    for (token in promptEncoding.validTokens until 32) {
      System.arraycopy(capPadToken, 0, captionEmbedded, token * 3840, 3840)
    }
    val captionPos = Array(32) { index -> intArrayOf(index + 1, 0, 0) }
    val captionRope = buildRope(captionPos)
    val caption =
      runGraph(
        Z_REFINE_CAPTION,
        listOf(captionEmbedded, captionRope.first, captionRope.second),
      )[0]

    progress?.invoke(ImageGenerationStageProgress("Step $step/9 · image", elapsed(startMs), step, 9))
    val imageTokens = patchify(latents)
    val imageEmbedded = runGraph(Z_EMBED_IMAGE, listOf(imageTokens))[0]
    require(imageEmbedded.size >= 256 * 3840) {
      "Z-Image image embed 输出尺寸异常：${imageEmbedded.size}"
    }
    val imagePos = Array(256) { index -> intArrayOf(33, index / 16, index % 16) }
    val imageRope = buildRope(imagePos)
    val image =
      runGraph(
        Z_REFINE_IMAGE,
        listOf(imageEmbedded, imageRope.first, imageRope.second, temb),
      )[0]

    var joint = FloatArray(image.size + caption.size)
    System.arraycopy(image, 0, joint, 0, image.size)
    System.arraycopy(caption, 0, joint, image.size, caption.size)
    val jointCos = FloatArray(imageRope.first.size + captionRope.first.size)
    val jointSin = FloatArray(imageRope.second.size + captionRope.second.size)
    System.arraycopy(imageRope.first, 0, jointCos, 0, imageRope.first.size)
    System.arraycopy(captionRope.first, 0, jointCos, imageRope.first.size, captionRope.first.size)
    System.arraycopy(imageRope.second, 0, jointSin, 0, imageRope.second.size)
    System.arraycopy(captionRope.second, 0, jointSin, imageRope.second.size, captionRope.second.size)

    for (block in mainGraphs.indices) {
      progress?.invoke(
        ImageGenerationStageProgress(
          "Step $step/9 · block ${block + 1}/6",
          elapsed(startMs),
          step,
          9,
        )
      )
      joint = runGraph(mainGraphs[block], listOf(joint, jointCos, jointSin, temb))[0]
    }
    progress?.invoke(ImageGenerationStageProgress("Step $step/9 · final", elapsed(startMs), step, 9))
    val final = runGraph(Z_FINAL, listOf(joint, temb))[0]
    require(final.size >= 256 * 64) { "Z-Image final 输出尺寸异常：${final.size}" }
    return unpatchify(final.copyOfRange(0, 256 * 64))
  }

  private fun buildSigmas(): FloatArray {
    val values = FloatArray(10)
    for (index in 0 until 9) {
      val d = 1.0 - index / 9.0
      values[index] = ((3.0 * d) / ((2.0 * d) + 1.0)).toFloat()
    }
    values[9] = 0f
    return values
  }

  private fun timestepEmbedding(t: Float): FloatArray {
    val Fourier = FloatArray(256)
    for (index in 0 until 128) {
      val value = t * 1000.0 * exp((-ln(10000.0) * index) / 128.0)
      Fourier[index] = cos(value).toFloat()
      Fourier[128 + index] = sin(value).toFloat()
    }
    val hidden = FloatArray(1024)
    for (out in 0 until 1024) {
      var sum = tembMlp[262144 + out].toDouble()
      val base = out * 256
      for (input in 0 until 256) sum += tembMlp[base + input] * Fourier[input]
      hidden[out] = (sum / (exp(-sum) + 1.0)).toFloat()
    }
    return FloatArray(256) { out ->
      var sum = tembMlp[525312 + out].toDouble()
      val base = 263168 + out * 1024
      for (input in 0 until 1024) sum += tembMlp[base + input] * hidden[input]
      sum.toFloat()
    }
  }

  private fun buildRope(positions: Array<IntArray>): Pair<FloatArray, FloatArray> {
    val axisDims = intArrayOf(32, 32, 64)
    val cosValues = FloatArray(positions.size * 64)
    val sinValues = FloatArray(positions.size * 64)
    for (token in positions.indices) {
      var offset = 0
      for (axis in 0 until 3) {
        val dim = axisDims[axis]
        val half = dim / 2
        for (frequency in 0 until half) {
          val angle =
            positions[token][axis] *
              (1.0 / 256.0.pow((frequency * 2.0) / dim))
          val target = token * 64 + offset + frequency
          cosValues[target] = cos(angle).toFloat()
          sinValues[target] = sin(angle).toFloat()
        }
        offset += half
      }
    }
    return cosValues to sinValues
  }

  private fun patchify(latents: FloatArray): FloatArray {
    require(latents.size == 16 * 32 * 32)
    val tokens = FloatArray(256 * 64)
    for (patchRow in 0 until 16) {
      for (patchCol in 0 until 16) {
        val tokenBase = (patchRow * 16 + patchCol) * 64
        for (dy in 0 until 2) {
          for (dx in 0 until 2) {
            for (channel in 0 until 16) {
              tokens[tokenBase + (dy * 2 + dx) * 16 + channel] =
                latents[channel * 1024 + (patchRow * 2 + dy) * 32 + (patchCol * 2 + dx)]
            }
          }
        }
      }
    }
    return tokens
  }

  private fun unpatchify(tokens: FloatArray): FloatArray {
    require(tokens.size >= 256 * 64)
    val latents = FloatArray(16 * 32 * 32)
    for (patchRow in 0 until 16) {
      for (patchCol in 0 until 16) {
        val tokenBase = (patchRow * 16 + patchCol) * 64
        for (dy in 0 until 2) {
          for (dx in 0 until 2) {
            for (channel in 0 until 16) {
              latents[channel * 1024 + (patchRow * 2 + dy) * 32 + (patchCol * 2 + dx)] =
                tokens[tokenBase + (dy * 2 + dx) * 16 + channel]
            }
          }
        }
      }
    }
    return latents
  }

  private fun embed(ids: IntArray): FloatArray {
    val width = 2560
    val result = FloatArray(ids.size * width)
    for (tokenIndex in ids.indices) {
      val id = ids[tokenIndex]
      require(id in 0 until 151936) { "Z-Image token id 越界：$id" }
      val base = id * width * 2
      for (dimension in 0 until width) {
        result[tokenIndex * width + dimension] = halfToFloat(embedMap.getShort(base + dimension * 2))
      }
    }
    return result
  }

  private fun halfToFloat(value: Short): Float {
    val bits = value.toInt() and 0xffff
    val sign = (bits ushr 15) and 1
    val exponent = (bits ushr 10) and 0x1f
    val mantissa = bits and 0x3ff
    val outBits =
      when {
        exponent == 0x1f -> (sign shl 31) or 0x7f800000 or (mantissa shl 13)
        exponent != 0 -> (sign shl 31) or ((exponent + 112) shl 23) or (mantissa shl 13)
        mantissa == 0 -> sign shl 31
        else -> {
          var m = mantissa
          var shift = 0
          while ((m and 0x400) == 0) {
            m = m shl 1
            shift++
          }
          (sign shl 31) or ((113 - shift) shl 23) or ((m and 0x3ff) shl 13)
        }
      }
    return Float.fromBits(outBits)
  }

  private fun elapsed(start: Long): String =
    "累计 %.1f 秒".format((System.currentTimeMillis() - start) / 1000.0)

  override fun close() {
    runCatching { embedFile.close() }
    runCatching { environment.close() }
  }
}

private class ZQwenTokenizer(vocabFile: File, mergesFile: File, specialFile: File) {
  private val vocab = HashMap<String, Int>(160000)
  private val merges = HashMap<Pair<String, String>, Int>(160000)
  private val specials = LinkedHashMap<String, Int>()
  private val byteEncoder = Array(256) { "" }
  private val ordinary =
    Pattern.compile("(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}| ?[^\\p{IsWhite_Space}\\p{L}\\p{N}]+[\\r\\n]*|\\p{IsWhite_Space}*[\\r\\n]+|\\p{IsWhite_Space}+(?!\\P{IsWhite_Space})|\\p{IsWhite_Space}+")
  private val specialPattern: Pattern

  init {
    vocabFile.useLines { lines -> lines.forEachIndexed { index, token -> vocab[token] = index } }
    mergesFile.useLines { lines ->
      lines.forEachIndexed { index, line ->
        if (line.isNotEmpty()) {
          val split = line.indexOf(' ')
          if (split > 0) merges[line.substring(0, split) to line.substring(split + 1)] = index
        }
      }
    }
    specialFile.useLines { lines ->
      lines.forEach { line ->
        if (line.isNotEmpty()) {
          val split = line.indexOf('\t')
          if (split > 0) specials[line.substring(0, split)] = line.substring(split + 1).trim().toInt()
        }
      }
    }
    buildByteEncoder()
    val keys = specials.keys.sortedByDescending { it.length }.joinToString("|") { Pattern.quote(it) }
    specialPattern = Pattern.compile(keys.ifEmpty { "(?!)" })
  }

  fun encode(text: String): IntArray {
    val out = ArrayList<Int>(text.length / 3 + 8)
    val matcher = specialPattern.matcher(text)
    var cursor = 0
    while (matcher.find()) {
      if (matcher.start() > cursor) encodeNormal(text.substring(cursor, matcher.start()), out)
      out.add(specials[matcher.group()] ?: error("未知 special token"))
      cursor = matcher.end()
    }
    if (cursor < text.length) encodeNormal(text.substring(cursor), out)
    return IntArray(out.size) { out[it] }
  }

  private fun encodeNormal(source: String, out: MutableList<Int>) {
    val matcher = ordinary.matcher(Normalizer.normalize(source, Normalizer.Form.NFC))
    while (matcher.find()) {
      val encoded = buildString {
        for (byte in matcher.group().toByteArray(Charsets.UTF_8)) append(byteEncoder[byte.toInt() and 255])
      }
      val pieces = encoded.map { it.toString() }.toMutableList()
      while (pieces.size > 1) {
        var bestIndex = -1
        var bestRank = Int.MAX_VALUE
        for (index in 0 until pieces.size - 1) {
          val rank = merges[pieces[index] to pieces[index + 1]] ?: continue
          if (rank < bestRank) {
            bestRank = rank
            bestIndex = index
          }
        }
        if (bestIndex < 0) break
        pieces[bestIndex] = pieces[bestIndex] + pieces[bestIndex + 1]
        pieces.removeAt(bestIndex + 1)
      }
      for (piece in pieces) out.add(vocab[piece] ?: error("Z-Image BPE token 不在词表：$piece"))
    }
  }

  private fun buildByteEncoder() {
    val base = ArrayList<Int>()
    for (value in 33..126) base.add(value)
    for (value in 161..172) base.add(value)
    for (value in 174..255) base.add(value)
    val values = ArrayList(base)
    var extra = 0
    for (value in 0..255) {
      if (!base.contains(value)) {
        base.add(value)
        values.add(256 + extra)
        extra++
      }
    }
    for (index in base.indices) byteEncoder[base[index]] = values[index].toChar().toString()
  }
}
