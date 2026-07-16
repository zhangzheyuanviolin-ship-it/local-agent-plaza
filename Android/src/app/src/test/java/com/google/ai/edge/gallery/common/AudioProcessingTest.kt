/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.common

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioProcessingTest {
  @Test
  fun maxSamplesReturnsNullWhenNoLimitIsRequested() {
    assertEquals(null, maxAudioSamples(sampleRate = 16000, maxSeconds = null))
  }

  @Test
  fun maxSamplesComputesPositiveLimit() {
    assertEquals(480000, maxAudioSamples(sampleRate = 16000, maxSeconds = 30))
  }

  @Test
  fun maxSamplesIgnoresNonPositiveLimit() {
    assertEquals(null, maxAudioSamples(sampleRate = 16000, maxSeconds = 0))
  }
}
