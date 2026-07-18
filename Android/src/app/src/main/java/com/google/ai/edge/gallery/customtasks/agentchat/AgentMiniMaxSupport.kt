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
import android.util.Base64
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
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

object AgentMiniMaxSupport {
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val normalClient =
    OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(180, TimeUnit.SECONDS)
      .writeTimeout(90, TimeUnit.SECONDS)
      .build()
  private val longClient =
    OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.MINUTES)
      .writeTimeout(120, TimeUnit.SECONDS)
      .build()

  fun generateText(
    apiKey: String,
    config: MiniMaxOmniConfig,
    prompt: String,
  ): JSONObject {
    val body =
      JSONObject()
        .put("model", config.textModel.value)
        .put("max_tokens", config.textMaxTokens)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt.trim())))
    val response = postJson(
      url = "${config.apiHost.value}/anthropic/v1/messages",
      apiKey = apiKey,
      body = body,
      authStyle = AuthStyle.X_API_KEY,
      client = normalClient,
    )
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", "minimax_text")
      .put("model", config.textModel.value)
      .put("content", extractAnthropicText(response).ifBlank { response.toString().take(2000) })
      .put("raw_response", compactRaw(response))
  }

  fun generateImage(
    context: Context,
    apiKey: String,
    config: MiniMaxOmniConfig,
    workspaceConfig: FileWorkspaceConfig,
    prompt: String,
    outputPath: String,
  ): JSONObject {
    val response = postJson(
      url = "${config.apiHost.value}/v1/image_generation",
      apiKey = apiKey,
      body =
        JSONObject()
          .put("model", "image-01")
          .put("prompt", prompt.trim())
          .put("aspect_ratio", config.imageRatio.value)
          .put("n", 1),
      authStyle = AuthStyle.BEARER,
      client = longClient,
    )
    val url =
      response.optJSONObject("data")?.optJSONArray("image_urls")?.optString(0)
        ?.takeIf { it.isNotBlank() }
        ?: findFirstUrl(response, setOf("jpg", "jpeg", "png", "webp"))
        ?: throw IOException("MiniMax image response did not include an image URL.")
    val targetPath =
      ensureMediaPath(
        path = outputPath,
        defaultPrefix = "minimax-image",
        extension = extensionFromUrl(url, "jpg"),
      )
    val bytesWritten = downloadToWorkspace(context, workspaceConfig, url, targetPath, longClient)
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", "minimax_generate_image")
      .put("path", targetPath)
      .put("url", url)
      .put("model", "image-01")
      .put("prompt", prompt.trim())
      .put("bytes_written", bytesWritten)
      .put("raw_response", compactRaw(response))
  }

  fun synthesizeSpeech(
    context: Context,
    apiKey: String,
    config: MiniMaxOmniConfig,
    workspaceConfig: FileWorkspaceConfig,
    text: String,
    inputPath: String,
    outputPath: String,
  ): JSONObject {
    val sourceText =
      if (inputPath.isNotBlank()) {
        readWorkspaceText(context, workspaceConfig, inputPath, maxBytes = 120_000)
      } else {
        text
      }.trim()
    if (sourceText.isBlank()) {
      throw IOException("Text or input_path is required.")
    }
    if (sourceText.length > 10_000) {
      throw IOException("MiniMax TTS supports up to 10000 characters per request; split the text file first.")
    }
    val response = postJson(
      url = "${config.apiHost.value}/v1/t2a_v2",
      apiKey = apiKey,
      body =
        JSONObject()
          .put("model", config.ttsModel.value)
          .put("text", sourceText)
          .put("voice_setting", JSONObject().put("voice_id", config.voice.value))
          .put(
            "audio_setting",
            JSONObject()
              .put("format", "mp3")
              .put("sample_rate", 32_000)
              .put("bitrate", 128_000)
              .put("channel", 1),
          )
          .put("output_format", "hex")
          .put("stream", false),
      authStyle = AuthStyle.BEARER,
      client = longClient,
    )
    val audio = response.optJSONObject("data")?.optString("audio").orEmpty()
    if (audio.isBlank()) {
      throw IOException("MiniMax TTS response did not include audio data.")
    }
    val bytes = decodeAudioPayload(audio)
    val targetPath = ensureMediaPath(outputPath, "minimax-tts", "mp3")
    val bytesWritten = writeBytesToWorkspace(context, workspaceConfig, targetPath, bytes, "audio/mpeg")
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", "minimax_tts_synthesize")
      .put("path", targetPath)
      .put("model", config.ttsModel.value)
      .put("voice", config.voice.value)
      .put("input_path", inputPath)
      .put("text_chars", sourceText.length)
      .put("bytes_written", bytesWritten)
      .put("raw_response", compactRaw(response))
  }

  fun generateMusic(
    context: Context,
    apiKey: String,
    config: MiniMaxOmniConfig,
    workspaceConfig: FileWorkspaceConfig,
    prompt: String,
    outputPath: String,
  ): JSONObject {
    val body =
      JSONObject()
        .put("model", "music-2.6")
        .put("prompt", prompt.trim())
        .put(
          "audio_setting",
          JSONObject()
            .put("format", "mp3")
            .put("sample_rate", 44_100)
            .put("bitrate", 128_000),
        )
        .put("output_format", "hex")
        .put("stream", false)
    when (config.musicMode) {
      MiniMaxMusicMode.INSTRUMENTAL -> body.put("is_instrumental", true).put("lyrics", "")
      MiniMaxMusicMode.AUTO_LYRICS -> body.put("lyrics_optimizer", true)
    }
    val response = postJson(
      url = "${config.apiHost.value}/v1/music_generation",
      apiKey = apiKey,
      body = body,
      authStyle = AuthStyle.BEARER,
      client = longClient,
    )
    val audio = response.optJSONObject("data")?.optString("audio").orEmpty()
    if (audio.isBlank()) {
      throw IOException("MiniMax music response did not include audio data.")
    }
    val bytes = decodeAudioPayload(audio)
    val targetPath = ensureMediaPath(outputPath, "minimax-music", "mp3")
    val bytesWritten = writeBytesToWorkspace(context, workspaceConfig, targetPath, bytes, "audio/mpeg")
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", "minimax_generate_music")
      .put("path", targetPath)
      .put("model", "music-2.6")
      .put("mode", config.musicMode.value)
      .put("prompt", prompt.trim())
      .put("bytes_written", bytesWritten)
      .put("raw_response", compactRaw(response))
  }

  fun analyzeImage(
    context: Context,
    apiKey: String,
    config: MiniMaxOmniConfig,
    workspaceConfig: FileWorkspaceConfig,
    inputPath: String,
    prompt: String,
  ): JSONObject {
    val bytes = readWorkspaceBytes(context, workspaceConfig, inputPath, config.mediaLimit.megabytes)
    val mime = mimeTypeForPath(inputPath)
    if (!mime.startsWith("image/")) {
      throw IOException("Image analysis requires an image file path.")
    }
    val dataUrl = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    val response = postJson(
      url = "${config.apiHost.value}/v1/coding_plan/vlm",
      apiKey = apiKey,
      body =
        JSONObject()
          .put("prompt", prompt.ifBlank { "Describe this image in the user's language." })
          .put("image_url", dataUrl),
      authStyle = AuthStyle.BEARER,
      client = longClient,
    )
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", "minimax_analyze_image")
      .put("path", inputPath)
      .put("model", config.visionModel.value)
      .put("content", response.optString("content").ifBlank { response.toString().take(2000) })
      .put("raw_response", compactRaw(response))
  }

  fun analyzeVideo(
    context: Context,
    apiKey: String,
    config: MiniMaxOmniConfig,
    workspaceConfig: FileWorkspaceConfig,
    inputPath: String,
    prompt: String,
  ): JSONObject {
    val bytes = readWorkspaceBytes(context, workspaceConfig, inputPath, config.mediaLimit.megabytes)
    val mime = mimeTypeForPath(inputPath)
    if (!mime.startsWith("video/")) {
      throw IOException("Video analysis requires a video file path.")
    }
    val response = postJson(
      url = "${config.apiHost.value}/anthropic/v1/messages",
      apiKey = apiKey,
      body =
        JSONObject()
          .put("model", config.videoModel.value)
          .put("max_tokens", config.textMaxTokens)
          .put(
            "messages",
            JSONArray()
              .put(
                JSONObject()
                  .put("role", "user")
                  .put(
                    "content",
                    JSONArray()
                      .put(JSONObject().put("type", "text").put("text", prompt.ifBlank { "Describe this video in the user's language." }))
                      .put(
                        JSONObject()
                          .put("type", "video")
                          .put(
                            "source",
                            JSONObject()
                              .put("type", "base64")
                              .put("media_type", mime)
                              .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                          ),
                      ),
                  ),
              ),
          ),
      authStyle = AuthStyle.X_API_KEY,
      client = longClient,
    )
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", "minimax_analyze_video")
      .put("path", inputPath)
      .put("model", config.videoModel.value)
      .put("content", extractAnthropicText(response).ifBlank { response.toString().take(2000) })
      .put("raw_response", compactRaw(response))
  }

  fun searchWeb(
    apiKey: String,
    config: MiniMaxOmniConfig,
    query: String,
  ): JSONObject {
    val response = postJson(
      url = "${config.apiHost.value}/v1/coding_plan/search",
      apiKey = apiKey,
      body = JSONObject().put("q", query.trim()),
      authStyle = AuthStyle.BEARER,
      client = normalClient,
    )
    val organic = response.optJSONArray("organic") ?: JSONArray()
    val compact = JSONArray()
    for (i in 0 until minOf(organic.length(), 5)) {
      val item = organic.optJSONObject(i) ?: continue
      compact.put(
        JSONObject()
          .put("title", item.optString("title"))
          .put("link", item.optString("link"))
          .put("snippet", item.optString("snippet").take(500))
          .put("date", item.optString("date")),
      )
    }
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", "minimax_search_web")
      .put("query", query.trim())
      .put("results", compact)
      .put("raw_response", compactRaw(response))
  }

  fun voicesSummary(): JSONArray {
    val voices = JSONArray()
    MiniMaxVoice.entries.forEach { voice ->
      voices.put(JSONObject().put("id", voice.value).put("label", voice.label))
    }
    return voices
  }

  private enum class AuthStyle {
    BEARER,
    X_API_KEY,
  }

  private fun postJson(
    url: String,
    apiKey: String,
    body: JSONObject,
    authStyle: AuthStyle,
    client: OkHttpClient,
  ): JSONObject {
    val builder =
      Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")
        .header("User-Agent", "local-agent-plaza-minimax/1.0")
    when (authStyle) {
      AuthStyle.BEARER -> builder.header("Authorization", "Bearer ${apiKey.trim()}")
      AuthStyle.X_API_KEY -> builder.header("x-api-key", apiKey.trim())
    }
    val request = builder.post(body.toString().toRequestBody(jsonMediaType)).build()
    client.newCall(request).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        throw IOException("MiniMax API HTTP ${response.code}: ${text.take(600)}")
      }
      val json = JSONObject(text.ifBlank { "{}" })
      val baseResp = json.optJSONObject("base_resp")
      if (baseResp != null && baseResp.optInt("status_code", 0) != 0) {
        throw IOException("MiniMax API status ${baseResp.optInt("status_code")}: ${baseResp.optString("status_msg")}")
      }
      return json
    }
  }

  private fun downloadToWorkspace(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    url: String,
    path: String,
    client: OkHttpClient,
  ): Long {
    val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IOException("Failed to download generated media HTTP ${response.code}.")
      }
      val body = response.body ?: throw IOException("Generated media response was empty.")
      val bytes = body.bytes()
      return writeBytesToWorkspace(context, workspaceConfig, path, bytes, mimeTypeForPath(path))
    }
  }

  private fun writeBytesToWorkspace(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    path: String,
    bytes: ByteArray,
    mimeType: String,
  ): Long {
    val root =
      DocumentFile.fromTreeUri(context, workspaceConfig.treeUri.toUri())
        ?: throw IOException("Workspace folder is not available.")
    val target = ensureWritableFile(root, path, mimeType)
    context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
      output.write(bytes)
      return bytes.size.toLong()
    }
    throw IOException("Failed to open generated media output file.")
  }

  private fun readWorkspaceText(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    path: String,
    maxBytes: Int,
  ): String {
    return readWorkspaceBytes(context, workspaceConfig, path, maxMegabytes = maxOf(1, maxBytes / 1_000_000))
      .toString(Charsets.UTF_8)
  }

  private fun readWorkspaceBytes(
    context: Context,
    workspaceConfig: FileWorkspaceConfig,
    path: String,
    maxMegabytes: Int,
  ): ByteArray {
    val root =
      DocumentFile.fromTreeUri(context, workspaceConfig.treeUri.toUri())
        ?: throw IOException("Workspace folder is not available.")
    val document = resolveWorkspaceFile(root, path)
    if (!document.isFile) {
      throw IOException("Workspace path is not a file: $path")
    }
    val maxBytes = maxMegabytes.toLong() * 1024L * 1024L
    val declaredLength = document.length()
    if (declaredLength > maxBytes) {
      throw IOException("File is ${declaredLength} bytes, over configured MiniMax media limit of $maxMegabytes MB.")
    }
    val buffer = ByteArrayOutputStream()
    context.contentResolver.openInputStream(document.uri)?.use { input ->
      val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(chunk)
        if (read <= 0) break
        buffer.write(chunk, 0, read)
        if (buffer.size().toLong() > maxBytes) {
          throw IOException("File is over configured MiniMax media limit of $maxMegabytes MB.")
        }
      }
    } ?: throw IOException("Failed to open workspace file: $path")
    return buffer.toByteArray()
  }

  private fun resolveWorkspaceFile(root: DocumentFile, path: String): DocumentFile {
    val clean = path.replace('\\', '/').trim('/')
    if (clean.isBlank()) {
      throw IOException("Workspace file path is required.")
    }
    var cur = root
    clean.split('/').filter { it.isNotBlank() }.forEach { part ->
      cur = cur.findFile(part) ?: throw IOException("Workspace file not found: $path")
    }
    return cur
  }

  private fun ensureWritableFile(root: DocumentFile, path: String, mimeType: String): DocumentFile {
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
    return dir.createFile(mimeType, name)
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
      "gif" -> "image/gif"
      "mp3" -> "audio/mpeg"
      "wav" -> "audio/wav"
      "m4a" -> "audio/mp4"
      "mp4" -> "video/mp4"
      "mov" -> "video/quicktime"
      "webm" -> "video/webm"
      else -> "application/octet-stream"
    }
  }

  private fun decodeAudioPayload(payload: String): ByteArray {
    val clean = payload.trim()
    return if (clean.length % 2 == 0 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
      ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    } else {
      Base64.decode(clean, Base64.DEFAULT)
    }
  }

  private fun extractAnthropicText(response: JSONObject): String {
    val content = response.optJSONArray("content") ?: return ""
    val pieces = mutableListOf<String>()
    for (i in 0 until content.length()) {
      val item = content.optJSONObject(i) ?: continue
      if (item.optString("type") == "text") {
        pieces += item.optString("text")
      }
    }
    return pieces.joinToString("\n").trim()
  }

  private fun compactRaw(response: JSONObject): JSONObject {
    return JSONObject(response.toString()).apply {
      optJSONObject("data")?.remove("audio")
      remove("content")
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
}
