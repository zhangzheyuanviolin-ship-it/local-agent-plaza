/*
 * FLUX.2 Klein LiteRT Android host pipeline.
 * Host preparation follows the Apache-2.0 LiteRT community reference and the Box 3.3.3 device route.
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
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object FluxKleinImageGenerationClient {
  fun generateImage(
    context: Context,
    modelPath: String,
    prompt: String,
    seed: Long,
    progressListener: ((ImageGenerationStageProgress) -> Unit)? = null,
  ): NativeImageGenerationResult {
    val primary = File(modelPath)
    require(primary.exists()) { "FLUX 主模型文件不存在：$modelPath" }
    val dir = primary.parentFile ?: error("无法解析 FLUX 模型目录")
    val engine = FluxKleinEngine(context, dir, progressListener)
    return try {
      engine.generate(prompt, seed)
    } finally {
      engine.close()
    }
  }
}

private class FluxKleinEngine(
  private val context: Context,
  private val dir: File,
  private val progress: ((ImageGenerationStageProgress) -> Unit)?,
) : AutoCloseable {
  private val requiredGraphs =
    listOf(
      "ke_enc0.tflite", "ke_enc1.tflite", "ke_enc2.tflite", "kc_prep.tflite",
      "kc_double0.tflite", "kc_double1.tflite", "kc_single0.tflite", "kc_single1.tflite",
      "kc_single2.tflite", "kc_single3.tflite", "kc_final.tflite", "kv_vae.tflite",
    )
  private val environment: Environment
  private val tokenizer: FluxQwenTokenizer
  private val embedFile: RandomAccessFile
  private val embedMap: MappedByteBuffer
  private val encCos: FloatArray
  private val encSin: FloatArray
  private val ditCos: FloatArray
  private val ditSin: FloatArray
  private val sigmas: FloatArray
  private val temb: Array<FloatArray>
  private val bnMean: FloatArray
  private val bnStd: FloatArray

  init {
    val required =
      requiredGraphs + listOf(FLUX_QWEN_EMBED, FLUX_QWEN_VOCAB, FLUX_QWEN_MERGES, FLUX_QWEN_SPECIAL)
    val missing = required.filter { !File(dir, it).exists() }
    require(missing.isEmpty()) { "FLUX 模型文件缺失：${missing.joinToString()}" }
    tokenizer =
      FluxQwenTokenizer(
        File(dir, FLUX_QWEN_VOCAB),
        File(dir, FLUX_QWEN_MERGES),
        File(dir, FLUX_QWEN_SPECIAL),
      )
    embedFile = RandomAccessFile(File(dir, FLUX_QWEN_EMBED), "r")
    embedMap =
      embedFile.channel.map(FileChannel.MapMode.READ_ONLY, 0L, embedFile.length()).apply {
        order(ByteOrder.LITTLE_ENDIAN)
      }
    encCos = assetFloats("enc_cos.bin")
    encSin = assetFloats("enc_sin.bin")
    ditCos = assetFloats("dit_cos.bin")
    ditSin = assetFloats("dit_sin.bin")
    sigmas = assetFloats("sigmas.bin")
    val allTemb = assetFloats("temb.bin")
    require(allTemb.size == 4 * 3072) { "FLUX temb.bin 尺寸异常：${allTemb.size}" }
    temb = Array(4) { step -> allTemb.copyOfRange(step * 3072, (step + 1) * 3072) }
    bnMean = assetFloats("bn_mean.bin")
    bnStd = assetFloats("bn_std.bin")
    require(sigmas.size == 5 && bnMean.size == 128 && bnStd.size == 128) { "FLUX host 常量尺寸异常" }
    environment = Environment.create()
  }

  private fun assetFloats(name: String): FloatArray {
    val bytes = context.assets.open("fluxklein/$name").use { it.readBytes() }
    val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(fb.remaining()).also { fb.get(it) }
  }

  private fun runGraph(name: String, inputs: List<FloatArray>): List<FloatArray> {
    val path = File(dir, name).absolutePath
    val gpuOptions = CompiledModel.Options(Accelerator.GPU).apply {
      gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
    }
    val model =
      try {
        CompiledModel.create(path, gpuOptions, environment)
      } catch (gpuError: Throwable) {
        Log.w("FluxKleinRuntime", "$name GPU compile failed; falling back to CPU", gpuError)
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
    progress?.invoke(ImageGenerationStageProgress("Encoding prompt", totalSteps = 4))
    val formatted =
      "<|im_start|>user\n$promptText<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
    val rawIds = tokenizer.encode(formatted)
    val valid = minOf(rawIds.size, 512)
    val ids = IntArray(512) { index -> if (index < valid) rawIds[index] else 151643 }
    var hidden = embed(ids)
    val mask = causalMask(valid)
    val taps = ArrayList<FloatArray>(3)
    for (i in 0 until 3) {
      progress?.invoke(
        ImageGenerationStageProgress(
          stageText = "Text encoder ${i + 1}/3",
          timingText = elapsed(totalStart),
          totalSteps = 4,
        )
      )
      hidden = runGraph("ke_enc$i.tflite", listOf(hidden, mask, encCos, encSin))[0]
      taps.add(hidden)
    }
    val promptEmbeds = interleaveTaps(taps)
    var latents = FloatArray(32768)
    Random(seed).let { random -> for (i in latents.indices) latents[i] = random.nextGaussian().toFloat() }

    for (step in 0 until 4) {
      val displayStep = step + 1
      progress?.invoke(
        ImageGenerationStageProgress(
          "Step $displayStep/4 · prep", elapsed(totalStart), displayStep, 4
        )
      )
      val prep = runGraph("kc_prep.tflite", listOf(latents, promptEmbeds, temb[step]))
      var image = prep[0]
      var text = prep[1]
      val modImg = prep[2]
      val modTxt = prep[3]
      val modSingle = prep[4]
      for (i in 0 until 2) {
        progress?.invoke(
          ImageGenerationStageProgress(
            "Step $displayStep/4 · double ${i + 1}/2", elapsed(totalStart), displayStep, 4
          )
        )
        val out =
          runGraph("kc_double$i.tflite", listOf(image, text, ditCos, ditSin, modImg, modTxt))
        image = out[0]
        text = out[1]
      }
      var joint = FloatArray(text.size + image.size)
      System.arraycopy(text, 0, joint, 0, text.size)
      System.arraycopy(image, 0, joint, text.size, image.size)
      for (i in 0 until 4) {
        progress?.invoke(
          ImageGenerationStageProgress(
            "Step $displayStep/4 · single ${i + 1}/4", elapsed(totalStart), displayStep, 4
          )
        )
        joint = runGraph("kc_single$i.tflite", listOf(joint, ditCos, ditSin, modSingle))[0]
      }
      progress?.invoke(
        ImageGenerationStageProgress(
          "Step $displayStep/4 · final", elapsed(totalStart), displayStep, 4
        )
      )
      val velocity = runGraph("kc_final.tflite", listOf(joint, temb[step]))[0]
      val deltaSigma = sigmas[step + 1] - sigmas[step]
      for (i in latents.indices) latents[i] += deltaSigma * velocity[i]
    }

    progress?.invoke(ImageGenerationStageProgress("Decoding image", elapsed(totalStart), 4, 4))
    val image = runGraph("kv_vae.tflite", listOf(unpackLatents(latents)))[0]
    require(image.size >= 3 * 256 * 256) { "FLUX VAE 输出尺寸异常：${image.size}" }
    val rgb = ByteArray(256 * 256 * 3)
    for (pixel in 0 until 256 * 256) {
      for (channel in 0 until 3) {
        val value = ((image[channel * 65536 + pixel] / 2f + 0.5f) * 255f).coerceIn(0f, 255f)
        rgb[pixel * 3 + channel] = kotlin.math.round(value).toInt().toByte()
      }
    }
    progress?.invoke(ImageGenerationStageProgress("Done", elapsed(totalStart), 4, 4))
    return NativeImageGenerationResult(256, 256, 3, rgb)
  }

  private fun elapsed(start: Long): String =
    "累计 %.1f 秒".format((System.currentTimeMillis() - start) / 1000.0)

  private fun embed(ids: IntArray): FloatArray {
    val width = 2560
    val result = FloatArray(512 * width)
    for (tokenIndex in 0 until 512) {
      val id = ids[tokenIndex]
      require(id in 0 until 151936) { "FLUX token id 越界：$id" }
      val base = id * width * 2
      for (j in 0 until width) result[tokenIndex * width + j] = halfToFloat(embedMap.getShort(base + j * 2))
    }
    return result
  }

  private fun causalMask(validTokens: Int): FloatArray {
    val plane = FloatArray(512 * 512)
    for (row in 0 until 512) {
      for (col in 0 until 512) {
        plane[row * 512 + col] = if (col > row || col >= validTokens) -Float.MAX_VALUE else 0f
      }
    }
    return FloatArray(32 * plane.size).also { all ->
      for (head in 0 until 32) System.arraycopy(plane, 0, all, head * plane.size, plane.size)
    }
  }

  private fun interleaveTaps(taps: List<FloatArray>): FloatArray {
    require(taps.size == 3)
    val result = FloatArray(512 * 7680)
    for (token in 0 until 512) {
      for (tap in 0 until 3) {
        System.arraycopy(taps[tap], token * 2560, result, token * 7680 + tap * 2560, 2560)
      }
    }
    return result
  }

  private fun unpackLatents(packed: FloatArray): FloatArray {
    require(packed.size == 32768)
    val out = FloatArray(32768)
    for (packedChannel in 0 until 128) {
      val outputChannel = packedChannel / 4
      val patchRow = (packedChannel % 4) / 2
      val patchCol = packedChannel % 2
      val std = bnStd[packedChannel]
      val mean = bnMean[packedChannel]
      for (h in 0 until 16) {
        for (w in 0 until 16) {
          val src = ((h * 16 + w) * 128) + packedChannel
          val dst = (outputChannel * 1024) + ((h * 2 + patchRow) * 32) + (w * 2 + patchCol)
          out[dst] = packed[src] * std + mean
        }
      }
    }
    return out
  }

  private fun halfToFloat(value: Short): Float {
    val bits = value.toInt() and 0xffff
    val sign = (bits ushr 15) and 1
    val exp = (bits ushr 10) and 0x1f
    val mant = bits and 0x3ff
    val outBits =
      when {
        exp == 0x1f -> (sign shl 31) or 0x7f800000 or (mant shl 13)
        exp != 0 -> (sign shl 31) or ((exp + 112) shl 23) or (mant shl 13)
        mant == 0 -> sign shl 31
        else -> {
          var m = mant
          var shift = 0
          while ((m and 0x400) == 0) { m = m shl 1; shift++ }
          (sign shl 31) or ((113 - shift) shl 23) or ((m and 0x3ff) shl 13)
        }
      }
    return Float.fromBits(outBits)
  }

  override fun close() {
    runCatching { embedFile.close() }
    runCatching { environment.close() }
  }
}

private class FluxQwenTokenizer(vocabFile: File, mergesFile: File, specialFile: File) {
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
        for (b in matcher.group().toByteArray(Charsets.UTF_8)) append(byteEncoder[b.toInt() and 255])
      }
      val pieces = encoded.map { it.toString() }.toMutableList()
      while (pieces.size > 1) {
        var bestIndex = -1
        var bestRank = Int.MAX_VALUE
        for (i in 0 until pieces.size - 1) {
          val rank = merges[pieces[i] to pieces[i + 1]] ?: continue
          if (rank < bestRank) { bestRank = rank; bestIndex = i }
        }
        if (bestIndex < 0) break
        pieces[bestIndex] = pieces[bestIndex] + pieces[bestIndex + 1]
        pieces.removeAt(bestIndex + 1)
      }
      for (piece in pieces) out.add(vocab[piece] ?: error("FLUX BPE token 不在词表：$piece"))
    }
  }

  private fun buildByteEncoder() {
    val base = ArrayList<Int>()
    for (i in 33..126) base.add(i)
    for (i in 161..172) base.add(i)
    for (i in 174..255) base.add(i)
    val values = ArrayList(base)
    var extra = 0
    for (i in 0..255) {
      if (!base.contains(i)) {
        base.add(i)
        values.add(256 + extra)
        extra++
      }
    }
    for (i in base.indices) byteEncoder[base[i]] = values[i].toChar().toString()
  }
}
