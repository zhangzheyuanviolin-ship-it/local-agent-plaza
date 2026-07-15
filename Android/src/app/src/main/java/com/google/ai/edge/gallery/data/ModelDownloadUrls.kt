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

package com.google.ai.edge.gallery.data

private const val PLAZA_ALLOWLIST_BASE_URL =
  "https://raw.githubusercontent.com/zhangzheyuanviolin-ship-it/local-agent-plaza/" +
    "refs/heads/main/model_allowlists"

private const val GOOGLE_ALLOWLIST_BASE_URL =
  "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists"

fun modelAllowlistUrls(version: String): List<String> {
  return listOf(
      "$PLAZA_ALLOWLIST_BASE_URL/$version.json",
      "$GOOGLE_ALLOWLIST_BASE_URL/$version.json",
    )
    .distinct()
}

fun huggingFaceModelFileUrl(modelId: String, revision: String?, modelFile: String): String {
  return huggingFaceModelFileUrls(modelId, revision, modelFile).last()
}

fun huggingFaceModelFileUrls(modelId: String, revision: String?, modelFile: String): List<String> {
  val resolvedRevision = revision?.takeIf { it.isNotBlank() } ?: "main"
  val path = "$modelId/resolve/$resolvedRevision/$modelFile?download=true"
  return listOfNotNull(
      modelScopeModelFileUrl(modelId = modelId, modelFile = modelFile),
      "https://hf-mirror.com/$path",
      "https://huggingface.co/$path",
    )
    .distinct()
}

fun expandModelDownloadUrls(primaryUrl: String): List<String> {
  if (primaryUrl.isBlank()) return emptyList()
  val hfPrefix = "https://huggingface.co/"
  return if (primaryUrl.startsWith(hfPrefix)) {
    listOf(primaryUrl.replaceFirst(hfPrefix, "https://hf-mirror.com/"), primaryUrl).distinct()
  } else {
    listOf(primaryUrl)
  }
}

private fun modelScopeModelFileUrl(modelId: String, modelFile: String): String? {
  val hasVerifiedModelScopeMirror =
    modelId.startsWith("litert-community/") || modelId.startsWith("google/")
  if (!hasVerifiedModelScopeMirror) return null

  val encodedFile = modelFile.replace(" ", "%20")
  return "https://modelscope.cn/models/$modelId/resolve/master/$encodedFile"
}
