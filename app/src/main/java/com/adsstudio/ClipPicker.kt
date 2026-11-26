package com.adsstudio

import kotlin.math.abs

/**
 * Пока без LLM: выбирает связные куски по 8-65 секунд,
 * отдавая приоритет словам-хукам. Заголовок собирается
 * из самой «зацепной» фразы внутри клипа.
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

    private val oneLetterAllowed = setOf("а", "и", "в", "к", "с", "у", "о", "я", "э")

    fun pick(utterances: List<Utterance>, maxClips: Int = 5): List<SuggestedClip> {
        if (utterances.isEmpty()) return emptyList()

        val candidates = mutableListOf<SuggestedClip>()
        var index = 0

        while (index < utterances.size) {
            var endIndex = index
            while (endIndex < utterances.size &&
                utterances[endIndex].endMs - utterances[index].startMs < 35_000
            ) {
                endIndex++
            }

            if (endIndex > index) {
                val start = utterances[index].startMs
                val end = utterances[endIndex - 1].endMs
                val duration = end - start
                val parts = utterances.subList(index, endIndex)
                val text = parts.joinToString(" ") { it.text }

                if (duration in 8_000..65_000) {
                    val lower = text.lowercase()
                    val hits = hooks.count { lower.contains(it) }
                    val score = hits * 10f - abs(duration - 30_000) / 1500f
                    candidates += SuggestedClip(
                        startMs = start,
                        endMs = end,
                        title = titleOf(parts),
                        text = text,
                        score = score
                    )
                }
            }

            index = if (endIndex > index) endIndex else index + 1
        }

        val chosen = mutableListOf<SuggestedClip>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (chosen.size >= maxClips) break
            if (chosen.none { it.startMs < candidate.endMs && candidate.startMs < it.endMs }) {
                chosen += candidate
            }
        }
        return chosen.sortedBy { it.startMs }
    }

    /**
     * Заголовок = окно вокруг самого «хукового» слова в лучшей фразе клипа.
     * Без LLM, но получается заметно человечнее, чем первые слова подряд.
     */
    private fun titleOf(parts: List<Utterance>): String {
        val cleaned = parts.map { cleanWords(it.text) }.filter { it.isNotEmpty() }
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
        val hits = words.count { word -> hooks.any { word.startsWith(it) } }
        val content = words.count { it !in stopWords }
        val lengthPenalty = abs(words.size - 8) * 0.6f
        return hits * 10f + content * 1.3f - lengthPenalty
    }

    private fun findStart(words: List<String>): Int {
        val hookIndex = words.indexOfFirst { word -> hooks.any { word.startsWith(it) } }
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
