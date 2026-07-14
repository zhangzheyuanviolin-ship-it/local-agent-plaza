package com.google.ai.edge.gallery.customtasks.aikeyboard.ime

object AiKeyboardCommitTextChunker {
  const val DEFAULT_CHUNK_SIZE = 200

  fun chunks(text: String, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<String> {
    if (text.isEmpty()) return emptyList()
    val safeChunkSize = chunkSize.coerceAtLeast(1)
    return text.chunked(safeChunkSize)
  }
}
