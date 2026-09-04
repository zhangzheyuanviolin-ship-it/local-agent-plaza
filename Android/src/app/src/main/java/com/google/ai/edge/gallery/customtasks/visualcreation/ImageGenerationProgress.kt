package com.google.ai.edge.gallery.customtasks.visualcreation

data class ImageGenerationStageProgress(
  val stageText: String,
  val timingText: String = "",
  val step: Int = 0,
  val totalSteps: Int = 0,
)
