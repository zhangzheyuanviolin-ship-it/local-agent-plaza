#!/usr/bin/env python3
"""Apply the MCP250 exact-model layer on top of the already-materialized MCP224 golden runtime.

The original MCP250 patch was authored against the pre-MCP224 source shape. This adapter temporarily
restores only the two source anchors that MCP224 rewrites, runs the original exact-model patch, then
restores MCP224's unlimited/user-stop generic loop and lifecycle diagnostics around the MCP250 target
branches. LocoOperator, Gemma-4-12B and every other non-target retain the MCP224 generic behavior.
"""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
AGENT = ROOT / "Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat"
UI = ROOT / "Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat"
ORIG = Path(__file__).with_name("patch_mcp250_target_agent_families.py")
SCREEN = AGENT / "AgentChatScreen.kt"
LLM = UI / "LlmChatModelHelper.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"MCP250 v2 {label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


# 1) Temporarily restore the two pre-MCP224 source shapes expected by the original MCP250 patch.
screen = SCREEN.read_text(encoding="utf-8")
mcp224_tool_block = '''        ModelLifecycleDiagnostics.record(
          context = context,
          modelName = model.name,
          stage = "agent.compat.tool_call",
          message = "Compatibility tool call accepted without a fixed step limit",
          detail = "tool=${parsedToolCall.toolName}",
        )
'''
legacy_tool_block = '''        val currentSteps = compatToolStepsByModel[model.name] ?: 0
        if (currentSteps >= MAX_COMPAT_TOOL_STEPS) {
          viewModel.removeLastMessage(model = model)
          viewModel.addMessage(
            model = model,
            message =
              ChatMessageInfo(
                content = "兼容工具调用已停止：连续工具调用超过 $MAX_COMPAT_TOOL_STEPS 步。请调整提示词或改用原生模式。"
              ),
          )
          compatToolStepsByModel.remove(model.name)
          updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
          return@handleGenerationDone
        }
        compatToolStepsByModel[model.name] = currentSteps + 1
'''
screen = replace_once(screen, mcp224_tool_block, legacy_tool_block, "screen MCP224 shim")
SCREEN.write_text(screen, encoding="utf-8")

llm = LLM.read_text(encoding="utf-8")
mcp224_engine_block = '''      ModelLifecycleDiagnostics.recordModel(
        context = context,
        model = model,
        stage = "litert.engine_initialize.start",
        message = "Engine.initialize() started",
      )
      val engine = Engine(engineConfig)
      engine.initialize()
      val engineInitMs = elapsedMsSince(engineInitStartNanos)
      ModelLifecycleDiagnostics.recordModel(
        context = context,
        model = model,
        stage = "litert.engine_initialize.success",
        message = "Engine.initialize() succeeded",
        detail = "elapsed_ms=$engineInitMs",
      )
'''
legacy_engine_block = '''      val engine = Engine(engineConfig)
      engine.initialize()
      val engineInitMs = elapsedMsSince(engineInitStartNanos)
'''
llm = replace_once(llm, mcp224_engine_block, legacy_engine_block, "helper MCP224 engine shim")
LLM.write_text(llm, encoding="utf-8")

# 2) Run the original exact-model patch. It creates Mcp250TargetAgentCompat.kt and applies all
# target-only parser, budget, thinking, continuation, Laguna and Gemma26 changes.
subprocess.run([sys.executable, str(ORIG)], cwd=ROOT, check=True)

# 3) Restore MCP224's generic unlimited/user-stop loop. MCP250 loop convergence lives entirely
# inside the exact-target branch; protected/non-target models flow straight to the original MCP224
# diagnostic block and then execute the already-verified tool call path.
screen = SCREEN.read_text(encoding="utf-8")n
start_marker = '        val currentSteps = compatToolStepsByModel[model.name] ?: 0\n'
end_marker = '''        if (Mcp250TargetAgentCompat.isTargetAgentModel(model.name)) {
          mcp250LastCompatToolByModel[model.name] = parsedToolCall.toolName
        }
        viewModel.removeLastMessage(model = model)
'''
start = screen.find(start_marker)
if start < 0:
    raise SystemExit("MCP250 v2 post-screen start marker missing")
end_start = screen.find(end_marker, start)
if end_start < 0:
    raise SystemExit("MCP250 v2 post-screen end marker missing")
end = end_start + len(end_marker)

target_only_loop = '''        if (Mcp250TargetAgentCompat.isTargetAgentModel(model.name)) {
          val currentSteps = compatToolStepsByModel[model.name] ?: 0
          val mcp250ForceFinalization =
            Mcp250TargetAgentCompat.shouldForceFinalization(
              modelName = model.name,
              completedToolSteps = currentSteps,
              requestedToolName = parsedToolCall.toolName,
              previousToolName = mcp250LastCompatToolByModel[model.name],
            )
          if (mcp250ForceFinalization) {
            val guardTrips = mcp250LoopGuardTripsByModel[model.name] ?: 0
            viewModel.removeLastMessage(model = model)
            if (guardTrips >= 1) {
              viewModel.addMessage(
                model = model,
                message = ChatMessageInfo(content = "MCP250 专项循环保护已停止再次工具调用；该模型未能在强制收敛后生成最终回复。"),
              )
              compatToolStepsByModel.remove(model.name)
              mcp250LastCompatToolByModel.remove(model.name)
              mcp250LoopGuardTripsByModel.remove(model.name)
              updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
              return@handleGenerationDone
            }
            mcp250LoopGuardTripsByModel[model.name] = guardTrips + 1
            viewModel.addMessage(
              model = model,
              message = ChatMessageInfo(content = "MCP250 专项循环保护：沿用上一轮成功工具结果并进入最终回答阶段。"),
            )
            continueCompatConversation?.invoke(
              model,
              Mcp250TargetAgentCompat.buildLoopGuardToolResult(
                originalUserRequest = originalUserRequest,
                requestedToolName = parsedToolCall.toolName,
              ),
            )
            return@handleGenerationDone
          }
          compatToolStepsByModel[model.name] = currentSteps + 1
          mcp250LastCompatToolByModel[model.name] = parsedToolCall.toolName
        }
        ModelLifecycleDiagnostics.record(
          context = context,
          modelName = model.name,
          stage = "agent.compat.tool_call",
          message = "Compatibility tool call accepted without a fixed step limit",
          detail = "tool=${parsedToolCall.toolName}",
        )
        viewModel.removeLastMessage(model = model)
'''
screen = screen[:start] + target_only_loop + screen[end:]
SCREEN.write_text(screen, encoding="utf-8")

# 4) Restore MCP224 engine lifecycle diagnostics around the MCP250 engine construction. Laguna's
# retry remains exact-name-only; protected/non-target models still use Engine(engineConfig).
llm = LLM.read_text(encoding="utf-8")
engine_start = '''      val engine =
        if (Mcp250TargetAgentCompat.isLaguna(model.name)) {
'''
engine_start_with_diag = '''      ModelLifecycleDiagnostics.recordModel(
        context = context,
        model = model,
        stage = "litert.engine_initialize.start",
        message = "Engine.initialize() started",
      )
      val engine =
        if (Mcp250TargetAgentCompat.isLaguna(model.name)) {
'''
llm = replace_once(llm, engine_start, engine_start_with_diag, "restore engine start diagnostic")
engine_elapsed = '      val engineInitMs = elapsedMsSince(engineInitStartNanos)\n'
engine_elapsed_with_diag = engine_elapsed + '''      ModelLifecycleDiagnostics.recordModel(
        context = context,
        model = model,
        stage = "litert.engine_initialize.success",
        message = "Engine.initialize() succeeded",
        detail = "elapsed_ms=$engineInitMs",
      )
'''
llm = replace_once(llm, engine_elapsed, engine_elapsed_with_diag, "restore engine success diagnostic")
LLM.write_text(llm, encoding="utf-8")

# 5) Fail closed on both golden and MCP250 invariants.
screen = SCREEN.read_text(encoding="utf-8")
llm = LLM.read_text(encoding="utf-8")
helper = (AGENT / "Mcp250TargetAgentCompat.kt").read_text(encoding="utf-8")
if "MAX_COMPAT_TOOL_STEPS" in screen:
    raise SystemExit("MCP250 v2 generic fixed step cap leaked back into AgentChatScreen")
if screen.count('message = "Compatibility tool call accepted without a fixed step limit"') != 1:
    raise SystemExit("MCP250 v2 MCP224 unlimited-loop diagnostic missing or duplicated")
if 'if (Mcp250TargetAgentCompat.isTargetAgentModel(model.name)) {' not in screen:
    raise SystemExit("MCP250 v2 target-only loop branch missing")
if 'parseCompatToolCall(lastAgentText.content)' not in screen:
    raise SystemExit("MCP250 v2 protected canonical parser path missing")
for marker in (
    'stage = "litert.engine_initialize.start"',
    'stage = "litert.engine_initialize.success"',
    'Every protected/non-target model retains the original Engine construction path.',
    'MCP250 Laguna GPU initialization failed; retrying exact model on CPU.',
):
    if marker not in llm:
        raise SystemExit(f"MCP250 v2 helper invariant missing: {marker}")
for protected in ('LocoOperator-4B LiteRTLM', 'Gemma-4-12B-it (experimental)'):
    if protected not in helper:
        raise SystemExit(f"MCP250 v2 protected exact-name guard missing: {protected}")

print("MCP250_TARGET_AGENT_PATCH_POST_MCP224_PASS")
