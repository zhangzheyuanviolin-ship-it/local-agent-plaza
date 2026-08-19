#!/usr/bin/env python3
"""Build-time MCP223/MCP224 Agent hardening while preserving the MCP210 runtime baseline."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> tuple[Path, str]:
    path = ROOT / rel
    return path, path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")
    print(f"MCP224 patched {path.relative_to(ROOT)}")


def require_index(text: str, marker: str, rel: str, start: int = 0) -> int:
    index = text.find(marker, start)
    if index < 0:
        print(f"MCP224 patch missing marker in {rel}: {marker[:120]!r}", file=sys.stderr)
        raise SystemExit(1)
    return index


def replace_once(text: str, old: str, new: str, rel: str) -> str:
    count = text.count(old)
    if count != 1:
        print(
            f"MCP224 patch expected one marker in {rel}, found {count}: {old[:120]!r}",
            file=sys.stderr,
        )
        raise SystemExit(1)
    return text.replace(old, new, 1)


# AgentTooling: preserve the old strict decoder as an internal canonical decoder and make the
# model-family adapter the only public parseCompatToolCall entrypoint.
rel = "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentTooling.kt"
path, text = read(rel)
if "CompatToolCallWireAdapter.parseFirstToolCall(rawText)" not in text:
    text = replace_once(
        text,
        "fun parseCompatToolCall(rawText: String): ParsedCompatToolCall? {",
        "internal fun decodeCanonicalCompatToolCall(rawText: String): ParsedCompatToolCall? {",
        rel,
    )
    text = replace_once(
        text,
        "\nfun stripCompatThinkingText(rawText: String): String {",
        "\nfun parseCompatToolCall(rawText: String): ParsedCompatToolCall? =\n"
        "  CompatToolCallWireAdapter.parseFirstToolCall(rawText)\n\n"
        "fun stripCompatThinkingText(rawText: String): String {",
        rel,
    )

# MCP224: manual Native means real Native for A/B testing; Auto keeps the safe known-model resolver.
# Unknown forced-native models receive tool definitions but do not get constrained decoding unless
# they are already known-good native models.
if "const val MAX_COMPAT_TOOL_STEPS = 8\n" in text:
    text = text.replace("const val MAX_COMPAT_TOOL_STEPS = 8\n", "", 1)
old_native_branch = '''    AgentToolModeValues.NATIVE ->
      if (supportsNativeAgentTools(model)) {
        ResolvedAgentToolMode.NATIVE
      } else {
        ResolvedAgentToolMode.COMPAT
      }
'''
if old_native_branch in text:
    text = text.replace(
        old_native_branch,
        "    AgentToolModeValues.NATIVE -> ResolvedAgentToolMode.NATIVE\n",
        1,
    )
old_constrained = '''  return resolveAgentToolMode(model) == ResolvedAgentToolMode.NATIVE && !model.imported
'''
if old_constrained in text:
    text = text.replace(
        old_constrained,
        "  return resolveAgentToolMode(model) == ResolvedAgentToolMode.NATIVE && supportsNativeAgentTools(model) && !model.imported\n",
        1,
    )
write(path, text)


# CompatToolCallWireAdapter: unify canonical/model-family parsing and recover invalid long writes.
rel = "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/CompatToolCallWireAdapter.kt"
path, text = read(rel)
if "internal fun parseFirstToolCall(rawText: String): ParsedCompatToolCall?" not in text:
    start_marker = "  internal fun normalizeFirstToolCall(rawText: String): String? {"
    end_marker = "\n\n  internal fun hasStrongToolSignal(text: String): Boolean {"
    start = require_index(text, start_marker, rel)
    end = require_index(text, end_marker, rel, start)
    replacement = '''  internal fun normalizeFirstToolCall(rawText: String): String? =
    parseFirstToolCall(rawText)?.let(::canonical)

  internal fun parseFirstToolCall(rawText: String): ParsedCompatToolCall? {
    val raw = rawText.trim()
    if (raw.isBlank()) return null
    decodeCanonicalCompatToolCall(raw)?.let { return it }
    return listOfNotNull(
        parseLongWorkspaceWrite(raw),
        parseLooseToolBlock(raw),
        parseQwen35(raw),
        parseGlm(raw),
        parseGemma(raw),
        parseMistral(raw),
        parseDeepSeek(raw),
        parseGptOss(raw),
        parsePythonTag(raw),
        parseMarkedJson(raw),
        parseInvokeXml(raw),
        parseToolUseXml(raw),
        parseBareJson(raw),
      )
      .firstOrNull()
  }'''
    text = text[:start] + replacement + text[end:]

    insertion_marker = "  // Generic <tool_call> variants with relaxed JSON, unquoted keys, or call:NAME{...}.\n"
    insertion_at = require_index(text, insertion_marker, rel)
    long_write_parser = r'''  // Long article/file writes can contain raw newlines or unescaped ASCII quotes inside content.
  // Recover the stable outer envelope and treat the final quote before the two closing braces as
  // the content terminator. Valid JSON is already handled by decodeCanonicalCompatToolCall first.
  private fun parseLongWorkspaceWrite(text: String): ParsedCompatToolCall? {
    val open = text.indexOf("<tool_call>", ignoreCase = true)
    if (open < 0) return null
    val close = text.indexOf("</tool_call>", startIndex = open, ignoreCase = true)
    val block =
      text.substring(
        open + "<tool_call>".length,
        if (close >= 0) close else text.length,
      ).trim()
    val toolMatch =
      Regex("[\\\"'](?:tool|name|tool_name)[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
        .find(block) ?: return null
    val toolName = normalizeName(toolMatch.groupValues[1])
    if (
      toolName.lowercase() !in
        setOf(
          "write_workspace_file",
          "write_workspace_text_file",
          "append_workspace_file",
          "append_workspace_text_file",
        )
    ) return null

    val contentMatch =
      Regex("[\\\"']content[\\\"']\\s*:\\s*([\\\"'])", RegexOption.IGNORE_CASE).find(block)
        ?: return null
    val quote = contentMatch.groupValues[1].firstOrNull() ?: return null
    val contentStart = contentMatch.range.last + 1
    val tail = block.substring(contentStart)
    val terminal =
      if (quote == '\"') Regex("\\\"\\s*}\\s*}\\s*$", RegexOption.DOT_MATCHES_ALL)
      else Regex("'\\s*}\\s*}\\s*$", RegexOption.DOT_MATCHES_ALL)
    val terminalMatch = terminal.find(tail) ?: return null
    val rawContent = tail.substring(0, terminalMatch.range.first)
    val prefix = block.substring(0, contentMatch.range.first)
    val pathValue =
      Regex("[\\\"']path[\\\"']\\s*:\\s*[\\\"']([^\\\"']*)[\\\"']", RegexOption.IGNORE_CASE)
        .find(prefix)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::decodeRecoveredString)
        .orEmpty()
    if (pathValue.isBlank()) return null
    return ParsedCompatToolCall(
      toolName = toolName,
      arguments = JSONObject().put("path", pathValue).put("content", decodeRecoveredString(rawContent)),
    )
  }

  private fun decodeRecoveredString(raw: String): String =
    raw
      .replace("\\r\\n", "\n")
      .replace("\\n", "\n")
      .replace("\\r", "\r")
      .replace("\\t", "\t")
      .replace("\\\"", "\"")
      .replace("\\'", "'")
      .replace("\\\\", "\\")

'''
    text = text[:insertion_at] + long_write_parser + text[insertion_at:]

    text = replace_once(
        text,
        "internal class CompatToolCallStreamGate(private val enabled: Boolean) {",
        "internal class CompatToolCallStreamGate(\n"
        "  private val enabled: Boolean,\n"
        "  private val holdUntilDone: Boolean = false,\n"
        ") {",
        rel,
    )
    text = replace_once(
        text,
        "  fun accept(text: String): String {\n    if (!enabled) return text\n",
        "  fun accept(text: String): String {\n"
        "    if (!enabled) return text\n"
        "    if (holdUntilDone) {\n"
        "      mode = Mode.TOOL\n"
        "      buffered.append(text)\n"
        "      return \"\"\n"
        "    }\n",
        rel,
    )
    write(path, text)


# Runtime coordinator: fingerprint exactly the same ParsedCompatToolCall used by execution/UI.
rel = "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentCompatRuntimeCoordinator.kt"
path, text = read(rel)
if "val call = parseCompatToolCall(text) ?: return null" not in text:
    start_marker = "  private fun extractToolCallFingerprint(text: String): String? {"
    end_marker = "\n\n  private fun extractFirstJsonObject(text: String): String? {"
    start = require_index(text, start_marker, rel)
    end = require_index(text, end_marker, rel, start)
    replacement = '''  private fun extractToolCallFingerprint(text: String): String? {
    val call = parseCompatToolCall(text) ?: return null
    val canonical =
      JSONObject()
        .put("tool", call.toolName.trim())
        .put("arguments", call.arguments)
    return canonicalizeJsonObject(canonical).take(MAX_FINGERPRINT_CHARS)
  }'''
    text = text[:start] + replacement + text[end:]
    write(path, text)


# LlmChatModelHelper: explicit-search control plus MCP224 model initialization diagnostics.
rel = "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt"
path, text = read(rel)
if "searchRequiredForTopLevel" not in text:
    text = replace_once(
        text,
        "import com.google.ai.edge.gallery.customtasks.agentchat.AgentCompatRuntimeCoordinator\n",
        "import com.google.ai.edge.gallery.customtasks.agentchat.AgentCompatRuntimeCoordinator\n"
        "import com.google.ai.edge.gallery.customtasks.agentchat.CompatSearchRequiredPolicy\n"
        "import com.google.ai.edge.gallery.customtasks.agentchat.CompatToolCallStreamGate\n",
        rel,
    )

    compat_start = require_index(text, "    val compatPassKind =", rel)
    extra_context = require_index(text, "\n\n    val effectiveExtraContext =", rel, compat_start)
    search_block = '''
    val searchRequiredForTopLevel =
      isCompatPass &&
        compatReason == COMPAT_FRESH_REASON_TOP_LEVEL &&
        CompatSearchRequiredPolicy.isSearchRequiredInput(effectiveInput)'''
    text = text[:extra_context] + search_block + text[extra_context:]

    text = replace_once(
        text,
        "    var generatedChars = 0\n    val generatedText = StringBuilder()\n\n    conversation.sendMessageAsync(\n",
        "    var generatedChars = 0\n"
        "    val generatedText = StringBuilder()\n"
        "    val compatToolCallGate =\n"
        "      CompatToolCallStreamGate(\n"
        "        enabled = isCompatPass,\n"
        "        holdUntilDone = searchRequiredForTopLevel,\n"
        "      )\n\n"
        "    conversation.sendMessageAsync(\n",
        rel,
    )
    text = replace_once(
        text,
        "            resultListener(text, false, thought.takeIf { it.isNotBlank() })\n",
        "            val visibleText = compatToolCallGate.accept(text)\n"
        "            if (visibleText.isNotEmpty() || thought.isNotBlank()) {\n"
        "              resultListener(visibleText, false, thought.takeIf { it.isNotBlank() })\n"
        "            }\n",
        rel,
    )

    send_start = require_index(text, "    conversation.sendMessageAsync(", rel)
    done_start = require_index(text, "          override fun onDone() {", rel, send_start)
    perf_marker = "            AgentPerformanceCoordinator.onInferenceDone(model.name, generatedChars)"
    perf_at = require_index(text, perf_marker, rel, done_start)
    new_done_prefix = '''          override fun onDone() {
            val rawGeneration = generatedText.toString()
            val generationForRouting =
              if (
                searchRequiredForTopLevel &&
                  !CompatSearchRequiredPolicy.hasWebSearchToolCall(rawGeneration)
              ) {
                CompatSearchRequiredPolicy.buildFallbackToolCall(effectiveInput) ?: rawGeneration
              } else {
                rawGeneration
              }
            val completedGeneration = compatToolCallGate.finish(generationForRouting)
            if (completedGeneration.uiTail.isNotBlank()) {
              resultListener(completedGeneration.uiTail, false, null)
            }
            val decision =
              AgentCompatRuntimeCoordinator.onGenerationCompleted(
                modelName = model.name,
                generatedText = completedGeneration.runtimeText,
              )
'''
    text = text[:done_start] + new_done_prefix + text[perf_at:]

    text = replace_once(
        text,
        "    val compactedRawInput = compactCompatEnvelope(input)\n",
        "    val compactedRawInput =\n"
        "      CompatSearchRequiredPolicy.injectIntoCompatInput(compactCompatEnvelope(input))\n",
        rel,
    )

if "AgentDiagnosticsLogger" not in text:
    text = text.replace(
        "import com.google.ai.edge.gallery.customtasks.agentchat.AgentPerformanceCoordinator\n",
        "import com.google.ai.edge.gallery.customtasks.agentchat.AgentPerformanceCoordinator\n"
        "import com.google.ai.edge.gallery.customtasks.agentchat.AgentDiagnosticsLogger\n",
        1,
    )

engine_marker = "      val engine = Engine(engineConfig)\n"
if "category = \"model.engine.initialize.start\"" not in text and engine_marker in text:
    text = text.replace(
        engine_marker,
        '''      AgentDiagnosticsLogger.log(
        context = context,
        category = "model.engine.initialize.start",
        message = "Creating LiteRT-LM engine for ${model.name}",
        detail =
          "modelPath=$modelPath\\nfileBytes=${runCatching { java.io.File(modelPath).length() }.getOrDefault(-1L)}\\n" +
            "backend=$accelerator\\nvisionBackend=$visionAccelerator\\nsupportImage=$shouldEnableImage\\nsupportAudio=$shouldEnableAudio\\n" +
            "configuredContextWindow=$configuredContextWindow\\nengineMaxNumTokens=$engineMaxNumTokens\\nmaxOutputTokens=$maxOutputTokens\\n" +
            "litert_lm_version=0.15.0\\nlitert_version=2.1.6",
      )
      val engine = Engine(engineConfig)
''',
        1,
    )

catch_marker = '''    } catch (e: Exception) {
      ExperimentalFlags.enableSpeculativeDecoding = false
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      return
    }
'''
if "category = \"model.engine.initialize.error\"" not in text and catch_marker in text:
    text = text.replace(
        catch_marker,
        '''    } catch (e: Exception) {
      AgentDiagnosticsLogger.logThrowable(
        context = context,
        category = "model.engine.initialize.error",
        message = "LiteRT-LM engine initialization failed for ${model.name}",
        throwable = e,
        extra =
          "modelPath=$modelPath\\nbackend=$accelerator\\nvisionBackend=$visionAccelerator\\n" +
            "configuredContextWindow=$configuredContextWindow\\nengineMaxNumTokens=$engineMaxNumTokens\\nmaxOutputTokens=$maxOutputTokens\\n" +
            "litert_lm_version=0.15.0\\nlitert_version=2.1.6",
      )
      ExperimentalFlags.enableSpeculativeDecoding = false
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      return
    }
''',
        1,
    )
write(path, text)


# AgentChatScreen: remove the eight-step ceiling. Keep only the repeated-identical-call guard in
# AgentCompatRuntimeCoordinator and the user's existing Stop button.
rel = "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatScreen.kt"
path, text = read(rel)
text = text.replace("  val compatToolStepsByModel = remember { mutableStateMapOf<String, Int>() }\n", "", 1)
text = text.replace("    compatToolStepsByModel.remove(model.name)\n", "")
text = text.replace("          compatToolStepsByModel.remove(model.name)\n", "")
step_block = '''        val currentSteps = compatToolStepsByModel[model.name] ?: 0
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
text = text.replace(step_block, "", 1)
text = text.replace(
    "    onBeforeSendMessage = { model, _ -> compatToolStepsByModel.remove(model.name) },\n",
    '''    onBeforeSendMessage = { model, _ ->
      AgentDiagnosticsLogger.log(
        context = context,
        category = "agent.tool_mode",
        message = "Starting Agent request for ${model.name}",
        detail =
          "configured=${getConfiguredAgentToolMode(model)}\\nresolved=${resolveAgentToolMode(model)}\\n" +
            "nativeKnown=${supportsNativeAgentTools(model)}\\nimported=${model.imported}\\ncontext=${model.getConfiguredContextWindow()}",
      )
    },
''',
    1,
)
if "category = \"agent.compat.tool_call\"" not in text:
    tool_marker = "      if (parsedToolCall != null) {\n"
    text = text.replace(
        tool_marker,
        '''      if (parsedToolCall != null) {
        AgentDiagnosticsLogger.log(
          context = context,
          category = "agent.compat.tool_call",
          message = "Parsed COMPAT tool call ${parsedToolCall.toolName} for ${model.name}",
          detail = "arguments=${parsedToolCall.arguments}",
        )
''',
        1,
    )
write(path, text)


# ModelManagerViewModel: persist model download/import/initialization evidence into the same
# copyable diagnostic stream used by Agent execution.
rel = "app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt"
path, text = read(rel)
if "customtasks.agentchat.AgentDiagnosticsLogger" not in text:
    text = text.replace(
        "import com.google.ai.edge.gallery.customtasks.aikeyboard.createAiKeyboardSettingsModel\n",
        "import com.google.ai.edge.gallery.customtasks.aikeyboard.createAiKeyboardSettingsModel\n"
        "import com.google.ai.edge.gallery.customtasks.agentchat.AgentDiagnosticsLogger\n"
        "import com.google.ai.edge.gallery.customtasks.agentchat.getConfiguredAgentToolMode\n"
        "import com.google.ai.edge.gallery.customtasks.agentchat.resolveAgentToolMode\n",
        1,
    )

init_marker = "      val initializeTargetModel: suspend () -> String = {\n"
if "category = \"model.initialize.request\"" not in text and init_marker in text:
    text = text.replace(
        init_marker,
        '''      AgentDiagnosticsLogger.log(
        context = context,
        category = "model.initialize.request",
        message = "Model initialization requested: ${model.name}",
        detail =
          "task=${task.id}\\nmodelId=${model.modelId}\\nfile=${model.downloadFileName}\\n" +
            "path=${runCatching { model.getPath(context) }.getOrElse { "<path-error:${it.message}>" }}\\n" +
            "expectedBytes=${model.totalBytes}\\nactualBytes=${runCatching { java.io.File(model.getPath(context)).length() }.getOrDefault(-1L)}\\n" +
            "backend=${model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)}\\n" +
            "context=${model.getConfiguredContextWindow()}\\nmaxTokens=${model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = model.llmMaxToken)}\\n" +
            "agentModeConfigured=${runCatching { getConfiguredAgentToolMode(model) }.getOrDefault("n/a")}\\n" +
            "agentModeResolved=${runCatching { resolveAgentToolMode(model).toString() }.getOrDefault("n/a")}",
      )

      val initializeTargetModel: suspend () -> String = {
''',
        1,
    )

attempt_marker = "        Log.d(TAG, \"Initializing model '${model.name}'... attempt=${attempt + 1}\")\n"
if "category = \"model.initialize.attempt\"" not in text and attempt_marker in text:
    text = text.replace(
        attempt_marker,
        attempt_marker + '''        AgentDiagnosticsLogger.log(
          context = context,
          category = "model.initialize.attempt",
          message = "Initializing ${model.name}",
          detail = "attempt=${attempt + 1}\\ntask=${task.id}",
        )
''',
        1,
    )

success_marker = "          Log.d(TAG, \"Model '${model.name}' initialized successfully on attempt ${attempt + 1}\")\n"
if "category = \"model.initialize.success\"" not in text and success_marker in text:
    text = text.replace(
        success_marker,
        success_marker + '''          AgentDiagnosticsLogger.log(
            context = context,
            category = "model.initialize.success",
            message = "Model initialized successfully: ${model.name}",
            detail = "attempt=${attempt + 1}\\ntask=${task.id}",
          )
''',
        1,
    )

error_marker = "          Log.d(TAG, \"Model '${model.name}' failed to initialize on attempt ${attempt + 1}: $error\")\n"
if "category = \"model.initialize.failure\"" not in text and error_marker in text:
    text = text.replace(
        error_marker,
        error_marker + '''          AgentDiagnosticsLogger.log(
            context = context,
            category = "model.initialize.failure",
            message = "Model initialization failed: ${model.name}",
            detail = "attempt=${attempt + 1}\\ntask=${task.id}\\nerror=$error",
          )
''',
        1,
    )

download_marker = "  fun setDownloadStatus(curModel: Model, status: ModelDownloadStatus) {\n"
if "category = \"model.download.status\"" not in text and download_marker in text:
    text = text.replace(
        download_marker,
        '''  fun setDownloadStatus(curModel: Model, status: ModelDownloadStatus) {
    if (status.status == ModelDownloadStatusType.FAILED || status.status == ModelDownloadStatusType.SUCCEEDED) {
      AgentDiagnosticsLogger.log(
        context = context,
        category = "model.download.status",
        message = "Download ${status.status}: ${curModel.name}",
        detail =
          "modelId=${curModel.modelId}\\nfile=${curModel.downloadFileName}\\nreceivedBytes=${status.receivedBytes}\\n" +
            "totalBytes=${status.totalBytes}\\nerror=${status.errorMessage}",
      )
    }
''',
        1,
    )

import_marker = "  fun addImportedLlmModel(info: ImportedModel) {\n    Log.d(TAG, \"adding imported llm model: $info\")\n"
if "category = \"model.import.added\"" not in text and import_marker in text:
    text = text.replace(
        import_marker,
        import_marker + '''    AgentDiagnosticsLogger.log(
      context = context,
      category = "model.import.added",
      message = "Imported LiteRT-LM model",
      detail = "file=${info.fileName}\\nfileSize=${info.fileSize}\\ninfo=$info",
    )
''',
        1,
    )
write(path, text)


# Model card overflow menu: diagnostics remain copyable even if Engine initialization fails before
# the chat screen can open.
rel = "app/src/main/java/com/google/ai/edge/gallery/ui/common/modelitem/ModelItem.kt"
path, text = read(rel)
if "customtasks.agentchat.AgentDiagnosticsLogger" not in text:
    text = text.replace(
        "import com.google.ai.edge.gallery.R\n",
        "import com.google.ai.edge.gallery.R\n"
        "import com.google.ai.edge.gallery.customtasks.agentchat.AgentDiagnosticsLogger\n"
        "import android.widget.Toast\n",
        1,
    )
menu_marker = "      if (showDeleteButton) {\n"
if "复制诊断信息" not in text and menu_marker in text:
    text = text.replace(
        menu_marker,
        '''      DropdownMenuItem(
        text = { Text("复制诊断信息") },
        onClick = {
          showMenu = false
          val chars = AgentDiagnosticsLogger.copyLatestToClipboard(context)
          Toast.makeText(context, "已复制诊断信息（${chars} 字符）", Toast.LENGTH_SHORT).show()
        },
      )
      if (showDeleteButton) {
''',
        1,
    )
write(path, text)
