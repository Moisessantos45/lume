package com.example.lume.util

import java.text.Normalizer

object TextSanitizer {
    private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALLOWED = Regex("[^\\p{L}\\p{N} &]")
    private val WHITESPACE = Regex("\\s+")

    fun sanitize(input: String): String {
        if (input.isEmpty()) return input
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val noAccents = DIACRITICS.replace(decomposed, "")
        val cleaned = NON_ALLOWED.replace(noAccents, " ")
        return WHITESPACE.replace(cleaned, " ").trim()
    }
}
