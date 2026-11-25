package io.github.kazox61.spacetimedb.codegen

/**
 * Port of the Rust `convert_case` crate, version 0.6.0, as used by SpacetimeDB's schema validation and
 * code generators (`s.to_case(Case::Snake)` and friends). Names must convert exactly as upstream does,
 * because they end up on the wire and in generated identifiers.
 *
 * Words are split at `_`, `-` and spaces (which are dropped), at lower→upper, upper→digit, digit→upper,
 * digit→lower and lower→digit changes, and before the last capital of an acronym (`HTTPServer` →
 * `HTTP`, `Server`). Upstream works on grapheme clusters; identifiers are code points here, which is
 * the same for anything a module can declare.
 */
public object CaseConversion {
    public fun snake(s: String): String = words(s).joinToString("_") { it.lowercase() }

    public fun camel(s: String): String =
        words(s).mapIndexed { i, w -> if (i == 0) w.lowercase() else capital(w) }.joinToString("")

    public fun pascal(s: String): String = words(s).joinToString("") { capital(it) }

    private fun capital(word: String): String {
        if (word.isEmpty()) return word
        val first = word.codePointAt(0)
        val firstLen = Character.charCount(first)
        return String(Character.toChars(first)).uppercase() + word.substring(firstLen).lowercase()
    }

    private fun isDelimiter(c: String) = c == "_" || c == "-" || c == " "

    private fun isDigit(c: String) = c.all { it in '0'..'9' }

    // Upstream: `c.to_uppercase() != c.to_lowercase() && c == c.to_uppercase()`.
    private fun isUpper(c: String) = c.uppercase() != c.lowercase() && c == c.uppercase()

    private fun isLower(c: String) = c.uppercase() != c.lowercase() && c == c.lowercase()

    private fun splitsBetween(c: String, d: String): Boolean =
        (isLower(c) && isUpper(d)) ||
            (isUpper(c) && isDigit(d)) ||
            (isDigit(c) && isUpper(d)) ||
            (isDigit(c) && isLower(d)) ||
            (isLower(c) && isDigit(d))

    private fun isAcronymEnd(c: String, d: String, e: String) = isUpper(c) && isUpper(d) && isLower(e)

    /** `segmentation::split` with `Boundary::defaults()`, empty words removed. */
    internal fun words(s: String): List<String> {
        val chars = buildList {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                add(String(Character.toChars(cp)))
                i += Character.charCount(cp)
            }
        }
        val words = mutableListOf<String>()
        val word = StringBuilder()
        for (i in chars.indices) {
            val c = chars[i]
            when {
                // Split here and drop the delimiter.
                isDelimiter(c) -> {
                    words += word.toString()
                    word.clear()
                }
                // Split before this character and keep it.
                (i >= 1 && splitsBetween(chars[i - 1], c)) ||
                    (i >= 1 && i + 1 < chars.size && isAcronymEnd(chars[i - 1], c, chars[i + 1])) -> {
                    words += word.toString()
                    word.clear()
                    word.append(c)
                }
                else -> word.append(c)
            }
        }
        words += word.toString()
        return words.filter { it.isNotEmpty() }
    }
}
