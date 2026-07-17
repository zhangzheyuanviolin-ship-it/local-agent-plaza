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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object AgentWeatherSupport {
  private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
  private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

  fun query(
    context: Context,
    location: String,
    mode: String,
    language: String = "zh",
  ): Map<String, Any> {
    val place = resolvePlace(context = context, location = location, language = language)
    val forecast = fetchForecast(latitude = place.latitude, longitude = place.longitude)
    val normalizedMode = mode.lowercase(Locale.US).ifBlank { "current" }
    val summary =
      when (normalizedMode) {
        "current", "now", "当前", "实时" -> summarizeCurrent(place = place, forecast = forecast)
        "24h", "24_hour", "next_24h", "hourly", "未来24小时" ->
          summarizeNext24Hours(place = place, forecast = forecast)
        "week", "7d", "weekly", "未来一周" -> summarizeWeek(place = place, forecast = forecast)
        else -> summarizeCurrent(place = place, forecast = forecast)
      }
    return mapOf(
      "status" to "succeeded",
      "operation" to "weather_query",
      "location" to place.displayName,
      "latitude" to place.latitude,
      "longitude" to place.longitude,
      "mode" to normalizedMode,
      "summary" to summary,
      "source" to "Open-Meteo",
      "result" to summary,
    )
  }

  private fun resolvePlace(context: Context, location: String, language: String): WeatherPlace {
    val trimmed = location.trim()
    if (trimmed.equals("current", ignoreCase = true) || trimmed == "当前位置" || trimmed == "本地") {
      getLastKnownLocation(context)?.let {
        return WeatherPlace(
          displayName = "当前位置",
          latitude = it.latitude,
          longitude = it.longitude,
        )
      }
      throw IllegalArgumentException("当前应用没有可用定位结果。请改用城市名称，例如 location=昆明。")
    }
    if (trimmed.isBlank()) {
      throw IllegalArgumentException("location is required. Use a city name such as 昆明, 北京, London, or New York.")
    }
    val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
    val lang = language.ifBlank { "zh" }
    val json =
      httpGetJson("$GEOCODING_URL?name=$encoded&count=1&language=$lang&format=json")
    val results = json.optJSONArray("results") ?: JSONArray()
    val first =
      results.optJSONObject(0)
        ?: throw IllegalArgumentException("没有找到城市或地区：$trimmed")
    val name = first.optString("name").ifBlank { trimmed }
    val admin = first.optString("admin1")
    val country = first.optString("country")
    val display =
      listOf(name, admin, country)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(", ")
    return WeatherPlace(
      displayName = display.ifBlank { trimmed },
      latitude = first.getDouble("latitude"),
      longitude = first.getDouble("longitude"),
    )
  }

  private fun getLastKnownLocation(context: Context): Location? {
    val hasFine =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val hasCoarse =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) {
      return null
    }
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers =
      runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
    return providers
      .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
      .maxByOrNull { it.time }
  }

  private fun fetchForecast(latitude: Double, longitude: Double): JSONObject {
    val current =
      "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
    val hourly =
      "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation_probability,precipitation,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
    val daily =
      "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max"
    return httpGetJson(
      "$FORECAST_URL?latitude=$latitude&longitude=$longitude&current=$current&hourly=$hourly&daily=$daily&timezone=auto&forecast_days=7&forecast_hours=24"
    )
  }

  private fun summarizeCurrent(place: WeatherPlace, forecast: JSONObject): String {
    val current = forecast.optJSONObject("current") ?: JSONObject()
    return buildString {
      append("${place.displayName}当前天气：")
      append(formatWeatherCode(current.optInt("weather_code", -1)))
      append("，气温${formatNumber(current.optDouble("temperature_2m"))}℃")
      append("，体感${formatNumber(current.optDouble("apparent_temperature"))}℃")
      append("，湿度${current.optInt("relative_humidity_2m", 0)}%")
      append("，降水${formatNumber(current.optDouble("precipitation"))}mm")
      append("，云量${current.optInt("cloud_cover", 0)}%")
      append("，风速${formatNumber(current.optDouble("wind_speed_10m"))}km/h")
      append("，阵风${formatNumber(current.optDouble("wind_gusts_10m"))}km/h")
      append("，气压${formatNumber(current.optDouble("pressure_msl"))}hPa。")
      current.optString("time").takeIf { it.isNotBlank() }?.let { append("更新时间：$it。") }
    }
  }

  private fun summarizeNext24Hours(place: WeatherPlace, forecast: JSONObject): String {
    val hourly = forecast.optJSONObject("hourly") ?: JSONObject()
    val times = hourly.optJSONArray("time") ?: JSONArray()
    val temps = hourly.optJSONArray("temperature_2m") ?: JSONArray()
    val pops = hourly.optJSONArray("precipitation_probability") ?: JSONArray()
    val codes = hourly.optJSONArray("weather_code") ?: JSONArray()
    val winds = hourly.optJSONArray("wind_speed_10m") ?: JSONArray()
    val blocks = mutableListOf<String>()
    val step = maxOf(1, times.length() / 8)
    var i = 0
    while (i < times.length() && blocks.size < 8) {
      blocks +=
        "${times.optString(i)} ${formatWeatherCode(codes.optInt(i, -1))} ${formatNumber(temps.optDouble(i))}℃ 降水概率${pops.optInt(i, 0)}% 风${formatNumber(winds.optDouble(i))}km/h"
      i += step
    }
    return "${place.displayName}未来24小时天气：${blocks.joinToString("；")}。"
  }

  private fun summarizeWeek(place: WeatherPlace, forecast: JSONObject): String {
    val daily = forecast.optJSONObject("daily") ?: JSONObject()
    val times = daily.optJSONArray("time") ?: JSONArray()
    val maxTemps = daily.optJSONArray("temperature_2m_max") ?: JSONArray()
    val minTemps = daily.optJSONArray("temperature_2m_min") ?: JSONArray()
    val pops = daily.optJSONArray("precipitation_probability_max") ?: JSONArray()
    val rain = daily.optJSONArray("precipitation_sum") ?: JSONArray()
    val codes = daily.optJSONArray("weather_code") ?: JSONArray()
    val winds = daily.optJSONArray("wind_speed_10m_max") ?: JSONArray()
    val days =
      (0 until minOf(7, times.length())).joinToString("；") { i ->
        "${times.optString(i)} ${formatWeatherCode(codes.optInt(i, -1))} ${formatNumber(minTemps.optDouble(i))}-${formatNumber(maxTemps.optDouble(i))}℃ 降水${formatNumber(rain.optDouble(i))}mm 概率${pops.optInt(i, 0)}% 最大风${formatNumber(winds.optDouble(i))}km/h"
      }
    return "${place.displayName}未来一周天气：$days。"
  }

  private fun httpGetJson(url: String): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection)
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 15000
    connection.readTimeout = 30000
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("User-Agent", "LocalAgentPlaza/1.0")
    val code = connection.responseCode
    val text =
      try {
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
      } catch (e: Exception) {
        val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        throw IllegalStateException("HTTP $code: $error")
      } finally {
        connection.disconnect()
      }
    return JSONObject(text)
  }

  private fun formatWeatherCode(code: Int): String {
    return when (code) {
      0 -> "晴"
      1 -> "大部晴朗"
      2 -> "局部多云"
      3 -> "阴"
      45, 48 -> "雾"
      51, 53, 55 -> "毛毛雨"
      56, 57 -> "冻雨"
      61, 63, 65 -> "降雨"
      66, 67 -> "冻雨"
      71, 73, 75 -> "降雪"
      77 -> "雪粒"
      80, 81, 82 -> "阵雨"
      85, 86 -> "阵雪"
      95 -> "雷暴"
      96, 99 -> "雷暴伴冰雹"
      else -> "未知天气"
    }
  }

  private fun formatNumber(value: Double): String {
    if (value.isNaN()) {
      return "0"
    }
    return if (kotlin.math.abs(value - value.toInt()) < 0.05) {
      value.toInt().toString()
    } else {
      String.format(Locale.US, "%.1f", value)
    }
  }

  private data class WeatherPlace(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
  )
}
