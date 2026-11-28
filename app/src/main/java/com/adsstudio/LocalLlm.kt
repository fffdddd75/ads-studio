package com.adsstudio

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.Closeable

/**
 * Обёртка над MediaPipe LLM Inference. Модель и генерация — полностью на телефоне.
 */
class LocalLlm(private val context: Context) : Closeable {

    private var engine: LlmInference? = null

    fun load(modelPath: String) {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(2048)
            .setMaxTopK(40)
            .setPreferredBackend(LlmInference.Backend.CPU)
            .build()

        engine = LlmInference.createFromOptions(context, options)
    }

    fun generate(prompt: String): String {
        val inference = engine ?: error("Нейросеть не загружена")

        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(0.2f)
            .setTopK(40)
            .setTopP(0.9f)
            .setRandomSeed(42)
            .build()

        val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
        try {
            session.addQueryChunk(prompt)
            val future = session.generateResponseAsync { _, _ -> }
            return future.get()
        } finally {
            session.close()
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
