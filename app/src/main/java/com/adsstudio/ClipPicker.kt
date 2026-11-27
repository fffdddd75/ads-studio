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

    private val setupWords = listOf(
        "секрет", "сейчас расскажу", "сейчас покажу", "сейчас будет", "смотри", "слушай",
        "вот что", "в общем", "короче", "представь", "и тут", "однажды",
        "как-то", "вопрос", "хочу рассказать", "хочу показать", "была история",
        "дело было", "всё началось", "все началось", "началось с", "расскажу",
        "покажу", "объясню", "история такая", "прикол", "фишка", "такое дело",
        "знаете", "понимаешь", "потом", "дальше", "вот такая"
    )

    private val payoffWords = listOf(
        "оказалось", "в итоге", "в конечном итоге", "вот почему", "поэтому", "так вот",
        "результат", "и тогда", "на самом деле", "секрет в том", "в чем секрет",
        "в чём секрет", "вот в чем", "вот в чём", "разгадка", "причина",
        "сработало", "не сработало", "получилось", "врезался", "врезалась",
        "выскочил", "выскочила", "упал", "упала", "спас", "спасла", "поймал",
        "поймала", "вот так", "и вот", "вот и всё", "вот и все", "так что",
        "в результате", "в конце", "смысл в том", "мораль", "вывод",
        "фишка в том", "прикол в том", "дело в том", "вот такая история",
        "вот такой", "после этого", "в тот момент", "в этот момент",
        "выяснилось", "неожиданно", "внезапно", "тогда я", "тогда он", "тогда она"
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
        "в этом выпуске", "в этом ролике", "сегодня мы", "сегодня поговорим",
        "речь пойдёт", "речь пойдет", "поговорим о", "разберем", "разберём",
        "тема выпуска", "основанных на реальных", "реальных событиях",
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

        fun add(startIndex: Int, endIndex: Int) {
            if (startIndex !in units.indices || endIndex !in units.indices || endIndex < startIndex) return
            val start = units[startIndex].startMs
            val end = units[endIndex].endMs
            val duration = end - start
            if (duration !in 8_000L..60_000L) return
            val texts = units.subList(startIndex, endIndex + 1).map { it.text }
            val text = texts.joinToString(" ")
            if (start < 8_000 && isIntroOnly(text)) return
            candidates += SuggestedClip(
                startMs = start,
                endMs = end,
                title = titleOf(texts),
                text = text,
                score = scoreText(
                    text = text,
                    duration = duration,
                    startMs = start,
                    totalDurationMs = totalDurationMs
                )
            )
        }

        for (i in units.indices) {
            // 1. Обычное окно вперёд: минимум 18 секунд, дальше — пока не закончится мысль.
            var endIndex = i
            while (endIndex + 1 < units.size) {
                val next = units[endIndex + 1]
                if (next.startMs - units[endIndex].endMs > 1_500) break
                if (next.endMs - units[i].startMs > 60_000) break
                val current = units.subList(i, endIndex + 1).joinToString(" ") { it.text }
                val duration = units[endIndex].endMs - units[i].startMs
                val shouldExtend = duration < 18_000 ||
                    needsPayoff(current) ||
                    endsWithContinuation(current)
                if (!shouldExtend) break
                endIndex++
            }
            add(i, endIndex)

            // 2. Окно с завязкой: захватываем предыдущую фразу, если в ней вопрос/начало истории.
            if (i > 0 && units[i].startMs - units[i - 1].endMs <= 3_000 &&
                (hasSetup(units[i - 1].text) || isQuestion(units[i - 1].text) ||
                    needsPayoff(units[i - 1].text) || startsWithContinuation(units[i].text))
            ) {
                var end = i
                if (i + 1 < units.size && units[i + 1].startMs - units[i].endMs <= 3_000) {
                    end = i + 1
                }
                add(i - 1, end)
            }

            // 3. Окно вокруг события: завязка + событие + развязка.
            if (hasEvent(units[i].text)) {
                val start = if (i > 0 && units[i].startMs - units[i - 1].endMs <= 3_000 &&
                    (hasSetup(units[i - 1].text) || isQuestion(units[i - 1].text) ||
                        startsWithContinuation(units[i].text))
                ) i - 1 else i
                var end = i
                if (i + 1 < units.size && units[i + 1].startMs - units[i].endMs <= 3_000) {
                    end = i + 1
                    if (i + 2 < units.size && containsPayoff(units[i + 1].text) &&
                        units[i + 2].startMs - units[i + 1].endMs <= 1_500
                    ) {
                        end = i + 2
                    }
                }
                add(start, end)
            }

            // 4. История с развязкой: от «секрет/вопрос» до «оказалось/в итоге»,
            // даже если между ними пауза до 5 секунд.
            if (needsPayoff(units[i].text) || isQuestion(units[i].text)) {
                var j = i + 1
                var last = i
                while (j < units.size && units[j].endMs - units[i].startMs <= 60_000) {
                    if (units[j].startMs - units[last].endMs > 5_000) break
                    last = j
                    if (containsPayoff(units[j].text)) {
                        add(i, j)
                        if (j + 1 < units.size && units[j + 1].startMs - units[j].endMs <= 1_500) {
                            add(i, j + 1)
                        }
                        break
                    }
                    if (j - i >= 2) {
                        add(i, j)
                        break
                    }
                    j++
                }
            }
        }

        // Вопрос + ответ через паузу — тоже законченная история.
        for (i in 0 until units.size - 1) {
            if (!isQuestion(units[i].text)) continue
            val answer = units[i + 1]
            if (answer.startMs - units[i].endMs > 4_000) continue
            add(i, i + 1)
            if (i + 2 < units.size && containsPayoff(units[i + 2].text) &&
                units[i + 2].startMs - answer.endMs <= 2_000
            ) {
                add(i, i + 2)
            }
        }

        return candidates.distinctBy { it.startMs to it.endMs }
    }

    private fun selectDiverse(candidates: List<SuggestedClip>, limit: Int): List<SuggestedClip> {
        if (candidates.isEmpty() || limit <= 0) return emptyList()
        val chosen = mutableListOf<SuggestedClip>()

        for (candidate in candidates.sortedByDescending { it.score }) {
            if (chosen.size >= limit) break
            if (chosen.any { it.startMs < candidate.endMs && candidate.startMs < it.endMs }) continue
            if (chosen.any { abs(it.startMs - candidate.startMs) < 24_000 }) continue
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
        val setupHits = setupWords.count { lower.contains(it) }
        val payoffHits = payoffWords.count { lower.contains(it) }
        val unresolvedQuestion = if (lower.trimEnd().endsWith("?")) 1 else 0
        val completeness = payoffHits * 7f - setupHits * 6f - unresolvedQuestion * 12f

        val introPenalty = if (
            startMs < 15_000 && introPatterns.any { lower.contains(it) }
        ) 35f else 0f
        val outroPenalty = if (
            totalDurationMs > 0 &&
            startMs > totalDurationMs - 20_000 &&
            outroPatterns.any { lower.contains(it) }
        ) 40f else 0f
        val startPenalty = when {
            startMs < 5_000 -> 30f
            startMs < 10_000 -> 18f
            else -> 0f
        }
        val endPenalty = if (
            totalDurationMs > 0 && startMs > totalDurationMs - 15_000
        ) 8f else 0f
        val fillerPenalty = fillerCount * 3f
        val densityPenalty = if (speechRate < 1.2) {
            ((1.2 - speechRate) * 15.0).toFloat()
        } else 0f
        val durationPenalty = abs(duration - 32_000).toFloat() / 2500f
        val continuationPenalty =
            (if (startsWithContinuation(text)) 8f else 0f) +
                (if (endsWithContinuation(text)) 6f else 0f) +
                (if (cleanWords(text).lastOrNull()?.let { it in prepositions } == true) 8f else 0f)

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
            specificityBonus +
            completeness -
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

    internal fun isQuestion(text: String): Boolean {
        if (text.contains("?")) return true
        val first = cleanWords(text).firstOrNull() ?: return false
        return questionWords.any { first.startsWith(it) }
    }

    private fun startsWithContinuation(text: String): Boolean {
        val first = cleanWords(text).firstOrNull() ?: return false
        return first in continuationWords
    }

    internal fun endsWithContinuation(text: String): Boolean {
        val last = cleanWords(text).lastOrNull() ?: return false
        return last in continuationWords
    }

    /** История только началась, а развязки/секрета в тексте ещё нет. */
    internal fun needsPayoff(text: String): Boolean {
        val lower = text.lowercase()
        val hasSetup = setupWords.any { lower.contains(it) }
        val hasPayoff = payoffWords.any { lower.contains(it) }
        return hasSetup && !hasPayoff
    }

    internal fun containsPayoff(text: String): Boolean {
        val lower = text.lowercase()
        return payoffWords.any { lower.contains(it) }
    }

    internal fun hasEvent(text: String): Boolean {
        val lower = text.lowercase()
        return eventWords.any { lower.contains(it) }
    }

    internal fun hasSetup(text: String): Boolean {
        val lower = text.lowercase()
        return setupWords.any { lower.contains(it) }
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
