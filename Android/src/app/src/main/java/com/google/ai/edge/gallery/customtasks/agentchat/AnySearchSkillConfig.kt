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

const val ANYSEARCH_SEARCH_SKILL_NAME = "anysearch-search"

enum class AnySearchDomainMode(override val value: String, val label: String) : SearchConfigValue {
  GENERAL("general", "通用搜索"),
  VERTICAL_ALLOWED("vertical_allowed", "允许垂直领域"),
}

data class AnySearchConfig(
  val resultCount: Int = 5,
  val domainMode: AnySearchDomainMode = AnySearchDomainMode.GENERAL,
)

fun isAnySearchSkill(skillName: String): Boolean = skillName == ANYSEARCH_SEARCH_SKILL_NAME

fun readAnySearchConfig(dataStoreRepository: DataStoreRepository): AnySearchConfig {
  val json = dataStoreRepository.readSecret(getSkillConfigKey(ANYSEARCH_SEARCH_SKILL_NAME)) ?: ""
  val obj = runCatching { JSONObject(json) }.getOrNull() ?: return AnySearchConfig()
  return AnySearchConfig(
    resultCount = clampSearchResultCount(obj.optInt("result_count", 5)),
    domainMode =
      anySearchEnumByValue(
        obj.optString("domain_mode"),
        AnySearchDomainMode.entries,
        AnySearchDomainMode.GENERAL,
      ),
  )
}

fun saveAnySearchConfig(dataStoreRepository: DataStoreRepository, config: AnySearchConfig) {
  dataStoreRepository.saveSecret(
    getSkillConfigKey(ANYSEARCH_SEARCH_SKILL_NAME),
    JSONObject()
      .put("result_count", clampSearchResultCount(config.resultCount))
      .put("domain_mode", config.domainMode.value)
      .toString(),
  )
}

private fun <T> anySearchEnumByValue(value: String, entries: Iterable<T>, default: T): T
  where T : Enum<T>, T : SearchConfigValue {
  return entries.firstOrNull { it.value == value } ?: default
}
