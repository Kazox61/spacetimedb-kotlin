package io.github.kazox61.spacetimedb.codegen

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The golden fixtures in the repository's `fixtures/` folder (see its README). */
object Fixtures {
    val dir: File = File(System.getProperty("fixtures.dir") ?: error("fixtures.dir not set"))

    val names: List<String> = dir.listFiles { f -> f.isDirectory }!!.map { it.name }.sorted().also {
        check(it.size >= 5) { "Expected the five golden fixtures in $dir, found $it" }
    }

    fun schema(name: String): String = dir.resolve("$name/schema.json").readText()

    /** Upstream's validated model, dumped by the fork-based tool (`model.json`). */
    fun oracle(name: String): String = dir.resolve("$name/model.json").readText()
}

/** The first place two JSON trees differ, as `path: expected vs actual`, or null if equal. */
fun firstDifference(expected: JsonElement?, actual: JsonElement?, path: String = "$"): String? {
    if (expected == actual) return null
    return when {
        expected is JsonObject && actual is JsonObject -> {
            (expected.keys + actual.keys).firstNotNullOfOrNull { key ->
                firstDifference(expected[key], actual[key], "$path.$key")
            }
        }
        expected is JsonArray && actual is JsonArray && expected.size == actual.size ->
            expected.indices.firstNotNullOfOrNull { i -> firstDifference(expected[i], actual[i], "$path[$i]") }
        else -> "$path:\n  expected ${expected.toString().take(400)}\n  actual   ${actual.toString().take(400)}"
    }
}
