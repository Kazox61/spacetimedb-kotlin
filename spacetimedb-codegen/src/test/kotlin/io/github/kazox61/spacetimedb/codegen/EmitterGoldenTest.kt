package io.github.kazox61.spacetimedb.codegen

import io.github.kazox61.spacetimedb.codegen.emit.GeneratorInfo
import io.github.kazox61.spacetimedb.codegen.emit.KotlinEmitter
import io.github.kazox61.spacetimedb.codegen.model.Normalizer
import io.github.kazox61.spacetimedb.codegen.schema.SchemaJson
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The generator must write exactly the files the old Rust generator wrote (`fixtures/<name>/expected`).
 * The version comment and `cliVersion` are inputs, taken from the expected `Module.kt`.
 */
class EmitterGoldenTest {
    @Test
    fun `generated files match the old generator for every fixture`() {
        val failures = Fixtures.names.mapNotNull { name -> compare(name, Fixtures.schema(name)) }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    @Test
    fun `output does not depend on the hash order of extract-schema`() {
        val failures = Fixtures.names.flatMap { name ->
            (1..3).mapNotNull { seed ->
                val shuffled = shuffle(Json.parseToJsonElement(Fixtures.schema(name)), Random(seed))
                compare(name, shuffled.toString())?.let { "seed $seed: $it" }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    private fun compare(name: String, schema: String): String? {
        val expected = expectedFiles(name)
        val actual = KotlinEmitter.generate(Normalizer.normalize(SchemaJson.parse(schema)), info(expected.getValue("Module.kt")))
        val missing = expected.keys - actual.keys
        val extra = actual.keys - expected.keys
        if (missing.isNotEmpty() || extra.isNotEmpty()) return "$name: missing $missing, extra $extra"
        val diffs = expected.keys.sorted().mapNotNull { path -> firstLineDifference(expected.getValue(path), actual.getValue(path))?.let { "$path $it" } }
        return if (diffs.isEmpty()) null else "$name (${diffs.size} files differ):\n" + diffs.take(5).joinToString("\n")
    }

    private fun expectedFiles(name: String): Map<String, String> {
        val root = Fixtures.dir.resolve("$name/expected")
        return root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    private fun info(moduleKt: String) = GeneratorInfo(
        versionComment = moduleKt.lines().first { it.startsWith("// This was generated using") },
        cliVersion = Regex("""override val cliVersion: String = "(.*)"""").find(moduleKt)!!.groupValues[1],
    )

    private fun firstLineDifference(expected: String, actual: String): String? {
        if (expected == actual) return null
        val e = expected.lines()
        val a = actual.lines()
        val i = (0 until maxOf(e.size, a.size)).first { e.getOrNull(it) != a.getOrNull(it) }
        return "line ${i + 1}:\n  expected |${e.getOrNull(i)}|\n  actual   |${a.getOrNull(i)}|"
    }

    /** Reorders what `extract-schema` writes in hash order, and the sections themselves. */
    private fun shuffle(json: JsonElement, random: Random): JsonElement = when (json) {
        is JsonObject -> JsonObject(
            json.mapValues { (key, value) ->
                val inner = shuffle(value, random)
                when {
                    key == "sections" && inner is JsonArray -> JsonArray(inner.shuffled(random))
                    key in HASH_ORDERED && inner is JsonArray -> JsonArray(inner.shuffled(random))
                    key == "ExplicitNames" && inner is JsonObject -> JsonObject(
                        inner.mapValues { (k, v) -> if (k == "entries" && v is JsonArray) JsonArray(v.shuffled(random)) else v },
                    )
                    else -> inner
                }
            },
        )
        is JsonArray -> JsonArray(json.map { shuffle(it, random) })
        else -> json
    }

    private companion object {
        val HASH_ORDERED = setOf("Types", "Tables", "Schedules", "Outboxes")
    }
}
