package com.adsstudio

import kotlin.math.abs

/**
 * Пока без LLM: выбирает связные куски по 8-65 секунд,
 * отдавая приоритет словам-хукам. Позже сюда встанет локальная LLM.
 */
object ClipPicker {

    private val hooks = listOf(
        "как ", "почему", "ошибк", "совет", "лайфхак", "секрет", "деньг",
        "лучш", "главн", "никогда", "всегда", "правд", "проблем", "решен",
        "научил", "советую", "запомни", "внимание"
    )

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
                val text = utterances.subList(index, endIndex).joinToString(" ") { it.text }

                if (duration in 8_000..65_000) {
                    val lower = text.lowercase()
                    val hits = hooks.count { lower.contains(it) }
                    val score = hits * 10f - abs(duration - 30_000) / 1500f
                    candidates += SuggestedClip(
                        startMs = start,
                        endMs = end,
                        title = titleOf(text),
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

    private val leadingFillers = setOf(
        "вот", "ну", "это", "а", "и", "так", "да", "короче", "значит", "там", "тут"
    )

    private fun titleOf(text: String): String {
        val words = text
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val start = words.indexOfFirst { it.lowercase() !in leadingFillers }
            .let { if (it < 0) 0 else it }

        var title = words.drop(start).take(9).joinToString(" ")
        if (title.length > 60) {
            val cut = title.take(60)
            title = cut.substringBeforeLast(" ").ifBlank { cut }
        }
        return title.replaceFirstChar { it.uppercase() }.ifBlank { "Клип" }
    }
}
