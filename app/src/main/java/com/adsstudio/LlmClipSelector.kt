package com.adsstudio

import java.util.Locale

/**
 * Промпты и разбор ответа для выбора клипов локальной LLM.
 *
 * Маленькая 0.5B-модель плохо рассуждает, поэтому мы не просим её «понять видео».
 * Мы даём ей уже отобранные кандидаты с признаками (смысл, звук, движение)
 * и просим только расставить номера. Если ответ кривой — работает эвристика.
 *
 * В 0.5.1 добавлен второй проход: критик придумывает проверочные вопросы,
 * редактор отвечает на них и выбирает. Оба прохода идут на одной модели,
 * поэтому память не удваивается.
 */
object LlmClipSelector {

    val defaultQuestions: String =
        "1. Есть ли здесь конкретное событие или история?\n" +
            "2. Есть ли конфликт, неожиданность, эмоция или польза?\n" +
            "3. Есть ли действие в кадре, а не только говорящая голова?"

    /** Обычный одношаговый промпт — запасной вариант. */
    fun prompt(
        candidates: List<SuggestedClip>,
        maxClips: Int,
        hints: Map<Int, String> = emptyMap()
    ): String {
        val count = minOf(maxClips, candidates.size)
        return """
            <|endoftext|><|im_start|>system
            Ты — редактор вирусных коротких видео. Ты выбираешь самые интересные моменты для Shorts.
            Главное — смысл текста: конкретный случай, конфликт, неожиданный поворот, сильное мнение, полезный совет, эмоция.
            Скучно и не выбирай: приветствие, «в этом видео», представление, реклама, подписка, вода, пустое перечисление.
            Подсказки: [движение 0.8] — действие; [склейки 2] — смена планов; [звук 0.7] — громкая речь.
            Пример: «Всем привет, сегодня...» — скучно. «Вдруг машина выскочила...» — интересно.
            Отвечай только номерами через запятую, от лучшего к худшему. Без объяснений.
            <|im_end|>
            <|im_start|>user
            Выбери $count самых интересных фрагментов. Сначала смысл, потом движение и звук.
            ${candidateList(candidates, hints)}
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }

    /** Первый агент: смотрит список и придумывает проверочные вопросы. */
    fun criticPrompt(candidates: List<SuggestedClip>): String {
        return """
            <|endoftext|><|im_start|>system
            Ты — строгий критик коротких видео. Ты не выбираешь клипы, а придумываешь проверочные вопросы.
            <|im_end|>
            <|im_start|>user
            Вот фрагменты:
            ${candidateList(candidates, emptyMap())}

            Придумай 3 коротких вопроса, которые проверят:
            1) есть ли конкретное событие или история;
            2) есть ли конфликт, неожиданность, эмоция или польза;
            3) есть ли действие в кадре, а не только говорящая голова.
            Верни только 3 вопроса, каждый с новой строки. Без ответов и пояснений.
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }

    /** Второй агент: отвечает на вопросы критика и выбирает 5 лучших. */
    fun editorPrompt(
        candidates: List<SuggestedClip>,
        maxClips: Int,
        questions: String
    ): String {
        val count = minOf(maxClips, candidates.size)
        val checklist = questions.ifBlank { defaultQuestions }.take(350)
        return """
            <|endoftext|><|im_start|>system
            Ты — редактор Shorts. Ответь на вопросы критика по каждому фрагменту и выбери $count лучших.
            Главное — смысл текста: конкретный случай, конфликт, неожиданный поворот, сильное мнение, полезный совет, эмоция.
            Скучно и не выбирай: приветствие, «в этом видео», представление, реклама, подписка, вода, пустое перечисление.
            Подсказки: [движение 0.8] — действие; [склейки 2] — смена планов; [звук 0.7] — громкая речь.
            <|im_end|>
            <|im_start|>user
            Вопросы критика:
            $checklist

            Фрагменты:
            ${candidateList(candidates, emptyMap())}

            Для каждого фрагмента напиши строку: номер — СИЛЬНЫЙ или СЛАБЫЙ — коротко почему.
            В конце обязательно: ВЫБОР: номера через запятую, от лучшего к худшему.
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

        return numbers(cleaned, candidateCount, maxClips)
    }

    /**
     * Разбор ответа редактора. Сначала ищем строку «ВЫБОР: ...».
     * Если её нет — берём номера, помеченные как СИЛЬНЫЙ.
     */
    fun parseEditorChoice(response: String, candidateCount: Int, maxClips: Int): List<Int> {
        val lower = response.lowercase()
        val choiceIndex = lower.lastIndexOf("выбор")
        if (choiceIndex >= 0) {
            val afterChoice = response.substring(choiceIndex + 5)
            val choiceLine = afterChoice.lineSequence()
                .firstOrNull { line -> line.any { it.isDigit() } }
                .orEmpty()
            // Берём только числовой префикс: «1, 3, 5 из 8» не должно добавлять 8.
            val numeric = choiceLine.takeWhile { ch ->
                ch.isDigit() || ch.isWhitespace() || ch in ",;.:-—–"
            }
            val choice = numbers(numeric, candidateCount, maxClips)
            if (choice.isNotEmpty()) return choice
        }

        val strong = parseStrong(response, candidateCount)
        if (strong.isNotEmpty()) return strong.take(maxClips)
        return emptyList()
    }

    /**
     * Мягкий разбор: принимаем ответ, только если он целиком похож на список номеров.
     * Это защищает от случайных чисел в рассуждениях модели.
     */
    fun parseLooseChoice(response: String, candidateCount: Int, maxClips: Int): List<Int> {
        val lower = response.lowercase()
        val answerIndex = lower.lastIndexOf("ответ")
        val raw = if (answerIndex >= 0) response.substring(answerIndex + 6) else response
        val cleaned = raw.trim().trim('.', ':', ' ', '\n', '\r', '—', '-')
        if (!cleaned.matches(Regex("[\\d,\\s]+"))) return emptyList()
        return numbers(cleaned, candidateCount, maxClips)
    }

    fun parseStrong(response: String, candidateCount: Int): List<Int> =
        parseVerdicts(response, "сильный", candidateCount)

    fun parseWeak(response: String, candidateCount: Int): List<Int> =
        parseVerdicts(response, "слабый", candidateCount)

    private fun parseVerdicts(
        response: String,
        verdict: String,
        candidateCount: Int
    ): List<Int> {
        return Regex(
            "(?im)^\\s*(?:фрагмент\\s*)?(\\d+)\\s*[.)—:-]?[*_\\s]*$verdict"
        ).findAll(response)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .filter { it in 1..candidateCount }
            .distinct()
            .toList()
    }

    private fun numbers(text: String, candidateCount: Int, maxClips: Int): List<Int> {
        return Regex("\\d+")
            .findAll(text)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..candidateCount }
            .distinct()
            .take(maxClips)
            .toList()
    }

    private fun candidateList(
        candidates: List<SuggestedClip>,
        hints: Map<Int, String>
    ): String = candidates.mapIndexed { index, clip ->
        val text = clip.text.replace(Regex("\\s+"), " ").take(120)
        val hint = hints[index]?.takeIf { it.isNotBlank() } ?: hintOf(clip)
        val prefix = if (hint.isBlank()) "" else " [$hint]"
        "${index + 1}.$prefix $text"
    }.joinToString("\n")

    private fun hintOf(clip: SuggestedClip): String {
        val parts = mutableListOf<String>()
        clip.visual?.let { visual ->
            parts += "движение ${oneDecimal(visual.score)}"
            if (visual.cuts > 0) parts += "склейки ${visual.cuts}"
        }
        clip.audio?.let { audio ->
            parts += "звук ${oneDecimal(audio.energy)}"
        }
        return parts.joinToString(", ")
    }

    private fun oneDecimal(value: Float): String =
        String.format(Locale.US, "%.1f", value)
}
