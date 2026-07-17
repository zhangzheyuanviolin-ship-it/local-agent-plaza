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
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object AgentAgnesSupport {
  private const val BASE_URL = "https://apihub.agnes-ai.com"
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val client =
    OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(180, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .build()

  fun generateImage(
    context: Context,
    apiKey: String,
    config: AgnesOmniConfig,
    workspaceConfig: FileWorkspaceConfig,
    prompt: String,
    outputPath: String,
  ): JSONObject {
    val body =
      JSONObject()
        .put("model", config.imageModel.value)
        .put("prompt", prompt.trim())
        .put("size", config.imageSize.value)
        .put("ratio", config.imageRatio.value)
        .put("extra_body", JSONObject().put("response_format", "url"))
    val response = postJson("$BASE_URL/v1/images/generations", apiKey, body)
    val url =
      response.optJSONArray("data")?.optJSONObject(0)?.optString("url")
        ?.takeIf { it.isNotBlank() }
        ?: findFirstUrl(response, setOf("png", "jpg", "jpeg", "webp"))
        ?: throw IOException("Agnes image response did not include an image URL.")
    val targetPath =
      ensureMediaPath(
        path = outputPath,
        defaultPrefix = "agnes-image",
        extension = extensionFromUrl(url, "png"),
      )
    val bytesWritten = downloadToWorkspace(context, workspaceConfig, url, targetPath)
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", "agnes_generate_image")
      .put("path", targetPath)
      .put("url", url)
      .put("model", config.imageModel.value)
      .put("prompt", prompt.trim())
      .put("bytes_written", bytesWritten)
      .put("raw_response", response)
  }

  fun generateVideo(
    context: Context,
    apiKey: String,
    config: AgnesOmniConfig,
    workspaceConfig: FileWorkspaceConfig,
    prompt: String,
    outputPath: String,
  ): JSONObject {
    val body =
      JSONObject()
        .put("model", "agnes-video-v2.0")
        .put("prompt", prompt.trim())
        .put("height", config.videoResolution.height)
        .put("width", config.videoResolution.width)
        .put("num_frames", config.videoDuration.frames)
        .put("frame_rate", 24)
    val createResponse = postJson("$BASE_URL/v1/videos", apiKey, body)
    val videoId =
      createResponse.optString("video_id").ifBlank {
        createResponse.optString("task_id")
      }.ifBlank {
        findStringByKey(createResponse, setOf("video_id", "task_id", "id"))
      } ?: throw IOException("Agnes video response did not include video_id or task_id.")
    val finalResponse = pollVideo(apiKey = apiKey, videoId = videoId)
    val url =
      findFirstUrl(finalResponse, setOf("mp4", "mov", "webm"))
        ?: throw IOException("Agnes video result did not include a downloadable video URL.")
    val targetPath =
      ensureMediaPath(
        path = outputPath,
        defaultPrefix = "agnes-video",
        extension = extensionFromUrl(url, "mp4"),
      )
    val bytesWritten = downloadToWorkspace(context, workspaceConfig, url, targetPath)
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", "agnes_generate_video")
      .put("path", targetPath)
      .put("url", url)
      .put("model", "agnes-video-v2.0")
      .put("video_id", videoId)
      .put("prompt", prompt.trim())
      .put("bytes_written", bytesWritten)
      .put("raw_response", finalResponse)
  }

  private fun postJson(url: String, apiKey: String, body: JSONObject): JSONObject {
    val request =
      Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${apiKey.trim()}")
        .header("Content-Type", "application/json")
        .post(body.toString().toRequestBody(jsonMediaType))
        .build()
    return executeJson(request)
  }

  private fun getJson(url: String, apiKey: String): JSONObject {
    val request =
      Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${apiKey.trim()}")
        .build()
    return executeJson(request)
  }

  private fun executeJson(request: Request): JSONObject {
    client.newCall(request).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        throw IOException("Agnes API HTTP ${response.code}: ${text.take(500)}")
      }
      return JSONObject(text.ifBlank { "{}" })
    }
  }

  private fun pollVideo(apiKey: String, videoId: String): JSONObject {
    var last = JSONObject()
    repeat(120) {
      last = getJson("$BASE_URL/v1/videos/$videoId", apiKey)
      val status = last.optString("status").lowercase(Locale.US)
      val url = findFirstUrl(last, setOf("mp4", "mov", "webm"))
      if (url != null && status !in setOf("queued", "pending", "running", "processing")) {
        return last
      }
      if (status in setOf("failed", "error", "cancelled", "canceled")) {
        throw IOException("Agnes video generation failed: ${last.toString().take(800)}")
      }
      Thread.sleep(3_000L)
    }
    throw IOException("Agnes video generation timed out. Last response: ${last.toString().take(800)}")
  }

  private fun downloadToWorkspace(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    url: String,
    path: String,
  ): Long {
    val root =
      DocumentFile.fromTreeUri(context, workspaceConfig.treeUri.toUri())
        ?: throw IOException("Workspace folder is not available.")
    val target = ensureWritableFile(root, path)
    val request = Request.Builder().url(url).build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IOException("Failed to download generated media HTTP ${response.code}.")
      }
      val body = response.body ?: throw IOException("Generated media response was empty.")
      context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
        body.byteStream().use { input -> return input.copyTo(output) }
      }
    }
    throw IOException("Failed to open generated media output file.")
  }

  private fun ensureWritableFile(root: DocumentFile, path: String): DocumentFile {
    val clean = path.replace('\\', '/').trim('/').ifBlank { throw IOException("Output path is empty.") }
    val parts = clean.split('/').filter { it.isNotBlank() }
    var dir = root
    for (part in parts.dropLast(1)) {
      dir = dir.findFile(part) ?: dir.createDirectory(part)
        ?: throw IOException("Failed to create workspace directory: $part")
      if (!dir.isDirectory) {
        throw IOException("Workspace path component is not a directory: $part")
      }
    }
    val name = parts.last()
    dir.findFile(name)?.delete()
    return dir.createFile(mimeTypeForPath(name), name)
      ?: throw IOException("Failed to create workspace file: $clean")
  }

  private fun ensureMediaPath(path: String, defaultPrefix: String, extension: String): String {
    val cleanExt = extension.trimStart('.').ifBlank { "bin" }
    val fallback =
      "media/$defaultPrefix-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.$cleanExt"
    val normalized = path.replace('\\', '/').trim()
    if (normalized.isBlank()) {
      return fallback
    }
    val withExt =
      if (normalized.substringAfterLast('/', normalized).contains('.')) normalized
      else "$normalized.$cleanExt"
    return if ('/' in withExt) withExt else "media/$withExt"
  }

  private fun extensionFromUrl(url: String, fallback: String): String {
    val path = url.substringBefore('?').substringBefore('#')
    val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
    return if (ext.length in 2..5) ext else fallback
  }

  private fun mimeTypeForPath(path: String): String {
    return when (path.substringAfterLast('.', "").lowercase(Locale.US)) {
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "webp" -> "image/webp"
      "mp4" -> "video/mp4"
      "mov" -> "video/quicktime"
      "webm" -> "video/webm"
      else -> "application/octet-stream"
    }
  }

  private fun findFirstUrl(json: JSONObject, extensions: Set<String>): String? {
    val urls = mutableListOf<String>()
    collectUrls(json, urls)
    return urls.firstOrNull { url ->
      val clean = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
      extensions.any { clean.endsWith(".$it") }
    } ?: urls.firstOrNull()
  }

  private fun collectUrls(value: Any?, urls: MutableList<String>) {
    when (value) {
      is JSONObject -> value.keys().forEach { key -> collectUrls(value.opt(key), urls) }
      is JSONArray -> for (i in 0 until value.length()) collectUrls(value.opt(i), urls)
      is String -> if (value.startsWith("http://") || value.startsWith("https://")) urls += value
    }
  }

  private fun findStringByKey(value: Any?, keys: Set<String>): String? {
    return when (value) {
      is JSONObject -> {
        for (key in value.keys()) {
          if (keys.contains(key) && value.optString(key).isNotBlank()) {
            return value.optString(key)
          }
          findStringByKey(value.opt(key), keys)?.let { return it }
        }
        null
      }
      is JSONArray -> {
        for (i in 0 until value.length()) {
          findStringByKey(value.opt(i), keys)?.let { return it }
        }
        null
      }
      else -> null
    }
  }
}
