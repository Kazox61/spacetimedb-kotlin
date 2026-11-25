package io.github.kazox61.spacetimedb.codegen.emit

/**
 * Line-based writer with indentation. Blank lines get no indent, like upstream's `CodeIndenter`,
 * so the output matches the old generator byte for byte.
 */
internal class CodeWriter {
    private val out = StringBuilder()
    private var level = 0

    fun line(text: String = "") {
        if (text.isNotEmpty()) repeat(level) { out.append(INDENT) }
        out.append(text).append('\n')
    }

    fun indent() {
        level++
    }

    fun dedent() {
        level = maxOf(0, level - 1)
    }

    /** Writes [open], the indented [body], then [close]. */
    inline fun block(open: String, close: String = "}", body: () -> Unit) {
        line(open)
        indent()
        body()
        dedent()
        line(close)
    }

    override fun toString(): String = out.toString()

    private companion object {
        const val INDENT = "    "
    }
}
