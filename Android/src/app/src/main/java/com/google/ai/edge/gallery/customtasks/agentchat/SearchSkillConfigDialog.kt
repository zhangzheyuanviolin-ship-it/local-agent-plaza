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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.proto.Skill

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchSkillConfigDialog(
  skill: Skill,
  dataStoreRepository: DataStoreRepository,
  onDismiss: () -> Unit,
) {
  when (skill.name) {
    TAVILY_SEARCH_SKILL_NAME -> {
      var topicMode by remember { mutableStateOf(readTavilySearchConfig(dataStoreRepository).topicMode) }
      var depthMode by remember { mutableStateOf(readTavilySearchConfig(dataStoreRepository).depthMode) }
      var resultCount by remember {
        mutableIntStateOf(readTavilySearchConfig(dataStoreRepository).resultCount)
      }
      var detailMode by remember {
        mutableStateOf(readTavilySearchConfig(dataStoreRepository).detailMode)
      }
      var timeMode by remember { mutableStateOf(readTavilySearchConfig(dataStoreRepository).timeMode) }
      SearchConfigDialogFrame(
        title = stringResource(R.string.search_config_tavily_title),
        subtitle = stringResource(R.string.search_config_tavily_subtitle),
        onDismiss = onDismiss,
        onSave = {
          saveTavilySearchConfig(
            dataStoreRepository,
            TavilySearchConfig(
              topicMode = topicMode,
              depthMode = depthMode,
              resultCount = resultCount,
              detailMode = detailMode,
              timeMode = timeMode,
            ),
          )
          onDismiss()
        },
      ) {
        ChoiceSection(
          title = stringResource(R.string.search_config_topic_title),
          description = stringResource(R.string.search_config_topic_description),
          selectedValue = topicMode.value,
          options = searchTopicOptions(),
          onSelected = { selected -> topicMode = SearchTopicMode.entries.first { it.value == selected } },
        )
        ChoiceSection(
          title = stringResource(R.string.search_config_search_depth_title),
          description = stringResource(R.string.search_config_search_depth_description),
          selectedValue = depthMode.value,
          options = tavilyDepthOptions(),
          onSelected = { selected -> depthMode = TavilyDepthMode.entries.first { it.value == selected } },
        )
        IntChoiceSection(
          title = stringResource(R.string.search_config_result_count_title),
          description = stringResource(R.string.search_config_result_count_description),
          selectedValue = resultCount,
          options = SEARCH_RESULT_COUNT_OPTIONS,
          onSelected = { resultCount = it },
        )
        ChoiceSection(
          title = stringResource(R.string.search_config_detail_mode_title),
          description = stringResource(R.string.search_config_detail_mode_description),
          selectedValue = detailMode.value,
          options = searchDetailModeOptions(),
          onSelected = { selected ->
            detailMode = SearchDetailMode.entries.first { it.value == selected }
          },
        )
        ChoiceSection(
          title = stringResource(R.string.search_config_time_range_title),
          description = stringResource(R.string.search_config_time_range_description),
          selectedValue = timeMode.value,
          options = searchTimeModeOptions(),
          onSelected = { selected -> timeMode = SearchTimeMode.entries.first { it.value == selected } },
        )
      }
    }
    EXA_SEARCH_SKILL_NAME -> {
      var topicMode by remember { mutableStateOf(readExaSearchConfig(dataStoreRepository).topicMode) }
      var searchTypeMode by remember {
        mutableStateOf(readExaSearchConfig(dataStoreRepository).searchTypeMode)
      }
      var resultCount by remember { mutableIntStateOf(readExaSearchConfig(dataStoreRepository).resultCount) }
      var detailMode by remember { mutableStateOf(readExaSearchConfig(dataStoreRepository).detailMode) }
      var timeMode by remember { mutableStateOf(readExaSearchConfig(dataStoreRepository).timeMode) }
      SearchConfigDialogFrame(
        title = stringResource(R.string.search_config_exa_title),
        subtitle = stringResource(R.string.search_config_exa_subtitle),
        onDismiss = onDismiss,
        onSave = {
          saveExaSearchConfig(
            dataStoreRepository,
            ExaSearchConfig(
              topicMode = topicMode,
              searchTypeMode = searchTypeMode,
              resultCount = resultCount,
              detailMode = detailMode,
              timeMode = timeMode,
            ),
          )
          onDismiss()
        },
      ) {
        ChoiceSection(
          title = stringResource(R.string.search_config_topic_title),
          description = stringResource(R.string.search_config_topic_description),
          selectedValue = topicMode.value,
          options = searchTopicOptions(),
          onSelected = { selected -> topicMode = SearchTopicMode.entries.first { it.value == selected } },
        )
        ChoiceSection(
          title = stringResource(R.string.search_config_search_type_title),
          description = stringResource(R.string.search_config_search_type_description),
          selectedValue = searchTypeMode.value,
          options = exaSearchTypeOptions(),
          onSelected = { selected ->
            searchTypeMode = ExaSearchTypeMode.entries.first { it.value == selected }
          },
        )
        IntChoiceSection(
          title = stringResource(R.string.search_config_result_count_title),
          description = stringResource(R.string.search_config_result_count_description),
          selectedValue = resultCount,
          options = SEARCH_RESULT_COUNT_OPTIONS,
          onSelected = { resultCount = it },
        )
        ChoiceSection(
          title = stringResource(R.string.search_config_detail_mode_title),
          description = stringResource(R.string.search_config_detail_mode_description),
          selectedValue = detailMode.value,
          options = searchDetailModeOptions(),
          onSelected = { selected ->
            detailMode = SearchDetailMode.entries.first { it.value == selected }
          },
        )
        ChoiceSection(
          title = stringResource(R.string.search_config_time_range_title),
          description = stringResource(R.string.search_config_time_range_description),
          selectedValue = timeMode.value,
          options = searchTimeModeOptions(),
          onSelected = { selected -> timeMode = SearchTimeMode.entries.first { it.value == selected } },
        )
      }
    }
    LANGSEARCH_SEARCH_SKILL_NAME -> {
      var freshnessMode by remember {
        mutableStateOf(readLangSearchConfig(dataStoreRepository).freshnessMode)
      }
      var resultCount by remember {
        mutableIntStateOf(readLangSearchConfig(dataStoreRepository).resultCount)
      }
      var detailMode by remember {
        mutableStateOf(readLangSearchConfig(dataStoreRepository).detailMode)
      }
      SearchConfigDialogFrame(
        title = stringResource(R.string.search_config_langsearch_title),
        subtitle = stringResource(R.string.search_config_langsearch_subtitle),
        onDismiss = onDismiss,
        onSave = {
          saveLangSearchConfig(
            dataStoreRepository,
            LangSearchConfig(
              freshnessMode = freshnessMode,
              resultCount = resultCount,
              detailMode = detailMode,
            ),
          )
          onDismiss()
        },
      ) {
        ChoiceSection(
          title = stringResource(R.string.search_config_freshness_title),
          description = stringResource(R.string.search_config_freshness_description),
          selectedValue = freshnessMode.value,
          options = langSearchFreshnessOptions(),
          onSelected = { selected ->
            freshnessMode = LangSearchFreshnessMode.entries.first { it.value == selected }
          },
        )
        IntChoiceSection(
          title = stringResource(R.string.search_config_result_count_title),
          description = stringResource(R.string.search_config_result_count_description),
          selectedValue = resultCount,
          options = SEARCH_RESULT_COUNT_OPTIONS,
          onSelected = { resultCount = it },
        )
        ChoiceSection(
          title = stringResource(R.string.search_config_detail_mode_title),
          description = stringResource(R.string.search_config_detail_mode_description),
          selectedValue = detailMode.value,
          options = searchDetailModeOptions(),
          onSelected = { selected ->
            detailMode = SearchDetailMode.entries.first { it.value == selected }
          },
        )
      }
    }
    ANYSEARCH_SEARCH_SKILL_NAME -> {
      var resultCount by remember {
        mutableIntStateOf(readAnySearchConfig(dataStoreRepository).resultCount)
      }
      var domainMode by remember {
        mutableStateOf(readAnySearchConfig(dataStoreRepository).domainMode)
      }
      SearchConfigDialogFrame(
        title = "AnySearch 设置",
        subtitle = "AnySearch 使用官方 JSON-RPC API。模型通常只需要传入 query；如启用垂直领域，模型可在必要时传入 domain、sub_domain 和 sub_domain_params。",
        onDismiss = onDismiss,
        onSave = {
          saveAnySearchConfig(
            dataStoreRepository,
            AnySearchConfig(resultCount = resultCount, domainMode = domainMode),
          )
          onDismiss()
        },
      ) {
        IntChoiceSection(
          title = stringResource(R.string.search_config_result_count_title),
          description = stringResource(R.string.search_config_result_count_description),
          selectedValue = resultCount,
          options = SEARCH_RESULT_COUNT_OPTIONS,
          onSelected = { resultCount = it },
        )
        ChoiceSection(
          title = "领域模式",
          description = "通用搜索最省心；垂直领域模式用于金融、学术、法律、代码、安全等结构化搜索。",
          selectedValue = domainMode.value,
          options = AnySearchDomainMode.entries.map { it.value to it.label },
          onSelected = { selected ->
            domainMode = AnySearchDomainMode.entries.first { it.value == selected }
          },
        )
      }
    }
  }
}

@Composable
private fun SearchConfigDialogFrame(
  title: String,
  subtitle: String,
  onDismiss: () -> Unit,
  onSave: () -> Unit,
  content: @Composable () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
      }
    },
    confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.save)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceSection(
  title: String,
  description: String,
  selectedValue: String,
  options: List<Pair<String, String>>,
  onSelected: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(
      text = description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      options.forEach { (value, label) ->
        FilterChip(
          selected = selectedValue == value,
          onClick = { onSelected(value) },
          label = { Text(label) },
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntChoiceSection(
  title: String,
  description: String,
  selectedValue: Int,
  options: List<Int>,
  onSelected: (Int) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(
      text = description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      options.forEach { value ->
        FilterChip(
          selected = selectedValue == value,
          onClick = { onSelected(value) },
          label = { Text(value.toString()) },
        )
      }
    }
  }
}

@Composable
private fun searchTopicOptions(): List<Pair<String, String>> {
  return SearchTopicMode.entries.map { mode ->
    mode.value to
      stringResource(
        when (mode) {
          SearchTopicMode.AUTO -> R.string.search_config_choice_auto
          SearchTopicMode.GENERAL -> R.string.search_config_choice_general
          SearchTopicMode.NEWS -> R.string.search_config_choice_news
          SearchTopicMode.FINANCE -> R.string.search_config_choice_finance
        }
      )
  }
}

@Composable
private fun searchTimeModeOptions(): List<Pair<String, String>> {
  return SearchTimeMode.entries.map { mode ->
    mode.value to
      stringResource(
        when (mode) {
          SearchTimeMode.AUTO -> R.string.search_config_choice_auto
          SearchTimeMode.OFF -> R.string.search_config_choice_off
          SearchTimeMode.DAY -> R.string.search_config_choice_day
          SearchTimeMode.WEEK -> R.string.search_config_choice_week
          SearchTimeMode.MONTH -> R.string.search_config_choice_month
          SearchTimeMode.YEAR -> R.string.search_config_choice_year
        }
      )
  }
}

@Composable
private fun searchDetailModeOptions(): List<Pair<String, String>> {
  return SearchDetailMode.entries.map { mode ->
    mode.value to
      stringResource(
        when (mode) {
          SearchDetailMode.SUMMARY -> R.string.search_config_choice_summary
          SearchDetailMode.LIGHT -> R.string.search_config_choice_light
          SearchDetailMode.STANDARD -> R.string.search_config_choice_standard
          SearchDetailMode.FULL -> R.string.search_config_choice_full
        }
      )
  }
}

@Composable
private fun tavilyDepthOptions(): List<Pair<String, String>> {
  return TavilyDepthMode.entries.map { mode ->
    mode.value to
      stringResource(
        when (mode) {
          TavilyDepthMode.AUTO -> R.string.search_config_choice_auto
          TavilyDepthMode.BASIC -> R.string.search_config_choice_basic
          TavilyDepthMode.ADVANCED -> R.string.search_config_choice_advanced
        }
      )
  }
}

@Composable
private fun exaSearchTypeOptions(): List<Pair<String, String>> {
  return ExaSearchTypeMode.entries.map { mode ->
    mode.value to
      stringResource(
        when (mode) {
          ExaSearchTypeMode.AUTO -> R.string.search_config_choice_auto
          ExaSearchTypeMode.INSTANT -> R.string.search_config_choice_instant
          ExaSearchTypeMode.FAST -> R.string.search_config_choice_fast
          ExaSearchTypeMode.DEEP -> R.string.search_config_choice_deep
        }
      )
  }
}

@Composable
private fun langSearchFreshnessOptions(): List<Pair<String, String>> {
  return LangSearchFreshnessMode.entries.map { mode ->
    mode.value to
      stringResource(
        when (mode) {
          LangSearchFreshnessMode.AUTO -> R.string.search_config_choice_auto
          LangSearchFreshnessMode.NO_LIMIT -> R.string.search_config_choice_no_limit
          LangSearchFreshnessMode.ONE_DAY -> R.string.search_config_choice_day
          LangSearchFreshnessMode.ONE_WEEK -> R.string.search_config_choice_week
          LangSearchFreshnessMode.ONE_MONTH -> R.string.search_config_choice_month
          LangSearchFreshnessMode.ONE_YEAR -> R.string.search_config_choice_year
        }
      )
  }
}
