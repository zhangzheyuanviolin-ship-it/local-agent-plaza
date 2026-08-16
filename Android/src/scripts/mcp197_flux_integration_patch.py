from pathlib import Path


def one(path, old, new):
    p = Path(path)
    s = p.read_text()
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:140]!r}")
    p.write_text(s.replace(old, new, 1))

registry = "Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/ImageGenerationModelRegistry.kt"
one(
    registry,
    '''enum class ImageGenerationBackend {
  LOCAL_DREAM_QNN_MNN,
  STABLE_DIFFUSION_CPP,
}
''',
    '''enum class ImageGenerationBackend {
  LOCAL_DREAM_QNN_MNN,
  STABLE_DIFFUSION_CPP,
  BONSAI_LITERT_CPU_XNNPACK,
  FLUX_LITERT_GPU_COMPILED_MODEL,
}
''',
)

bonsai = "Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/BonsaiImageModel.kt"
one(
    bonsai,
    '''    // MCP195 gives Bonsai its own Kotlin/LiteRT execution route. This legacy enum value is retained
    // only so the shared settings data class remains binary-compatible with the MCP194 UI model.
    backend = ImageGenerationBackend.STABLE_DIFFUSION_CPP,
''',
    '''    backend = ImageGenerationBackend.BONSAI_LITERT_CPU_XNNPACK,
''',
)
one(
    bonsai,
    '''  when {
    isBonsaiImageModel(modelName) -> "LiteRT CPU / XNNPACK"
    modelInfo?.backend == ImageGenerationBackend.LOCAL_DREAM_QNN_MNN -> "Local Dream QNN / MNN"
    modelInfo?.backend == ImageGenerationBackend.STABLE_DIFFUSION_CPP -> "stable-diffusion.cpp"
    else -> "未知"
  }
''',
    '''  when {
    isBonsaiImageModel(modelName) || modelInfo?.backend == ImageGenerationBackend.BONSAI_LITERT_CPU_XNNPACK ->
      "LiteRT CPU / XNNPACK"
    isFluxKleinImageModel(modelName) || modelInfo?.backend == ImageGenerationBackend.FLUX_LITERT_GPU_COMPILED_MODEL ->
      "LiteRT GPU / CompiledModel FP32"
    modelInfo?.backend == ImageGenerationBackend.LOCAL_DREAM_QNN_MNN -> "Local Dream QNN / MNN"
    modelInfo?.backend == ImageGenerationBackend.STABLE_DIFFUSION_CPP -> "stable-diffusion.cpp"
    else -> "未知"
  }
''',
)
one(
    bonsai,
    '''fun findVisualCreationImageModelInfo(modelId: String): ImageGenerationModelInfo? =
  if (isBonsaiImageModel(modelId)) {
    BONSAI_IMAGE_MODEL_INFO
  } else {
    ImageGenerationModelRegistry.findModel(modelId)
  }
''',
    '''fun findVisualCreationImageModelInfo(modelId: String): ImageGenerationModelInfo? =
  when {
    isBonsaiImageModel(modelId) -> BONSAI_IMAGE_MODEL_INFO
    isFluxKleinImageModel(modelId) -> FLUX_KLEIN_IMAGE_MODEL_INFO
    else -> ImageGenerationModelRegistry.findModel(modelId)
  }
''',
)
one(
    bonsai,
    '''      bestForTaskIds = listOf(TASK_ID_LOCAL_VISUAL_CREATION),
''',
    '''      bestForTaskIds = listOf(TASK_ID_LOCAL_VISUAL_CREATION, TASK_ID_BONSAI_IMAGE),
''',
)

task = "Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationTask.kt"
one(
    task,
    '''const val TASK_ID_LOCAL_VISUAL_CREATION = "llm_local_visual_creation"
const val TASK_ID_BONSAI_IMAGE = "llm_bonsai_image"
''',
    '''const val TASK_ID_LOCAL_VISUAL_CREATION = "llm_local_visual_creation"
const val TASK_ID_BONSAI_IMAGE = "llm_bonsai_image"
const val TASK_ID_FLUX_KLEIN_IMAGE = "llm_flux_klein_image"
''',
)
one(
    task,
    '''  override fun clear() {
    delegate.removeAll { it.name != BONSAI_IMAGE_MODEL_ID }
  }
''',
    '''  override fun clear() {
    delegate.removeAll { it.name != BONSAI_IMAGE_MODEL_ID && it.name != FLUX_KLEIN_IMAGE_MODEL_ID }
  }
''',
)
one(
    task,
    '''        if (delegate[lastReturned].name != BONSAI_IMAGE_MODEL_ID) {
''',
    '''        if (
          delegate[lastReturned].name != BONSAI_IMAGE_MODEL_ID &&
            delegate[lastReturned].name != FLUX_KLEIN_IMAGE_MODEL_ID
        ) {
''',
)
one(
    task,
    '''private fun bonsaiVisualModels(): MutableList<Model> =
  BonsaiPreservingModelList(listOf(createBonsaiImageModel()) + createVisualCreationImageModels())

private fun bonsaiOnlyModels(): MutableList<Model> =
  BonsaiPreservingModelList(listOf(createBonsaiImageModel()))
''',
    '''private fun bonsaiVisualModels(): MutableList<Model> =
  BonsaiPreservingModelList(
    listOf(createBonsaiImageModel(), createFluxKleinImageModel()) + createVisualCreationImageModels()
  )

private fun bonsaiOnlyModels(): MutableList<Model> =
  BonsaiPreservingModelList(listOf(createBonsaiImageModel()))

private fun fluxOnlyModels(): MutableList<Model> =
  BonsaiPreservingModelList(listOf(createFluxKleinImageModel()))
''',
)
one(
    task,
    '''      description = "在设备本地生成图片，并把生成结果继续交给本地视觉语言模型进行描述、评审、分析和文本创作。包含 Bonsai Image 4B LiteRT。",
''',
    '''      description = "在设备本地生成图片，并把生成结果继续交给本地视觉语言模型进行描述、评审、分析和文本创作。包含 Bonsai Image 4B 与 FLUX.2 Klein 4B LiteRT。",
''',
)
module_marker = '''@Module
@InstallIn(SingletonComponent::class)
internal object VisualCreationTaskModule {
'''
flux_task = '''/** Dedicated FLUX.2 Klein entry with one directly downloadable model. */
class FluxKleinImageTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = TASK_ID_FLUX_KLEIN_IMAGE,
      label = "FLUX.2 Klein 图像生成",
      category = Category.LLM,
      icon = Icons.Outlined.Image,
      models = fluxOnlyModels(),
      description = "FLUX.2 Klein 4B LiteRT 本地图像生成。模型约 7.45 GB，固定 256 × 256、4 步，优先使用 LiteRT GPU CompiledModel FP32。",
      shortDescription = "下载 FLUX.2 Klein 4B，在手机 GPU 本地生成图片",
      docUrl = "https://huggingface.co/litert-community/FLUX.2-klein-4B-LiteRT",
      sourceCodeUrl = "",
      handleModelConfigChangesInTask = true,
      newFeature = true,
      useThemeColor = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    model.instance = VisualCreationWorkbenchInstance()
    onDone("")
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customTaskData = data as CustomTaskData
    VisualCreationScreen(
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      bottomPadding = customTaskData.bottomPadding,
      setAppBarControlsDisabled = customTaskData.setAppBarControlsDisabled,
    )
  }
}

'''
p = Path(task); s = p.read_text()
if module_marker not in s:
    raise SystemExit("VisualCreationTask module marker missing")
if "class FluxKleinImageTask" not in s:
    s = s.replace(module_marker, flux_task + module_marker, 1)
p.write_text(s)
one(
    task,
    '''  @Provides
  @IntoSet
  fun provideBonsaiImageTask(): CustomTask = BonsaiImageTask()
}''',
    '''  @Provides
  @IntoSet
  fun provideBonsaiImageTask(): CustomTask = BonsaiImageTask()

  @Provides
  @IntoSet
  fun provideFluxKleinImageTask(): CustomTask = FluxKleinImageTask()
}''',
)

view = "Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationViewModel.kt"
one(
    view,
    '''    val bonsaiModel = isBonsaiImageModel(model.name)
    val nativeFiles =
      if (modelInfo.backend == ImageGenerationBackend.STABLE_DIFFUSION_CPP && !bonsaiModel) {
''',
    '''    val bonsaiModel = isBonsaiImageModel(model.name)
    val fluxModel = isFluxKleinImageModel(model.name)
    val nativeFiles =
      if (modelInfo.backend == ImageGenerationBackend.STABLE_DIFFUSION_CPP) {
''',
)
one(
    view,
    '''          } else if (modelInfo.backend == ImageGenerationBackend.LOCAL_DREAM_QNN_MNN) {
''',
    '''          } else if (fluxModel) {
            FluxKleinImageGenerationClient.generateImage(
              context = context.applicationContext,
              modelPath = nativeFiles.modelPath,
              prompt = prompt,
              seed = seed,
              progressListener = { progress ->
                _uiState.update { current ->
                  if (current.status == VisualCreationStatus.GENERATING_IMAGE) {
                    current.copy(
                      generationProgressStep = progress.step,
                      generationProgressSteps = if (progress.totalSteps > 0) progress.totalSteps else 4,
                      generationStageText = progress.stageText,
                      generationTimingText = progress.timingText,
                      statusText = progress.stageText,
                    )
                  } else current
                }
              },
            )
          } else if (modelInfo.backend == ImageGenerationBackend.LOCAL_DREAM_QNN_MNN) {
''',
)
one(
    view,
    '''    "Bonsai Image 4B" -> base.copy(width = 512, height = 512, steps = 4, cfgScale = 1.0f)
''',
    '''    "Bonsai Image 4B" -> base.copy(width = 512, height = 512, steps = 4, cfgScale = 1.0f)
    "FLUX.2 Klein 4B" -> base.copy(width = 256, height = 256, steps = 4, cfgScale = 1.0f)
''',
)

screen = "Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationScreen.kt"
p = Path(screen); s = p.read_text()
if "import androidx.compose.ui.semantics.LiveRegionMode" not in s:
    target = "import androidx.compose.ui.semantics.contentDescription\n"
    if target not in s:
        raise SystemExit("screen semantics import marker missing")
    s = s.replace(
        target,
        "import androidx.compose.ui.semantics.LiveRegionMode\nimport androidx.compose.ui.semantics.contentDescription\nimport androidx.compose.ui.semantics.liveRegion\n",
        1,
    )
# Make the live stage an accessibility live region without changing its visual layout.
stage = '''            Text(
              text = "当前阶段：${uiState.generationStageText}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
'''
stage_new = '''            Text(
              text = "当前阶段：${uiState.generationStageText}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
'''
if stage in s:
    s = s.replace(stage, stage_new, 1)
else:
    raise SystemExit("generation stage block missing")
p.write_text(s)

print("FLUX integration patch applied")
