package com.google.ai.edge.gallery.customtasks.aikeyboard.ime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiKeyboardCommitVerifierTest {
  @Test
  fun needsClipboardFallback_whenCommittedTextIsShortPrefix() {
    val expected =
      "The teaching content for this semester is heavy, and the workload for preparing " +
        "teaching materials is immense. Late last night, I did not finish compiling all " +
        "the exams and teaching materials until two in the morning."
    val committed = expected.take(255)

    assertThat(AiKeyboardCommitVerifier.needsClipboardFallback(expected, committed)).isTrue()
  }

  @Test
  fun doesNotNeedClipboardFallback_whenCommittedTextMatchesExpected() {
    val expected = "完整提交的英文文本。"

    assertThat(AiKeyboardCommitVerifier.needsClipboardFallback(expected, expected)).isFalse()
  }

  @Test
  fun doesNotNeedClipboardFallback_whenReadbackIsEmpty() {
    assertThat(AiKeyboardCommitVerifier.needsClipboardFallback("Some output", "")).isFalse()
  }
}
