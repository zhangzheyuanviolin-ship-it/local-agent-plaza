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

package com.google.ai.edge.gallery.customtasks.videoqa

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFramePlannerTest {
  @Test
  fun completeModeSamplesSegmentCentersAcrossVideoDuration() {
    val frames = planEvenFrameTimesUs(durationMs = 20_000L, frameCount = 5)

    assertEquals(listOf(2_000_000L, 6_000_000L, 10_000_000L, 14_000_000L, 18_000_000L), frames)
  }

  @Test
  fun completeModeUsesSingleMiddleFrameWhenFrameCountIsOne() {
    val frames = planEvenFrameTimesUs(durationMs = 30_000L, frameCount = 1)

    assertEquals(listOf(15_000_000L), frames)
  }

  @Test
  fun keyframeModeParsesSecondsAndMinuteSecondLabels() {
    val frames =
      parseKeyFrameTimesUs(inputs = listOf("0", "12.5", "01:05", "", "bad"), durationMs = 70_000L)

    assertEquals(listOf(0L, 12_500_000L, 65_000_000L), frames)
  }

  @Test
  fun keyframeModeClampsTimesToVideoDurationAndCapsAtFiveFrames() {
    val frames =
      parseKeyFrameTimesUs(
        inputs = listOf("1", "2", "3", "4", "5", "99"),
        durationMs = 4_000L,
      )

    assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L, 4_000_000L), frames)
  }

  @Test
  fun keyframeValidationReportsUnrecognizedInputs() {
    val invalidInputs = validateKeyFrameInputs(listOf("三秒", "1:05", "12.5", "abc"))

    assertEquals(listOf("三秒", "abc"), invalidInputs)
  }
}
