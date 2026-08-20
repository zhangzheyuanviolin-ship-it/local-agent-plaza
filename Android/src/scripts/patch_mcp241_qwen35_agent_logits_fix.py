#!/usr/bin/env python3
"""MCP241: restore Qwen3.5 Agent generation by removing LiteRT-LM 0.15's incompatible
runtime RepetitionPenaltyProcessor from the COMPAT path.

MCP240 device diagnostics fail before the first callback with:
  Logits dimensions must be [batch_size, 1, vocab_size].

That string comes directly from LiteRT-LM's RepetitionPenaltyProcessor, which only
accepts [B,1,V] logits. MCP240 injected RepetitionPenaltyConfig only for COMPAT
passes, explaining why the same repaired model works in normal AI Chat while Agent
fails before emitting any token.

Keep all model-side MCP240 repairs (stable ChatML, stop tokens 248044 + 248046,
ExecutorMetadata), CPU routing, official top-k/top-p/temperature profile, full
4096 output budget, and the clean Qwen3.5 Engine reset. Remove only the confirmed
incompatible logits processor. No truncation/watchdog/no-repeat mechanism is added.
"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
helper_path = ROOT / "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt"
text = helper_path.read_text(encoding="utf-8")


def fail(msg: str) -> None:
    print(f"MCP241 patch failure: {msg}", file=sys.stderr)
    raise SystemExit(1)

import_line = "import com.google.ai.edge.litertlm.RepetitionPenaltyConfig\n"
if text.count(import_line) != 1:
    fail(f"expected one RepetitionPenaltyConfig import, found {text.count(import_line)}")
text = text.replace(import_line, "", 1)

block = '''      repetitionPenaltyConfig =
        if (model.name == "Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP240" && isCompatPass) {
          RepetitionPenaltyConfig(
            repetitionPenalty = 1.0f,
            presencePenalty = 2.0f,
          )
        } else {
          null
        },
'''
if text.count(block) != 1:
    fail(f"expected one MCP240 repetition penalty block, found {text.count(block)}")
text = text.replace(block, "", 1)

# Leave an explicit source marker next to the existing MCP240 clean-engine reset so
# future work does not accidentally reintroduce the incompatible processor.
marker = "// MCP240_QWEN35_RECURRENT_STATE_RESET:"
replacement = (
    "// MCP241_QWEN35_AGENT_LOGITS_FIX: LiteRT-LM 0.15 RepetitionPenaltyProcessor is not "
    "compatible with this model's COMPAT prefill logits shape; model-side ChatML/EOS repairs remain.\n"
    + marker
)
if text.count(marker) != 1:
    fail(f"expected one recurrent-state marker, found {text.count(marker)}")
text = text.replace(marker, replacement, 1)

helper_path.write_text(text, encoding="utf-8")

final = helper_path.read_text(encoding="utf-8")
for forbidden in (
    "RepetitionPenaltyConfig",
    "presencePenalty = 2.0f",
    "repetitionPenalty = 1.0f",
):
    if forbidden in final:
        fail(f"confirmed incompatible repetition processor still present: {forbidden}")
for required in (
    "MCP241_QWEN35_AGENT_LOGITS_FIX",
    "MCP240_QWEN35_RECURRENT_STATE_RESET",
    "Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP240",
):
    if required not in final:
        fail(f"required retained MCP240 behavior missing: {required}")
if "NoRepeatNgramConfig" in final:
    fail("MCP241 must not add no-repeat ngram blocking")
if "maxOutputToken =" in final:
    fail("MCP241 must not add per-call output truncation")

print("MCP241 Agent logits fix applied: incompatible RepetitionPenaltyProcessor removed")
