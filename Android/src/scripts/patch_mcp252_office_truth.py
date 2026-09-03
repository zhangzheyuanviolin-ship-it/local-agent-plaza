#!/usr/bin/env python3
"""MCP252 evidence-driven Office truth hardening.

This patch MUST run after patch_mcp251_office_skills.py. It changes only the additive Office
boundary: tolerant request normalization, safe operation inference, semantic verification, and
richer audit evidence. It does not alter model loading, local music/image runtimes, JNI, or the
AUTO/NATIVE/COMPAT state machine.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AGENT = ROOT / "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat"


def require_once(text: str, needle: str, label: str) -> None:
    count = text.count(needle)
    if count != 1:
        raise SystemExit(f"MCP252 fail-closed: {label} count={count}")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    require_once(text, old, label)
    return text.replace(old, new, 1)


def patch_file(path: Path, marker: str, patcher) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print(f"MCP252 already applied: {path.name}")
        return
    updated = patcher(text)
    if updated == text or marker not in updated:
        raise SystemExit(f"MCP252 fail-closed: marker missing after patch: {path}")
    path.write_text(updated, encoding="utf-8")
    print(f"MCP252 patched: {path}")


def patch_intent_handler(text: str) -> str:
    old = '''      isWorkspaceSkill(skillName) && action == IntentAction.FILE_WORKSPACE.action ->
        handleFileWorkspaceAction(context = context, parameters = parameters, config = config)
'''
    new = '''      isWorkspaceSkill(skillName) && action == IntentAction.FILE_WORKSPACE.action -> {
        // MCP252_OFFICE_TRUTH_CONFIGURED_NORMALIZATION
        // Keep the public run_configured_intent contract unchanged while accepting common small-model aliases.
        val effectiveParameters =
          if (isOfficeWorkspaceSkill(skillName)) {
            AgentOfficeTruthGuard.prepareConfiguredParameters(skillName = skillName, parameters = parameters)
          } else {
            parameters
          }
        handleFileWorkspaceAction(context = context, parameters = effectiveParameters, config = config)
      }
'''
    text = replace_once(text, old, new, "configured Office normalization")

    old = '''          if (AgentOfficeDocumentSupport.supports(operation)) {
            // MCP251_OFFICE_SKILLS_WORKSPACE_DISPATCH
            AgentOfficeDocumentSupport.execute(context = context, root = root, request = request)
          } else {
'''
    new = '''          if (AgentOfficeDocumentSupport.supports(operation) || operation == "office_auto") {
            // MCP251_OFFICE_SKILLS_WORKSPACE_DISPATCH
            // MCP252_OFFICE_TRUTH_VERIFIED_DISPATCH: backend success is provisional until read-back passes.
            AgentOfficeTruthGuard.executeVerified(context = context, root = root, rawRequest = request)
          } else {
'''
    return replace_once(text, old, new, "verified Office workspace dispatch")


def patch_agent_tools(text: str) -> str:
    start = text.find('  private fun runOfficeCompatTool(\n')
    end_marker = '  // MCP251_OFFICE_SKILLS_COMPAT_HELPER\n'
    end = text.find(end_marker, start)
    if start < 0 or end < 0:
        raise SystemExit("MCP252 fail-closed: MCP251 runOfficeCompatTool helper not found")
    helper = '''  private fun runOfficeCompatTool(
    skillName: String,
    arguments: JSONObject,
    forcedOperation: String = "",
  ): Map<String, Any?> {
    if (!skillManagerViewModel.isSkillSelected(skillName)) {
      return mapOf(
        "status" to "failed",
        "error" to "Skill \\"$skillName\\" is disabled. Enable it before using this Office tool.",
      )
    }

    // MCP252_OFFICE_TRUTH_COMPAT_NORMALIZATION
    // Preserve the model's exact arguments for evidence, then canonicalize aliases without
    // silently defaulting ambiguous Word/Excel/PPT calls to create.
    val rawArguments = arguments.toString()
    val request =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = skillName,
        rawArguments = arguments,
        forcedOperation = forcedOperation,
      )
    val normalizedArguments = request.toString()
    val flattened =
      runConfiguredIntent(
          skillName = skillName,
          intent = IntentAction.FILE_WORKSPACE.action,
          parameters = normalizedArguments,
        )
        .mapValues { it.value }
        .toMutableMap()
    flattened["audit_raw_arguments"] = rawArguments
    flattened["audit_normalized_arguments"] = normalizedArguments
    flattened["audit_resolved_operation"] =
      runCatching {
        val resultJson = JSONObject(flattened["raw_result_json"]?.toString().orEmpty())
        resultJson.optString("resolved_operation").ifBlank { resultJson.optString("operation") }
      }.getOrDefault("")
    return flattened
  }

  // MCP252_OFFICE_TRUTH_COMPAT_HELPER
  // MCP251_OFFICE_SKILLS_COMPAT_HELPER
'''
    text = text[:start] + helper + text[end + len(end_marker):]

    old = '''          .put("timestamp", timestamp)
          .put("tool_name", toolName)
          .put("original_user_request", originalUserRequest)
          .put("model_prompt", modelPrompt)
          .put("tool_result", JSONObject(result))
'''
    new = '''          .put("timestamp", timestamp)
          .put("tool_name", toolName)
          .put("original_user_request", originalUserRequest)
          // MCP252_OFFICE_TRUTH_AUDIT_EVIDENCE
          // These fields make future diagnosis evidence-based: exact parsed model args,
          // normalized args, and the final operation selected by the compatibility layer.
          .put("model_tool_arguments", result["audit_raw_arguments"]?.toString().orEmpty())
          .put("normalized_tool_arguments", result["audit_normalized_arguments"]?.toString().orEmpty())
          .put("resolved_operation", result["audit_resolved_operation"]?.toString().orEmpty())
          .put("model_prompt", modelPrompt)
          .put("tool_result", JSONObject(result))
'''
    return replace_once(text, old, new, "audit evidence promotion")


def patch_agent_tooling(text: str) -> str:
    replacements = {
      'Creates, reads, or edits DOCX inside the workspace file folder.':
        'DOCX tool. Prefer explicit operation. create needs title/content; read needs input_path; modify needs input_path plus operations. Common text/path aliases are normalized safely.',
      'Creates, reads, or edits XLSX workbooks.':
        'XLSX tool. Prefer explicit operation. create uses rows/sheets; read uses input_path; modify uses input_path plus operations. Common aliases are normalized safely.',
      'Creates, reads, or edits PPTX presentations.':
        'PPTX tool. Prefer explicit operation. create uses slides; read uses input_path; modify uses input_path plus operations. Common aliases are normalized safely.',
      'Converts TXT, DOCX, PDF, and HTML through the workspace.':
        'Converts TXT, DOCX, PDF, and HTML. Success is returned only after the output is reopened and source-text preservation is verified.',
    }
    changed = False
    for old, new in replacements.items():
        if old in text:
            text = text.replace(old, new, 1)
            changed = True
    if not changed:
        raise SystemExit("MCP252 fail-closed: Office COMPAT schema descriptions not found")
    marker = '// MCP251_OFFICE_SKILLS_COMPAT_SCHEMA: one compact entry per enabled Office skill.'
    return text.replace(marker, marker + '\n  // MCP252_OFFICE_TRUTH_SCHEMA_GUIDANCE', 1)


patch_file(AGENT / "IntentHandler.kt", "MCP252_OFFICE_TRUTH_VERIFIED_DISPATCH", patch_intent_handler)
patch_file(AGENT / "AgentTools.kt", "MCP252_OFFICE_TRUTH_COMPAT_HELPER", patch_agent_tools)
patch_file(AGENT / "AgentTooling.kt", "MCP252_OFFICE_TRUTH_SCHEMA_GUIDANCE", patch_agent_tooling)

truth_guard = AGENT / "AgentOfficeTruthGuard.kt"
if not truth_guard.exists():
    raise SystemExit("MCP252 fail-closed: AgentOfficeTruthGuard.kt missing")
truth_text = truth_guard.read_text(encoding="utf-8")
for marker in (
    "prepareCompatRequest",
    "prepareConfiguredParameters",
    "executeVerified",
    "workspace_safe_inference",
    "Output verification failed",
    "rolled_back",
):
    if marker not in truth_text:
        raise SystemExit(f"MCP252 fail-closed: Truth Guard marker missing: {marker}")

print("MCP252_OFFICE_TRUTH_PATCH_PASS")
