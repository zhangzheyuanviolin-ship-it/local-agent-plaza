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

interface SearchConfigValue {
  val value: String
}

const val TAVILY_SEARCH_SKILL_NAME = "tavily-search"
const val EXA_SEARCH_SKILL_NAME = "exa-search"
const val LANGSEARCH_SEARCH_SKILL_NAME = "langsearch-search"

val SEARCH_SKILL_NAMES =
  setOf(TAVILY_SEARCH_SKILL_NAME, EXA_SEARCH_SKILL_NAME, LANGSEARCH_SEARCH_SKILL_NAME)

fun isSearchSkill(skillName: String): Boolean {
  return SEARCH_SKILL_NAMES.contains(skillName)
}

enum class SearchTopicMode(override val value: String, val label: String) : SearchConfigValue {
  AUTO("auto", "Auto"),
  GENERAL("general", "General"),
  NEWS("news", "News"),
  FINANCE("finance", "Finance"),
}

enum class SearchTimeMode(override val value: String, val label: String) : SearchConfigValue {
  AUTO("auto", "Auto"),
  OFF("off", "Off"),
  DAY("day", "Day"),
  WEEK("week", "Week"),
  MONTH("month", "Month"),
  YEAR("year", "Year"),
}

enum class SearchDetailMode(override val value: String, val label: String) : SearchConfigValue {
  SUMMARY("summary", "Summary"),
  LIGHT("light", "Light"),
  STANDARD("standard", "Standard"),
  FULL("full", "Full"),
}

enum class TavilyDepthMode(override val value: String, val label: String) : SearchConfigValue {
  AUTO("auto", "Auto"),
  BASIC("basic", "Basic"),
  ADVANCED("advanced", "Advanced"),
}

enum class ExaSearchTypeMode(override val value: String, val label: String) : SearchConfigValue {
  AUTO("auto", "Auto"),
  INSTANT("instant", "Instant"),
  FAST("fast", "Fast"),
  DEEP("deep", "Deep"),
}

enum class LangSearchFreshnessMode(override val value: String, val label: String) :
  SearchConfigValue {
  AUTO("auto", "Auto"),
  NO_LIMIT("noLimit", "No limit"),
  ONE_DAY("oneDay", "Day"),
  ONE_WEEK("oneWeek", "Week"),
  ONE_MONTH("oneMonth", "Month"),
  ONE_YEAR("oneYear", "Year"),
}

val SEARCH_RESULT_COUNT_OPTIONS = listOf(1, 3, 5, 8)

data class TavilySearchConfig(
  val topicMode: SearchTopicMode = SearchTopicMode.AUTO,
  val depthMode: TavilyDepthMode = TavilyDepthMode.BASIC,
  val resultCount: Int = 3,
  val detailMode: SearchDetailMode = SearchDetailMode.SUMMARY,
  val timeMode: SearchTimeMode = SearchTimeMode.AUTO,
)

data class ExaSearchConfig(
  val topicMode: SearchTopicMode = SearchTopicMode.AUTO,
  val searchTypeMode: ExaSearchTypeMode = ExaSearchTypeMode.AUTO,
  val resultCount: Int = 3,
  val detailMode: SearchDetailMode = SearchDetailMode.SUMMARY,
  val timeMode: SearchTimeMode = SearchTimeMode.AUTO,
)

data class LangSearchConfig(
  val freshnessMode: LangSearchFreshnessMode = LangSearchFreshnessMode.AUTO,
  val resultCount: Int = 3,
  val detailMode: SearchDetailMode = SearchDetailMode.SUMMARY,
)

fun readTavilySearchConfig(dataStoreRepository: DataStoreRepository): TavilySearchConfig {
  val json = dataStoreRepository.readSecret(getSkillConfigKey(TAVILY_SEARCH_SKILL_NAME)) ?: ""
  return parseTavilySearchConfig(json)
}

fun saveTavilySearchConfig(
  dataStoreRepository: DataStoreRepository,
  config: TavilySearchConfig,
) {
  dataStoreRepository.saveSecret(
    getSkillConfigKey(TAVILY_SEARCH_SKILL_NAME),
    JSONObject()
      .put("topic_mode", config.topicMode.value)
      .put("depth_mode", config.depthMode.value)
      .put("result_count", clampSearchResultCount(config.resultCount))
      .put("detail_mode", config.detailMode.value)
      .put("time_mode", config.timeMode.value)
      .toString(),
  )
}

fun readExaSearchConfig(dataStoreRepository: DataStoreRepository): ExaSearchConfig {
  val json = dataStoreRepository.readSecret(getSkillConfigKey(EXA_SEARCH_SKILL_NAME)) ?: ""
  return parseExaSearchConfig(json)
}

fun saveExaSearchConfig(dataStoreRepository: DataStoreRepository, config: ExaSearchConfig) {
  dataStoreRepository.saveSecret(
    getSkillConfigKey(EXA_SEARCH_SKILL_NAME),
    JSONObject()
      .put("topic_mode", config.topicMode.value)
      .put("search_type_mode", config.searchTypeMode.value)
      .put("result_count", clampSearchResultCount(config.resultCount))
      .put("detail_mode", config.detailMode.value)
      .put("time_mode", config.timeMode.value)
      .toString(),
  )
}

fun readLangSearchConfig(dataStoreRepository: DataStoreRepository): LangSearchConfig {
  val json = dataStoreRepository.readSecret(getSkillConfigKey(LANGSEARCH_SEARCH_SKILL_NAME)) ?: ""
  return parseLangSearchConfig(json)
}

fun saveLangSearchConfig(dataStoreRepository: DataStoreRepository, config: LangSearchConfig) {
  dataStoreRepository.saveSecret(
    getSkillConfigKey(LANGSEARCH_SEARCH_SKILL_NAME),
    JSONObject()
      .put("freshness_mode", config.freshnessMode.value)
      .put("result_count", clampSearchResultCount(config.resultCount))
      .put("detail_mode", config.detailMode.value)
      .toString(),
  )
}

private fun parseTavilySearchConfig(json: String): TavilySearchConfig {
  val obj = json.toJsonObjectOrNull() ?: return TavilySearchConfig()
  return TavilySearchConfig(
    topicMode = enumByValue(obj.optString("topic_mode"), SearchTopicMode.entries, SearchTopicMode.AUTO),
    depthMode = enumByValue(obj.optString("depth_mode"), TavilyDepthMode.entries, TavilyDepthMode.BASIC),
    resultCount = clampSearchResultCount(obj.optInt("result_count", 3)),
    detailMode = enumByValue(obj.optString("detail_mode"), SearchDetailMode.entries, SearchDetailMode.SUMMARY),
    timeMode = enumByValue(obj.optString("time_mode"), SearchTimeMode.entries, SearchTimeMode.AUTO),
  )
}

private fun parseExaSearchConfig(json: String): ExaSearchConfig {
  val obj = json.toJsonObjectOrNull() ?: return ExaSearchConfig()
  return ExaSearchConfig(
    topicMode = enumByValue(obj.optString("topic_mode"), SearchTopicMode.entries, SearchTopicMode.AUTO),
    searchTypeMode =
      enumByValue(
        obj.optString("search_type_mode"),
        ExaSearchTypeMode.entries,
        ExaSearchTypeMode.AUTO,
      ),
    resultCount = clampSearchResultCount(obj.optInt("result_count", 3)),
    detailMode = enumByValue(obj.optString("detail_mode"), SearchDetailMode.entries, SearchDetailMode.SUMMARY),
    timeMode = enumByValue(obj.optString("time_mode"), SearchTimeMode.entries, SearchTimeMode.AUTO),
  )
}

private fun parseLangSearchConfig(json: String): LangSearchConfig {
  val obj = json.toJsonObjectOrNull() ?: return LangSearchConfig()
  return LangSearchConfig(
    freshnessMode =
      enumByValue(
        obj.optString("freshness_mode"),
        LangSearchFreshnessMode.entries,
        LangSearchFreshnessMode.AUTO,
      ),
    resultCount = clampSearchResultCount(obj.optInt("result_count", 3)),
    detailMode = enumByValue(obj.optString("detail_mode"), SearchDetailMode.entries, SearchDetailMode.SUMMARY),
  )
}

private fun clampSearchResultCount(value: Int): Int {
  return SEARCH_RESULT_COUNT_OPTIONS.minByOrNull { kotlin.math.abs(it - value) } ?: 3
}

private fun String.toJsonObjectOrNull(): JSONObject? {
  if (isBlank()) {
    return null
  }
  return runCatching { JSONObject(this) }.getOrNull()
}

private fun <T> enumByValue(value: String, entries: Iterable<T>, default: T): T where T : Enum<T>, T : SearchConfigValue {
  return entries.firstOrNull { it.value == value } ?: default
}
