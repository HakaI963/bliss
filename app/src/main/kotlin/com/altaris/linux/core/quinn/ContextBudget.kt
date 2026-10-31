package com.altaris.linux.core.quinn

/**
 * Rough context accounting for the context ring.
 *
 * The exact token count belongs to whichever model is answering, and no agent
 * exposes it over a stable interface. This estimates by character count with
 * the usual ~4 characters per token, split by what actually occupies a
 * conversation, so the ring can show *what* is taking the space rather than
 * just a number. It is labelled as an estimate in the UI.
 */
object ContextBudget {

    /** Bytes per token, the widely used approximation for English + code. */
    private const val CHARS_PER_TOKEN = 4.0

    data class Part(val label: String, val tokens: Int) {
        /** Share of the total, 0..1. */
        var fraction: Float = 0f
    }

    data class Estimate(
        val parts: List<Part>,
        val total: Int,
        /** The model's window, when known; 0 means unknown. */
        val window: Int,
    ) {
        /** Fill of the window, 0..1. Zero when the window is unknown. */
        val fraction: Float
            get() = if (window <= 0) 0f else (total.toFloat() / window).coerceIn(0f, 1f)

        /** Tokens left before the window is full. */
        val remaining: Int get() = (window - total).coerceAtLeast(0)
    }

    /** Approximate token count of [text]. */
    fun tokens(text: String): Int =
        if (text.isEmpty()) 0 else Math.ceil(text.length / CHARS_PER_TOKEN).toInt()

    /**
     * Split a conversation into its parts.
     *
     * @param systemPrompt the instructions sent ahead of the conversation
     * @param tools the tool/command definitions sent with every request
     * @param skills any agent skill files that are in play
     * @param messages the conversation itself
     * @param window the model's context window, or 0 when unknown
     */
    fun estimate(
        systemPrompt: String,
        tools: String,
        skills: String,
        messages: List<Pair<String, String>>,
        window: Int = DEFAULT_WINDOW,
    ): Estimate {
        val parts = mutableListOf(
            Part("System", tokens(systemPrompt)),
            Part("Tools", tokens(tools)),
            Part("Skills", tokens(skills)),
            Part("Messages", messages.sumOf { tokens(it.first) + tokens(it.second) }),
        ).filter { it.tokens > 0 }
        val total = parts.sumOf { it.tokens }
        for (part in parts) {
            part.fraction = if (total <= 0) 0f else part.tokens.toFloat() / total
        }
        return Estimate(parts = parts, total = total, window = window)
    }

    /**
     * Known context windows for the model families this app sees.
     *
     * Used only for the ring's denominator; an unknown model falls back to
     * [DEFAULT_WINDOW] and the UI marks the figure as approximate.
     */
    fun windowFor(model: String): Int {
        val m = model.lowercase()
        return when {
            m.contains("gemini") -> 1_000_000
            m.contains("claude") -> 200_000
            m.contains("gpt-5") || m.contains("gpt-4.1") -> 400_000
            m.contains("gpt-4o") || m.contains("gpt-4") -> 128_000
            m.contains("nemotron") -> 128_000
            m.contains("qwen") -> 128_000
            m.contains("kimi") -> 256_000
            m.contains("deepseek") -> 128_000
            else -> DEFAULT_WINDOW
        }
    }

    /** Fallback window when the model is unknown. */
    const val DEFAULT_WINDOW = 128_000
}
