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

import com.google.ai.edge.gallery.data.DataStoreRepository
import org.json.JSONObject

const val FILE_WORKSPACE_SKILL_NAME = "file-workspace"
const val LONG_TEXT_WRITER_SKILL_NAME = "long-text-writer"
const val EDGE_TTS_SKILL_NAME = "edge-tts"
const val WEATHER_QUERY_SKILL_NAME = "weather-query"

data class FileWorkspaceConfig(
  val treeUri: String = "",
  val displayName: String = "",
)

fun hasSkillConfig(skillName: String): Boolean {
  return isSearchSkill(skillName) || isWorkspaceSkill(skillName) || isAgnesSkill(skillName)
}

fun isWorkspaceSkill(skillName: String): Boolean {
  return skillName == FILE_WORKSPACE_SKILL_NAME ||
    skillName == LONG_TEXT_WRITER_SKILL_NAME ||
    skillName == EDGE_TTS_SKILL_NAME
}

fun readFileWorkspaceConfig(dataStoreRepository: DataStoreRepository): FileWorkspaceConfig {
  val json = dataStoreRepository.readSecret(getSkillConfigKey(FILE_WORKSPACE_SKILL_NAME)) ?: ""
  return parseFileWorkspaceConfig(json)
}

fun saveFileWorkspaceConfig(
  dataStoreRepository: DataStoreRepository,
  config: FileWorkspaceConfig,
) {
  dataStoreRepository.saveSecret(
    getSkillConfigKey(FILE_WORKSPACE_SKILL_NAME),
    JSONObject().put("tree_uri", config.treeUri).put("display_name", config.displayName).toString(),
  )
}

private fun parseFileWorkspaceConfig(json: String): FileWorkspaceConfig {
  val obj = json.toFileWorkspaceJsonObjectOrNull() ?: return FileWorkspaceConfig()
  return FileWorkspaceConfig(
    treeUri = obj.optString("tree_uri"),
    displayName = obj.optString("display_name"),
  )
}

private fun String.toFileWorkspaceJsonObjectOrNull(): JSONObject? {
  if (isBlank()) {
    return null
  }
  return runCatching { JSONObject(this) }.getOrNull()
}
