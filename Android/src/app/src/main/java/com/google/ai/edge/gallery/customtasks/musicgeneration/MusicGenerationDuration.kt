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

package com.google.ai.edge.gallery.customtasks.musicgeneration

import java.util.Locale

const val DEFAULT_MUSIC_DURATION_SECONDS = 12f

fun parseMusicDurationSeconds(input: String): Float? {
  val normalized =
    input
      .trim()
      .replace("秒", "")
      .replace("s", "", ignoreCase = true)
      .replace(",", ".")
      .trim()
  if (normalized.isBlank()) {
    return null
  }
  val value = normalized.toFloatOrNull() ?: return null
  return value.takeIf { it.isFinite() && it > 0f }
}

fun formatMusicDurationSeconds(value: Float): String {
  return if (value % 1f == 0f) {
    value.toInt().toString()
  } else {
    String.format(Locale.US, "%.1f", value)
  }
}
