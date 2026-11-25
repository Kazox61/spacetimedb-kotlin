package io.github.kazox61.spacetimedb.codegen

import io.github.kazox61.spacetimedb.codegen.model.Field
import io.github.kazox61.spacetimedb.codegen.model.ModuleModel
import io.github.kazox61.spacetimedb.codegen.model.Normalizer
import io.github.kazox61.spacetimedb.codegen.model.TableModel
import io.github.kazox61.spacetimedb.codegen.model.TypeDefModel
import io.github.kazox61.spacetimedb.codegen.model.TypeUse
import io.github.kazox61.spacetimedb.codegen.schema.SchemaJson
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The normalizer must produce what upstream's `ModuleDef` gives the generator, for every fixture.
 * The oracle (`model.json`) was dumped from the fork's real `ModuleDef`; this test writes the
 * Kotlin model in the same JSON shape and compares.
 */
class NormalizerOracleTest {
    @Test
    fun `normalized model matches upstream for every fixture`() {
        val failures = Fixtures.names.mapNotNull { name ->
            val expected = canonical(Json.parseToJsonElement(Fixtures.oracle(name)).jsonObject.getValue("model"))
            val actual = canonical(moduleJson(Normalizer.normalize(SchemaJson.parse(Fixtures.schema(name)))))
            firstDifference(expected, actual)?.let { "$name: $it" }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    @Test
    fun `case conversion matches convert_case for every identifier`() {
        val failures = Fixtures.names.flatMap { name ->
            Json.parseToJsonElement(Fixtures.oracle(name)).jsonObject.getValue("cases").jsonObject.mapNotNull { (input, cases) ->
                val expected = cases.jsonObject.mapValues { it.value.jsonPrimitive.content }
                val actual = mapOf(
                    "snake" to CaseConversion.snake(input),
                    "camel" to CaseConversion.camel(input),
                    "pascal" to CaseConversion.pascal(input),
                )
                if (expected == actual) null else "'$input': expected $expected, got $actual"
            }
        }.distinct()
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /** Upstream keeps tables and types in hash order, so compare them sorted. */
    private fun canonical(module: JsonElement): JsonElement {
        val obj = module.jsonObject
        fun sortedBy(key: String, by: (JsonElement) -> String) =
            JsonArray(obj.getValue(key).jsonArray.sortedBy(by))
        return JsonObject(
            obj + mapOf(
                "tables" to sortedBy("tables") { it.jsonObject.getValue("name").jsonPrimitive.content },
                "types" to sortedBy("types") { it.jsonObject.getValue("name_segments").toString() },
                "submodules" to JsonArray(obj.getValue("submodules").jsonArray.map { sub ->
                    JsonObject(sub.jsonObject + ("module" to canonical(sub.jsonObject.getValue("module"))))
                }),
            ),
        )
    }

    private fun moduleJson(m: ModuleModel): JsonElement = buildJsonObject {
        put("path", m.pathPrefix)
        put("path_segments", strings(m.path))
        put("accessor_path_segments", strings(m.accessorPath))
        put("tables", JsonArray(m.tables.map(::tableJson)))
        put("views", JsonArray(m.views.map { v ->
            buildJsonObject {
                put("name", v.name)
                put("accessor_name", v.accessorName)
                put("params", JsonArray(v.params.map { JsonPrimitive(it) }))
                put("params_for_generate", fields(v.paramsForGenerate))
                put("as_table", tableJson(v.asTable))
            }
        }))
        put("reducers", JsonArray(m.reducers.map { r ->
            buildJsonObject {
                put("name", r.name)
                put("accessor_name", r.accessorName)
                put("params_for_generate", fields(r.params))
                put("lifecycle", r.lifecycle?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("private", r.isPrivate)
            }
        }))
        put("procedures", JsonArray(m.procedures.map { p ->
            buildJsonObject {
                put("name", p.name)
                put("accessor_name", p.accessorName)
                put("params_for_generate", fields(p.params))
                put("return_type_for_generate", useJson(p.returnType))
                put("private", p.isPrivate)
            }
        }))
        put("types", JsonArray(m.types.map { t ->
            buildJsonObject {
                put("name_segments", strings(t.nameSegments))
                put("ref", t.ref)
            }
        }))
        put("typespace_for_generate", JsonObject(m.typeDefs.entries.associate { (ref, def) -> ref.toString() to defJson(def) }))
        put("submodules", JsonArray(m.submodules.map { (key, sub) ->
            buildJsonObject {
                put("key", key)
                put("module", moduleJson(sub))
            }
        }))
    }

    private fun tableJson(t: TableModel) = buildJsonObject {
        put("name", t.name)
        put("accessor_name", t.accessorName)
        put("product_type_ref", t.productTypeRef)
        put("primary_key", t.primaryKey?.let(::JsonPrimitive) ?: JsonNull)
        put("is_event", t.isEvent)
        put("public", t.isPublic)
        put("columns", JsonArray(t.columns.map { c ->
            buildJsonObject {
                put("name", c.name)
                put("accessor_name", c.accessorName)
            }
        }))
        put("indexes", JsonArray(t.indexes.map { i ->
            buildJsonObject {
                put("name", i.name)
                put("accessor_name", i.accessorName?.let(::JsonPrimitive) ?: JsonNull)
                put("algorithm", i.algorithm.name)
                put("columns", buildJsonArray { i.columns.forEach { add(JsonPrimitive(it)) } })
                put("is_unique", i.isUnique)
            }
        }))
    }

    private fun defJson(def: TypeDefModel): JsonElement = when (def) {
        is TypeDefModel.Product -> buildJsonObject {
            put("Product", buildJsonObject {
                put("elements", fields(def.elements))
                put("recursive", def.recursive)
            })
        }
        is TypeDefModel.Sum -> buildJsonObject {
            put("Sum", buildJsonObject {
                put("variants", fields(def.variants))
                put("recursive", def.recursive)
            })
        }
        is TypeDefModel.PlainEnum -> buildJsonObject {
            put("PlainEnum", buildJsonObject { put("variants", strings(def.variants)) })
        }
    }

    private fun useJson(u: TypeUse): JsonElement = when (u) {
        is TypeUse.Ref -> buildJsonObject { put("Ref", u.index) }
        is TypeUse.Array -> buildJsonObject { put("Array", useJson(u.element)) }
        is TypeUse.Option -> buildJsonObject { put("Option", useJson(u.inner)) }
        is TypeUse.Result -> buildJsonObject {
            put("Result", buildJsonObject {
                put("ok", useJson(u.ok))
                put("err", useJson(u.err))
            })
        }
        is TypeUse.Primitive -> buildJsonObject { put("Primitive", u.kind.name) }
        TypeUse.Str -> JsonPrimitive("String")
        else -> JsonPrimitive(u.toString())
    }

    private fun fields(fields: List<Field>) = JsonArray(fields.map { f ->
        buildJsonObject {
            put("name", f.name)
            put("type", useJson(f.type))
        }
    })

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
}
