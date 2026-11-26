package com.adsstudio

import android.graphics.Bitmap
import android.net.Uri

data class Utterance(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class SuggestedClip(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val text: String,
    val score: Float
)

enum class OutputRatio(
    val label: String,
    val hint: String,
    val width: Int,
    val height: Int
) {
    RATIO_16_9("16:9", "YouTube", 1280, 720),
    RATIO_9_16("9:16", "Shorts", 1080, 1920),
    RATIO_1_1("1:1", "Instagram", 1080, 1080)
}

data class UiState(
    val videoUri: Uri? = null,
    val info: VideoInfo? = null,
    val transcript: List<Utterance> = emptyList(),
    val clips: List<SuggestedClip> = emptyList(),
    val ratio: OutputRatio = OutputRatio.RATIO_16_9,
    val llmEnabled: Boolean = true,
    val llmReady: Boolean = false,
    val adSet: Boolean = false,
    val adPreview: Bitmap? = null,
    val busy: String? = null,
    val progress: Float? = null,
    val output: Uri? = null,
    val outputTitle: String? = null,
    val exportingClipStart: Long? = null,
    val error: String? = null,
    val notice: String? = null
)
