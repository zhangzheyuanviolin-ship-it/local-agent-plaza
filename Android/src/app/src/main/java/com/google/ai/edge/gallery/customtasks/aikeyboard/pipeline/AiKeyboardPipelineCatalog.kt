package com.google.ai.edge.gallery.customtasks.aikeyboard.pipeline

data class AiKeyboardPipelinePreset(
  val id: String,
  val displayName: String,
  val keyboardLabel: String,
  val instruction: String,
)

object AiKeyboardPipelineCatalog {
  const val DEFAULT_PRESET_ID = "polish"

  val presets: List<AiKeyboardPipelinePreset> =
    listOf(
      AiKeyboardPipelinePreset(
        id = "polish",
        displayName = "润色",
        keyboardLabel = "润色",
        instruction = "润色文字，让表达更自然、更清晰，保留原意和原文语言。",
      ),
      AiKeyboardPipelinePreset(
        id = "proofread",
        displayName = "校对纠正",
        keyboardLabel = "校对",
        instruction = "纠正错别字、语法、标点和明显用词问题，保留原意和原文语言。",
      ),
      AiKeyboardPipelinePreset(
        id = "rewrite",
        displayName = "重写",
        keyboardLabel = "重写",
        instruction = "在不改变核心意思的前提下重新组织表达，让文字更顺畅。",
      ),
      AiKeyboardPipelinePreset(
        id = "simplify",
        displayName = "简化",
        keyboardLabel = "简化",
        instruction = "简化文字，用更直接、更容易理解的表达保留重点。",
      ),
      AiKeyboardPipelinePreset(
        id = "professional",
        displayName = "专业风格",
        keyboardLabel = "专业",
        instruction = "改写为更正式、专业、可信的风格，保留事实和原意。",
      ),
      AiKeyboardPipelinePreset(
        id = "casual",
        displayName = "日常风格",
        keyboardLabel = "日常",
        instruction = "改写为更日常、自然、口语化的风格，避免生硬。",
      ),
      AiKeyboardPipelinePreset(
        id = "shorten",
        displayName = "缩写",
        keyboardLabel = "缩写",
        instruction = "压缩文字长度，保留最重要的信息和语气。",
      ),
      AiKeyboardPipelinePreset(
        id = "expand",
        displayName = "扩写",
        keyboardLabel = "扩写",
        instruction = "在保留原意的基础上补充必要细节，让表达更完整。",
      ),
      AiKeyboardPipelinePreset(
        id = "summarize",
        displayName = "总结",
        keyboardLabel = "总结",
        instruction = "总结为一段简明文字，保留核心信息。",
      ),
      AiKeyboardPipelinePreset(
        id = "bullets",
        displayName = "要点",
        keyboardLabel = "要点",
        instruction = "整理为清晰的要点列表，只保留关键信息。",
      ),
      AiKeyboardPipelinePreset(
        id = "email",
        displayName = "电子邮件",
        keyboardLabel = "邮件",
        instruction = "改写为完整、礼貌、清晰的电子邮件正文。",
      ),
      AiKeyboardPipelinePreset(
        id = "chat",
        displayName = "聊天",
        keyboardLabel = "聊天",
        instruction = "改写为适合即时聊天发送的自然表达。",
      ),
      AiKeyboardPipelinePreset(
        id = "twitter",
        displayName = "Twitter",
        keyboardLabel = "推文",
        instruction = "改写为适合发布到 Twitter 或 X 的短文本，尽量简洁有力。",
      ),
      AiKeyboardPipelinePreset(
        id = "list",
        displayName = "列表",
        keyboardLabel = "列表",
        instruction = "整理为有序、易读的列表。",
      ),
      AiKeyboardPipelinePreset(
        id = "table",
        displayName = "表格",
        keyboardLabel = "表格",
        instruction = "整理为 Markdown 表格；如果内容不适合表格，则输出最接近表格的信息结构。",
      ),
      AiKeyboardPipelinePreset(
        id = "translate",
        displayName = "翻译",
        keyboardLabel = "翻译",
        instruction = "在中文和英文之间翻译；如果原文不是中文或英文，则翻译为中文。",
      ),
      AiKeyboardPipelinePreset(
        id = "custom",
        displayName = "自定义",
        keyboardLabel = "自定",
        instruction = "按通用文字助手方式优化文字，优先让表达更清楚、更可直接发送。",
      ),
    )

  fun defaultPreset(): AiKeyboardPipelinePreset {
    return byId(DEFAULT_PRESET_ID) ?: presets.first()
  }

  fun byId(id: String): AiKeyboardPipelinePreset? {
    return presets.firstOrNull { it.id == id }
  }

  fun nextAfter(id: String): AiKeyboardPipelinePreset {
    val index = presets.indexOfFirst { it.id == id }
    if (index < 0) return defaultPreset()
    return presets[(index + 1) % presets.size]
  }

  fun buildPrompt(presetId: String, input: String): String {
    val text = input.trim()
    if (text.isEmpty()) return ""
    val preset = byId(presetId) ?: defaultPreset()
    return """
      你是本地 AI 键盘的文字处理流水线。
      任务：${preset.instruction}
      要求：只输出处理后的正文，不要解释，不要加前后缀，不要描述你做了什么。
      原文：
      $text
    """.trimIndent()
  }
}
