/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.common.AgentAction
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.CallJsSkillResult
import com.google.ai.edge.gallery.common.CallJsSkillResultImage
import com.google.ai.edge.gallery.common.CallJsSkillResultWebview
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AGAgentTools"
private const val MAX_LIST_SUMMARY_ENTRIES = 12

open class AgentTools() : ToolSet {
  lateinit var context: Context
  lateinit var skillManagerViewModel: SkillManagerViewModel
  lateinit var mcpManagerViewModel: McpManagerViewModel

  fun isMcpInitialized(): Boolean = ::mcpManagerViewModel.isInitialized

  private val _actionChannel = Channel<AgentAction>(Channel.UNLIMITED)
  val actionChannel: ReceiveChannel<AgentAction> = _actionChannel
  var resultImageToShow: CallJsSkillResultImage? = null
  var resultWebviewToShow: CallJsSkillResultWebview? = null

  data class CompatToolExecutionResult(
    val toolName: String,
    val result: Map<String, Any?>,
  )

  /** Loads skill. */
  @Tool(description = "Loads a skill.")
  fun loadSkill(
    @ToolParam(description = "The name of the skill to load.") skillName: String
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      AgentDiagnosticsLogger.log(
        context = context,
        category = "tool.load_skill.start",
        message = "Loading skill $skillName",
      )
      val skills = skillManagerViewModel.getSelectedSkills()
      val skill = skills.find { it.name == skillName.trim() }
      val skillContent =
        if (skill != null) {
          "---\nname: ${skill.name}\ndescription: ${skill.description}\n---\n\n${skill.instructions}"
        } else {
          "Skill not found"
        }
      Log.d(TAG, "load skill. Skill content:\n$skillContent")
      if (skill != null) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Loading skill \"$skillName\"",
            inProgress = true,
            addItemTitle = "Load \"${skill.name}\"",
            addItemDescription = "Description: ${skill.description}",
            customData = skill,
          )
        )
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Loaded skill \"$skillName\"",
            inProgress = false,
            addItemTitle = "Skill ready",
            addItemDescription = "Instruction length: ${skill.instructions.length} chars",
          )
        )
        AgentDiagnosticsLogger.log(
          context = context,
          category = "tool.load_skill.success",
          message = "Loaded skill $skillName",
          detail = "instruction_length=${skill.instructions.length}",
        )
      } else {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to load skill \"$skillName\"",
            inProgress = false,
            addItemTitle = "Error",
            addItemDescription = "Skill not found.",
          )
        )
        AgentDiagnosticsLogger.log(
          context = context,
          category = "tool.load_skill.failed",
          message = "Failed to load skill $skillName",
          detail = "skill_not_found",
        )
      }

      mapOf("skill_name" to skillName, "skill_instructions" to skillContent)
    }
  }

  /** Call JS skill */
  @Tool(description = "Runs JS script")
  fun runJs(
    @ToolParam(description = "The name of skill") skillName: String,
    @ToolParam(description = "The script name to run. Use 'index.html' if not provided by user")
    scriptName: String,
    @ToolParam(
      description = "The data to pass to the script. Use empty string if not provided by user"
    )
    data: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      Log.d(
        TAG,
        "runJS tool called with:" +
          "\n- skillName: ${skillName}\n- scriptName: ${scriptName}\n- data: ${data}\n",
      )
      AgentDiagnosticsLogger.log(
        context = context,
        category = "tool.run_js.start",
        message = "Calling JS skill ${skillName}/${scriptName}",
        detail = "data=$data",
      )

      val skills = skillManagerViewModel.getSelectedSkills()
      val skill = skills.find { it.name == skillName.trim() }

      if (skill == null) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to call skill \"$scriptName\"",
            inProgress = false,
            addItemTitle = "Error",
            addItemDescription = "Skill not found.",
          )
        )
        AgentDiagnosticsLogger.log(
          context = context,
          category = "tool.run_js.failed",
          message = "Failed to find JS skill ${skillName}/${scriptName}",
        )
        return@runBlocking mapOf(
          "error" to "Skill \"${scriptName}\" not found",
          "status" to "failed",
        )
      }

      // Check secret. If a skill requires a secret and the secret is not provided, show error.
      var secret = ""
      if (skill.requireSecret) {
        val savedSecret =
          skillManagerViewModel.dataStoreRepository.readSecret(
            key = getSkillSecretKey(skillName = skillName)
          )
        if (savedSecret == null || savedSecret.isEmpty()) {
          val action =
            AskInfoAgentAction(
              dialogTitle = "Enter secret",
              fieldLabel =
                skill.requireSecretDescription.ifEmpty {
                  "The JS script needs a secret (API key / token) to proceed:"
                },
            )
          _actionChannel.send(action)
          secret = action.result.await()
          if (secret.isNotEmpty()) {
            skillManagerViewModel.dataStoreRepository.saveSecret(
              key = getSkillSecretKey(skillName = skillName),
              value = secret,
            )
            Log.d(TAG, "Got Secret from ask info dialog: ${secret.substring(0, 3)}")
          } else {
            Log.d(TAG, "The ask info dialog got cancelled. No secret.")
          }
        } else {
          secret = savedSecret
        }
      }

      // Get the url for the skill.
      val url =
        skillManagerViewModel.getJsSkillUrl(skillName = skillName, scriptName = scriptName)
          ?: return@runBlocking mapOf(
            "result" to "JS Skill URL not set properly or skill not found"
          )
      val config =
        skillManagerViewModel.dataStoreRepository.readSecret(
          key = getSkillConfigKey(skillName = skillName)
        ) ?: ""
      Log.d(TAG, "Calling JS script.\n- url: $url\n- data: $data")

      // Update progress.
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Calling JS script \"${skillName}/${scriptName}\"",
          inProgress = true,
          addItemTitle = "Call JS script: \"${skillName}/${scriptName}\"",
          addItemDescription = "- URL: ${url.replace(LOCAL_URL_BASE, "")}\n- Data: $data",
          customData = skill,
        )
      )

      // Actually run it and wait for the result.
      val action =
        CallJsAgentAction(
          url = url,
          data = data.trim().ifEmpty { "{}" },
          secret = secret,
          config = config,
        )
      _actionChannel.send(action)
      val result =
        try {
          action.result.await()
        } catch (e: Exception) {
          val errorMessage = e.message ?: "Failed to execute JS skill."
          _actionChannel.send(
            SkillProgressAgentAction(
              label = "Failed to call skill \"$skillName/$scriptName\"",
              inProgress = false,
              addItemTitle = "Error",
              addItemDescription = errorMessage,
            )
          )
          AgentDiagnosticsLogger.log(
            context = context,
            category = "tool.run_js.failed",
            message = "JS skill ${skillName}/${scriptName} threw",
            detail = errorMessage,
          )
          return@runBlocking mapOf(
            "error" to errorMessage,
            "status" to "failed",
          )
        }

      // Try to parse result to CallJsSkillResult.
      val moshi: Moshi = Moshi.Builder().build()
      val jsonAdapter: JsonAdapter<CallJsSkillResult> =
        moshi.adapter(CallJsSkillResult::class.java).failOnUnknown()
      val resultJson = runCatching { jsonAdapter.fromJson(result) }.getOrNull()
      val error = resultJson?.error

      // Failed to parse. Treat its whole as a result string.
      if (
        resultJson == null ||
          (resultJson.result == null && resultJson.webview == null && resultJson.image == null)
      ) {
        val shortResult = result.take(500)
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Called JS script \"$skillName/$scriptName\"",
            inProgress = false,
            addItemTitle = "JS result",
            addItemDescription = shortResult,
          )
        )
        AgentDiagnosticsLogger.log(
          context = context,
          category = "tool.run_js.success",
          message = "JS skill ${skillName}/${scriptName} returned raw text",
          detail = shortResult,
        )
        mapOf("result" to result, "status" to "succeeded")
      }
      // Error case.
      else if (error != null) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to call skill \"$skillName/$scriptName\"",
            inProgress = false,
            addItemTitle = "Error",
            addItemDescription = error,
          )
        )
        AgentDiagnosticsLogger.log(
          context = context,
          category = "tool.run_js.failed",
          message = "JS skill ${skillName}/${scriptName} returned error",
          detail = error,
        )
        mapOf("error" to error, "status" to "failed")
      }
      // Non-error cases.
      else {
        // Handle image and webview in result.
        val image = resultJson.image
        val webview = resultJson.webview
        if (image != null) {
          Log.d(TAG, "Got an image response.")
          resultImageToShow = image
        }
        if (webview != null) {
          Log.d(TAG, "Got an webview response.")
          val webviewUrl =
            skillManagerViewModel.getJsSkillWebviewUrl(
              skillName = skillName,
              url = webview.url ?: "",
            )
          Log.d(TAG, "Webview url: $webviewUrl")
          resultWebviewToShow = webview.copy(url = webviewUrl)
        }
        Log.d(TAG, "Result: ${resultJson.result}")
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Called JS script \"$skillName/$scriptName\"",
            inProgress = false,
            addItemTitle = "JS result",
            addItemDescription = (resultJson.result ?: "").take(500),
          )
        )
        AgentDiagnosticsLogger.log(
          context = context,
          category = "tool.run_js.success",
          message = "JS skill ${skillName}/${scriptName} returned structured result",
          detail = (resultJson.result ?: "").take(1000),
        )
        mapOf("result" to (resultJson.result ?: ""), "status" to "succeeded")
      }
    }
  }

  @Tool(
    description =
      "Run an Android intent. It is used to interact with the app to perform certain actions."
  )
  fun runIntent(
    @ToolParam(description = "The intent to run.") intent: String,
    @ToolParam(
      description = "A JSON string containing the parameter values required for the intent."
    )
    parameters: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      requiredSkillForIntent(intent)?.let { requiredSkill ->
        if (!skillManagerViewModel.isSkillSelected(requiredSkill)) {
          val error = "Skill \"$requiredSkill\" is disabled. Enable it in the skill manager before using this intent."
          AgentDiagnosticsLogger.log(
            context = context,
            category = "tool.run_intent.blocked",
            message = "Blocked intent $intent because $requiredSkill is disabled",
          )
          return@runBlocking mapOf(
            "action" to intent,
            "parameters" to parameters,
            "result" to JSONObject().put("status", "failed").put("error", error).toString(),
          )
        }
      }
      AgentDiagnosticsLogger.log(
        context = context,
        category = "tool.run_intent.start",
        message = "Executing intent $intent",
        detail = parameters,
      )
      Log.d(TAG, "Run intent. Intent: '$intent', parameters: '$parameters'")
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Executing intent \"$intent\"",
          inProgress = true,
          addItemTitle = "Execute intent \"$intent\"",
          addItemDescription = "Parameters: $parameters",
        )
      )
      val res = IntentHandler.handleAction(context, intent, parameters)
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Executed intent \"$intent\"",
          inProgress = false,
          addItemTitle = "Intent result",
          addItemDescription = res.take(500),
        )
      )
      AgentDiagnosticsLogger.logJson(
        context = context,
        category = "tool.run_intent.done",
        message = "Intent $intent finished",
        rawJson = res,
      )
      return@runBlocking mapOf("action" to intent, "parameters" to parameters, "result" to res)
    }
  }

  @Tool(
    description =
      "Runs an Android intent with the saved configuration of the specified skill. Use this when a skill needs its own mounted folder or other stored settings."
  )
  fun runConfiguredIntent(
    @ToolParam(description = "The name of the skill that owns the saved configuration.")
    skillName: String,
    @ToolParam(description = "The intent to run.") intent: String,
    @ToolParam(
      description = "A JSON string containing the parameter values required for the intent."
    )
    parameters: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      AgentDiagnosticsLogger.log(
        context = context,
        category = "tool.run_configured_intent.start",
        message = "Executing configured intent $intent for $skillName",
        detail = parameters,
      )
      val skill = skillManagerViewModel.getSelectedSkills().find { it.name == skillName.trim() }
      if (skill == null) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to execute configured intent \"$intent\"",
            inProgress = false,
          )
        )
        return@runBlocking mapOf(
          "action" to intent,
          "status" to "failed",
          "error" to "Skill \"$skillName\" not found.",
        )
      }

      val config =
        skillManagerViewModel.dataStoreRepository.readSecret(
          key = getSkillConfigKey(skillName = skillName)
        ) ?: ""

      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Executing configured intent \"$intent\"",
          inProgress = true,
          addItemTitle = "Execute configured intent \"$intent\"",
          addItemDescription = "Skill: $skillName\nParameters: $parameters",
          customData = skill,
        )
      )
      val res =
        IntentHandler.handleConfiguredAction(
          context = context,
          skillName = skillName,
          action = intent,
          parameters = parameters,
          config = config,
        )
      val flattened = buildConfiguredIntentResult(intent = intent, parameters = parameters, result = res)
      val summary = (flattened["summary"] as? String) ?: (flattened["error"] as? String).orEmpty()
      _actionChannel.send(
        SkillProgressAgentAction(
          label =
            if ((flattened["status"] as? String) == "succeeded") {
              "Executed configured intent \"$intent\""
            } else {
              "Failed to execute configured intent \"$intent\""
            },
          inProgress = false,
          addItemTitle = if ((flattened["status"] as? String) == "succeeded") "Intent result" else "Intent error",
          addItemDescription = summary,
        )
      )
      AgentDiagnosticsLogger.logJson(
        context = context,
        category = "tool.run_configured_intent.raw",
        message = "Configured intent $intent raw result",
        rawJson = res,
      )
      AgentDiagnosticsLogger.log(
        context = context,
        category = "tool.run_configured_intent.flattened",
        message = "Configured intent $intent flattened result",
        detail = flattened.toString(),
      )
      return@runBlocking flattened
    }
  }

  @Tool(
    description =
      "Writes one txt or md file inside the mounted workspace using the saved long-text-writer configuration. Use this for both short and long document writing."
  )
  fun writeWorkspaceTextFile(
    @ToolParam(description = "Workspace-relative file path ending in .txt or .md.")
    path: String,
    @ToolParam(description = "The full final text to write into the file.")
    content: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val skillName = LONG_TEXT_WRITER_SKILL_NAME
      val trimmedPath = normalizeWorkspacePathForOperation(operation = "write_text", path = path.trim())
      if (trimmedPath.isBlank()) {
        return@runBlocking mapOf(
          "action" to "write_workspace_text_file",
          "status" to "failed",
          "error" to "Path is required.",
          "recovery_hint" to "Retry with a workspace-relative .txt or .md file path.",
        )
      }
      if (
        !trimmedPath.lowercase().endsWith(".txt") &&
          !trimmedPath.lowercase().endsWith(".md")
      ) {
        return@runBlocking mapOf(
          "action" to "write_workspace_text_file",
          "status" to "failed",
          "error" to "Only .txt and .md files are supported.",
          "recovery_hint" to "Retry with a workspace-relative .txt or .md file path.",
        )
      }
      if (content.contains("__ASSISTANT_RESPONSE__")) {
        return@runBlocking mapOf(
          "action" to "write_workspace_text_file",
          "status" to "failed",
          "error" to "Do not use __ASSISTANT_RESPONSE__.",
          "recovery_hint" to "Put the full final text directly into content.",
        )
      }

      val skill =
        skillManagerViewModel.getSelectedSkills().find { it.name == skillName.trim() }
      if (skill == null) {
        return@runBlocking mapOf(
          "action" to "write_workspace_text_file",
          "status" to "failed",
          "error" to "Skill \"$skillName\" not found.",
        )
      }

      val parameters =
        JSONObject()
          .put("operation", "write_text")
          .put("path", trimmedPath)
          .put("content", content)
          .toString()
      AgentDiagnosticsLogger.log(
        context = context,
        category = "tool.write_workspace_text.start",
        message = "Writing workspace text file for $skillName",
        detail = parameters.take(2000),
      )
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Executing long text write",
          inProgress = true,
          addItemTitle = "Write workspace text file",
          addItemDescription = "Skill: $skillName\nParameters: $parameters",
          customData = skill,
        )
      )
      val config =
        skillManagerViewModel.dataStoreRepository.readSecret(
          key = getSkillConfigKey(skillName = skillName)
        ) ?: ""
      val res =
        IntentHandler.handleConfiguredAction(
          context = context,
          skillName = skillName,
          action = IntentAction.FILE_WORKSPACE.action,
          parameters = parameters,
          config = config,
        )
      val flattened =
        buildConfiguredIntentResult(
          intent = IntentAction.FILE_WORKSPACE.action,
          parameters = parameters,
          result = res,
        )
      val summary = (flattened["summary"] as? String) ?: (flattened["error"] as? String).orEmpty()
      _actionChannel.send(
        SkillProgressAgentAction(
          label =
            if ((flattened["status"] as? String) == "succeeded") {
              "Executed long text write"
            } else {
              "Failed long text write"
            },
          inProgress = false,
          addItemTitle = if ((flattened["status"] as? String) == "succeeded") "Intent result" else "Intent error",
          addItemDescription = summary,
        )
      )
      AgentDiagnosticsLogger.logJson(
        context = context,
        category = "tool.write_workspace_text.raw",
        message = "Long text writer raw result",
        rawJson = res,
      )
      AgentDiagnosticsLogger.log(
        context = context,
        category = "tool.write_workspace_text.flattened",
        message = "Long text writer flattened result",
        detail = flattened.toString(),
      )
      return@runBlocking flattened
    }
  }

  fun queryWeather(
    location: String,
    mode: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.IO) {
      try {
        AgentWeatherSupport.query(context = context, location = location, mode = mode)
      } catch (e: Exception) {
        mapOf(
          "status" to "failed",
          "operation" to "weather_query",
          "error" to (e.message ?: "Weather query failed."),
          "recovery_hint" to "Retry with a city name and mode current, 24h, or week.",
        )
      }
    }
  }

  fun listEdgeTtsVoices(): Map<String, Any> {
    return mapOf(
      "status" to "succeeded",
      "operation" to "edge_tts_list_voices",
      "voices" to AgentEdgeTtsSupport.voicesJson().toString(),
      "summary" to
        AgentEdgeTtsSupport.voices.joinToString("; ") {
          "${it.id} (${it.locale}, ${it.description})"
        },
    )
  }

  fun edgeTtsSynthesize(
    text: String,
    inputPath: String,
    voice: String,
    outputPath: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.IO) {
      val parameters =
        JSONObject()
          .put("operation", "edge_tts_synthesize")
          .put("text", text)
          .put("input_path", inputPath)
          .put("voice", voice.ifBlank { "zh-CN-XiaoxiaoNeural" })
          .put("output_path", outputPath)
          .toString()
      val flattened =
        runConfiguredIntent(
          skillName = EDGE_TTS_SKILL_NAME,
          intent = IntentAction.FILE_WORKSPACE.action,
          parameters = parameters,
        )
      flattened
    }
  }

  fun executeCompatToolCall(
    toolName: String,
    arguments: JSONObject,
  ): CompatToolExecutionResult {
    val normalizedToolName = toolName.trim().lowercase()
    val result: Map<String, Any?> =
      when (normalizedToolName) {
        "load_skill" ->
          loadSkill(
              skillName =
                getRequiredStringArgument(
                  arguments = arguments,
                  names = listOf("skill_name", "skillName"),
                )
            )
            .mapValues { it.value }
        "run_js" ->
          runJs(
              skillName =
                getRequiredStringArgument(
                  arguments = arguments,
                  names = listOf("skill_name", "skillName"),
                ),
              scriptName =
                getOptionalStringArgument(
                  arguments = arguments,
                  names = listOf("script_name", "scriptName"),
                  defaultValue = "index.html",
                ),
              data =
                getOptionalStringArgument(
                  arguments = arguments,
                  names = listOf("data"),
                  defaultValue = "",
                ),
            )
            .mapValues { it.value }
        "run_intent" ->
          runIntent(
              intent =
                getRequiredStringArgument(
                  arguments = arguments,
                  names = listOf("intent"),
                ),
              parameters =
                getOptionalStringArgument(
                  arguments = arguments,
                  names = listOf("parameters"),
                  defaultValue = "{}",
                ),
            )
            .mapValues { it.value }
        "run_configured_intent" ->
          runConfiguredIntent(
              skillName =
                getRequiredStringArgument(
                  arguments = arguments,
                  names = listOf("skill_name", "skillName"),
                ),
              intent =
                getRequiredStringArgument(
                  arguments = arguments,
                  names = listOf("intent"),
                ),
              parameters =
                getOptionalStringArgument(
                  arguments = arguments,
                  names = listOf("parameters"),
                  defaultValue = "{}",
                ),
            )
            .mapValues { it.value }
        "query_weather",
        "weather_query",
        "get_weather" ->
          if (!skillManagerViewModel.isSkillSelected(WEATHER_QUERY_SKILL_NAME)) {
            mapOf(
              "status" to "failed",
              "error" to "Skill \"$WEATHER_QUERY_SKILL_NAME\" is disabled. Enable it before using weather tools.",
            )
          } else queryWeather(
              location =
                getOptionalStringArgument(
                  arguments = arguments,
                  names = listOf("location", "city", "place", "地区", "城市"),
                  defaultValue = "",
                ),
              mode =
                getOptionalStringArgument(
                  arguments = arguments,
                  names = listOf("mode", "type", "forecast", "range"),
                  defaultValue = "current",
                ),
            )
            .mapValues { it.value }
        "list_edge_tts_voices",
        "edge_tts_list_voices",
        "list_tts_voices" ->
          if (!skillManagerViewModel.isSkillSelected(EDGE_TTS_SKILL_NAME)) {
            mapOf(
              "status" to "failed",
              "error" to "Skill \"$EDGE_TTS_SKILL_NAME\" is disabled. Enable it before using Edge TTS.",
            )
          } else listEdgeTtsVoices().mapValues { it.value }
        "edge_tts_synthesize",
        "text_to_speech",
        "synthesize_speech",
        "tts_synthesize" ->
          if (!skillManagerViewModel.isSkillSelected(EDGE_TTS_SKILL_NAME)) {
            mapOf(
              "status" to "failed",
              "error" to "Skill \"$EDGE_TTS_SKILL_NAME\" is disabled. Enable it before using Edge TTS.",
            )
          } else edgeTtsSynthesize(
              text =
                getOptionalStringArgument(
                  arguments = arguments,
                  names = listOf("text", "content", "input"),
                  defaultValue = "",
                ),
              inputPath =
                getOptionalStringArgument(
                  arguments = arguments,
                  names = listOf("input_path", "inputPath", "source_path", "sourcePath"),
                  defaultValue = "",
                ),
              voice =
                getOptionalStringArgument(
                  arguments = arguments,
                  names = listOf("voice", "voice_id", "voiceId"),
                  defaultValue = "zh-CN-XiaoxiaoNeural",
                ),
              outputPath =
                getOptionalStringArgument(
                  arguments = arguments,
                  names = listOf("output_path", "outputPath", "path"),
                  defaultValue = "",
                ),
            )
            .mapValues { it.value }
        TAVILY_SEARCH_SKILL_NAME,
        EXA_SEARCH_SKILL_NAME,
        LANGSEARCH_SEARCH_SKILL_NAME ->
          runSearchCompatTool(skillName = normalizedToolName, arguments = arguments)
        "search_web",
        "web_search" ->
          runSearchCompatTool(
            skillName =
              getOptionalStringArgument(
                  arguments = arguments,
                  names = listOf("skill_name", "skillName", "engine", "provider"),
                  defaultValue =
                    preferredEnabledSearchSkillName(
                      query =
                        getOptionalStringArgument(
                          arguments = arguments,
                          names = listOf("query", "search_query", "searchQuery", "q", "input", "topic"),
                          defaultValue = "",
                        )
                    ),
                )
                .lowercase(),
            arguments = arguments,
          )
        "list_workspace" ->
          runFileWorkspaceCompatOperation(
            operation = "list",
            arguments = arguments,
            defaultPath = ".",
          )
        "read_workspace_text_file" ->
          runFileWorkspaceCompatOperation(operation = "read_text", arguments = arguments)
        "download_workspace_file",
        "download_file_to_workspace",
        "download_url_to_workspace" ->
          runFileWorkspaceCompatOperation(operation = "download_url", arguments = arguments)
        "write_workspace_text_file",
        "write_workspace_file" ->
          runFileWorkspaceCompatOperation(operation = "write_text", arguments = arguments)
        "append_workspace_text_file",
        "append_workspace_file" ->
          runFileWorkspaceCompatOperation(operation = "append_text", arguments = arguments)
        "create_workspace_dir",
        "create_workspace_directory" ->
          runFileWorkspaceCompatOperation(operation = "create_dir", arguments = arguments)
        "delete_workspace_file",
        "delete_workspace_text_file",
        "delete_workspace_path" ->
          runFileWorkspaceCompatOperation(operation = "delete", arguments = arguments)
        "stat_workspace_file",
        "stat_workspace_path" ->
          runFileWorkspaceCompatOperation(operation = "stat", arguments = arguments)
        else ->
          mapOf(
            "status" to "failed",
            "error" to "Unknown compatibility tool \"$toolName\".",
            "recovery_hint" to "Retry only with a compatibility tool listed in the current system instruction. Disabled skills are not available.",
          )
      }
    return CompatToolExecutionResult(toolName = normalizedToolName, result = result)
  }

  private fun runFileWorkspaceCompatOperation(
    operation: String,
    arguments: JSONObject,
    defaultPath: String? = null,
  ): Map<String, Any?> {
    val parameters =
      JSONObject(arguments.toString())
        .apply {
          put("operation", operation)
          if (defaultPath != null && !has("path")) {
            put("path", defaultPath)
          }
          val currentPath = optString("path")
          val normalizedPath = normalizeWorkspacePathForOperation(operation = operation, path = currentPath)
          if (normalizedPath != currentPath) {
            put("path", normalizedPath)
          }
        }
        .toString()
    return runConfiguredIntent(
        skillName = FILE_WORKSPACE_SKILL_NAME,
        intent = IntentAction.FILE_WORKSPACE.action,
        parameters = parameters,
      )
      .mapValues { it.value }
  }

  private fun normalizeWorkspacePathForOperation(operation: String, path: String): String {
    val trimmed = path.replace('\\', '/').trim()
    if (trimmed.isBlank() || trimmed == "." || trimmed.startsWith("/")) {
      return trimmed
    }
    if ('/' in trimmed) {
      return trimmed
    }
    return when (operation) {
      "write_text", "append_text" -> "file/$trimmed"
      "download_url" -> "download/$trimmed"
      else -> trimmed
    }
  }

  fun saveCompatToolAudit(
    toolName: String,
    originalUserRequest: String,
    result: Map<String, Any?>,
    modelPrompt: String,
  ): String? {
    return runBlocking(Dispatchers.IO) {
      val config =
        skillManagerViewModel.dataStoreRepository.readSecret(
          key = getSkillConfigKey(skillName = FILE_WORKSPACE_SKILL_NAME)
        ) ?: return@runBlocking null
      val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
      val safeToolName = toolName.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { "tool" }
      val path = "tool-audit/$timestamp-$safeToolName.json"
      val content =
        JSONObject()
          .put("timestamp", timestamp)
          .put("tool_name", toolName)
          .put("original_user_request", originalUserRequest)
          .put("model_prompt", modelPrompt)
          .put("tool_result", JSONObject(result))
          .toString(2)
      val parameters =
        JSONObject()
          .put("operation", "write_text")
          .put("path", path)
          .put("content", content)
          .put("create_parents", true)
          .toString()
      val writeResult =
        IntentHandler.handleConfiguredAction(
          context = context,
          skillName = FILE_WORKSPACE_SKILL_NAME,
          action = IntentAction.FILE_WORKSPACE.action,
          parameters = parameters,
          config = config,
        )
      val status = runCatching { JSONObject(writeResult).optString("status") }.getOrDefault("")
      if (status == "succeeded") path else null
    }
  }

  private fun runSearchCompatTool(skillName: String, arguments: JSONObject): Map<String, Any?> {
    val normalizedSkillName = skillName.trim().lowercase()
    if (!isSearchSkill(normalizedSkillName)) {
      return mapOf(
        "status" to "failed",
        "error" to "Unsupported search skill \"$skillName\".",
        "recovery_hint" to "Use exa-search, tavily-search, langsearch-search, or search_web.",
      )
    }
    val selectedSkill = skillManagerViewModel.getSelectedSkills().find { it.name == normalizedSkillName }
    if (selectedSkill == null) {
      return mapOf(
        "status" to "failed",
        "error" to "Skill \"$normalizedSkillName\" is not enabled in this session.",
        "recovery_hint" to "Enable $normalizedSkillName in the skill manager or call another enabled search tool.",
      )
    }
    val query =
      getOptionalStringArgument(
        arguments = arguments,
        names = listOf("query", "search_query", "searchQuery", "q", "input", "topic"),
        defaultValue = "",
      )
    if (query.isBlank()) {
      return mapOf(
        "status" to "failed",
        "error" to "Search query is required.",
        "recovery_hint" to "Retry with arguments {\"query\":\"...\"}.",
      )
    }
    val data =
      JSONObject(arguments.toString())
        .apply {
          put("query", query)
          remove("skill_name")
          remove("skillName")
          remove("engine")
          remove("provider")
        }
        .toString()
    return runJs(
        skillName = normalizedSkillName,
        scriptName = "index.html",
        data = data,
      )
      .mapValues { it.value }
  }

  private fun preferredEnabledSearchSkillName(query: String = ""): String {
    val selected = skillManagerViewModel.getSelectedSkills().map { it.name }.toSet()
    val preferredOrder =
      if (query.any { it in '\u4e00'..'\u9fff' }) {
        listOf(LANGSEARCH_SEARCH_SKILL_NAME, EXA_SEARCH_SKILL_NAME, TAVILY_SEARCH_SKILL_NAME)
      } else {
        listOf(EXA_SEARCH_SKILL_NAME, TAVILY_SEARCH_SKILL_NAME, LANGSEARCH_SEARCH_SKILL_NAME)
      }
    return preferredOrder
      .firstOrNull { selected.contains(it) }
      ?: EXA_SEARCH_SKILL_NAME
  }

  @Tool(description = "Run a MCP tool")
  fun runMcpTool(
    @ToolParam(description = "The name of the tool to run.") toolName: String,
    @ToolParam(description = "The parameters passed to tool as input") input: String,
  ): Map<String, String> {
    Log.d(TAG, "Run MCP tool:\n- name: $toolName\n- input: $input")

    return runBlocking(Dispatchers.IO) {
      if (!::mcpManagerViewModel.isInitialized) {
        return@runBlocking mapOf("error" to "MCP not initialized", "status" to "failed")
      }
      val matchingServerStates =
        mcpManagerViewModel.uiState.value.mcpServers.filter { serverState ->
          serverState.mcpServer.enabled &&
            serverState.client != null &&
            serverState.mcpServer.toolsList.any { it.enabled && it.name == toolName }
        }

      if (matchingServerStates.size > 1) {
        val serverUrls = matchingServerStates.joinToString { it.mcpServer.url }
        Log.w(TAG, "Ambiguous MCP tool name \"$toolName\" on: $serverUrls")
        return@runBlocking mapOf(
          "error" to
            "More than one enabled MCP server exposes tool \"$toolName\". Disable duplicate tools or servers first: $serverUrls",
          "status" to "failed",
        )
      }

      val serverState = matchingServerStates.singleOrNull()

      if (serverState == null) {
        Log.w(TAG, "MCP server or tool not found for: $toolName")
        return@runBlocking mapOf("error" to "Tool not found", "status" to "failed")
      }

      val client =
        serverState.client
          ?: return@runBlocking mapOf("error" to "Client not initialized", "status" to "failed")
      try {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Calling MCP tool \"$toolName\"",
            inProgress = true,
            addItemTitle = "Call MCP tool: \"$toolName\"",
            addItemDescription = "- Input: $input",
          )
        )
        val result =
          client.callTool(
            request =
              CallToolRequest(
                CallToolRequestParams(
                  name = toolName,
                  arguments = Json.parseToJsonElement(input.ifBlank { "{}" }).jsonObject,
                )
              )
          )

        if (result == null) {
          _actionChannel.send(
            SkillProgressAgentAction(
              label = "Failed to call MCP tool \"$toolName\"",
              inProgress = false,
            )
          )
          return@runBlocking mapOf("error" to "Null result", "status" to "failed")
        }

        if (result.isError == true) {
          val errorText =
            result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
          _actionChannel.send(
            SkillProgressAgentAction(
              label = "Failed to call MCP tool \"$toolName\"",
              addItemTitle = "Call MCP tool \"$toolName\" failed",
              addItemDescription = errorText,
              inProgress = false,
            )
          )
          return@runBlocking mapOf("error" to errorText, "status" to "failed")
        } else {
          val successText =
            result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
          _actionChannel.send(
            SkillProgressAgentAction(
              label = "Succeeded calling MCP tool \"$toolName\"",
              inProgress = true,
              addItemTitle = "Call MCP tool \"$toolName\" succeeded",
              addItemDescription = successText,
            )
          )
          return@runBlocking mapOf("result" to successText, "status" to "succeeded")
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error calling MCP tool", e)
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Error calling MCP tool \"$toolName\"",
            inProgress = false,
            addItemTitle = "Call MCP tool \"$toolName\" failed",
            addItemDescription = e.message ?: "Unknown error",
          )
        )
        return@runBlocking mapOf("error" to (e.message ?: "Unknown error"), "status" to "failed")
      }
    }
  }

  fun sendAgentAction(action: AgentAction) {
    runBlocking(Dispatchers.Default) { _actionChannel.send(action) }
  }
}

private fun getRequiredStringArgument(arguments: JSONObject, names: List<String>): String {
  for (name in names) {
    val value = arguments.optString(name).trim()
    if (value.isNotEmpty()) {
      return value
    }
  }
  throw IllegalArgumentException("Missing required argument: ${names.first()}")
}

private fun getOptionalStringArgument(
  arguments: JSONObject,
  names: List<String>,
  defaultValue: String,
): String {
  for (name in names) {
    val rawValue = arguments.opt(name) ?: continue
    val value = rawValue.toString().trim()
    if (value.isNotEmpty()) {
      return value
    }
  }
  return defaultValue
}

private fun buildConfiguredIntentResult(
  intent: String,
  parameters: String,
  result: String,
): Map<String, Any> {
  val fallbackOperation = extractOperation(parameters)
  val payload =
    runCatching { JSONObject(result) }.getOrElse {
      return mapOf(
        "action" to intent,
        "status" to "succeeded",
        "operation" to fallbackOperation,
        "summary" to result.take(1000),
      )
    }

  val status = payload.optString("status").ifBlank { if (payload.has("error")) "failed" else "succeeded" }
  val operation = payload.optString("operation").ifBlank { fallbackOperation }
  val flattened =
    linkedMapOf<String, Any>(
      "action" to intent,
      "status" to status,
      "raw_result_json" to result,
    )
  if (operation.isNotBlank()) {
    flattened["operation"] = operation
  }
  putIfNotBlank(flattened, "path", payload.optString("path"))
  putIfNotBlank(flattened, "destination_path", payload.optString("destination_path"))

  if (status != "succeeded") {
    val error = payload.optString("error").ifBlank { "Tool execution failed." }
    flattened["error"] = error
    flattened["recovery_hint"] = buildRecoveryHint(operation = operation, error = error)
    flattened["summary"] = "Failed $operation: $error"
    return flattened
  }

  when (operation) {
    "status" -> {
      val folderName = payload.optString("folder_name").ifBlank { "workspace" }
      flattened["mounted"] = payload.optBoolean("mounted", false)
      flattened["folder_name"] = folderName
      flattened["can_read"] = payload.optBoolean("can_read", false)
      flattened["can_write"] = payload.optBoolean("can_write", false)
      flattened["summary"] =
        "Workspace \"$folderName\" is mounted. Read=${payload.optBoolean("can_read", false)}, write=${payload.optBoolean("can_write", false)}."
    }
    "list" -> {
      val entries = payload.optJSONArray("entries") ?: JSONArray()
      val entryNames = mutableListOf<String>()
      for (i in 0 until minOf(entries.length(), MAX_LIST_SUMMARY_ENTRIES)) {
        val entry = entries.optJSONObject(i) ?: continue
        val name = entry.optString("name").ifBlank { "(unnamed)" }
        val type = entry.optString("type")
        entryNames += if (type == "directory") "$name/" else name
      }
      flattened["entry_count"] = entries.length()
      flattened["truncated"] = payload.optBoolean("truncated", false)
      flattened["entry_names"] = entryNames.joinToString(", ")
      flattened["summary"] =
        if (entryNames.isEmpty()) {
          "Listed ${flattened["path"] ?: "."}. The directory is empty."
        } else {
          "Listed ${flattened["path"] ?: "."}. Found ${entries.length()} entries: ${entryNames.joinToString(", ")}"
        }
    }
    "stat" -> {
      val entry = payload.optJSONObject("entry") ?: JSONObject()
      val name = entry.optString("name").ifBlank { payload.optString("path").ifBlank { "." } }
      val type = entry.optString("type").ifBlank { "unknown" }
      val size = entry.optLong("size", 0L)
      flattened["entry_name"] = name
      flattened["entry_type"] = type
      flattened["size"] = size
      flattened["summary"] = "Path ${flattened["path"] ?: "."} is a $type named $name with size $size bytes."
    }
    "read_text" -> {
      val content = payload.optString("content")
      flattened["content"] = content
      flattened["bytes_read"] = payload.optInt("bytes_read", content.toByteArray(Charsets.UTF_8).size)
      flattened["content_chars"] = payload.optInt("content_chars", content.length)
      flattened["content_bytes"] = payload.optInt("content_bytes", content.toByteArray(Charsets.UTF_8).size)
      putIfNotBlank(flattened, "detected_format", payload.optString("detected_format"))
      putIfNotBlank(flattened, "content_type", payload.optString("content_type"))
      flattened["truncated"] = payload.optBoolean("truncated", false)
      flattened["summary"] =
        "Read ${flattened["path"] ?: "file"} (${flattened["bytes_read"]} file bytes, ${flattened["content_chars"]} text chars${if (payload.optString("detected_format").isNotBlank()) ", ${payload.optString("detected_format")}" else ""}${if (payload.optBoolean("truncated", false)) ", truncated" else ""})."
    }
    "download_url" -> {
      flattened["url"] = payload.optString("url")
      flattened["response_code"] = payload.optInt("response_code", 0)
      flattened["resumed"] = payload.optBoolean("resumed", false)
      flattened["existing_bytes"] = payload.optLong("existing_bytes", 0L)
      flattened["bytes_written"] = payload.optLong("bytes_written", 0L)
      flattened["final_bytes"] = payload.optLong("final_bytes", 0L)
      flattened["summary"] =
        "Downloaded ${flattened["url"] ?: "URL"} to ${flattened["path"] ?: "file"} (${flattened["final_bytes"]} bytes, resumed=${flattened["resumed"]})."
    }
    "edge_tts_synthesize" -> {
      flattened["voice"] = payload.optString("voice")
      flattened["input_path"] = payload.optString("input_path")
      flattened["text_chars"] = payload.optInt("text_chars", 0)
      flattened["bytes_written"] = payload.optInt("bytes_written", 0)
      flattened["summary"] =
        "Synthesized ${flattened["text_chars"]} characters with ${flattened["voice"]} to ${flattened["path"] ?: "media file"} (${flattened["bytes_written"]} bytes)."
    }
    "prepare_write_text" -> {
      flattened["summary"] =
        payload.optString("summary").ifBlank {
          "Prepared ${flattened["path"] ?: "file"} for the next assistant reply."
        }
    }
    "write_text", "append_text" -> {
      flattened["bytes_written"] = payload.optInt("bytes_written", 0)
      flattened["summary"] =
        "${if (operation == "write_text") "Wrote" else "Appended"} ${flattened["bytes_written"]} bytes to ${flattened["path"] ?: "file"}."
    }
    "create_dir" -> {
      flattened["summary"] = "Created directory ${flattened["path"] ?: "."}."
    }
    "delete" -> {
      flattened["summary"] = "Deleted ${flattened["path"] ?: "target"}."
    }
    "copy", "move" -> {
      flattened["summary"] =
        "${if (operation == "copy") "Copied" else "Moved"} ${flattened["path"] ?: "source"} to ${flattened["destination_path"] ?: "destination"}."
    }
    else -> {
      flattened["summary"] = result.take(1000)
    }
  }

  return flattened
}

private fun extractOperation(parameters: String): String {
  return runCatching { JSONObject(parameters.ifBlank { "{}" }).optString("operation") }.getOrDefault("")
}

private fun putIfNotBlank(target: MutableMap<String, Any>, key: String, value: String) {
  if (value.isNotBlank()) {
    target[key] = value
  }
}

private fun buildRecoveryHint(operation: String, error: String): String {
  return when {
    error.contains("__ASSISTANT_RESPONSE__", ignoreCase = true) ->
      "Do not use __ASSISTANT_RESPONSE__. Retry one write_text call and put the full final text directly in content."
    error.contains("workspace-relative", ignoreCase = true) ||
      error.contains("Directory not found", ignoreCase = true) ||
      error.contains("Path not found", ignoreCase = true) ||
      error.contains("Source path not found", ignoreCase = true) ->
      "Use workspace-relative paths only. If unsure, list the parent directory first."
    error.contains("Both path and destination_path are required", ignoreCase = true) ->
      "Retry with both path and destination_path."
    error.contains("required", ignoreCase = true) && operation == "read_text" ->
      "Retry with operation=read_text and a workspace-relative path."
    error.contains("required", ignoreCase = true) && operation == "download_url" ->
      "Retry with operation=download_url plus url and a workspace-relative path."
    error.contains("required", ignoreCase = true) && operation == "write_text" ->
      "Retry with operation=write_text plus path and content."
    error.contains("Directory is not empty", ignoreCase = true) ->
      "If the user wants to remove the directory and its contents, retry with recursive=true."
    else -> "Check the required fields and retry with a workspace-relative path."
  }
}

fun getSkillSecretKey(skillName: String): String {
  return "skill___${skillName}"
}

fun getSkillConfigKey(skillName: String): String {
  if (isWorkspaceSkill(skillName)) {
    return "skill_config___${FILE_WORKSPACE_SKILL_NAME}"
  }
  return "skill_config___${skillName}"
}

private fun requiredSkillForIntent(intent: String): String? {
  return when (intent.trim()) {
    IntentAction.SEND_EMAIL.action -> "send-email"
    IntentAction.CREATE_CALENDAR_EVENT.action -> "create-calendar-event"
    IntentAction.QUERY_WEATHER.action -> WEATHER_QUERY_SKILL_NAME
    IntentAction.LIST_EDGE_TTS_VOICES.action -> EDGE_TTS_SKILL_NAME
    else -> null
  }
}
