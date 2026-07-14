package com.google.ai.edge.gallery.customtasks.aikeyboard

import org.junit.Assert.assertFalse
import org.junit.Test

class AiKeyboardTaskTest {
  @Test
  fun taskDescriptionDoesNotExposePhaseCopy() {
    val description = AiKeyboardTask().task.description

    assertFalse(description.contains("第一阶段"))
    assertFalse(description.contains("Vosk 模型管理"))
  }
}
