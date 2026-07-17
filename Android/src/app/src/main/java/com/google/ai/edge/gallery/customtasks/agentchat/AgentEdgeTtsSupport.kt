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

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

object AgentEdgeTtsSupport {
  private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
  private const val WSS_URL =
    "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
  private const val SEC_MS_GEC_VERSION = "1-143.0.3650.75"
  private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
  private const val MAX_TEXT_CHARS = 20000
  private const val CHUNK_CHARS = 3500
  @Volatile private var clockSkewSeconds = 0L
  private val client =
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .writeTimeout(120, TimeUnit.SECONDS)
      .pingInterval(20, TimeUnit.SECONDS)
      .build()

  val voices =
    listOf(
      EdgeVoice("zh-CN-XiaoxiaoNeural", "晓晓", "中文普通话", "温柔女声"),
      EdgeVoice("zh-CN-XiaoyiNeural", "晓伊", "中文普通话", "知性女声"),
      EdgeVoice("zh-CN-YunyangNeural", "云扬", "中文普通话", "沉稳男声"),
      EdgeVoice("zh-CN-YunxiNeural", "云希", "中文普通话", "青年男声"),
      EdgeVoice("zh-CN-YunjianNeural", "云健", "中文普通话", "成熟男声"),
      EdgeVoice("zh-CN-liaoning-XiaobeiNeural", "晓北", "中文辽宁方言", "东北女声"),
      EdgeVoice("zh-TW-HsiaoChenNeural", "晓臻", "中文台湾", "台湾女声"),
      EdgeVoice("zh-TW-YunJheNeural", "云哲", "中文台湾", "台湾男声"),
      EdgeVoice("zh-HK-HiuGaaiNeural", "晓佳", "粤语香港", "粤语女声"),
      EdgeVoice("zh-HK-WanLungNeural", "云龙", "粤语香港", "粤语男声"),
      EdgeVoice("en-US-JennyNeural", "Jenny", "English US", "美式女声"),
      EdgeVoice("en-US-GuyNeural", "Guy", "English US", "美式男声"),
      EdgeVoice("en-GB-SoniaNeural", "Sonia", "English UK", "英式女声"),
      EdgeVoice("en-GB-RyanNeural", "Ryan", "English UK", "英式男声"),
      EdgeVoice("en-AU-NatashaNeural", "Natasha", "English AU", "澳洲女声"),
    )

  fun voicesJson(): JSONArray {
    val array = JSONArray()
    for (voice in voices) {
      array.put(
        JSONObject()
          .put("voice", voice.id)
          .put("name", voice.name)
          .put("locale", voice.locale)
          .put("description", voice.description)
      )
    }
    return array
  }

  fun synthesize(
    text: String,
    voice: String,
    rate: String = "+0%",
    pitch: String = "+0Hz",
    volume: String = "+0%",
  ): ByteArray {
    val normalizedText = text.trim()
    require(normalizedText.isNotBlank()) { "text is required." }
    require(normalizedText.length <= MAX_TEXT_CHARS) {
      "Text is too long for one TTS request. Max $MAX_TEXT_CHARS characters."
    }
    val voiceId = resolveVoice(voice)
    val output = ByteArrayOutputStream()
    for (chunk in splitText(normalizedText)) {
      output.write(synthesizeChunk(chunk, voiceId, rate, pitch, volume))
    }
    return output.toByteArray()
  }

  fun defaultOutputPath(): String {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "media/tts-$timestamp.mp3"
  }

  fun ensureMediaPath(path: String): String {
    val trimmed = path.replace('\\', '/').trim()
    if (trimmed.isBlank()) {
      return defaultOutputPath()
    }
    val withExtension = if (trimmed.lowercase(Locale.US).endsWith(".mp3")) trimmed else "$trimmed.mp3"
    return if ('/' in withExtension) withExtension else "media/$withExtension"
  }

  private fun resolveVoice(voice: String): String {
    val normalized = voice.trim()
    if (normalized.isBlank()) {
      return "zh-CN-XiaoxiaoNeural"
    }
    voices.firstOrNull {
      it.id.equals(normalized, ignoreCase = true) ||
        it.name.equals(normalized, ignoreCase = true)
    }?.let { return it.id }
    return normalized
  }

  private fun splitText(text: String): List<String> {
    val chunks = mutableListOf<String>()
    var rest = text
    while (rest.length > CHUNK_CHARS) {
      val splitIndex =
        listOf(
            rest.lastIndexOf('。', CHUNK_CHARS),
            rest.lastIndexOf('！', CHUNK_CHARS),
            rest.lastIndexOf('？', CHUNK_CHARS),
            rest.lastIndexOf('.', CHUNK_CHARS),
            rest.lastIndexOf('\n', CHUNK_CHARS),
            rest.lastIndexOf(' ', CHUNK_CHARS),
          )
          .filter { it > CHUNK_CHARS / 2 }
          .maxOrNull()
          ?: CHUNK_CHARS
      chunks += rest.substring(0, splitIndex + 1).trim()
      rest = rest.substring(splitIndex + 1).trim()
    }
    if (rest.isNotBlank()) {
      chunks += rest
    }
    return chunks
  }

  private fun synthesizeChunk(
    text: String,
    voice: String,
    rate: String,
    pitch: String,
    volume: String,
  ): ByteArray {
    var lastFailure: EdgeTtsFailure? = null
    repeat(2) { attempt ->
      val result =
        trySynthesizeChunk(
          text = text,
          voice = voice,
          rate = rate,
          pitch = pitch,
          volume = volume,
        )
      if (result.failure == null) {
        return result.audio
      }
      lastFailure = result.failure
      if (
        attempt == 0 &&
          result.failure.statusCode == 403 &&
          adjustClockSkewFromServerDate(result.failure.serverDate)
      ) {
        return@repeat
      }
      throw IllegalStateException(result.failure.message)
    }
    throw IllegalStateException(lastFailure?.message ?: "Edge TTS request failed.")
  }

  private fun trySynthesizeChunk(
    text: String,
    voice: String,
    rate: String,
    pitch: String,
    volume: String,
  ): EdgeTtsChunkResult {
    val connectionId = UUID.randomUUID().toString().replace("-", "")
    val secMsGec = buildSecMsGec()
    val request =
      Request.Builder()
        .url(
          "$WSS_URL?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=$secMsGec" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
        )
        .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
        .header("User-Agent", USER_AGENT)
        .header("Accept-Encoding", "gzip, deflate, br, zstd")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Pragma", "no-cache")
        .header("Cache-Control", "no-cache")
        .header("Cookie", "muid=${generateMuid()};")
        .build()
    val latch = CountDownLatch(1)
    val audio = ByteArrayOutputStream()
    val failure = arrayOfNulls<EdgeTtsFailure>(1)
    val listener =
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          webSocket.send(buildSpeechConfigMessage())
          webSocket.send(buildSsmlMessage(requestId = connectionId, text = text, voice = voice, rate = rate, pitch = pitch, volume = volume))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
          val frame = bytes.toByteArray()
          val audioPayload = extractAudioPayload(frame)
          if (audioPayload != null) {
            audio.write(audioPayload)
          }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          if (text.contains("Path:turn.end")) {
            webSocket.close(1000, "done")
            latch.countDown()
          }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          val status = response?.code
          val serverDate = response?.header("Date")
          val serverMessage =
            when {
              status != null -> "HTTP $status ${response.message}".trim()
              else -> t.message ?: "Edge TTS request failed."
            }
          val dateHint = serverDate?.let { " Server-Date=$it." } ?: ""
          failure[0] =
            EdgeTtsFailure(
              message = "$serverMessage.${dateHint} ${t.message ?: ""}".trim(),
              statusCode = status,
              serverDate = serverDate,
            )
          latch.countDown()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
          latch.countDown()
        }
      }
    val webSocket = client.newWebSocket(request, listener)
    if (!latch.await(120, TimeUnit.SECONDS)) {
      webSocket.cancel()
      return EdgeTtsChunkResult(
        audio = ByteArray(0),
        failure = EdgeTtsFailure("Edge TTS timed out.", statusCode = null, serverDate = null),
      )
    }
    failure[0]?.let { return EdgeTtsChunkResult(audio = ByteArray(0), failure = it) }
    val bytes = audio.toByteArray()
    if (bytes.isEmpty()) {
      return EdgeTtsChunkResult(
        audio = ByteArray(0),
        failure =
          EdgeTtsFailure(
            message = "Edge TTS returned empty audio.",
            statusCode = null,
            serverDate = null,
          ),
      )
    }
    return EdgeTtsChunkResult(audio = bytes, failure = null)
  }

  private fun buildSpeechConfigMessage(): String {
    return buildString {
      append("X-Timestamp:${edgeDate()}\r\n")
      append("Content-Type:application/json; charset=utf-8\r\n")
      append("Path:speech.config\r\n\r\n")
      append("""{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":false,"wordBoundaryEnabled":false},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""")
    }
  }

  private fun buildSsmlMessage(
    requestId: String,
    text: String,
    voice: String,
    rate: String,
    pitch: String,
    volume: String,
  ): String {
    val ssml =
      """<speak version="1.0" xml:lang="zh-CN"><voice name="$voice"><prosody pitch="$pitch" rate="$rate" volume="$volume">${escapeXml(text)}</prosody></voice></speak>"""
    return buildString {
      append("X-RequestId:$requestId\r\n")
      append("X-Timestamp:${edgeDate()}\r\n")
      append("Content-Type:application/ssml+xml\r\n")
      append("Path:ssml\r\n\r\n")
      append(ssml)
    }
  }

  private fun extractAudioPayload(frame: ByteArray): ByteArray? {
    if (frame.size < 2) {
      return null
    }
    val headerLength = ((frame[0].toInt() and 0xff) shl 8) or (frame[1].toInt() and 0xff)
    if (frame.size <= 2 + headerLength) {
      return null
    }
    val header = frame.copyOfRange(2, 2 + headerLength).toString(Charsets.UTF_8)
    if (!header.contains("Path:audio")) {
      return null
    }
    return frame.copyOfRange(2 + headerLength, frame.size)
  }

  private fun buildSecMsGec(): String {
    val unixSeconds = System.currentTimeMillis() / 1000L + clockSkewSeconds
    val roundedUnixSeconds = unixSeconds - unixSeconds % 300L
    val roundedTicks = (roundedUnixSeconds + 11644473600L) * 10_000_000L
    val input = "$roundedTicks$TRUSTED_CLIENT_TOKEN"
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02X".format(it.toInt() and 0xff) }
  }

  private fun adjustClockSkewFromServerDate(serverDate: String?): Boolean {
    if (serverDate.isNullOrBlank()) {
      return false
    }
    val parser =
      SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
      }
    val serverMillis =
      try {
        parser.parse(serverDate)?.time
      } catch (_: Exception) {
        null
      } ?: return false
    clockSkewSeconds = (serverMillis - System.currentTimeMillis()) / 1000L
    return true
  }

  private fun generateMuid(): String {
    return UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)
  }

  private fun edgeDate(): String {
    return SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z (zzzz)", Locale.US)
      .apply { timeZone = TimeZone.getTimeZone("GMT") }
      .format(Date())
  }

  private fun escapeXml(text: String): String {
    return text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
  }

  data class EdgeVoice(
    val id: String,
    val name: String,
    val locale: String,
    val description: String,
  )

  private data class EdgeTtsChunkResult(
    val audio: ByteArray,
    val failure: EdgeTtsFailure?,
  )

  private data class EdgeTtsFailure(
    val message: String,
    val statusCode: Int?,
    val serverDate: String?,
  )
}
