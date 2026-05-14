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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AGAgentTools"
private const val MAX_LIST_SUMMARY_ENTRIES = 12

open class AgentTools() : ToolSet {
  lateinit var context: Context
  lateinit var skillManagerViewModel: SkillManagerViewModel

  private val _actionChannel = Channel<AgentAction>(Channel.UNLIMITED)
  val actionChannel: ReceiveChannel<AgentAction> = _actionChannel
  var resultImageToShow: CallJsSkillResultImage? = null
  var resultWebviewToShow: CallJsSkillResultWebview? = null

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

  fun sendAgentAction(action: AgentAction) {
    runBlocking(Dispatchers.Default) { _actionChannel.send(action) }
  }
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
      flattened["truncated"] = payload.optBoolean("truncated", false)
      flattened["summary"] =
        "Read ${flattened["path"] ?: "file"} (${flattened["bytes_read"]} bytes${if (payload.optBoolean("truncated", false)) ", truncated" else ""})."
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
