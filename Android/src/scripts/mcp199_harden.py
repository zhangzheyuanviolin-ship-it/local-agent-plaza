from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TASK = ROOT / "app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation/VisualCreationTask.kt"
VM = ROOT / "app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt"

task_text = TASK.read_text()
old_comment = '''/**
 * The model allowlist refresh in ModelManagerViewModel removes every non-imported custom-task model
 * before restoring the built-in local model sets. Bonsai is app-owned and must remain reachable
 * through that refresh. This list behaves normally for all user/model operations, while the
 * allowlist cleanup iterator deliberately keeps the Bonsai entry alive.
 */'''
new_comment = '''/**
 * App-owned image models use ordinary mutable lists. ModelManagerViewModel explicitly restores
 * them after bootstrap and allowlist refresh so dedicated tasks remain canonical and single-model.
 */'''
if old_comment not in task_text:
    raise SystemExit("stale preserving-list comment not found")
task_text = task_text.replace(old_comment, new_comment, 1)
TASK.write_text(task_text)

vm_text = VM.read_text()
old_loop = '''    for (model in createVisualCreationImageModels()) {'''
new_loop = '''    for (
      model in
        listOf(createBonsaiImageModel(), createFluxKleinImageModel()) +
          createVisualCreationImageModels()
    ) {'''
if old_loop not in vm_text:
    raise SystemExit("visual restore loop not found")
vm_text = vm_text.replace(old_loop, new_loop, 1)

old_best = '''      // Move the model that is best for this task to the front.
      val bestModel = task.models.find { it.bestForTaskIds.contains(task.id) }
      if (bestModel != null) {
        task.models.remove(bestModel)
        task.models.add(0, bestModel)
      }'''
new_best = '''      // Move the model that is best for this task to the front without equality-based remove().
      // Index-based removal guarantees we cannot accidentally duplicate the same model.
      val bestModelIndex = task.models.indexOfFirst { it.bestForTaskIds.contains(task.id) }
      if (bestModelIndex > 0) {
        val bestModel = task.models.removeAt(bestModelIndex)
        task.models.add(0, bestModel)
      }'''
if old_best not in vm_text:
    raise SystemExit("best-model reorder block not found")
vm_text = vm_text.replace(old_best, new_best, 1)
VM.write_text(vm_text)
print("MCP199 hardening applied")
