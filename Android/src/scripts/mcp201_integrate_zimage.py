from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VC = ROOT / "app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation"
TASK = VC / "VisualCreationTask.kt"
REG = VC / "ImageGenerationModelRegistry.kt"
BONSAI = VC / "BonsaiImageModel.kt"
VM = VC / "VisualCreationViewModel.kt"
MANAGER = ROOT / "app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if text.count(old) != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, got {text.count(old)}")
    return text.replace(old, new, 1)

# 1) Backend enum.
text = REG.read_text()
text = replace_once(
    text,
    "  FLUX_LITERT_GPU_COMPILED_MODEL,\n}",
    "  FLUX_LITERT_GPU_COMPILED_MODEL,\n  Z_IMAGE_LITERT_GPU_COMPILED_MODEL,\n}",
    "backend enum",
)
REG.write_text(text)

# 2) Shared model lookup/backend display.
text = BONSAI.read_text()
text = replace_once(
    text,
    '''    isFluxKleinImageModel(modelName) || modelInfo?.backend == ImageGenerationBackend.FLUX_LITERT_GPU_COMPILED_MODEL ->
      "LiteRT GPU / CompiledModel FP32"
    modelInfo?.backend == ImageGenerationBackend.LOCAL_DREAM_QNN_MNN ->''',
    '''    isFluxKleinImageModel(modelName) || modelInfo?.backend == ImageGenerationBackend.FLUX_LITERT_GPU_COMPILED_MODEL ->
      "LiteRT GPU / CompiledModel FP32"
    isZImageTurboModel(modelName) || modelInfo?.backend == ImageGenerationBackend.Z_IMAGE_LITERT_GPU_COMPILED_MODEL ->
      "LiteRT GPU / CompiledModel FP32"
    modelInfo?.backend == ImageGenerationBackend.LOCAL_DREAM_QNN_MNN ->''',
    "backend display",
)
text = replace_once(
    text,
    '''    isBonsaiImageModel(modelId) -> BONSAI_IMAGE_MODEL_INFO
    isFluxKleinImageModel(modelId) -> FLUX_KLEIN_IMAGE_MODEL_INFO
    else -> ImageGenerationModelRegistry.findModel(modelId)''',
    '''    isBonsaiImageModel(modelId) -> BONSAI_IMAGE_MODEL_INFO
    isFluxKleinImageModel(modelId) -> FLUX_KLEIN_IMAGE_MODEL_INFO
    isZImageTurboModel(modelId) -> Z_IMAGE_TURBO_MODEL_INFO
    else -> ImageGenerationModelRegistry.findModel(modelId)''',
    "model lookup",
)
BONSAI.write_text(text)

# 3) Home task and aggregate model list. Dedicated task must contain exactly one ordinary MutableList item.
text = TASK.read_text()
text = replace_once(
    text,
    'const val TASK_ID_FLUX_KLEIN_IMAGE = "llm_flux_klein_image"',
    'const val TASK_ID_FLUX_KLEIN_IMAGE = "llm_flux_klein_image"\nconst val TASK_ID_Z_IMAGE_TURBO = "llm_z_image_turbo"',
    "task id",
)
text = replace_once(
    text,
    '''private fun visualCreationModels(): MutableList<Model> =
  (listOf(createBonsaiImageModel(), createFluxKleinImageModel()) + createVisualCreationImageModels())
    .toMutableList()''',
    '''private fun visualCreationModels(): MutableList<Model> =
  (listOf(createBonsaiImageModel(), createFluxKleinImageModel(), createZImageTurboModel()) +
      createVisualCreationImageModels())
    .toMutableList()''',
    "aggregate models",
)
text = replace_once(
    text,
    'private fun fluxOnlyModels(): MutableList<Model> = mutableListOf(createFluxKleinImageModel())',
    'private fun fluxOnlyModels(): MutableList<Model> = mutableListOf(createFluxKleinImageModel())\n\nprivate fun zImageOnlyModels(): MutableList<Model> = mutableListOf(createZImageTurboModel())',
    "z single list",
)
text = replace_once(
    text,
    'description = "在设备本地生成图片，并把生成结果继续交给本地视觉语言模型进行描述、评审、分析和文本创作。包含 Bonsai Image 4B 与 FLUX.2 Klein 4B LiteRT。",',
    'description = "在设备本地生成图片，并把生成结果继续交给本地视觉语言模型进行描述、评审、分析和文本创作。包含 Bonsai Image 4B、FLUX.2 Klein 4B 与 Z-Image Turbo 6B LiteRT。",',
    "aggregate description",
)
module_anchor = '''@Module
@InstallIn(SingletonComponent::class)
internal object VisualCreationTaskModule {'''
z_task = '''/** Dedicated Alibaba Z-Image Turbo entry with one directly downloadable model. */
class ZImageTurboTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = TASK_ID_Z_IMAGE_TURBO,
      label = "Z-Image Turbo 图像生成",
      category = Category.LLM,
      icon = Icons.Outlined.Image,
      models = zImageOnlyModels(),
      description = "Alibaba Tongyi-MAI Z-Image-Turbo 6B LiteRT 本地图像生成。模型约 10.6 GB，固定 256 × 256、9 步，优先使用 LiteRT GPU CompiledModel FP32。",
      shortDescription = "下载 Z-Image Turbo 6B，在手机 GPU 本地生成图片",
      docUrl = "https://huggingface.co/litert-community/Z-Image-Turbo-LiteRT",
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
text = replace_once(text, module_anchor, z_task + module_anchor, "z task class")
text = replace_once(
    text,
    '''  @Provides
  @IntoSet
  fun provideFluxKleinImageTask(): CustomTask = FluxKleinImageTask()
}''',
    '''  @Provides
  @IntoSet
  fun provideFluxKleinImageTask(): CustomTask = FluxKleinImageTask()

  @Provides
  @IntoSet
  fun provideZImageTurboTask(): CustomTask = ZImageTurboTask()
}''',
    "z hilt provider",
)
TASK.write_text(text)

# 4) ModelManager explicit restoration. Preserve MCP200 duplicate guards and index-based sorting.
text = MANAGER.read_text()
text = replace_once(
    text,
    '''import com.google.ai.edge.gallery.customtasks.visualcreation.FLUX_KLEIN_IMAGE_MODEL_ID
import com.google.ai.edge.gallery.customtasks.visualcreation.TASK_ID_BONSAI_IMAGE''',
    '''import com.google.ai.edge.gallery.customtasks.visualcreation.FLUX_KLEIN_IMAGE_MODEL_ID
import com.google.ai.edge.gallery.customtasks.visualcreation.Z_IMAGE_TURBO_MODEL_ID
import com.google.ai.edge.gallery.customtasks.visualcreation.TASK_ID_BONSAI_IMAGE''',
    "manager model imports",
)
text = replace_once(
    text,
    '''import com.google.ai.edge.gallery.customtasks.visualcreation.TASK_ID_FLUX_KLEIN_IMAGE
import com.google.ai.edge.gallery.customtasks.visualcreation.TASK_ID_LOCAL_VISUAL_CREATION''',
    '''import com.google.ai.edge.gallery.customtasks.visualcreation.TASK_ID_FLUX_KLEIN_IMAGE
import com.google.ai.edge.gallery.customtasks.visualcreation.TASK_ID_Z_IMAGE_TURBO
import com.google.ai.edge.gallery.customtasks.visualcreation.TASK_ID_LOCAL_VISUAL_CREATION''',
    "manager task imports",
)
text = replace_once(
    text,
    '''import com.google.ai.edge.gallery.customtasks.visualcreation.createFluxKleinImageModel
import com.google.ai.edge.gallery.customtasks.visualcreation.createVisualCreationImageModels''',
    '''import com.google.ai.edge.gallery.customtasks.visualcreation.createFluxKleinImageModel
import com.google.ai.edge.gallery.customtasks.visualcreation.createZImageTurboModel
import com.google.ai.edge.gallery.customtasks.visualcreation.createVisualCreationImageModels''',
    "manager factory imports",
)
text = replace_once(
    text,
    '''        listOf(createBonsaiImageModel(), createFluxKleinImageModel()) +
          createVisualCreationImageModels()''',
    '''        listOf(createBonsaiImageModel(), createFluxKleinImageModel(), createZImageTurboModel()) +
          createVisualCreationImageModels()''',
    "manager aggregate restore",
)
flux_restore = '''  private fun restoreFluxKleinImageModel(tasks: Collection<Task>) {
    restoreDedicatedSingleModelTask(
      tasks = tasks,
      taskId = TASK_ID_FLUX_KLEIN_IMAGE,
      modelId = FLUX_KLEIN_IMAGE_MODEL_ID,
      createModel = ::createFluxKleinImageModel,
    )
  }
'''
z_restore = flux_restore + '''
  private fun restoreZImageTurboModel(tasks: Collection<Task>) {
    restoreDedicatedSingleModelTask(
      tasks = tasks,
      taskId = TASK_ID_Z_IMAGE_TURBO,
      modelId = Z_IMAGE_TURBO_MODEL_ID,
      createModel = ::createZImageTurboModel,
    )
  }
'''
text = replace_once(text, flux_restore, z_restore, "manager z restore helper")
text = replace_once(
    text,
    '''    restoreBonsaiImageModel(tasks)
    restoreFluxKleinImageModel(tasks)
  }''',
    '''    restoreBonsaiImageModel(tasks)
    restoreFluxKleinImageModel(tasks)
    restoreZImageTurboModel(tasks)
  }''',
    "manager z restore call",
)
MANAGER.write_text(text)

# 5) Generation route and default settings.
text = VM.read_text()
text = replace_once(
    text,
    '''    val bonsaiModel = isBonsaiImageModel(model.name)
    val fluxModel = isFluxKleinImageModel(model.name)''',
    '''    val bonsaiModel = isBonsaiImageModel(model.name)
    val fluxModel = isFluxKleinImageModel(model.name)
    val zImageModel = isZImageTurboModel(model.name)''',
    "viewmodel route flags",
)
flux_branch = '''          } else if (fluxModel) {
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
'''
z_branch = flux_branch + '''          } else if (zImageModel) {
            ZImageTurboGenerationClient.generateImage(
              context = context.applicationContext,
              modelPath = nativeFiles.modelPath,
              prompt = prompt,
              seed = seed,
              progressListener = { progress ->
                _uiState.update { current ->
                  if (current.status == VisualCreationStatus.GENERATING_IMAGE) {
                    current.copy(
                      generationProgressStep = progress.step,
                      generationProgressSteps = if (progress.totalSteps > 0) progress.totalSteps else 9,
                      generationStageText = progress.stageText,
                      generationTimingText = progress.timingText,
                      statusText = progress.stageText,
                    )
                  } else current
                }
              },
            )
'''
text = replace_once(text, flux_branch, z_branch, "viewmodel z runtime branch")
text = replace_once(
    text,
    '''    "FLUX.2 Klein 4B" -> base.copy(width = 256, height = 256, steps = 4, cfgScale = 1.0f)
    "Z-Image" -> base.copy(steps = 8, cfgScale = 1.0f)''',
    '''    "FLUX.2 Klein 4B" -> base.copy(width = 256, height = 256, steps = 4, cfgScale = 1.0f)
    "Alibaba Tongyi-MAI Z-Image-Turbo 6B" ->
      base.copy(width = 256, height = 256, steps = 9, cfgScale = 1.0f)
    "Z-Image" -> base.copy(width = 256, height = 256, steps = 9, cfgScale = 1.0f)''',
    "viewmodel z defaults",
)
VM.write_text(text)

print("MCP201 Z-Image integration patch applied")
