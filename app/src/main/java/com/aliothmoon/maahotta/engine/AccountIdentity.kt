package com.aliothmoon.maahotta.engine

/** A masked number proves identity only when no saved account shares its visible digits. */
object AccountIdentity {
    private fun normalize(phone: String): String = phone.trim().removePrefix("+86")

    fun matches(shown: String?, expectedPhone: String, savedPhones: List<String>): Boolean {
        val expected = normalize(expectedPhone)
        if (expected.length != 11 || !expected.all { it in '0'..'9' }) return false
        val text = shown.orEmpty()
        val full = Regex("(?<![0-9])(?:\\+86\\s*)?([0-9]{11})(?![0-9])")
            .findAll(text).map { it.groupValues[1] }.toList()
        if (full.isNotEmpty()) return full.distinct() == listOf(expected)
        val masks = Regex("(?<![0-9])([0-9]{3})\\*+([0-9]{4})(?![0-9])").findAll(text).toList()
        if (masks.size != 1) return false
        val mask = masks.single()
        if (mask.groupValues[1] != expected.take(3) || mask.groupValues[2] != expected.takeLast(4)) return false
        val candidates = (savedPhones + expectedPhone).map(::normalize).distinct().filter {
            it.length == 11 && it.all { digit -> digit in '0'..'9' } &&
                it.take(3) == mask.groupValues[1] && it.takeLast(4) == mask.groupValues[2]
        }
        return candidates == listOf(expected)
    }
}
