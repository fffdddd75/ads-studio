package com.adsstudio

/**
 * Промпт и разбор ответа для выбора клипов локальной LLM.
 */
object LlmClipSelector {

    fun prompt(
        candidates: List<SuggestedClip>,
        maxClips: Int,
        hints: Map<Int, String> = emptyMap()
    ): String {
        val count = minOf(maxClips, candidates.size)
        val list = candidates.mapIndexed { index, clip ->
            val text = clip.text.replace(Regex("\\s+"), " ").take(130)
            val hint = hints[index]?.let { "[$it] " } ?: ""
            "${index + 1}. $hint$text"
        }.joinToString("\n")

        return """
            <|endoftext|><|im_start|>system
            Ты — редактор вирусных коротких видео. Ты выбираешь самые интересные моменты для Shorts.
            Интересно: интрига, конфликт, сильное мнение, полезный совет, неожиданный факт, эмоция.
            Скучно: приветствие, вода, реклама, перечисление без мысли.
            Подсказки в квадратных скобках: [динамично] — в кадре что-то происходит, [статично] — говорящая голова.
            Для визуальных видео предпочитай динамичные фрагменты, для историй и советов важнее смысл.
            Отвечай только номерами через запятую, от лучшего к худшему. Без объяснений.
            <|im_end|>
            <|im_start|>user
            Выбери $count самых интересных фрагментов из списка. Ответь только номерами, например: 3, 7, 1.
            $list
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }

    fun parse(response: String, candidateCount: Int, maxClips: Int): List<Int> {
        // Убираем нумерацию списка («1. ...», «2) ...»), чтобы не спутать её с выбором.
        val cleaned = response.lineSequence()
            .joinToString("\n") { line -> line.replace(Regex("^\\s*\\d+[.)]\\s*"), "") }

        return Regex("\\d+")
            .findAll(cleaned)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..candidateCount }
            .distinct()
            .take(maxClips)
            .toList()
    }
}
