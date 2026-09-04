#!/usr/bin/env python3
"""MCP253 evidence-driven Office contract repair.

MUST run after MCP251 and MCP252. This patch is intentionally limited to the additive Office
boundary. Every behavior here maps to the uploaded MCP252 field evidence from 2026-09-04:
- wrapped create directives were misclassified as modify;
- append operations using type/operation + direct content were rejected;
- fixed fallback filenames collided across tasks;
- detailed backend recovery hints were replaced with a generic outer hint.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AGENT = ROOT / "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat"


def require_count(text: str, needle: str, expected: int, label: str) -> None:
    count = text.count(needle)
    if count != expected:
        raise SystemExit(f"MCP253 fail-closed: {label} count={count}, expected={expected}")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    require_count(text, old, 1, label)
    return text.replace(old, new, 1)


def patch_file(path: Path, marker: str, patcher) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print(f"MCP253 already applied: {path.name}")
        return
    updated = patcher(text)
    if updated == text or marker not in updated:
        raise SystemExit(f"MCP253 fail-closed: marker missing after patch: {path}")
    path.write_text(updated, encoding="utf-8")
    print(f"MCP253 patched: {path}")


def patch_truth_guard(text: str) -> str:
    aliases = '''    normalizeCommonAliases(request)\n    normalizeSkillAliases(skillName, request)\n'''
    require_count(text, aliases, 2, "Truth Guard normalization sites")
    aliases_new = aliases + '''    // MCP253_OFFICE_EVIDENCE_DIALECT_NORMALIZATION\n    AgentOfficeMcp253Compat.normalizeBeforeRouting(skillName = skillName, request = request)\n'''
    text = text.replace(aliases, aliases_new)

    old = '''    request.put("operation", operation)\n    if (request.optString(META_RESOLUTION).isBlank()) request.put(META_RESOLUTION, "backend_explicit")\n    materializeSimpleModifyOperations(skillName, request, operation)\n\n    val outputPath = expectedOutputPath(operation, request)\n'''
    new = '''    request.put("operation", operation)\n    if (request.optString(META_RESOLUTION).isBlank()) request.put(META_RESOLUTION, "backend_explicit")\n    materializeSimpleModifyOperations(skillName, request, operation)\n\n    // MCP253_OFFICE_COLLISION_SAFE_OUTPUT\n    // If output_path was omitted, allocate a semantic, collision-safe workspace path before the\n    // existing no-overwrite and semantic read-back checks run. Explicit paths remain untouched.\n    AgentOfficeMcp253Compat.ensureCollisionSafeOutputPath(\n      root = root,\n      skillName = skillName,\n      request = request,\n      operation = operation,\n    )\n\n    val outputPath = expectedOutputPath(operation, request)\n'''
    return replace_once(text, old, new, "collision-safe output hook")


def patch_agent_tools(text: str) -> str:
    old = '''    val error = payload.optString("error").ifBlank { "Tool execution failed." }\n    flattened["error"] = error\n    flattened["recovery_hint"] = buildRecoveryHint(operation = operation, error = error)\n    flattened["summary"] = "Failed $operation: $error"\n'''
    new = '''    val error = payload.optString("error").ifBlank { "Tool execution failed." }\n    flattened["error"] = error\n    // MCP253_OFFICE_PRESERVE_BACKEND_RECOVERY_HINT\n    // MCP252 field logs proved the Truth Guard emitted a precise retry contract, but this outer\n    // layer discarded it and sent the model the generic fallback. Preserve the backend evidence.\n    flattened["recovery_hint"] =\n      payload.optString("recovery_hint").ifBlank { buildRecoveryHint(operation = operation, error = error) }\n    flattened["summary"] = "Failed $operation: $error"\n'''
    return replace_once(text, old, new, "backend recovery hint preservation")


def patch_agent_tooling(text: str) -> str:
    replacements = {
      'DOCX tool. Prefer explicit operation. create needs title/content; read needs input_path; modify needs input_path plus operations. Common text/path aliases are normalized safely.':
        'DOCX tool. Minimal forms: create={operation:create,title/content}; read={operation:read,input_path}; append={operation:modify,input_path,operations:[{action:add_paragraph,params:{text:...}}]}. output_path is optional for create; the app allocates a unique path. Reuse the exact returned path for follow-up edits. Common small-model aliases are accepted.',
      'XLSX tool. Prefer explicit operation. create uses rows/sheets; read uses input_path; modify uses input_path plus operations. Common aliases are normalized safely.':
        'XLSX tool. create uses rows/sheets, read uses input_path, modify uses input_path plus operations. output_path is optional for create; the app allocates a unique path. Reuse the exact returned path for follow-up edits. Nested action aliases are normalized.',
      'PPTX tool. Prefer explicit operation. create uses slides; read uses input_path; modify uses input_path plus operations. Common aliases are normalized safely.':
        'PPTX tool. create uses slides/title/content, read uses input_path, modify uses input_path plus operations. output_path is optional for create; the app allocates a unique path. Reuse the exact returned path for follow-up edits. Nested action aliases are normalized.',
      'Creates, reads, merges, extracts, reorders, deletes, or rotates PDF pages.':
        'PDF tool. output_path is optional for create/merge; the app allocates a collision-safe path. Reuse exact returned paths for later operations.',
      'Converts TXT, DOCX, PDF, and HTML. Success is returned only after the output is reopened and source-text preservation is verified.':
        'Converts TXT, DOCX, PDF, and HTML. output_path may be omitted; a collision-safe path is allocated. Success is returned only after reopening the output and verifying source-text preservation.',
    }
    changed = 0
    for old, new in replacements.items():
        if old in text:
            text = text.replace(old, new, 1)
            changed += 1
    if changed < 4:
        raise SystemExit(f"MCP253 fail-closed: Office schema descriptions changed={changed}, expected at least 4")
    marker = '// MCP252_OFFICE_TRUTH_SCHEMA_GUIDANCE'
    require_count(text, marker, 1, "MCP252 schema marker")
    return text.replace(marker, marker + '\n  // MCP253_OFFICE_EVIDENCE_SCHEMA_GUIDANCE', 1)


patch_file(AGENT / "AgentOfficeTruthGuard.kt", "MCP253_OFFICE_COLLISION_SAFE_OUTPUT", patch_truth_guard)
patch_file(AGENT / "AgentTools.kt", "MCP253_OFFICE_PRESERVE_BACKEND_RECOVERY_HINT", patch_agent_tools)
patch_file(AGENT / "AgentTooling.kt", "MCP253_OFFICE_EVIDENCE_SCHEMA_GUIDANCE", patch_agent_tooling)

helper = AGENT / "AgentOfficeMcp253Compat.kt"
if not helper.exists():
    raise SystemExit("MCP253 fail-closed: AgentOfficeMcp253Compat.kt missing")
helper_text = helper.read_text(encoding="utf-8")
for marker in (
    "promoteWrappedRootOperation",
    "normalizeModifyOperations",
    "ensureCollisionSafeOutputPath",
    "chooseUniquePath",
    "META_AUTO_OUTPUT_PATH",
):
    if marker not in helper_text:
        raise SystemExit(f"MCP253 fail-closed: helper marker missing: {marker}")

print("MCP253_OFFICE_CONTRACT_PATCH_PASS")
