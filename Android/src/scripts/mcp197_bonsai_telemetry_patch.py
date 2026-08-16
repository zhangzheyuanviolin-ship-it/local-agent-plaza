from pathlib import Path


def one(path, old, new):
    p = Path(path)
    s = p.read_text()
    c = s.count(old)
    if c != 1:
        raise SystemExit(f"{path}: expected one match, got {c}: {old[:120]!r}")
    p.write_text(s.replace(old, new, 1))

runtime = "Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/BonsaiImageRuntime.kt"
one(runtime, "import java.nio.ByteOrder\n", "import java.nio.ByteOrder\nimport java.util.concurrent.ConcurrentHashMap\n")
one(runtime,
'''object BonsaiImageGenerationClient {
  fun generateImage(
    modelPath: String,
    prompt: String,
    seed: Long,
    steps: Int,
    threadCount: Int,
    progressListener: ((step: Int, steps: Int) -> Unit)? = null,
  ): NativeImageGenerationResult {
''',
'''object BonsaiImageGenerationClient {
  private val tokenizerCache = ConcurrentHashMap<String, BonsaiQwenTokenizer>()

  fun generateImage(
    modelPath: String,
    prompt: String,
    seed: Long,
    steps: Int,
    threadCount: Int,
    progressListener: ((ImageGenerationStageProgress) -> Unit)? = null,
  ): NativeImageGenerationResult {
''')
one(runtime,
'''    val tokenizer =
      vocabFile.inputStream().use { vocab ->
        mergesFile.inputStream().use { merges -> BonsaiQwenTokenizer(vocab, merges) }
      }
    val pipeline = BonsaiPipeline(modelDir = modelDir, meta = meta)
''',
'''    progressListener?.invoke(ImageGenerationStageProgress("读取模型元数据和 tokenizer"))
    val tokenizerKey = "${vocabFile.absolutePath}:${vocabFile.lastModified()}:${mergesFile.lastModified()}"
    val tokenizer =
      tokenizerCache[tokenizerKey] ?:
        vocabFile.inputStream().use { vocab ->
          mergesFile.inputStream().use { merges -> BonsaiQwenTokenizer(vocab, merges) }
        }.also { tokenizerCache[tokenizerKey] = it }
    val pipeline = BonsaiPipeline(modelDir = modelDir, meta = meta)
''')
one(runtime,
'''        onSamplingProgress = { step, total -> progressListener?.invoke(step, total) },
''',
'''        onStageProgress = { update -> progressListener?.invoke(update) },
''')
one(runtime,
'''    onSamplingProgress: (step: Int, steps: Int) -> Unit,
  ): Result {
    val encoded = tokenizer.encodePrompt(prompt)
    val embeddings: FloatArray
    Graph(modelFile(textEncoderFile), threads).use { textEncoder ->
      embeddings =
        textEncoder.run(
          inputs = listOf(intBuffer(encoded.ids), intBuffer(encoded.mask)),
          outputFloatCount = BonsaiMath.SEQ * 7680,
        )
    }
    System.gc()
''',
'''    onStageProgress: (ImageGenerationStageProgress) -> Unit,
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
''')
one(runtime,
'''    Graph(modelFile(ditFile), threads).use { dit ->
      for (step in 0 until steps) {
''',
'''    val diffusionStart = System.currentTimeMillis()
    onStageProgress(ImageGenerationStageProgress("正在加载 Bonsai DiT 扩散模型", totalSteps = steps))
    Graph(modelFile(ditFile), threads).use { dit ->
      for (step in 0 until steps) {
''')
one(runtime,
'''        onSamplingProgress(step + 1, steps)
''',
'''        val diffusionMs = System.currentTimeMillis() - diffusionStart
        onStageProgress(
          ImageGenerationStageProgress(
            stageText = "DiT 扩散采样：第 ${step + 1} / $steps 步",
            timingText = "扩散累计 %.1f 秒".format(diffusionMs / 1000.0),
            step = step + 1,
            totalSteps = steps,
          )
        )
''')
one(runtime,
'''    val vaeLatents = BonsaiMath.unpatchify(latents, bnScale, bnShift)
    val rgb = ByteArray(512 * 512 * 3)
    Graph(modelFile(vaeFile), threads).use { vae ->
''',
'''    val diffusionMs = System.currentTimeMillis() - diffusionStart
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
''')
one(runtime,
'''    return Result(rgb = rgb)
''',
'''    val vaeMs = System.currentTimeMillis() - vaeStart
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
''')

view = "Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationViewModel.kt"
one(view,
'''  val generationProgressStep: Int = 0,
  val generationProgressSteps: Int = 0,
  val generatedImagePath: String? = null,
''',
'''  val generationProgressStep: Int = 0,
  val generationProgressSteps: Int = 0,
  val generationStageText: String = "等待开始",
  val generationTimingText: String = "",
  val generatedImagePath: String? = null,
''')
one(view,
'''        generationProgressStep = 0,
        generationProgressSteps = settings.steps,
      )
''',
'''        generationProgressStep = 0,
        generationProgressSteps = settings.steps,
        generationStageText = "准备模型和推理环境",
        generationTimingText = "",
      )
''')
one(view,
'''              progressListener = { step, steps ->
                _uiState.update { current ->
                  if (current.status == VisualCreationStatus.GENERATING_IMAGE) {
                    current.copy(
                      generationProgressStep = step,
                      generationProgressSteps = steps,
                      statusText =
                        if (step >= steps) {
                          buildDecodingStatusText(
                            modelName = model.displayName.ifBlank { model.name },
                            prompt = current.submittedPrompt,
                          )
                        } else {
                          buildSamplingStatusText(
                            modelName = model.displayName.ifBlank { model.name },
                            step = step,
                            steps = steps,
                            prompt = current.submittedPrompt,
                          )
                        },
                    )
                  } else {
                    current
                  }
                }
              },
''',
'''              progressListener = { progress ->
                _uiState.update { current ->
                  if (current.status == VisualCreationStatus.GENERATING_IMAGE) {
                    current.copy(
                      generationProgressStep = progress.step,
                      generationProgressSteps = if (progress.totalSteps > 0) progress.totalSteps else current.generationProgressSteps,
                      generationStageText = progress.stageText,
                      generationTimingText = progress.timingText,
                      statusText = progress.stageText,
                    )
                  } else current
                }
              },
''')

screen = "Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationScreen.kt"
one(screen,
'''          if (uiState.status == VisualCreationStatus.GENERATING_IMAGE) {
            Text(
              text =
                if (uiState.generationProgressStep > 0) {
                  "采样进度：第 ${uiState.generationProgressStep} / ${uiState.generationProgressSteps} 步"
                } else {
                  "进度：正在加载模型和初始化推理引擎"
                },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
''',
'''          if (uiState.status == VisualCreationStatus.GENERATING_IMAGE) {
            Text(
              text = "当前阶段：${uiState.generationStageText}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            if (uiState.generationTimingText.isNotBlank()) {
              Text(
                text = uiState.generationTimingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
''')

print("Bonsai telemetry patch applied")
