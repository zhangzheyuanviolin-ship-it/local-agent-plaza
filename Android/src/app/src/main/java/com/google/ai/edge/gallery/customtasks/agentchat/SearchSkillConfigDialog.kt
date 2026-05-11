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
import androidx.compose.ui.unit.dp
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
        title = "Tavily search settings",
        subtitle = "These defaults control how much live web content Tavily sends back to the model.",
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
          title = "Topic",
          description = "Auto lets the model decide when to use general, news, or finance.",
          selectedValue = topicMode.value,
          options = SearchTopicMode.entries.map { it.value to it.label },
          onSelected = { selected -> topicMode = SearchTopicMode.entries.first { it.value == selected } },
        )
        ChoiceSection(
          title = "Search depth",
          description = "Advanced gives broader retrieval but costs more context and latency.",
          selectedValue = depthMode.value,
          options = TavilyDepthMode.entries.map { it.value to it.label },
          onSelected = { selected -> depthMode = TavilyDepthMode.entries.first { it.value == selected } },
        )
        IntChoiceSection(
          title = "Result count",
          description = "More results improve coverage but increase prompt size.",
          selectedValue = resultCount,
          options = SEARCH_RESULT_COUNT_OPTIONS,
          onSelected = { resultCount = it },
        )
        ChoiceSection(
          title = "Detail mode",
          description = "Summary is the fastest. Full returns the most source text.",
          selectedValue = detailMode.value,
          options = SearchDetailMode.entries.map { it.value to it.label },
          onSelected = { selected ->
            detailMode = SearchDetailMode.entries.first { it.value == selected }
          },
        )
        ChoiceSection(
          title = "Time range",
          description = "Auto lets the model decide when recency matters.",
          selectedValue = timeMode.value,
          options = SearchTimeMode.entries.map { it.value to it.label },
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
        title = "Exa search settings",
        subtitle = "These defaults control Exa search type, coverage, and how much source text comes back.",
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
          title = "Topic",
          description = "Auto lets the model decide when the request is general, news, or finance.",
          selectedValue = topicMode.value,
          options = SearchTopicMode.entries.map { it.value to it.label },
          onSelected = { selected -> topicMode = SearchTopicMode.entries.first { it.value == selected } },
        )
        ChoiceSection(
          title = "Search type",
          description = "Deep gives the richest search but can be slower and heavier.",
          selectedValue = searchTypeMode.value,
          options = ExaSearchTypeMode.entries.map { it.value to it.label },
          onSelected = { selected ->
            searchTypeMode = ExaSearchTypeMode.entries.first { it.value == selected }
          },
        )
        IntChoiceSection(
          title = "Result count",
          description = "Higher counts improve coverage but increase prompt size.",
          selectedValue = resultCount,
          options = SEARCH_RESULT_COUNT_OPTIONS,
          onSelected = { resultCount = it },
        )
        ChoiceSection(
          title = "Detail mode",
          description = "Full returns the most page text available from Exa.",
          selectedValue = detailMode.value,
          options = SearchDetailMode.entries.map { it.value to it.label },
          onSelected = { selected ->
            detailMode = SearchDetailMode.entries.first { it.value == selected }
          },
        )
        ChoiceSection(
          title = "Time range",
          description = "Auto lets the model request recent web results only when needed.",
          selectedValue = timeMode.value,
          options = SearchTimeMode.entries.map { it.value to it.label },
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
        title = "LangSearch settings",
        subtitle = "These defaults control result freshness, result count, and how much returned text is kept.",
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
          title = "Freshness",
          description = "Auto lets the model ask for recency only when it matters.",
          selectedValue = freshnessMode.value,
          options = LangSearchFreshnessMode.entries.map { it.value to it.label },
          onSelected = { selected ->
            freshnessMode = LangSearchFreshnessMode.entries.first { it.value == selected }
          },
        )
        IntChoiceSection(
          title = "Result count",
          description = "Higher counts improve coverage but add more context for the model to read.",
          selectedValue = resultCount,
          options = SEARCH_RESULT_COUNT_OPTIONS,
          onSelected = { resultCount = it },
        )
        ChoiceSection(
          title = "Detail mode",
          description = "Full keeps the most detail available from LangSearch results.",
          selectedValue = detailMode.value,
          options = SearchDetailMode.entries.map { it.value to it.label },
          onSelected = { selected ->
            detailMode = SearchDetailMode.entries.first { it.value == selected }
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
    confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
