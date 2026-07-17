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
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.Exception
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

@JsonClass(generateAdapter = true)
data class SendEmailParams(
  val extra_email: String,
  val extra_subject: String,
  val extra_text: String,
)

@JsonClass(generateAdapter = true)
data class SendSmsParams(val phone_number: String, val sms_body: String)

@JsonClass(generateAdapter = true)
data class CreateCalendarEventParams(
  val title: String,
  val description: String,
  val begin_time: String,
  val end_time: String,
)

enum class IntentAction(val action: String) {
  SEND_EMAIL("send_email"),
  SEND_SMS("send_sms"),
  CREATE_CALENDAR_EVENT("create_calendar_event"),
  GET_CURRENT_DATE_AND_TIME("get_current_date_and_time"),
  FILE_WORKSPACE("file_workspace");

  companion object {
    fun from(action: String): IntentAction? = entries.find { it.action == action }
  }
}

object IntentHandler {
  private const val TAG = "IntentHandler"
  private const val DEFAULT_READ_MAX_BYTES = 64000
  private const val DEFAULT_DOCUMENT_INPUT_MAX_BYTES = 20_000_000
  private const val DEFAULT_LIST_MAX_ENTRIES = 200
  private const val ASSISTANT_RESPONSE_PLACEHOLDER = "__ASSISTANT_RESPONSE__"
  private const val WORKSPACE_PATH_HINT =
    "Use workspace-relative paths only. Prefer an empty path for the workspace root."
  private var pendingAssistantWrite: PendingAssistantWrite? = null

  data class PendingAssistantWrite(
    val treeUriString: String,
    val path: String,
    val bufferedContent: String = "",
    val bytesWritten: Int = 0,
  )

  data class PendingAssistantWriteCommitResult(
    val path: String,
    val bytesWritten: Int,
  )

  fun handleAction(context: Context, action: String, parameters: String): String {
    return when (IntentAction.from(action)) {
      IntentAction.SEND_EMAIL -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(SendEmailParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val intent =
              Intent(Intent.ACTION_SEND).apply {
                data = "mailto:".toUri()
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(params.extra_email))
                putExtra(Intent.EXTRA_SUBJECT, params.extra_subject)
                putExtra(Intent.EXTRA_TEXT, params.extra_text)
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse send_email parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse send_email parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.SEND_SMS -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(SendSmsParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val uri = "smsto:${params.phone_number}".toUri()
            val intent = Intent(Intent.ACTION_SENDTO, uri)
            intent.putExtra("sms_body", params.sms_body)
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse send_sms parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse send_sms parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.CREATE_CALENDAR_EVENT -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(CreateCalendarEventParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val beginTimeMillis = format.parse(params.begin_time)?.time ?: 0L
            val endTimeMillis = format.parse(params.end_time)?.time ?: 0L
            val intent =
              Intent(Intent.ACTION_INSERT).apply {
                data = android.provider.CalendarContract.Events.CONTENT_URI
                putExtra(android.provider.CalendarContract.Events.TITLE, params.title)
                putExtra(android.provider.CalendarContract.Events.DESCRIPTION, params.description)
                putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTimeMillis)
                putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endTimeMillis)
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse create_calendar_event parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse create_calendar_event parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.GET_CURRENT_DATE_AND_TIME -> {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss EEEE", Locale.getDefault())
        val currentDateAndTime = sdf.format(Date())
        Log.d(
          TAG,
          "get_current_date_and_time via handleAction. Current date and time: $currentDateAndTime",
        )
        currentDateAndTime
      }
      IntentAction.FILE_WORKSPACE ->
        errorJson(
          "The file workspace intent requires saved skill configuration. Use run_configured_intent instead of run_intent."
        )
      null -> "failed"
    }
  }

  fun handleConfiguredAction(
    context: Context,
    skillName: String,
    action: String,
    parameters: String,
    config: String,
  ): String {
    return when {
      isWorkspaceSkill(skillName) && action == IntentAction.FILE_WORKSPACE.action ->
        handleFileWorkspaceAction(context = context, parameters = parameters, config = config)
      else -> handleAction(context = context, action = action, parameters = parameters)
    }
  }

  fun commitPendingAssistantWrite(
    context: Context,
    content: String? = null,
  ): PendingAssistantWriteCommitResult? {
    val pending = pendingAssistantWrite ?: return null
    return try {
      val root = DocumentFile.fromTreeUri(context, Uri.parse(pending.treeUriString))
      if (root == null || !root.exists()) {
        pendingAssistantWrite = null
        null
      } else {
        val finalContent =
          when {
            pending.bufferedContent.isNotEmpty() -> pending.bufferedContent
            !content.isNullOrEmpty() -> content
            else -> ""
          }
        var bytesWritten = pending.bytesWritten
        if (finalContent.isNotEmpty()) {
          val document =
            ensureWritableFile(
              root = root,
              path = pending.path,
              createParents = true,
              overwrite = true,
            )
          if (document == null) {
            pendingAssistantWrite = null
            return null
          }
          context.contentResolver.openOutputStream(document.uri, "wt")?.use { output ->
            output.write(finalContent.toByteArray(Charsets.UTF_8))
          }
          bytesWritten = finalContent.toByteArray(Charsets.UTF_8).size
        }
        pendingAssistantWrite = null
        PendingAssistantWriteCommitResult(
          path = normalizeDisplayPath(pending.path),
          bytesWritten = bytesWritten,
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to commit pending assistant write.", e)
      pendingAssistantWrite = null
      null
    }
  }

  fun hasPendingAssistantWrite(): Boolean = pendingAssistantWrite != null

  fun appendPendingAssistantWrite(context: Context, contentChunk: String): Boolean {
    val pending = pendingAssistantWrite ?: return false
    if (contentChunk.isEmpty()) {
      return true
    }
    return try {
      val bytes = contentChunk.toByteArray(Charsets.UTF_8)
      pendingAssistantWrite =
        pending.copy(
          bufferedContent = pending.bufferedContent + contentChunk,
          bytesWritten = pending.bytesWritten + bytes.size,
        )
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to append pending assistant write.", e)
      pendingAssistantWrite = null
      false
    }
  }

  fun clearPendingAssistantWrite() {
    pendingAssistantWrite = null
  }

  private fun handleFileWorkspaceAction(
    context: Context,
    parameters: String,
    config: String,
  ): String {
    return try {
      val configJson = JSONObject(config.ifBlank { "{}" })
      val treeUriString = configJson.optString("tree_uri")
      val displayName = configJson.optString("display_name")
      if (treeUriString.isBlank()) {
        return errorJson("No authorized folder is mounted for the file workspace skill.")
      }

      val rootUri = Uri.parse(treeUriString)
      val root = DocumentFile.fromTreeUri(context, rootUri)
      if (root == null || !root.exists()) {
        return errorJson("The authorized folder is unavailable. Please reselect it in skill config.")
      }

      val request = JSONObject(parameters.ifBlank { "{}" })
      val operation = request.optString("operation").trim()
      when (operation) {
        "status" ->
          successJson()
            .put("operation", "status")
            .put("mounted", true)
            .put("folder_name", displayName.ifBlank { root.name ?: "" })
            .put("tree_uri", treeUriString)
            .put("can_read", root.canRead())
            .put("can_write", root.canWrite())
            .toString()
        "list" -> handleList(root = root, request = request)
        "stat" -> handleStat(root = root, request = request)
        "read_text" -> handleReadText(context = context, root = root, request = request)
        "download_url" -> handleDownloadUrl(context = context, root = root, request = request)
        "write_text" -> handleWriteText(context = context, root = root, request = request)
        "append_text" -> handleAppendText(context = context, root = root, request = request)
        "create_dir" -> handleCreateDir(root = root, request = request)
        "delete" -> handleDelete(root = root, request = request)
        "copy" -> handleCopy(context = context, root = root, request = request)
        "move" -> handleMove(context = context, root = root, request = request)
        else ->
          errorJson(
            "Unsupported file workspace operation \"$operation\". Supported operations: status, list, stat, read_text, download_url, write_text, append_text, create_dir, delete, copy, move."
          )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to execute file workspace operation.", e)
      errorJson("Failed to execute file workspace operation: ${e.message ?: "Unknown error"}")
    }
  }

  private fun handleList(root: DocumentFile, request: JSONObject): String {
    val path = request.optString("path")
    val directory = if (path.isBlank()) root else resolveExistingDocument(root, path)
    if (directory == null || !directory.exists() || !directory.isDirectory) {
      return errorJson("Directory not found: ${path.ifBlank { "." }}. $WORKSPACE_PATH_HINT")
    }

    val entries = directory.listFiles().sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name ?: "" })
    val maxEntries = request.optInt("max_entries", DEFAULT_LIST_MAX_ENTRIES).coerceIn(1, 1000)
    val resultEntries = JSONArray()
    for (file in entries.take(maxEntries)) {
      resultEntries.put(documentToJson(file))
    }
    return successJson()
      .put("operation", "list")
      .put("path", normalizeDisplayPath(path))
      .put("entries", resultEntries)
      .put("truncated", entries.size > maxEntries)
      .toString()
  }

  private fun handleStat(root: DocumentFile, request: JSONObject): String {
    val path = request.optString("path")
    if (path.isBlank()) {
      return successJson()
        .put("operation", "stat")
        .put("path", ".")
        .put("entry", documentToJson(root).put("name", root.name ?: "."))
        .toString()
    }
    val document =
      resolveExistingDocument(root, path)
        ?: return errorJson("Path not found: $path. $WORKSPACE_PATH_HINT")
    return successJson()
      .put("operation", "stat")
      .put("path", normalizeDisplayPath(path))
      .put("entry", documentToJson(document))
      .toString()
  }

  private fun handleReadText(context: Context, root: DocumentFile, request: JSONObject): String {
    val path = request.optString("path")
    val document = resolveExistingDocument(root, path)
    if (document == null || !document.exists() || !document.isFile) {
      return errorJson("File not found: $path. $WORKSPACE_PATH_HINT")
    }

    val maxBytes = request.optInt("max_bytes", DEFAULT_READ_MAX_BYTES).coerceIn(256, 500000)
    val rawBytes =
      context.contentResolver.openInputStream(document.uri)?.use { input ->
        readUpTo(input, DEFAULT_DOCUMENT_INPUT_MAX_BYTES)
      } ?: return errorJson("Failed to open file: $path")
    val extracted =
      try {
        WorkspaceDocumentTextExtractor.extract(
          fileName = document.name ?: path,
          bytes = rawBytes.first,
          maxBytes = maxBytes,
          context = context,
        )
      } catch (e: Exception) {
        Log.e(TAG, "Failed to extract workspace document text.", e)
        return errorJson("Failed to extract text from file: $path. ${e.message ?: "Unknown error"}")
      }
    return successJson()
      .put("operation", "read_text")
      .put("path", normalizeDisplayPath(path))
      .put("content", extracted.content)
      .put("content_type", extracted.contentType)
      .put("detected_format", extracted.detectedFormat)
      .put("truncated", rawBytes.second || extracted.truncated)
      .put("bytes_read", extracted.bytesRead)
      .put("content_chars", extracted.contentChars)
      .put("content_bytes", extracted.contentBytes)
      .toString()
  }

  private fun handleDownloadUrl(context: Context, root: DocumentFile, request: JSONObject): String {
    val urlString = request.optString("url").ifBlank { request.optString("download_url") }.trim()
    if (urlString.isBlank()) {
      return errorJson("url is required for download_url.")
    }
    val url =
      runCatching { URL(urlString) }.getOrElse {
        return errorJson("Invalid url: $urlString")
      }
    if (url.protocol.lowercase(Locale.US) !in setOf("http", "https")) {
      return errorJson("Only http and https URLs are supported.")
    }
    val createParents = request.optBoolean("create_parents", true)
    val resume = request.optBoolean("resume", true)
    val overwrite = request.optBoolean("overwrite", false)
    val fallbackName =
      url.path.substringAfterLast('/').substringBefore('?').ifBlank {
        "download-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.bin"
      }
    val path = request.optString("path").ifBlank { fallbackName }
    val existing = resolveExistingDocument(root, path)
    if (existing != null && existing.isDirectory) {
      return errorJson("Target path is a directory: $path")
    }
    if (existing != null && overwrite && !existing.delete()) {
      return errorJson("Failed to overwrite existing file: $path")
    }
    val document =
      ensureWritableFile(
        root = root,
        path = path,
        createParents = createParents,
        overwrite = false,
      ) ?: return errorJson("Failed to create target file: $path")

    var existingBytes = if (resume && !overwrite) document.length().coerceAtLeast(0L) else 0L
    var connection = (url.openConnection() as HttpURLConnection)
    connection.instanceFollowRedirects = true
    connection.connectTimeout = request.optInt("connect_timeout_ms", 15000).coerceIn(1000, 120000)
    connection.readTimeout = request.optInt("read_timeout_ms", 60000).coerceIn(1000, 300000)
    if (existingBytes > 0L) {
      connection.setRequestProperty("Range", "bytes=$existingBytes-")
    }
    val responseCode = connection.responseCode
    val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
    if (existingBytes > 0L && !append) {
      existingBytes = 0L
    }

    val input =
      try {
        connection.inputStream
      } catch (e: Exception) {
        connection.errorStream?.use { errorStream ->
          val errorText = readUpTo(errorStream, 4096).first.toString(Charsets.UTF_8)
          return errorJson("Download failed with HTTP $responseCode: $errorText")
        }
        return errorJson("Download failed with HTTP $responseCode.")
      }
    val outputMode = if (append) "wa" else "wt"
    var written = 0L
    input.use { inStream ->
      context.contentResolver.openOutputStream(document.uri, outputMode)?.use { outStream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val read = inStream.read(buffer)
          if (read <= 0) {
            break
          }
          outStream.write(buffer, 0, read)
          written += read
        }
      } ?: return errorJson("Failed to open file for writing: $path")
    }
    connection.disconnect()

    return successJson()
      .put("operation", "download_url")
      .put("url", urlString)
      .put("path", normalizeDisplayPath(path))
      .put("response_code", responseCode)
      .put("resumed", append)
      .put("existing_bytes", existingBytes)
      .put("bytes_written", written)
      .put("final_bytes", document.length())
      .toString()
  }

  private fun handleWriteText(context: Context, root: DocumentFile, request: JSONObject): String {
    val path = request.optString("path")
    val content = request.optString("content")
    val createParents = request.optBoolean("create_parents", true)
    if (content.trim() == ASSISTANT_RESPONSE_PLACEHOLDER) {
      clearPendingAssistantWrite()
      return errorJson(
        "Do not use __ASSISTANT_RESPONSE__. Retry write_text with the full final text in content."
      )
    }
    val document =
      ensureWritableFile(
        root = root,
        path = path,
        createParents = createParents,
        overwrite = true,
      ) ?: return errorJson("Failed to create target file: $path")

    context.contentResolver.openOutputStream(document.uri, "wt")?.use { output ->
      output.write(content.toByteArray(Charsets.UTF_8))
    } ?: return errorJson("Failed to open file for writing: $path")

    return successJson()
      .put("operation", "write_text")
      .put("path", normalizeDisplayPath(path))
      .put("bytes_written", content.toByteArray(Charsets.UTF_8).size)
      .toString()
  }

  private fun handleAppendText(context: Context, root: DocumentFile, request: JSONObject): String {
    val path = request.optString("path")
    val content = request.optString("content")
    val createParents = request.optBoolean("create_parents", true)
    val document =
      ensureWritableFile(
        root = root,
        path = path,
        createParents = createParents,
        overwrite = false,
      ) ?: return errorJson("Failed to create target file: $path")

    val existingBytes =
      context.contentResolver.openInputStream(document.uri)?.use { input -> input.readBytes() }
        ?: byteArrayOf()
    val appendedBytes = existingBytes + content.toByteArray(Charsets.UTF_8)
    context.contentResolver.openOutputStream(document.uri, "wt")?.use { output ->
      output.write(appendedBytes)
    } ?: return errorJson("Failed to open file for appending: $path")

    return successJson()
      .put("operation", "append_text")
      .put("path", normalizeDisplayPath(path))
      .put("bytes_written", content.toByteArray(Charsets.UTF_8).size)
      .toString()
  }

  private fun handleCreateDir(root: DocumentFile, request: JSONObject): String {
    val path = request.optString("path")
    val created = ensureDirectory(root = root, path = path)
    return if (created == null) {
      errorJson("Failed to create directory: $path")
    } else {
      successJson()
        .put("operation", "create_dir")
        .put("path", normalizeDisplayPath(path))
        .toString()
    }
  }

  private fun handleDelete(root: DocumentFile, request: JSONObject): String {
    val path = request.optString("path")
    val recursive = request.optBoolean("recursive", false)
    if (path.isBlank()) {
      return errorJson("Refusing to delete the mounted root folder.")
    }
    val target =
      resolveExistingDocument(root, path)
        ?: return errorJson("Path not found: $path. $WORKSPACE_PATH_HINT")
    if (target.isDirectory && !recursive && target.listFiles().isNotEmpty()) {
      return errorJson("Directory is not empty. Set recursive=true to delete it.")
    }
    val deleted = deleteDocumentRecursive(target)
    return if (deleted) {
      successJson().put("operation", "delete").put("path", normalizeDisplayPath(path)).toString()
    } else {
      errorJson("Failed to delete: $path")
    }
  }

  private fun handleCopy(context: Context, root: DocumentFile, request: JSONObject): String {
    val sourcePath = request.optString("path")
    val destinationPath = request.optString("destination_path")
    val overwrite = request.optBoolean("overwrite", false)
    if (sourcePath.isBlank() || destinationPath.isBlank()) {
      return errorJson("Both path and destination_path are required for copy.")
    }
    val source =
      resolveExistingDocument(root, sourcePath)
        ?: return errorJson("Source path not found: $sourcePath. $WORKSPACE_PATH_HINT")
    val copied =
      copyDocument(
        context = context,
        root = root,
        source = source,
        destinationPath = destinationPath,
        overwrite = overwrite,
      )
    return if (copied) {
      successJson()
        .put("operation", "copy")
        .put("path", normalizeDisplayPath(sourcePath))
        .put("destination_path", normalizeDisplayPath(destinationPath))
        .toString()
    } else {
      errorJson("Failed to copy to: $destinationPath")
    }
  }

  private fun handleMove(context: Context, root: DocumentFile, request: JSONObject): String {
    val sourcePath = request.optString("path")
    val destinationPath = request.optString("destination_path")
    val overwrite = request.optBoolean("overwrite", false)
    if (sourcePath.isBlank() || destinationPath.isBlank()) {
      return errorJson("Both path and destination_path are required for move.")
    }
    val source =
      resolveExistingDocument(root, sourcePath)
        ?: return errorJson("Source path not found: $sourcePath. $WORKSPACE_PATH_HINT")
    val copied =
      copyDocument(
        context = context,
        root = root,
        source = source,
        destinationPath = destinationPath,
        overwrite = overwrite,
      )
    if (!copied) {
      return errorJson("Failed to move to: $destinationPath")
    }
    if (!deleteDocumentRecursive(source)) {
      return errorJson("The destination was created, but deleting the source failed: $sourcePath")
    }
    return successJson()
      .put("operation", "move")
      .put("path", normalizeDisplayPath(sourcePath))
      .put("destination_path", normalizeDisplayPath(destinationPath))
      .toString()
  }

  private fun normalizeSegments(path: String): List<String>? {
    val normalized = path.replace('\\', '/').trim()
    if (
      normalized.isBlank() ||
        normalized == "." ||
        normalized == "/" ||
        normalized == "./"
    ) {
      return emptyList()
    }
    val segments = normalized.trimStart('/').split('/').filter { it.isNotBlank() }
    if (segments.any { it == "." || it == ".." }) {
      return null
    }
    return segments
  }

  private fun normalizeDisplayPath(path: String): String {
    return path.replace('\\', '/').trim().ifBlank { "." }
  }

  private fun resolveExistingDocument(root: DocumentFile, path: String): DocumentFile? {
    val segments = normalizeSegments(path) ?: return null
    var current = root
    for (segment in segments) {
      current = current.findFile(segment) ?: return null
    }
    return current
  }

  private fun ensureDirectory(root: DocumentFile, path: String): DocumentFile? {
    val segments = normalizeSegments(path) ?: return null
    var current = root
    for (segment in segments) {
      val existing = current.findFile(segment)
      current =
        when {
          existing == null -> current.createDirectory(segment)
          existing.isDirectory -> existing
          else -> return null
        } ?: return null
    }
    return current
  }

  private fun ensureWritableFile(
    root: DocumentFile,
    path: String,
    createParents: Boolean,
    overwrite: Boolean,
  ): DocumentFile? {
    val segments = normalizeSegments(path) ?: return null
    if (segments.isEmpty()) {
      return null
    }
    val parentSegments = segments.dropLast(1).joinToString("/")
    val parent =
      if (parentSegments.isBlank()) root
      else if (createParents) ensureDirectory(root, parentSegments)
      else resolveExistingDocument(root, parentSegments)
    if (parent == null || !parent.isDirectory) {
      return null
    }
    val fileName = segments.last()
    val existing = parent.findFile(fileName)
    if (existing != null) {
      if (existing.isDirectory) {
        return null
      }
      if (!overwrite) {
        return existing
      }
      if (!existing.delete()) {
        return null
      }
    }
    return parent.createFile(guessMimeType(fileName), fileName)
  }

  private fun copyDocument(
    context: Context,
    root: DocumentFile,
    source: DocumentFile,
    destinationPath: String,
    overwrite: Boolean,
  ): Boolean {
    val segments = normalizeSegments(destinationPath) ?: return false
    if (segments.isEmpty()) {
      return false
    }
    val targetParentPath = segments.dropLast(1).joinToString("/")
    val targetName = segments.last()
    val targetParent =
      if (targetParentPath.isBlank()) root else ensureDirectory(root = root, path = targetParentPath)
        ?: return false
    val existing = targetParent.findFile(targetName)
    if (existing != null) {
      if (!overwrite || !deleteDocumentRecursive(existing)) {
        return false
      }
    }
    return if (source.isDirectory) {
      val createdDir = targetParent.createDirectory(targetName) ?: return false
      source.listFiles().all { child ->
        copyDocument(
          context = context,
          root = createdDir,
          source = child,
          destinationPath = child.name ?: "",
          overwrite = true,
        )
      }
    } else {
      val createdFile = targetParent.createFile(guessMimeType(targetName), targetName) ?: return false
      val input = context.contentResolver.openInputStream(source.uri) ?: return false
      val output = context.contentResolver.openOutputStream(createdFile.uri, "wt") ?: return false
      input.use { inStream -> output.use { outStream -> inStream.copyTo(outStream) } }
      true
    }
  }

  private fun deleteDocumentRecursive(document: DocumentFile): Boolean {
    if (document.isDirectory) {
      for (child in document.listFiles()) {
        if (!deleteDocumentRecursive(child)) {
          return false
        }
      }
    }
    return document.delete()
  }

  private fun documentToJson(document: DocumentFile): JSONObject {
    return JSONObject()
      .put("name", document.name ?: "")
      .put("type", if (document.isDirectory) "directory" else "file")
      .put("size", document.length())
      .put("last_modified", document.lastModified())
      .put("can_read", document.canRead())
      .put("can_write", document.canWrite())
  }

  private fun guessMimeType(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    if (extension.isBlank()) {
      return "application/octet-stream"
    }
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
      ?: URLConnection.guessContentTypeFromName(fileName)
      ?: if (extension in setOf("txt", "md", "json", "csv", "xml", "log", "html")) "text/plain"
      else "application/octet-stream"
  }

  private fun readUpTo(input: InputStream, maxBytes: Int): Pair<ByteArray, Boolean> {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    var total = 0
    while (true) {
      val read = input.read(buffer)
      if (read <= 0) {
        break
      }
      val bytesToWrite = minOf(read, maxBytes - total)
      if (bytesToWrite > 0) {
        output.write(buffer, 0, bytesToWrite)
        total += bytesToWrite
      }
      if (total >= maxBytes) {
        return output.toByteArray() to true
      }
    }
    return output.toByteArray() to false
  }

  private fun successJson(): JSONObject {
    return JSONObject().put("status", "succeeded")
  }

  private fun errorJson(message: String): String {
    return JSONObject().put("status", "failed").put("error", message).toString()
  }
}
