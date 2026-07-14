package com.google.ai.edge.gallery.customtasks.aikeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiKeyboardCommitTextChunkerTest {
  @Test
  fun chunksLongEnglishTextBelowSingleCommitLimit() {
    val text = "a".repeat(524)

    val chunks = AiKeyboardCommitTextChunker.chunks(text)

    assertEquals(text, chunks.joinToString(separator = ""))
    assertEquals(listOf(200, 200, 124), chunks.map { it.length })
    assertTrue(chunks.all { it.length <= AiKeyboardCommitTextChunker.DEFAULT_CHUNK_SIZE })
  }

  @Test
  fun chunksEmptyTextToNoCommits() {
    assertEquals(emptyList<String>(), AiKeyboardCommitTextChunker.chunks(""))
  }
}
