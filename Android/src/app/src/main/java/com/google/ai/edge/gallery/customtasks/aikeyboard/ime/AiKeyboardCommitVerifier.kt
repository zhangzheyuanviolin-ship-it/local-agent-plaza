package com.google.ai.edge.gallery.customtasks.aikeyboard.ime

object AiKeyboardCommitVerifier {
  fun needsClipboardFallback(expected: String, committedReadback: String): Boolean {
    if (expected.isBlank() || committedReadback.isBlank()) return false
    if (expected == committedReadback) return false
    return expected.length > committedReadback.length && expected.startsWith(committedReadback)
  }
}
