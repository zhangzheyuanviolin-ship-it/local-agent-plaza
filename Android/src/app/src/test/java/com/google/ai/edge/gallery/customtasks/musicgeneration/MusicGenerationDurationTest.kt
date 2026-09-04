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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicGenerationDurationTest {
  @Test
  fun parseMusicDurationSeconds_acceptsBoxProblemDurations() {
    assertEquals(24f, parseMusicDurationSeconds("24")!!)
    assertEquals(180f, parseMusicDurationSeconds("180秒")!!)
    assertEquals(67.5f, parseMusicDurationSeconds("67.5s")!!)
  }

  @Test
  fun parseMusicDurationSeconds_rejectsInvalidValues() {
    assertNull(parseMusicDurationSeconds(""))
    assertNull(parseMusicDurationSeconds("0"))
    assertNull(parseMusicDurationSeconds("-3"))
    assertNull(parseMusicDurationSeconds("abc"))
  }
}
