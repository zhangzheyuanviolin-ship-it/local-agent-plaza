from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
TASK = ROOT / "app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationTask.kt"
VM = ROOT / "app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt"

# 1) Remove the custom preserving MutableList. It blocks remove(bestModel), which caused
# processTasks() to insert the same model twice and Compose LazyColumn duplicate-key crashes.
task_text = TASK.read_text()
pattern = re.compile(
    r"private class BonsaiPreservingModelList\(models: List<Model>\) : AbstractMutableList<Model>\(\) \{.*?"
    r"private fun fluxOnlyModels\(\): MutableList<Model> =\n\s*BonsaiPreservingModelList\(listOf\(createFluxKleinImageModel\(\)\)\)",
    re.S,
)
replacement = '''private fun visualCreationModels(): MutableList<Model> =
  (listOf(createBonsaiImageModel(), createFluxKleinImageModel()) + createVisualCreationImageModels())
    .toMutableList()

private fun bonsaiOnlyModels(): MutableList<Model> = mutableListOf(createBonsaiImageModel())

private fun fluxOnlyModels(): MutableList<Model> = mutableListOf(createFluxKleinImageModel())'''
new_task_text, count = pattern.subn(replacement, task_text)
if count != 1:
    raise SystemExit(f"Expected one preserving-list block, found {count}")
new_task_text = new_task_text.replace("models = bonsaiVisualModels(),", "models = visualCreationModels(),")
TASK.write_text(new_task_text)

# 2) Make ModelManager explicitly restore the app-owned image models by task ID.
vm_text = VM.read_text()
old_imports = '''import com.google.ai.edge.gallery.customtasks.visualcreation.TASK_ID_LOCAL_VISUAL_CREATION
import com.google.ai.edge.gallery.customtasks.visualcreation.createVisualCreationImageModels'''
new_imports = '''import com.google.ai.edge.gallery.customtasks.visualcreation.BONSAI_IMAGE_MODEL_ID
import com.google.ai.edge.gallery.customtasks.visualcreation.FLUX_KLEIN_IMAGE_MODEL_ID
import com.google.ai.edge.gallery.customtasks.visualcreation.TASK_ID_BONSAI_IMAGE
import com.google.ai.edge.gallery.customtasks.visualcreation.TASK_ID_FLUX_KLEIN_IMAGE
import com.google.ai.edge.gallery.customtasks.visualcreation.TASK_ID_LOCAL_VISUAL_CREATION
import com.google.ai.edge.gallery.customtasks.visualcreation.createBonsaiImageModel
import com.google.ai.edge.gallery.customtasks.visualcreation.createFluxKleinImageModel
import com.google.ai.edge.gallery.customtasks.visualcreation.createVisualCreationImageModels'''
if old_imports not in vm_text:
    raise SystemExit("Visual creation import anchor not found")
vm_text = vm_text.replace(old_imports, new_imports, 1)

anchor = '''  private fun restoreAiKeyboardSettingsModel(tasks: Collection<Task>) {'''
helpers = '''  private fun restoreDedicatedSingleModelTask(
    tasks: Collection<Task>,
    taskId: String,
    modelId: String,
    createModel: () -> Model,
  ) {
    val task = tasks.firstOrNull { it.id == taskId } ?: return
    val alreadyCanonical = task.models.size == 1 && task.models.first().name == modelId
    if (alreadyCanonical) return

    task.models.clear()
    task.models.add(createModel())
    task.updateTrigger.value = System.currentTimeMillis()
  }

  private fun restoreBonsaiImageModel(tasks: Collection<Task>) {
    restoreDedicatedSingleModelTask(
      tasks = tasks,
      taskId = TASK_ID_BONSAI_IMAGE,
      modelId = BONSAI_IMAGE_MODEL_ID,
      createModel = ::createBonsaiImageModel,
    )
  }

  private fun restoreFluxKleinImageModel(tasks: Collection<Task>) {
    restoreDedicatedSingleModelTask(
      tasks = tasks,
      taskId = TASK_ID_FLUX_KLEIN_IMAGE,
      modelId = FLUX_KLEIN_IMAGE_MODEL_ID,
      createModel = ::createFluxKleinImageModel,
    )
  }

  private fun restoreLocalImageGenerationModels(tasks: Collection<Task>) {
    restoreLocalVisualCreationModels(tasks)
    restoreBonsaiImageModel(tasks)
    restoreFluxKleinImageModel(tasks)
  }

'''
if anchor not in vm_text:
    raise SystemExit("restoreAiKeyboardSettingsModel anchor not found")
vm_text = vm_text.replace(anchor, helpers + anchor, 1)

# Replace startup/allowlist restoration call sites only.
vm_text = vm_text.replace("    restoreLocalVisualCreationModels(curTasks)\n", "    restoreLocalImageGenerationModels(curTasks)\n")
vm_text = vm_text.replace("    restoreLocalVisualCreationModels(activeTasks)\n", "    restoreLocalImageGenerationModels(activeTasks)\n")

# Add a final generic duplicate guard before best-model reordering. This protects all tasks against
# accidental duplicate names, including stale objects in an already-running process.
needle = '''    for (task in curTasks) {
      for (model in task.models) {'''
replacement_loop = '''    for (task in curTasks) {
      val uniqueModels = task.models.distinctBy { it.name }
      if (uniqueModels.size != task.models.size) {
        task.models.clear()
        task.models.addAll(uniqueModels)
        task.updateTrigger.value = System.currentTimeMillis()
      }
      for (model in task.models) {'''
if needle not in vm_text:
    raise SystemExit("processTasks loop anchor not found")
vm_text = vm_text.replace(needle, replacement_loop, 1)

VM.write_text(vm_text)

print("MCP199 hotfix applied")
