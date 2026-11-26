package com.adsstudio

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

data class UiState(
    val videoUri: Uri? = null,
    val info: VideoInfo? = null,
    val transcript: List<Utterance> = emptyList(),
    val clips: List<SuggestedClip> = emptyList(),
    val adSet: Boolean = false,
    val busy: String? = null,
    val progress: Float? = null,
    val output: Uri? = null,
    val error: String? = null
)
