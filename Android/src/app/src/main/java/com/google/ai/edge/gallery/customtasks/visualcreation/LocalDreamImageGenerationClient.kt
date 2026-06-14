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

package com.google.ai.edge.gallery.customtasks.visualcreation

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class LocalDreamImageGenerationClient(private val context: Context) {
  private val baseUrl = "http://127.0.0.1:${LocalDreamBackendService.LOCAL_DREAM_PORT}"
  private val client =
    OkHttpClient.Builder()
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.MINUTES)
      .writeTimeout(30, TimeUnit.SECONDS)
      .build()

  suspend fun generateImage(
    modelPath: String,
    prompt: String,
    negativePrompt: String,
    width: Int,
    height: Int,
    steps: Int,
    cfgScale: Float,
    seed: Long,
    useOpenCl: Boolean = false,
  ): NativeImageGenerationResult = withContext(Dispatchers.IO) {
    require(File(modelPath).exists()) { "Local Dream 模型目录不存在：$modelPath" }
    LocalDreamBackendService.start(context = context, modelPath = modelPath, useGpu = useOpenCl)
    waitUntilHealthy()

    val json =
      JSONObject().apply {
        put("prompt", prompt)
        put(
          "negative_prompt",
          negativePrompt.ifBlank { DEFAULT_NEGATIVE_PROMPT },
        )
        put("steps", steps)
        put("cfg", cfgScale.toDouble())
        if (seed > 0L) {
          put("seed", seed)
        }
        put("width", width)
        put("height", height)
        put("scheduler", "dpm")
        put("use_opencl", useOpenCl)
        put("stream", false)
      }
    val request =
      Request.Builder()
        .url("$baseUrl/generate")
        .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        error("Local Dream 生成失败：HTTP ${response.code} ${response.message}")
      }
      val body = response.body?.string().orEmpty()
      val imageBase64 =
        parseGenerateResponse(body)?.optString("image").orEmpty().ifBlank {
          error("Local Dream 没有返回图片数据")
        }
      val imageBytes = Base64.decode(imageBase64, Base64.DEFAULT)
      val pixelCount = width * height
      val channels =
        when (imageBytes.size) {
          pixelCount * 3 -> 3
          pixelCount * 4 -> 4
          else -> error("Local Dream 返回图片字节数异常：${imageBytes.size}")
        }
      NativeImageGenerationResult(width = width, height = height, channels = channels, bytes = imageBytes)
    }
  }

  suspend fun stopGeneration() = withContext(Dispatchers.IO) {
    try {
      client
        .newCall(Request.Builder().url("$baseUrl/stop").post(ByteArray(0).toRequestBody()).build())
        .execute()
        .close()
    } catch (_: Throwable) {}
  }

  private suspend fun waitUntilHealthy() {
    repeat(45) {
      if (checkBackendHealth()) {
        return
      }
      delay(1_000)
    }
    error("Local Dream 后端 45 秒内没有就绪")
  }

  private fun checkBackendHealth(): Boolean {
    return try {
      client.newCall(Request.Builder().url("$baseUrl/health").get().build()).execute().use {
        it.isSuccessful
      }
    } catch (_: IOException) {
      false
    }
  }

  private fun parseGenerateResponse(responseBody: String): JSONObject? {
    try {
      return JSONObject(responseBody)
    } catch (_: Exception) {}

    try {
      val array = JSONArray(responseBody)
      for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i)
        if (obj != null && obj.has("image")) {
          return obj
        }
      }
      if (array.length() > 0) {
        return array.optJSONObject(0)
      }
    } catch (_: Exception) {}

    var lastObject: JSONObject? = null
    responseBody.lineSequence().forEach { rawLine ->
      val line = rawLine.trim()
      if (line.isBlank()) {
        return@forEach
      }
      val payload =
        when {
          line.startsWith("data:", ignoreCase = true) -> line.substringAfter(':').trim()
          line.startsWith("event:", ignoreCase = true) -> return@forEach
          else -> line
        }
      if (payload.isBlank() || payload.equals("[DONE]", ignoreCase = true)) {
        return@forEach
      }
      try {
        val obj = JSONObject(payload)
        if (obj.has("image")) {
          lastObject = obj
          return@forEach
        }
        lastObject = obj
      } catch (_: Exception) {
        val start = payload.indexOf('{')
        val end = payload.lastIndexOf('}')
        if (start >= 0 && end > start) {
          try {
            val obj = JSONObject(payload.substring(start, end + 1))
            lastObject = obj
          } catch (_: Exception) {}
        }
      }
    }
    return lastObject
  }

  companion object {
    private const val DEFAULT_NEGATIVE_PROMPT =
      "lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, fewer digits, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, blurry"
  }
}
