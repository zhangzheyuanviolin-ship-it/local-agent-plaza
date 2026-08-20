#!/usr/bin/env python3
"""MCP246: reset Qwen3.5 recurrent Engine state only at COMPAT tool continuations.

Apply ONLY after MCP238 + MCP239 + MCP245.

Physical MCP245 evidence shows the initial pass and one tool call are coherent, but the post-tool
continuation emits thousands of callbacks for minutes without reaching onDone. LiteRT-LM 0.15
running-state/gated-delta models can carry recurrent state across Conversation recreation on one
Engine. MCP246 therefore changes one lifecycle boundary only: before an MCP245 Qwen3.5 COMPAT
TOOL_CONTINUATION, close the current Conversation and Engine, recreate the Engine from the exact
same EngineConfig, initialize it, and create the fresh Conversation on that zero-state Engine.

Top-level COMPAT passes and ordinary AI chat keep the existing MCP245 behavior. No model bytes,
sampler, context, max output, JNI/AAR, repetition processor, watchdog, truncation, or no-repeat
mechanism are changed.
"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt"
MODEL_NAME = "Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP245"
MARKER = "MCP246_QWEN35_CONTINUATION_ENGINE_RESET"


def fail(msg: str) -> None:
    print(f"MCP246 patch failure: {msg}", file=sys.stderr)
    raise SystemExit(1)


text = HELPER.read_text(encoding="utf-8")
if MARKER in text:
    print("MCP246 already applied")
    raise SystemExit(0)
if MODEL_NAME not in text:
    fail("MCP245 model identity missing; apply MCP245 first")
for forbidden in (
    "RepetitionPenaltyConfig",
    "NoRepeatNgramConfig",
    "MCP242_LOCAL_LITERTLM_AAR",
    "MCP240_QWEN35_RECURRENT_STATE_RESET",
):
    if forbidden in text:
        fail(f"unsafe/non-isolated precondition present: {forbidden}")

# Keep the exact EngineConfig used for the loaded model so a continuation can rebuild only the
# official LiteRT-LM Engine lifecycle without reconstructing configuration by hand.
old_instance = "data class LlmModelInstance(val engine: Engine, var conversation: Conversation)"
new_instance = (
    "data class LlmModelInstance(\n"
    "  var engine: Engine,\n"
    "  var conversation: Conversation,\n"
    "  val engineConfig: EngineConfig,\n"
    ")"
)
if text.count(old_instance) != 1:
    fail(f"LlmModelInstance anchor count={text.count(old_instance)}")
text = text.replace(old_instance, new_instance, 1)

old_assign = "model.instance = LlmModelInstance(engine = engine, conversation = conversation)"
new_assign = (
    "model.instance =\n"
    "        LlmModelInstance(engine = engine, conversation = conversation, engineConfig = engineConfig)"
)
if text.count(old_assign) != 1:
    fail(f"initial instance assignment anchor count={text.count(old_assign)}")
text = text.replace(old_assign, new_assign, 1)

# A one-shot process-local flag carries the already-known COMPAT fresh reason into the existing
# resetConversation interface. ConcurrentHashMap.newKeySet keeps the operation safe if different
# models are active concurrently.
import_anchor = "import java.io.ByteArrayOutputStream\n"
if text.count(import_anchor) != 1:
    fail("ByteArrayOutputStream import anchor missing")
text = text.replace(
    import_anchor,
    import_anchor + "import java.util.concurrent.ConcurrentHashMap\n",
    1,
)

object_anchor = "object LlmChatModelHelper : LlmModelHelper {\n  // Indexed by model name."
object_insert = (
    "object LlmChatModelHelper : LlmModelHelper {\n"
    "  // MCP246_QWEN35_CONTINUATION_ENGINE_RESET: one-shot flag set only for the exact\n"
    "  // MCP245 Qwen3.5 COMPAT tool-continuation boundary.\n"
    "  private val hardResetEngineOnNextConversation = ConcurrentHashMap.newKeySet<String>()\n\n"
    "  // Indexed by model name."
)
if text.count(object_anchor) != 1:
    fail("object anchor missing")
text = text.replace(object_anchor, object_insert, 1)

# Consume the one-shot flag inside the normal reset path. Ordinary resets keep the live Engine.
old_reset_engine = """      val instance = model.instance as LlmModelInstance? ?: return
      instance.conversation.close()

      val engine = instance.engine
"""
new_reset_engine = """      val instance = model.instance as LlmModelInstance? ?: return
      instance.conversation.close()

      val hardResetEngine = hardResetEngineOnNextConversation.remove(model.name)
      val engine =
        if (hardResetEngine) {
          // MCP246_QWEN35_CONTINUATION_ENGINE_RESET: Qwen3.5 uses recurrent gated-delta state.
          // LiteRT-LM 0.15 can retain that running state across createConversation() on one Engine.
          // Rebuild through the public official Engine API so this continuation starts at zero state.
          Log.i(TAG, "MCP246 rebuilding Engine for Qwen3.5 COMPAT tool continuation: ${model.name}")
          instance.engine.close()
          Engine(instance.engineConfig).also { rebuilt -> rebuilt.initialize() }
        } else {
          instance.engine
        }
"""
if text.count(old_reset_engine) != 1:
    fail(f"reset engine anchor count={text.count(old_reset_engine)}")
text = text.replace(old_reset_engine, new_reset_engine, 1)

old_swap = """      ExperimentalFlags.enableConversationConstrainedDecoding = false
      instance.conversation = newConversation
"""
new_swap = """      ExperimentalFlags.enableConversationConstrainedDecoding = false
      instance.engine = engine
      instance.conversation = newConversation
"""
if text.count(old_swap) != 1:
    fail(f"conversation swap anchor count={text.count(old_swap)}")
text = text.replace(old_swap, new_swap, 1)

# prepareCompatAgentInput is the only place that knows this fresh reset is specifically the result
# of a tool return. Arm the one-shot reset ONLY for the exact frozen Qwen3.5 model and continuation.
old_before_reset = """    val resetStartedNanos = SystemClock.elapsedRealtimeNanos()
    resetConversation(
"""
new_before_reset = f"""    val resetStartedNanos = SystemClock.elapsedRealtimeNanos()
    val hardResetQwen35Continuation =
      model.name == \"{MODEL_NAME}\" &&
        prepared.freshConversationReason == COMPAT_FRESH_REASON_TOOL_CONTINUATION
    if (hardResetQwen35Continuation) {{
      hardResetEngineOnNextConversation.add(model.name)
    }}
    resetConversation(
"""
if text.count(old_before_reset) != 1:
    fail(f"prepare/reset anchor count={text.count(old_before_reset)}")
text = text.replace(old_before_reset, new_before_reset, 1)

HELPER.write_text(text, encoding="utf-8")

final = HELPER.read_text(encoding="utf-8")
for required in (
    MARKER,
    MODEL_NAME,
    "val engineConfig: EngineConfig",
    "hardResetEngineOnNextConversation",
    "prepared.freshConversationReason == COMPAT_FRESH_REASON_TOOL_CONTINUATION",
    "Engine(instance.engineConfig).also",
    "instance.engine = engine",
):
    if required not in final:
        fail(f"postcondition missing: {required}")
for forbidden in (
    "RepetitionPenaltyConfig",
    "NoRepeatNgramConfig",
    "maxOutputToken =",
    "presencePenalty = 2.0f",
    "repetitionPenalty = 1.0f",
):
    if forbidden in final:
        fail(f"forbidden output-control/runtime feature introduced: {forbidden}")

print("MCP246 isolated continuation Engine reset patch complete")
