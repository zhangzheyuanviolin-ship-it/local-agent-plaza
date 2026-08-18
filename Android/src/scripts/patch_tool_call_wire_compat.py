#!/usr/bin/env python3
"""Idempotently wire the COMPAT tool-call normalizer into LlmChatModelHelper.

The runtime source is kept close to the MCP210 rollback baseline. This tiny build-time patch changes
only the message boundary: model-family tool-call text is normalized before the existing UI parser
and AgentCompatRuntimeCoordinator see it. AgentTools remains the execution/permission authority.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt"
text = TARGET.read_text(encoding="utf-8")

SENTINEL = "CompatToolCallStreamGate(enabled = isCompatPass)"
if SENTINEL in text:
    raise SystemExit(0)

replacements = [
    (
        "import com.google.ai.edge.gallery.customtasks.agentchat.AgentCompatRuntimeCoordinator\n",
        "import com.google.ai.edge.gallery.customtasks.agentchat.AgentCompatRuntimeCoordinator\n"
        "import com.google.ai.edge.gallery.customtasks.agentchat.CompatToolCallStreamGate\n",
    ),
    (
        "    var generatedChars = 0\n    val generatedText = StringBuilder()\n\n    conversation.sendMessageAsync(\n",
        "    var generatedChars = 0\n"
        "    val generatedText = StringBuilder()\n"
        "    val compatToolCallGate = CompatToolCallStreamGate(enabled = isCompatPass)\n\n"
        "    conversation.sendMessageAsync(\n",
    ),
    (
        "            resultListener(text, false, thought.takeIf { it.isNotBlank() })\n",
        "            val visibleText = compatToolCallGate.accept(text)\n"
        "            if (visibleText.isNotEmpty() || thought.isNotBlank()) {\n"
        "              resultListener(visibleText, false, thought.takeIf { it.isNotBlank() })\n"
        "            }\n",
    ),
    (
        "          override fun onDone() {\n            val decision =\n              AgentCompatRuntimeCoordinator.onGenerationCompleted(\n                modelName = model.name,\n                generatedText = generatedText.toString(),\n              )\n",
        "          override fun onDone() {\n"
        "            val completedGeneration = compatToolCallGate.finish(generatedText.toString())\n"
        "            if (completedGeneration.uiTail.isNotBlank()) {\n"
        "              resultListener(completedGeneration.uiTail, false, null)\n"
        "            }\n"
        "            val decision =\n"
        "              AgentCompatRuntimeCoordinator.onGenerationCompleted(\n"
        "                modelName = model.name,\n"
        "                generatedText = completedGeneration.runtimeText,\n"
        "              )\n",
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_tool_call_wire_compat: expected exactly one anchor, found {count}: {old[:80]!r}")
    text = text.replace(old, new, 1)

TARGET.write_text(text, encoding="utf-8")
print(f"Patched {TARGET.relative_to(ROOT)} with COMPAT tool-call wire normalization")
