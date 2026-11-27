package com.adsstudio

import java.util.Locale

/**
 * Промпт и разбор ответа для выбора клипов локальной LLM.
 *
 * Маленькая 0.5B-модель плохо рассуждает, поэтому мы не просим её «понять видео».
 * Мы даём ей уже отобранные кандидаты с признаками (смысл, звук, движение)
 * и просим только расставить номера. Если ответ кривой — работает эвристика.
 */
object LlmClipSelector {

    fun prompt(
        candidates: List<SuggestedClip>,
        maxClips: Int,
        hints: Map<Int, String> = emptyMap()
    ): String {
        val count = minOf(maxClips, candidates.size)
        val list = candidates.mapIndexed { index, clip ->
            val text = clip.text.replace(Regex("\\s+"), " ").take(180)
            val hint = hints[index]?.takeIf { it.isNotBlank() } ?: hintOf(clip)
            val prefix = if (hint.isBlank()) "" else " [$hint]"
            "${index + 1}.$prefix $text"
        }.joinToString("\n")

        return """
            <|endoftext|><|im_start|>system
            Ты — редактор вирусных коротких видео. Ты выбираешь самые интересные моменты для Shorts.
            Главное — смысл текста: конкретный случай, конфликт, неожиданный поворот, сильное мнение, полезный совет, эмоция.
            Скучно и не выбирай: приветствие, «в этом видео», представление, реклама, подписка, вода, пустое перечисление.
            Подсказки: [движение 0.8] — в кадре действие; [склейки 2] — смена планов; [звук 0.7] — громкая речь; [темп 4.2] — слов в секунду.
            Пример: «Всем привет, сегодня...» — скучно. «Вдруг машина выскочила...» — интересно.
            Отвечай только номерами через запятую, от лучшего к худшему. Без объяснений.
            <|im_end|>
            <|im_start|>user
            Выбери $count самых интересных фрагментов. Сначала смысл, потом движение и звук.
            $list
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }

    fun parse(response: String, candidateCount: Int, maxClips: Int): List<Int> {
        val lower = response.lowercase()
        val answerIndex = lower.lastIndexOf("ответ")
        val answer = if (answerIndex >= 0) response.substring(answerIndex + 6) else response

        // Убираем нумерацию списка («1. ...», «2) ...»), чтобы не спутать её с выбором.
        val cleaned = answer.lineSequence()
            .joinToString("\n") { line -> line.replace(Regex("^\\s*\\d+[.)]\\s*"), "") }

        return Regex("\\d+")
            .findAll(cleaned)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..candidateCount }
            .distinct()
            .take(maxClips)
            .toList()
    }

    private fun hintOf(clip: SuggestedClip): String {
        val parts = mutableListOf<String>()
        clip.visual?.let { visual ->
            parts += "движение ${oneDecimal(visual.score)}"
            if (visual.cuts > 0) parts += "склейки ${visual.cuts}"
        }
        clip.audio?.let { audio ->
            parts += "звук ${oneDecimal(audio.energy)}"
            if (audio.speechRate > 0f) parts += "темп ${oneDecimal(audio.speechRate)}"
        }
        return parts.joinToString(", ")
    }

    private fun oneDecimal(value: Float): String =
        String.format(Locale.US, "%.1f", value)
}
