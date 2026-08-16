/*
 * Bonsai Image Android host pipeline adapted from the Apache-2.0 reference implementation in
 * john-rocky/hf-to-litertlm (bonsai_image_work/device/BonsaiAppAndroid).
 *
 * Copyright 2026 Daisuke Majima. All Rights Reserved.
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

package com.google.ai.edge.gallery.customtasks.visualcreation

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject
import org.tensorflow.lite.Interpreter

object BonsaiImageGenerationClient {
  private val tokenizerCache = ConcurrentHashMap<String, BonsaiQwenTokenizer>()

  fun generateImage(
    modelPath: String,
    prompt: String,
    seed: Long,
    steps: Int,
    threadCount: Int,
    progressListener: ((ImageGenerationStageProgress) -> Unit)? = null,
  ): NativeImageGenerationResult {
    val primary = File(modelPath)
    require(primary.exists()) { "Bonsai DiT文件不存在：$modelPath" }
    val modelDir = primary.parentFile ?: error("无法解析Bonsai模型目录：$modelPath")
    val metaFile = File(modelDir, BONSAI_PIPELINE_META_FILE)
    val vocabFile = File(modelDir, BONSAI_TOKENIZER_VOCAB_FILE)
    val mergesFile = File(modelDir, BONSAI_TOKENIZER_MERGES_FILE)
    require(metaFile.exists()) { "Bonsai pipeline_meta.json不存在：${metaFile.absolutePath}" }
    require(vocabFile.exists()) { "Bonsai tokenizer vocab不存在：${vocabFile.absolutePath}" }
    require(mergesFile.exists()) { "Bonsai tokenizer merges不存在：${mergesFile.absolutePath}" }

    val meta = JSONObject(metaFile.readText())
    val missing = BonsaiPipeline.missingFiles(modelDir, meta)
    require(missing.isEmpty()) { "Bonsai模型文件缺失：${missing.joinToString()}" }

    progressListener?.invoke(ImageGenerationStageProgress("读取模型元数据和 tokenizer"))
    val tokenizerKey = "${vocabFile.absolutePath}:${vocabFile.lastModified()}:${mergesFile.lastModified()}"
    val tokenizer =
      tokenizerCache[tokenizerKey] ?:
        vocabFile.inputStream().use { vocab ->
          mergesFile.inputStream().use { merges -> BonsaiQwenTokenizer(vocab, merges) }
        }.also { tokenizerCache[tokenizerKey] = it }
    val pipeline = BonsaiPipeline(modelDir = modelDir, meta = meta)
    val safeSteps = steps.coerceIn(1, 20)
    val safeThreads = threadCount.coerceIn(2, 6)
    val result =
      pipeline.generate(
        tokenizer = tokenizer,
        prompt = prompt,
        seed = seed,
        steps = safeSteps,
        threads = safeThreads,
        onStageProgress = { update -> progressListener?.invoke(update) },
      )
    return NativeImageGenerationResult(
      width = 512,
      height = 512,
      channels = 3,
      bytes = result.rgb,
    )
  }
}

private class BonsaiPipeline(private val modelDir: File, meta: JSONObject) {
  private val ditFile = meta.getJSONObject("files").getString("dit")
  private val textEncoderFile = meta.getJSONObject("files").getString("textenc")
  private val vaeFile = meta.getJSONObject("files").getString("vae")
  private val bnScale =
    meta.getJSONArray("latent_bn_scale").let { array ->
      FloatArray(array.length()) { array.getDouble(it).toFloat() }
    }
  private val bnShift =
    meta.getJSONArray("latent_bn_shift").let { array ->
      FloatArray(array.length()) { array.getDouble(it).toFloat() }
    }

  data class Result(val rgb: ByteArray)

  companion object {
    private fun resolveModel(name: String, dir: File): File? =
      listOf(name, name.replace(".tflite", "_fixed.tflite"))
        .map { File(dir, it) }
        .firstOrNull { it.exists() }

    fun missingFiles(modelDir: File, meta: JSONObject): List<String> {
      val files = meta.getJSONObject("files")
      return listOf("dit", "textenc", "vae")
        .map { files.getString(it) }
        .filter { resolveModel(it, modelDir) == null }
    }
  }

  private class Graph(file: File, threads: Int) : AutoCloseable {
    private val interpreter: Interpreter
    private val argumentOrder: IntArray

    init {
      interpreter =
        Interpreter(
          file,
          Interpreter.Options().apply {
            numThreads = threads
            setUseXNNPACK(true)
          },
        )
      interpreter.allocateTensors()
      val count = interpreter.inputTensorCount
      fun argumentPosition(inputIndex: Int): Int {
        val name = interpreter.getInputTensor(inputIndex).name()
        val marker = name.lastIndexOf("args_")
        if (marker < 0) return inputIndex
        return name.substring(marker + 5).takeWhile { it.isDigit() }.toIntOrNull() ?: inputIndex
      }
      argumentOrder = (0 until count).sortedBy { argumentPosition(it) }.toIntArray()
    }

    fun run(inputs: List<ByteBuffer>, outputFloatCount: Int): FloatArray {
      require(inputs.size == argumentOrder.size) {
        "Bonsai输入数量不匹配：host=${inputs.size}, graph=${argumentOrder.size}"
      }
      val graphInputs = arrayOfNulls<Any>(inputs.size)
      for ((argumentIndex, graphIndex) in argumentOrder.withIndex()) {
        val tensor = interpreter.getInputTensor(graphIndex)
        val buffer = inputs[argumentIndex]
        buffer.rewind()
        require(tensor.numBytes() == buffer.capacity()) {
          "Bonsai输入字节数不匹配：index=$graphIndex graph=${tensor.numBytes()} host=${buffer.capacity()}"
        }
        graphInputs[graphIndex] = buffer
      }
      val output =
        ByteBuffer.allocateDirect(outputFloatCount * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
      interpreter.runForMultipleInputsOutputs(graphInputs, mapOf(0 to output))
      output.rewind()
      return FloatArray(outputFloatCount).also { output.asFloatBuffer().get(it) }
    }

    override fun close() {
      interpreter.close()
    }
  }

  private fun modelFile(name: String): File =
    resolveModel(name, modelDir) ?: error("Bonsai模型文件缺失：$name")

  private fun floatBuffer(values: FloatArray): ByteBuffer =
    ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
      .order(ByteOrder.nativeOrder())
      .apply {
        asFloatBuffer().put(values)
        rewind()
      }

  private fun intBuffer(values: IntArray): ByteBuffer =
    ByteBuffer.allocateDirect(values.size * Int.SIZE_BYTES)
      .order(ByteOrder.nativeOrder())
      .apply {
        asIntBuffer().put(values)
        rewind()
      }

  fun generate(
    tokenizer: BonsaiQwenTokenizer,
    prompt: String,
    seed: Long,
    steps: Int,
    threads: Int,
    onStageProgress: (ImageGenerationStageProgress) -> Unit,
  ): Result {
    val totalStart = System.currentTimeMillis()
    onStageProgress(ImageGenerationStageProgress("提示词编码与文本编码器加载中"))
    val encoded = tokenizer.encodePrompt(prompt)
    val embeddings: FloatArray
    val textStart = System.currentTimeMillis()
    Graph(modelFile(textEncoderFile), threads).use { textEncoder ->
      embeddings =
        textEncoder.run(
          inputs = listOf(intBuffer(encoded.ids), intBuffer(encoded.mask)),
          outputFloatCount = BonsaiMath.SEQ * 7680,
        )
    }
    val textMs = System.currentTimeMillis() - textStart
    onStageProgress(
      ImageGenerationStageProgress(
        stageText = "文本编码完成，准备 DiT 扩散模型",
        timingText = "文本编码 %.1f 秒".format(textMs / 1000.0),
        totalSteps = steps,
      )
    )
    System.gc()

    val sigmas = BonsaiMath.sigmas(steps)
    val imageIds = floatBuffer(BonsaiMath.imageIds())
    val textIds = floatBuffer(BonsaiMath.textIds())
    val embeddingBuffer = floatBuffer(embeddings)
    var latents = BonsaiMath.noise(seed)

    val diffusionStart = System.currentTimeMillis()
    onStageProgress(ImageGenerationStageProgress("正在加载 Bonsai DiT 扩散模型", totalSteps = steps))
    Graph(modelFile(ditFile), threads).use { dit ->
      for (step in 0 until steps) {
        val velocity =
          dit.run(
            inputs =
              listOf(
                floatBuffer(latents),
                embeddingBuffer,
                floatBuffer(floatArrayOf(sigmas[step])),
                imageIds,
                textIds,
              ),
            outputFloatCount = BonsaiMath.TOKENS * BonsaiMath.PACKED_CHANNELS,
          )
        val deltaSigma = sigmas[step + 1] - sigmas[step]
        for (i in latents.indices) {
          latents[i] += deltaSigma * velocity[i]
        }
        val diffusionMs = System.currentTimeMillis() - diffusionStart
        onStageProgress(
          ImageGenerationStageProgress(
            stageText = "DiT 扩散采样：第 ${step + 1} / $steps 步",
            timingText = "扩散累计 %.1f 秒".format(diffusionMs / 1000.0),
            step = step + 1,
            totalSteps = steps,
          )
        )
      }
    }
    System.gc()

    val diffusionMs = System.currentTimeMillis() - diffusionStart
    onStageProgress(
      ImageGenerationStageProgress(
        stageText = "扩散完成，正在加载 VAE 解码器",
        timingText = "扩散总耗时 %.1f 秒".format(diffusionMs / 1000.0),
        step = steps,
        totalSteps = steps,
      )
    )
    val vaeLatents = BonsaiMath.unpatchify(latents, bnScale, bnShift)
    val rgb = ByteArray(512 * 512 * 3)
    val vaeStart = System.currentTimeMillis()
    Graph(modelFile(vaeFile), threads).use { vae ->
      val output = vae.run(listOf(floatBuffer(vaeLatents)), 3 * 512 * 512)
      for (channel in 0 until 3) {
        for (pixel in 0 until 512 * 512) {
          val value =
            ((output[channel * 262144 + pixel] / 2f + 0.5f) * 255f).coerceIn(0f, 255f)
          rgb[pixel * 3 + channel] = Math.round(value).toByte()
        }
      }
    }
    val vaeMs = System.currentTimeMillis() - vaeStart
    val totalMs = System.currentTimeMillis() - totalStart
    onStageProgress(
      ImageGenerationStageProgress(
        stageText = "VAE 解码完成，正在整理图片",
        timingText = "VAE %.1f 秒；总计 %.1f 秒".format(vaeMs / 1000.0, totalMs / 1000.0),
        step = steps,
        totalSteps = steps,
      )
    )
    return Result(rgb = rgb)
  }
}

private object BonsaiMath {
  const val SEQ = 256
  const val TOKENS = 1024
  private const val LATENT_GRID = 32
  const val PACKED_CHANNELS = 128

  fun sigmas(steps: Int): FloatArray {
    val m200 = 0.00016927 * TOKENS + 0.45666666
    val m10 = 8.73809524e-05 * TOKENS + 1.89833333
    val a = (m200 - m10) / 190.0
    val mu = a * steps + (m200 - 200.0 * a)
    val output = FloatArray(steps + 1)
    for (i in 0 until steps) {
      val linear = 1.0 - i * (1.0 - 1.0 / steps) / maxOf(steps - 1, 1)
      output[i] = (exp(mu) / (exp(mu) + (1.0 / linear - 1.0))).toFloat()
    }
    return output
  }

  fun imageIds(): FloatArray {
    val output = FloatArray(TOKENS * 4)
    for (height in 0 until LATENT_GRID) {
      for (width in 0 until LATENT_GRID) {
        val base = (height * LATENT_GRID + width) * 4
        output[base + 1] = height.toFloat()
        output[base + 2] = width.toFloat()
      }
    }
    return output
  }

  fun textIds(): FloatArray {
    val output = FloatArray(SEQ * 4)
    for (i in 0 until SEQ) output[i * 4 + 3] = i.toFloat()
    return output
  }

  fun noise(seed: Long): FloatArray {
    var state = seed
    fun nextLong(): Long {
      state += -0x61c8864680b583ebL
      var z = state
      z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
      z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
      return z xor (z ushr 31)
    }
    fun uniform(): Double = ((nextLong() ushr 11) + 1.0) / 9007199254740993.0

    val output = FloatArray(TOKENS * PACKED_CHANNELS)
    var index = 0
    while (index < output.size) {
      val radius = sqrt(-2.0 * ln(uniform()))
      val theta = 2.0 * Math.PI * uniform()
      output[index] = (radius * cos(theta)).toFloat()
      if (index + 1 < output.size) output[index + 1] = (radius * sin(theta)).toFloat()
      index += 2
    }
    return output
  }

  fun unpatchify(latents: FloatArray, scale: FloatArray, shift: FloatArray): FloatArray {
    require(scale.size == PACKED_CHANNELS && shift.size == PACKED_CHANNELS) {
      "Bonsai latent normalization参数长度异常：scale=${scale.size}, shift=${shift.size}"
    }
    val output = FloatArray(32 * 64 * 64)
    for (height in 0 until LATENT_GRID) {
      for (width in 0 until LATENT_GRID) {
        val base = (height * LATENT_GRID + width) * PACKED_CHANNELS
        for (channel in 0 until 32) {
          for (row in 0..1) {
            for (column in 0..1) {
              val packedChannel = channel * 4 + row * 2 + column
              output[channel * 4096 + (2 * height + row) * 64 + (2 * width + column)] =
                scale[packedChannel] * latents[base + packedChannel] + shift[packedChannel]
            }
          }
        }
      }
    }
    return output
  }
}

private class BonsaiQwenTokenizer(vocabJson: InputStream, mergesText: InputStream) {
  companion object {
    private const val SEQUENCE_LENGTH = 256
    private const val PAD_ID = 151643
    private const val IM_START_ID = 151644
    private val SUFFIX_IDS = intArrayOf(151645, 198, 151644, 77091, 198, 151667, 271, 151668, 271)
    private val PRETOKEN =
      Regex(
        "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}|" +
          " ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+",
        setOf(RegexOption.UNIX_LINES),
      )
    private val BYTE_TO_CHAR: CharArray =
      run {
        val bytes = ((33..126) + (161..172) + (174..255)).toMutableList()
        val chars = bytes.toMutableList()
        var extra = 0
        for (byte in 0..255) {
          if (byte !in bytes) {
            bytes.add(byte)
            chars.add(256 + extra)
            extra++
          }
        }
        CharArray(256).also { table ->
          for (i in bytes.indices) table[bytes[i]] = chars[i].toChar()
        }
      }
  }

  private val vocab = HashMap<String, Int>(160_000)
  private val mergeRanks = HashMap<String, Int>(160_000)
  private val cache = HashMap<String, IntArray>()

  init {
    val json = JSONObject(vocabJson.bufferedReader().readText())
    for (key in json.keys()) vocab[key] = json.getInt(key)
    mergesText.bufferedReader().forEachLine { line ->
      if (line.isNotEmpty() && !line.startsWith("#")) mergeRanks[line] = mergeRanks.size
    }
  }

  data class Encoded(val ids: IntArray, val mask: IntArray)

  fun encodePrompt(prompt: String): Encoded {
    var body = encode("user\n$prompt")
    val maxBody = SEQUENCE_LENGTH - 1 - SUFFIX_IDS.size
    if (body.size > maxBody) body = body.copyOf(maxBody)
    val realLength = 1 + body.size + SUFFIX_IDS.size
    val ids = IntArray(SEQUENCE_LENGTH) { PAD_ID }
    val mask = IntArray(SEQUENCE_LENGTH)
    ids[0] = IM_START_ID
    body.copyInto(ids, 1)
    SUFFIX_IDS.copyInto(ids, 1 + body.size)
    for (i in 0 until realLength) mask[i] = 1
    return Encoded(ids = ids, mask = mask)
  }

  private fun encode(text: String): IntArray {
    val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
    val output = ArrayList<Int>(normalized.length / 3 + 8)
    for (match in PRETOKEN.findAll(normalized)) {
      if (match.value.isEmpty()) continue
      for (id in bytePairEncode(match.value)) output.add(id)
    }
    return output.toIntArray()
  }

  private fun bytePairEncode(pretoken: String): IntArray {
    cache[pretoken]?.let { return it }
    var word =
      pretoken.toByteArray(Charsets.UTF_8).map { byte ->
        BYTE_TO_CHAR[byte.toInt() and 0xff].toString()
      }
    while (word.size > 1) {
      var bestRank = Int.MAX_VALUE
      var bestIndex = -1
      for (i in 0 until word.size - 1) {
        val rank = mergeRanks[word[i] + " " + word[i + 1]] ?: continue
        if (rank < bestRank) {
          bestRank = rank
          bestIndex = i
        }
      }
      if (bestIndex < 0) break
      val left = word[bestIndex]
      val right = word[bestIndex + 1]
      val merged = ArrayList<String>(word.size)
      var index = 0
      while (index < word.size) {
        if (index < word.size - 1 && word[index] == left && word[index + 1] == right) {
          merged.add(left + right)
          index += 2
        } else {
          merged.add(word[index])
          index++
        }
      }
      word = merged
    }
    val ids = word.mapNotNull { vocab[it] }.toIntArray()
    cache[pretoken] = ids
    return ids
  }
}
