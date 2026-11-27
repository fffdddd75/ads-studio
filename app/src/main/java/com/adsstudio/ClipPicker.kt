package com.adsstudio

import kotlin.math.abs
import kotlin.math.ln

/**
 * Кандидаты для нарезки. Границы привязаны к паузам в речи,
 * а финальный выбор делает локальная LLM (LlmClipSelector).
 * Если LLM выключена или не справилась — остаётся эвристика.
 *
 * Важно: это не «тыканье пальцем». Кандидат получает баллы за смысл текста
 * (событие, конфликт, польза, вопрос), за громкость и плотность речи,
 * за движение в кадре. Вступления, прощания и реклама штрафуются.
 */
object ClipPicker {

    private val hooks = listOf(
        "почему", "зачем", "как", "когда", "где", "кто", "чем", "чего", "сколько",
        "какой", "какая", "какие", "каких", "ошибк", "совет", "лайфхак", "секрет",
        "деньг", "лучш", "главн", "никогда", "всегда", "правд", "проблем", "решен",
        "научил", "советую", "запомни", "внимание", "важно", "нельзя", "нужно",
        "должен", "работает", "помогает", "выгод", "бесплат", "быстро", "просто",
        "сложно", "опасн", "разруш", "убивает", "спасает", "тест", "сравнен",
        "результат", "причина", "смысл", "выбор", "правил", "закон", "штраф",
        "налог", "кредит", "ипотек", "зарплат", "бизнес", "маркет", "реклам",
        "подпис", "просмотр", "ютуб", "телефон", "андроид", "приложен", "настройк",
        "батаре", "интернет", "вайфай", "сигнал", "камер", "фото", "видео"
    )

    private val eventWords = listOf(
        "вдруг", "внезапно", "неожиданно", "в этот момент", "вот что произошло",
        "машина выскочила", "машинка выскочила", "машина", "машинка", "автомобиль",
        "выскочил", "выскочила", "вылетел", "вылетела", "врезался", "врезалась",
        "столкнулся", "столкнулась",
        "упал", "сломался", "взорвался", "загорелся", "поймал", "спас", "потерял",
        "нашёл", "нашел", "украли", "обманул", "кинул", "провал", "успел", "чудом",
        "опасно", "жёстко", "жестко", "жесть", "шок", "смотри", "представь",
        "оказалось", "на самом деле", "впервые", "эксперимент", "тест", "проверил",
        "результат", "сравнил", "сработало", "не сработало", "получилось"
    )

    private val valueWords = listOf(
        "главное", "важно", "запомни", "никогда", "всегда", "нельзя", "нужно",
        "правильно", "ошибка", "решение", "причина", "смысл", "совет", "лайфхак",
        "секрет", "бесплатно", "выгодно", "быстро", "просто", "сложно", "польза"
    )

    private val emotionWords = listOf(
        "круто", "жесть", "шок", "ошибк", "провал", "секрет", "деньг", "бесплатн",
        "лучш", "худш", "ненавиж", "любл", "боюсь", "опасн", "важн", "срочн",
        "наконец", "представь", "смотри", "внимание", "правда", "обман", "развод",
        "спаса", "убива", "взорв", "скандал", "смешно", "страшно", "обидно"
    )

    private val introPatterns = listOf(
        "всем привет", "привет", "добро пожаловать", "сегодня", "в этом видео",
        "на связи", "меня зовут", "итак", "поехали", "начнём", "начнем", "давайте",
        "друзья", "здравствуйте"
    )

    private val outroPatterns = listOf(
        "подписывайтесь", "ставьте лайк", "спасибо за просмотр", "до встречи",
        "в следующем видео", "на канал", "реклама", "спонсор", "промокод",
        "ссылке в описании", "ссылке в описании"
    )

    private val questionWords = listOf(
        "почему", "зачем", "как", "когда", "где", "кто", "чем", "чего", "сколько",
        "какой", "какая", "какие", "можно", "стоит", "правда", "что"
    )

    private val stopWords = setOf(
        "и", "а", "но", "да", "или", "что", "чтобы", "как", "это", "же", "ли", "бы",
        "не", "ни", "то", "в", "во", "на", "с", "со", "по", "к", "ко", "о", "об",
        "обо", "от", "до", "из", "у", "за", "для", "при", "про", "над", "под", "без",
        "через", "между", "перед", "после", "около",
        "я", "ты", "он", "она", "оно", "мы", "вы", "они", "меня", "тебя", "его",
        "ее", "её", "их", "нас", "вас", "мне", "тебе", "ему", "ей", "им", "нами",
        "вами", "ими",
        "мой", "моя", "мое", "моё", "мои", "твой", "твоя", "твое", "твоё", "твои",
        "наш", "наша", "наше", "наши", "ваш", "ваша", "ваше", "ваши", "свой", "своя",
        "свое", "своё", "свои",
        "этот", "эта", "эти", "тот", "та", "те",
        "был", "была", "было", "были", "будет", "будут", "есть", "быть",
        "так", "там", "тут", "вот", "ну", "короче", "значит", "типа", "просто",
        "вообще", "кстати", "очень", "более", "менее", "самый", "самая", "самое",
        "самые", "весь", "вся", "все", "всё", "который", "которая", "которое",
        "которые", "которых", "которым", "которой", "если", "пока", "уже", "еще",
        "ещё", "только", "даже", "ведь", "вон",
        "один", "одна", "одно", "одни", "два", "две", "три", "четыре", "пять"
    )

    private val prepositions = setOf(
        "в", "во", "на", "с", "со", "по", "к", "ко", "о", "об", "обо", "от", "до",
        "из", "у", "за", "для", "при", "про", "над", "под", "без", "через", "между",
        "перед", "после", "около"
    )

    private val leadingJunk = setOf(
        "и", "а", "но", "да", "или", "ну", "вот", "короче", "значит", "типа", "там",
        "тут", "просто", "вообще", "кстати", "это", "же", "ли", "бы"
    )

    private val fillers = setOf(
        "ну", "вот", "короче", "значит", "типа", "там", "тут", "просто", "вообще",
        "кстати", "эээ", "эм", "ммм", "ага", "угу", "окей", "ок"
    )

    private val continuationWords = setOf(
        "и", "а", "но", "что", "чтобы", "потому", "поэтому", "вот", "ну", "так",
        "тогда", "если", "когда", "который", "которая", "которое", "которые",
        "или", "зато", "причем", "причём", "дальше", "теперь", "значит", "короче",
        "то"
    )

    private val oneLetterAllowed = setOf("а", "и", "в", "к", "с", "у", "о", "я", "э")

    private data class Unit(val startMs: Long, val endMs: Long, val text: String)

    /** Эвристический выбор — запасной вариант и основа для кандидатов. */
    fun pick(utterances: List<Utterance>, maxClips: Int = 5): List<SuggestedClip> =
        selectDiverse(buildCandidates(buildUnits(utterances), 0L), maxClips)

    /** Кандидаты для LLM: интересные, не пересекаются, разбросаны по видео. */
    fun candidates(
        utterances: List<Utterance>,
        limit: Int = 12,
        totalDurationMs: Long = 0L
    ): List<SuggestedClip> =
        selectDiverse(buildCandidates(buildUnits(utterances), totalDurationMs), limit)

    fun pickFromCandidates(candidates: List<SuggestedClip>, maxClips: Int = 5): List<SuggestedClip> =
        selectDiverse(candidates, maxClips)

    /** Добавляет кандидатов, если LLM выбрала меньше клипов, чем нужно. */
    fun fillSelection(
        selected: List<SuggestedClip>,
        candidates: List<SuggestedClip>,
        maxClips: Int = 5
    ): List<SuggestedClip> {
        val result = selected.distinctBy { it.startMs to it.endMs }.toMutableList()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (result.size >= maxClips) break
            if (result.any { it.startMs == candidate.startMs && it.endMs == candidate.endMs }) continue
            if (result.any { it.startMs < candidate.endMs && candidate.startMs < it.endMs }) continue
            result += candidate
        }
        return result.sortedBy { it.startMs }
    }

    /**
     * Второй проход, когда уже известны звук и картинка.
     * audio/visual — списки той же длины, что candidates; null = признака нет.
     */
    fun rescore(
        candidates: List<SuggestedClip>,
        totalDurationMs: Long,
        audio: List<AudioHint?>,
        visual: List<VisualHint?>
    ): List<SuggestedClip> {
        if (candidates.isEmpty()) return emptyList()

        val docs = candidates.map { contentWords(it.text).toSet() }
        val documentFrequency = mutableMapOf<String, Int>()
        docs.forEach { words ->
            words.forEach { word ->
                documentFrequency[word] = (documentFrequency[word] ?: 0) + 1
            }
        }
        val total = candidates.size.toDouble()

        return candidates.mapIndexed { index, clip ->
            val specificity = docs[index].sumOf { word ->
                ln(total / (1.0 + (documentFrequency[word] ?: 0)))
            }.toFloat()

            clip.copy(
                score = scoreText(
                    text = clip.text,
                    duration = clip.endMs - clip.startMs,
                    startMs = clip.startMs,
                    totalDurationMs = totalDurationMs,
                    audio = audio.getOrNull(index),
                    visual = visual.getOrNull(index),
                    specificity = specificity
                ),
                audio = audio.getOrNull(index),
                visual = visual.getOrNull(index)
            )
        }
    }

    private fun buildUnits(utterances: List<Utterance>): List<Unit> {
        val units = mutableListOf<Unit>()
        var index = 0
        while (index < utterances.size) {
            val start = utterances[index].startMs
            var end = utterances[index].endMs
            val texts = mutableListOf(utterances[index].text)
            var next = index + 1
            while (next < utterances.size &&
                utterances[next].startMs - end <= 600 &&
                utterances[next].endMs - start <= 12_000
            ) {
                end = utterances[next].endMs
                texts += utterances[next].text
                next++
            }
            units += Unit(start, end, texts.joinToString(" "))
            index = next
        }
        return units
    }

    private fun buildCandidates(units: List<Unit>, totalDurationMs: Long): List<SuggestedClip> {
        val candidates = mutableListOf<SuggestedClip>()

        for (i in units.indices) {
            val includePrevious = i > 0 &&
                startsWithContinuation(units[i].text) &&
                units[i].startMs - units[i - 1].endMs <= 800
            val startIndex = if (includePrevious) i - 1 else i

            var endIndex = startIndex
            var end = units[startIndex].endMs
            val texts = mutableListOf(units[startIndex].text)

            while (endIndex + 1 < units.size) {
                val next = units[endIndex + 1]
                if (next.startMs - end > 800) break
                if (next.endMs - units[startIndex].startMs > 55_000) break
                endIndex++
                end = next.endMs
                texts += next.text
                val duration = end - units[startIndex].startMs
                if (duration >= 18_000 && !endsWithContinuation(next.text)) break
            }

            val duration = end - units[startIndex].startMs
            if (duration !in 8_000L..60_000L) continue

            val text = texts.joinToString(" ")
            // Не отдаём в кандидаты чистое вступление в первые 8 секунд.
            if (units[startIndex].startMs < 8_000 && isIntroOnly(text)) continue

            candidates += SuggestedClip(
                startMs = units[startIndex].startMs,
                endMs = end,
                title = titleOf(texts),
                text = text,
                score = scoreText(
                    text = text,
                    duration = duration,
                    startMs = units[startIndex].startMs,
                    totalDurationMs = totalDurationMs
                )
            )
        }

        // Вопрос + ответ — сильный крючок для Shorts, даже если ответ не влез в 18 секунд.
        for (i in 0 until units.size - 1) {
            val question = units[i]
            val answer = units[i + 1]
            if (!isQuestion(question.text)) continue
            if (answer.startMs - question.endMs > 900) continue
            val duration = answer.endMs - question.startMs
            if (duration !in 8_000L..60_000L) continue
            val text = question.text + " " + answer.text
            candidates += SuggestedClip(
                startMs = question.startMs,
                endMs = answer.endMs,
                title = titleOf(listOf(question.text, answer.text)),
                text = text,
                score = scoreText(
                    text = text,
                    duration = duration,
                    startMs = question.startMs,
                    totalDurationMs = totalDurationMs
                )
            )
        }

        return candidates.distinctBy { it.startMs to it.endMs }
    }

    private fun selectDiverse(candidates: List<SuggestedClip>, limit: Int): List<SuggestedClip> {
        if (candidates.isEmpty() || limit <= 0) return emptyList()
        val chosen = mutableListOf<SuggestedClip>()

        for (candidate in candidates.sortedByDescending { it.score }) {
            if (chosen.size >= limit) break
            if (chosen.any { it.startMs < candidate.endMs && candidate.startMs < it.endMs }) continue
            if (chosen.any { abs(it.startMs - candidate.startMs) < 20_000 }) continue
            if (chosen.any { textSimilarity(it.text, candidate.text) >= 0.72 }) continue
            chosen += candidate
        }

        if (chosen.size < limit) {
            for (candidate in candidates.sortedByDescending { it.score }) {
                if (chosen.size >= limit) break
                if (chosen.any { it.startMs == candidate.startMs && it.endMs == candidate.endMs }) continue
                if (chosen.any { it.startMs < candidate.endMs && candidate.startMs < it.endMs }) continue
                chosen += candidate
            }
        }

        return chosen.sortedBy { it.startMs }
    }

    private fun scoreText(
        text: String,
        duration: Long,
        startMs: Long = 0L,
        totalDurationMs: Long = 0L,
        audio: AudioHint? = null,
        visual: VisualHint? = null,
        specificity: Float = 0f
    ): Float {
        val lower = text.lowercase()
        val words = cleanWords(text)

        val hookHits = words.count { word -> hooks.any { word.startsWith(it) } }
        val eventHits = eventWords.count { lower.contains(it) }
        val valueHits = valueWords.count { lower.contains(it) }
        val emotionHits = emotionWords.count { lower.contains(it) }
        val question = if (
            lower.contains("?") ||
            words.any { word -> questionWords.any { word.startsWith(it) } }
        ) 1 else 0
        val numbers = Regex("\\d").findAll(text).count().coerceAtMost(3)
        val fillerCount = words.count { it in fillers }
        val speechRate = if (duration > 0) words.size / (duration / 1000.0) else 0.0

        val introPenalty = if (
            startMs < 15_000 && introPatterns.any { lower.contains(it) }
        ) 35f else 0f
        val outroPenalty = if (
            totalDurationMs > 0 &&
            startMs > totalDurationMs - 20_000 &&
            outroPatterns.any { lower.contains(it) }
        ) 40f else 0f
        val startPenalty = when {
            startMs < 5_000 -> 18f
            startMs < 10_000 -> 10f
            else -> 0f
        }
        val endPenalty = if (
            totalDurationMs > 0 && startMs > totalDurationMs - 15_000
        ) 8f else 0f
        val fillerPenalty = fillerCount * 3f
        val densityPenalty = if (speechRate < 1.2) {
            ((1.2 - speechRate) * 15.0).toFloat()
        } else 0f
        val durationPenalty = abs(duration - 28_000).toFloat() / 2000f
        val continuationPenalty =
            (if (startsWithContinuation(text)) 8f else 0f) +
                (if (endsWithContinuation(text)) 6f else 0f)

        val audioBonus = (audio?.energy ?: 0f) * 14f - (audio?.silence ?: 0f) * 12f
        val motionBonus = (visual?.score ?: 0f) * 8f
        val specificityBonus = specificity * 3f

        return hookHits * 10f +
            eventHits * 14f +
            valueHits * 8f +
            emotionHits * 4f +
            question * 6f +
            numbers * 2f +
            audioBonus +
            motionBonus +
            specificityBonus -
            introPenalty -
            outroPenalty -
            startPenalty -
            endPenalty -
            fillerPenalty -
            densityPenalty -
            durationPenalty -
            continuationPenalty
    }

    private fun textSimilarity(a: String, b: String): Double {
        val left = contentWords(a).toSet()
        val right = contentWords(b).toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.count { it in right }
        return intersection.toDouble() / (left.size + right.size - intersection).coerceAtLeast(1)
    }

    private fun contentWords(text: String): List<String> =
        cleanWords(text).filter { it.length > 2 && it !in stopWords }

    private fun isIntroOnly(text: String): Boolean {
        val lower = text.lowercase()
        return introPatterns.any { lower.contains(it) } && !hasStrongHook(text)
    }

    private fun hasStrongHook(text: String): Boolean {
        val lower = text.lowercase()
        return eventWords.any { lower.contains(it) } ||
            hooks.any { lower.contains(it) } ||
            emotionWords.any { lower.contains(it) }
    }

    private fun isQuestion(text: String): Boolean {
        if (text.contains("?")) return true
        val first = cleanWords(text).firstOrNull() ?: return false
        return questionWords.any { first.startsWith(it) }
    }

    private fun startsWithContinuation(text: String): Boolean {
        val first = cleanWords(text).firstOrNull() ?: return false
        return first in continuationWords
    }

    private fun endsWithContinuation(text: String): Boolean {
        val last = cleanWords(text).lastOrNull() ?: return false
        return last in continuationWords
    }

    /**
     * Заголовок = окно вокруг самого «хукового» слова в лучшей фразе клипа.
     */
    private fun titleOf(texts: List<String>): String {
        val cleaned = texts.map { cleanWords(it) }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return "Клип"

        var bestIndex = 0
        var bestScore = Float.NEGATIVE_INFINITY
        cleaned.forEachIndexed { index, words ->
            val score = utteranceScore(words)
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }

        val stream = mutableListOf<String>()
        for (i in bestIndex until cleaned.size) {
            stream += cleaned[i]
            if (stream.size >= 14) break
        }
        if (stream.size < 5) {
            val before = cleaned.subList(0, bestIndex).flatten().takeLast(8)
            stream.addAll(0, before)
        }

        val start = findStart(stream)
        val picked = mutableListOf<String>()
        var length = 0
        for (i in start until stream.size) {
            val word = stream[i]
            val extra = if (picked.isEmpty()) word.length else word.length + 1
            if (picked.size >= 8 || length + extra > 60) break
            picked += word
            length += extra
        }
        trimEdges(picked)

        if (picked.size < 3) {
            picked.clear()
            picked += cleaned.flatten().filter { it !in leadingJunk }.take(7)
            trimEdges(picked)
        }

        val title = picked.joinToString(" ")
        return title.replaceFirstChar { it.uppercase() }.ifBlank { "Клип" }
    }

    private fun cleanWords(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s-]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim('-') }
            .filter { it.isNotBlank() }
            .filter { it !in fillers }
            .filter { it.length > 1 || it in oneLetterAllowed }

    private fun utteranceScore(words: List<String>): Float {
        val hits = words.count { word ->
            hooks.any { word.startsWith(it) } || eventWords.any { word.startsWith(it) }
        }
        val content = words.count { it !in stopWords }
        val lengthPenalty = abs(words.size - 8) * 0.6f
        return hits * 10f + content * 1.3f - lengthPenalty
    }

    private fun findStart(words: List<String>): Int {
        val hookIndex = words.indexOfFirst { word ->
            hooks.any { word.startsWith(it) } || eventWords.any { word.startsWith(it) }
        }
        if (hookIndex >= 0) {
            val before = hookIndex - 1
            val usefulBefore = before >= 0 &&
                words[before] !in leadingJunk &&
                (words[before] !in stopWords || words[before] in prepositions)
            return if (usefulBefore) before else hookIndex
        }
        val contentIndex = words.indexOfFirst { it !in stopWords && it !in leadingJunk }
        return contentIndex.coerceAtLeast(0)
    }

    private fun trimEdges(words: MutableList<String>) {
        while (words.isNotEmpty() && words.first() in leadingJunk) {
            words.removeAt(0)
        }
        while (words.isNotEmpty() && words.last() in stopWords) {
            words.removeAt(words.lastIndex)
        }
    }
}
