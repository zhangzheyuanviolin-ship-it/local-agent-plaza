#!/usr/bin/env python3
"""MCP251 additive Office-skill integration.

Fail-closed and idempotent. It only patches the existing Agent skill/workspace boundaries;
it does not touch model loading, LiteRT, local music, image generation, media runtime,
or the AUTO/NATIVE/COMPAT state machine itself.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AGENT = ROOT / "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"MCP251 fail-closed: {label} anchor count={count}")
    return text.replace(old, new, 1)


def patch_file(path: Path, marker: str, patcher) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print(f"MCP251 already applied: {path.name}")
        return
    updated = patcher(text)
    if updated == text or marker not in updated:
        raise SystemExit(f"MCP251 fail-closed: marker missing after patch: {path}")
    path.write_text(updated, encoding="utf-8")
    print(f"MCP251 patched: {path}")


def patch_workspace_config(text: str) -> str:
    old = '''const val WEATHER_QUERY_SKILL_NAME = "weather-query"\n'''
    new = '''const val WEATHER_QUERY_SKILL_NAME = "weather-query"\n\n// MCP251_OFFICE_SKILLS_CONFIG\nconst val WORD_DOCUMENT_SKILL_NAME = "word-document"\nconst val PDF_DOCUMENT_SKILL_NAME = "pdf-document"\nconst val EXCEL_WORKBOOK_SKILL_NAME = "excel-workbook"\nconst val POWERPOINT_PRESENTATION_SKILL_NAME = "powerpoint-presentation"\nconst val DOCUMENT_CONVERT_SKILL_NAME = "document-convert"\n'''
    text = replace_once(text, old, new, "office skill constants")
    old = '''fun isWorkspaceSkill(skillName: String): Boolean {\n  return skillName == FILE_WORKSPACE_SKILL_NAME ||\n    skillName == LONG_TEXT_WRITER_SKILL_NAME ||\n    skillName == EDGE_TTS_SKILL_NAME\n}\n'''
    new = '''fun isOfficeWorkspaceSkill(skillName: String): Boolean {\n  return skillName == WORD_DOCUMENT_SKILL_NAME ||\n    skillName == PDF_DOCUMENT_SKILL_NAME ||\n    skillName == EXCEL_WORKBOOK_SKILL_NAME ||\n    skillName == POWERPOINT_PRESENTATION_SKILL_NAME ||\n    skillName == DOCUMENT_CONVERT_SKILL_NAME\n}\n\nfun isWorkspaceSkill(skillName: String): Boolean {\n  return skillName == FILE_WORKSPACE_SKILL_NAME ||\n    skillName == LONG_TEXT_WRITER_SKILL_NAME ||\n    skillName == EDGE_TTS_SKILL_NAME ||\n    isOfficeWorkspaceSkill(skillName)\n}\n'''
    return replace_once(text, old, new, "workspace skill family")


def patch_skill_manager(text: str) -> str:
    old = '''                          it.name == ANYSEARCH_SEARCH_SKILL_NAME ||\n                          it.name == WEB_PAGE_EXTRACT_SKILL_NAME\n                      ) false else true\n'''
    new = '''                          it.name == ANYSEARCH_SEARCH_SKILL_NAME ||\n                          it.name == WEB_PAGE_EXTRACT_SKILL_NAME ||\n                          // MCP251_OFFICE_SKILLS_DEFAULT_OFF: keep small-model prompt injection opt-in.\n                          it.name == WORD_DOCUMENT_SKILL_NAME ||\n                          it.name == PDF_DOCUMENT_SKILL_NAME ||\n                          it.name == EXCEL_WORKBOOK_SKILL_NAME ||\n                          it.name == POWERPOINT_PRESENTATION_SKILL_NAME ||\n                          it.name == DOCUMENT_CONVERT_SKILL_NAME\n                      ) false else true\n'''
    return replace_once(text, old, new, "office skills default-off")


def patch_intent_handler(text: str) -> str:
    old = '''        else ->\n          errorJson(\n            "Unsupported file workspace operation \\"$operation\\". Supported operations: status, list, stat, read_text, download_url, edge_tts_synthesize, write_text, append_text, create_dir, delete, copy, move."\n          )\n'''
    new = '''        else ->\n          if (AgentOfficeDocumentSupport.supports(operation)) {\n            // MCP251_OFFICE_SKILLS_WORKSPACE_DISPATCH\n            AgentOfficeDocumentSupport.execute(context = context, root = root, request = request)\n          } else {\n            errorJson(\n              "Unsupported file workspace operation \\"$operation\\". Supported operations: status, list, stat, read_text, download_url, edge_tts_synthesize, write_text, append_text, create_dir, delete, copy, move, and enabled MCP251 Office operations."\n            )\n          }\n'''
    return replace_once(text, old, new, "Office workspace dispatch")


def patch_agent_tools(text: str) -> str:
    old = '''      val config =\n        skillManagerViewModel.dataStoreRepository.readSecret(\n          key = getSkillConfigKey(skillName = skillName)\n        ) ?: ""\n\n      if (skill.name == AGNES_OMNI_SKILL_NAME) {\n'''
    new = '''      // MCP251_OFFICE_SKILLS_SHARED_WORKSPACE_CONFIG\n      // Office skills use the exact same mounted SAF workspace as file-workspace.\n      val configOwnerSkillName =\n        if (isOfficeWorkspaceSkill(skill.name)) FILE_WORKSPACE_SKILL_NAME else skillName\n      val config =\n        skillManagerViewModel.dataStoreRepository.readSecret(\n          key = getSkillConfigKey(skillName = configOwnerSkillName)\n        ) ?: ""\n\n      if (skill.name == AGNES_OMNI_SKILL_NAME) {\n'''
    text = replace_once(text, old, new, "Office shared workspace config")

    old = '''        "list_workspace" ->\n          runFileWorkspaceCompatOperation(\n'''
    new = '''        // MCP251_OFFICE_SKILLS_COMPAT_DISPATCH\n        "word_document" -> runOfficeCompatTool(WORD_DOCUMENT_SKILL_NAME, arguments)\n        "create_docx" -> runOfficeCompatTool(WORD_DOCUMENT_SKILL_NAME, arguments, "create")\n        "read_docx" -> runOfficeCompatTool(WORD_DOCUMENT_SKILL_NAME, arguments, "read")\n        "modify_docx" -> runOfficeCompatTool(WORD_DOCUMENT_SKILL_NAME, arguments, "modify")\n        "pdf_document" -> runOfficeCompatTool(PDF_DOCUMENT_SKILL_NAME, arguments)\n        "create_pdf" -> runOfficeCompatTool(PDF_DOCUMENT_SKILL_NAME, arguments, "create")\n        "read_pdf" -> runOfficeCompatTool(PDF_DOCUMENT_SKILL_NAME, arguments, "read")\n        "pdf_manage" -> runOfficeCompatTool(PDF_DOCUMENT_SKILL_NAME, arguments)\n        "excel_workbook" -> runOfficeCompatTool(EXCEL_WORKBOOK_SKILL_NAME, arguments)\n        "create_xlsx" -> runOfficeCompatTool(EXCEL_WORKBOOK_SKILL_NAME, arguments, "create")\n        "read_xlsx" -> runOfficeCompatTool(EXCEL_WORKBOOK_SKILL_NAME, arguments, "read")\n        "modify_xlsx" -> runOfficeCompatTool(EXCEL_WORKBOOK_SKILL_NAME, arguments, "modify")\n        "powerpoint_presentation" -> runOfficeCompatTool(POWERPOINT_PRESENTATION_SKILL_NAME, arguments)\n        "create_pptx" -> runOfficeCompatTool(POWERPOINT_PRESENTATION_SKILL_NAME, arguments, "create")\n        "read_pptx" -> runOfficeCompatTool(POWERPOINT_PRESENTATION_SKILL_NAME, arguments, "read")\n        "modify_pptx" -> runOfficeCompatTool(POWERPOINT_PRESENTATION_SKILL_NAME, arguments, "modify")\n        "convert_document" -> runOfficeCompatTool(DOCUMENT_CONVERT_SKILL_NAME, arguments, "convert")\n        "list_workspace" ->\n          runFileWorkspaceCompatOperation(\n'''
    text = replace_once(text, old, new, "Office COMPAT dispatcher")

    old = '''  private fun runFileWorkspaceCompatOperation(\n    operation: String,\n'''
    helper = '''  private fun runOfficeCompatTool(\n    skillName: String,\n    arguments: JSONObject,\n    forcedOperation: String = "",\n  ): Map<String, Any?> {\n    if (!skillManagerViewModel.isSkillSelected(skillName)) {\n      return mapOf(\n        "status" to "failed",\n        "error" to "Skill \\"$skillName\\" is disabled. Enable it before using this Office tool.",\n      )\n    }\n    val requested =\n      forcedOperation.ifBlank {\n        arguments.optString("operation")\n          .ifBlank { arguments.optString("action") }\n          .trim()\n          .lowercase()\n      }\n    val operation =\n      when (skillName) {\n        WORD_DOCUMENT_SKILL_NAME ->\n          when (requested.ifBlank { "create" }) {\n            "create", "word_create" -> "word_create"\n            "read", "word_read" -> "word_read"\n            "modify", "edit", "word_modify" -> "word_modify"\n            else -> "word_$requested"\n          }\n        PDF_DOCUMENT_SKILL_NAME ->\n          when (requested.ifBlank { "read" }) {\n            "create", "pdf_create" -> "pdf_create"\n            "read", "pdf_read" -> "pdf_read"\n            "merge", "pdf_merge" -> "pdf_merge"\n            "extract", "extract_pages", "split", "pdf_extract_pages" -> "pdf_extract_pages"\n            "reorder", "reorder_pages", "pdf_reorder_pages" -> "pdf_reorder_pages"\n            "delete", "delete_pages", "pdf_delete_pages" -> "pdf_delete_pages"\n            "rotate", "rotate_pages", "pdf_rotate_pages" -> "pdf_rotate_pages"\n            else -> "pdf_$requested"\n          }\n        EXCEL_WORKBOOK_SKILL_NAME ->\n          when (requested.ifBlank { "create" }) {\n            "create", "xlsx_create" -> "xlsx_create"\n            "read", "xlsx_read" -> "xlsx_read"\n            "modify", "edit", "xlsx_modify" -> "xlsx_modify"\n            else -> "xlsx_$requested"\n          }\n        POWERPOINT_PRESENTATION_SKILL_NAME ->\n          when (requested.ifBlank { "create" }) {\n            "create", "pptx_create" -> "pptx_create"\n            "read", "pptx_read" -> "pptx_read"\n            "modify", "edit", "pptx_modify" -> "pptx_modify"\n            else -> "pptx_$requested"\n          }\n        DOCUMENT_CONVERT_SKILL_NAME -> "document_convert"\n        else -> requested\n      }\n    val request = JSONObject(arguments.toString()).put("operation", operation)\n    return runConfiguredIntent(\n        skillName = skillName,\n        intent = IntentAction.FILE_WORKSPACE.action,\n        parameters = request.toString(),\n      )\n      .mapValues { it.value }\n  }\n\n  // MCP251_OFFICE_SKILLS_COMPAT_HELPER\n  private fun runFileWorkspaceCompatOperation(\n    operation: String,\n'''
    return replace_once(text, old, helper, "Office COMPAT helper")


def patch_agent_tooling(text: str) -> str:
    old = '''  if (selectedSkillNames.contains(FILE_WORKSPACE_SKILL_NAME)) {\n'''
    new = '''  // MCP251_OFFICE_SKILLS_COMPAT_SCHEMA: one compact entry per enabled Office skill.\n  if (selectedSkillNames.contains(WORD_DOCUMENT_SKILL_NAME)) {\n    tools += "- word_document arguments: {\\\"operation\\\":\\\"create|read|modify\\\",\\\"input_path\\\":\\\"file/input.docx\\\",\\\"output_path\\\":\\\"file/output.docx\\\",\\\"title\\\":\\\"...\\\",\\\"content\\\":\\\"...\\\",\\\"operations\\\":[...]}. Creates, reads, or edits DOCX inside the workspace file folder."\n  }\n  if (selectedSkillNames.contains(PDF_DOCUMENT_SKILL_NAME)) {\n    tools += "- pdf_document arguments: {\\\"operation\\\":\\\"create|read|merge|extract_pages|reorder_pages|delete_pages|rotate_pages\\\",\\\"input_path\\\":\\\"file/input.pdf\\\",\\\"input_paths\\\":[...],\\\"pages\\\":\\\"1-3,5\\\",\\\"degrees\\\":90,\\\"output_path\\\":\\\"file/output.pdf\\\",\\\"content\\\":\\\"...\\\"}. Creates, reads, merges, extracts, reorders, deletes, or rotates PDF pages."\n  }\n  if (selectedSkillNames.contains(EXCEL_WORKBOOK_SKILL_NAME)) {\n    tools += "- excel_workbook arguments: {\\\"operation\\\":\\\"create|read|modify\\\",\\\"input_path\\\":\\\"file/input.xlsx\\\",\\\"output_path\\\":\\\"file/output.xlsx\\\",\\\"rows\\\":[[...]],\\\"sheets\\\":[...],\\\"operations\\\":[...]}. Creates, reads, or edits XLSX workbooks."\n  }\n  if (selectedSkillNames.contains(POWERPOINT_PRESENTATION_SKILL_NAME)) {\n    tools += "- powerpoint_presentation arguments: {\\\"operation\\\":\\\"create|read|modify\\\",\\\"input_path\\\":\\\"file/input.pptx\\\",\\\"output_path\\\":\\\"file/output.pptx\\\",\\\"slides\\\":[{\\\"title\\\":\\\"...\\\",\\\"content\\\":\\\"...\\\"}],\\\"operations\\\":[...]}. Creates, reads, or edits PPTX presentations."\n  }\n  if (selectedSkillNames.contains(DOCUMENT_CONVERT_SKILL_NAME)) {\n    tools += "- convert_document arguments: {\\\"input_path\\\":\\\"file/source.docx\\\",\\\"output_format\\\":\\\"txt|docx|pdf|html\\\",\\\"output_path\\\":\\\"file/result.pdf\\\"}. Converts TXT, DOCX, PDF, and HTML through the workspace."\n  }\n  if (selectedSkillNames.contains(FILE_WORKSPACE_SKILL_NAME)) {\n'''
    return replace_once(text, old, new, "Office COMPAT schema")


patch_file(AGENT / "FileWorkspaceSkillConfig.kt", "MCP251_OFFICE_SKILLS_CONFIG", patch_workspace_config)
patch_file(AGENT / "SkillManagerViewModel.kt", "MCP251_OFFICE_SKILLS_DEFAULT_OFF", patch_skill_manager)
patch_file(AGENT / "IntentHandler.kt", "MCP251_OFFICE_SKILLS_WORKSPACE_DISPATCH", patch_intent_handler)
patch_file(AGENT / "AgentTools.kt", "MCP251_OFFICE_SKILLS_COMPAT_HELPER", patch_agent_tools)
patch_file(AGENT / "AgentTooling.kt", "MCP251_OFFICE_SKILLS_COMPAT_SCHEMA", patch_agent_tooling)

support = AGENT / "AgentOfficeDocumentSupport.kt"
if not support.exists():
    raise SystemExit("MCP251 fail-closed: AgentOfficeDocumentSupport.kt missing")

skill_root = ROOT / "app/src/main/assets/skills"
for skill in ("word-document", "pdf-document", "excel-workbook", "powerpoint-presentation", "document-convert"):
    if not (skill_root / skill / "SKILL.md").exists():
        raise SystemExit(f"MCP251 fail-closed: missing built-in skill asset {skill}")

print("MCP251_OFFICE_SKILLS_PATCH_PASS")
