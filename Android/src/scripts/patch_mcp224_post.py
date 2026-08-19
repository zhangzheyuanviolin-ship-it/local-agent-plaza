#!/usr/bin/env python3
"""MCP224 post-patch cleanup for Agent diagnostics/tool-mode experiments."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
REL = "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatScreen.kt"
path = ROOT / REL
text = path.read_text(encoding="utf-8")

# The MCP223 screen kept a UI-side eight-step counter. MCP224 intentionally removes every total
# tool-step ceiling; the runtime's repeated-identical-call guard and the user's Stop button remain.
start_marker = "        val currentSteps = compatToolStepsByModel[model.name] ?: 0\n"
end_marker = "        viewModel.removeLastMessage(model = model)\n"
start = text.find(start_marker)
if start >= 0:
    end = text.find(end_marker, start)
    if end < 0:
        print("MCP224 could not locate end of COMPAT step-limit block", file=sys.stderr)
        raise SystemExit(1)
    text = text[:start] + text[end:]

text = text.replace("  val compatToolStepsByModel = remember { mutableStateMapOf<String, Int>() }\n", "")
lines = []
for line in text.splitlines():
    if "compatToolStepsByModel.remove(model.name)" in line:
        continue
    if "compatToolStepsByModel[model.name] =" in line:
        continue
    lines.append(line)
text = "\n".join(lines) + "\n"

if "compatToolStepsByModel" in text or "MAX_COMPAT_TOOL_STEPS" in text:
    print("MCP224 stale COMPAT step-limit reference remains", file=sys.stderr)
    raise SystemExit(1)

path.write_text(text, encoding="utf-8")
print(f"MCP224 finalized {REL}: total COMPAT tool-step limit removed")
