package io.github.kazox61.spacetimedb.codegen.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads a module schema in SATS-JSON, the encoding the server uses for `RawModuleDef`:
 * a sum value is a one-key object (`{"Ref": 3}`, `{"Private": []}`), an option is `{"some": x}` or
 * `{"none": []}`, and unit is `[]`.
 *
 * Only `RawModuleDefV10` is supported. Sections that don't affect clients (environment, HTTP routes,
 * outboxes, row-level security, table cache policies) are skipped, and so is any section added by a
 * later server version, since a new section can't change the meaning of the existing ones.
 */
public object SchemaJson {
    public class SchemaFormatException(message: String) : IllegalArgumentException(message)

    public fun parse(json: String): RawModuleDef {
        val (version, body) = variant(Json.parseToJsonElement(json))
        if (version != "V10") {
            throw SchemaFormatException("Unsupported module definition version $version; this generator reads V10 (SpacetimeDB 2.x)")
        }
        return parseV10(body)
    }

    private fun parseV10(body: JsonElement): RawModuleDef {
        val sections = body.obj("sections").jsonArray.associate { variant(it) }
        return RawModuleDef(
            caseConversionPolicy = sections["CaseConversionPolicy"]?.let { CaseConversionPolicy.valueOf(variant(it).first) }
                ?: CaseConversionPolicy.SnakeCase,
            typespace = sections["Typespace"]?.obj("types")?.jsonArray?.map(::algebraicType).orEmpty(),
            types = sections["Types"]?.jsonArray?.map(::typeDef).orEmpty(),
            tables = sections["Tables"]?.jsonArray?.map(::tableDef).orEmpty(),
            reducers = sections["Reducers"]?.jsonArray?.map(::reducerDef).orEmpty(),
            procedures = sections["Procedures"]?.jsonArray?.map(::procedureDef).orEmpty(),
            views = sections["Views"]?.jsonArray?.map(::viewDef).orEmpty(),
            schedules = sections["Schedules"]?.jsonArray?.map {
                RawScheduleDef(it.str("table_name"), it.str("function_name"), it.obj("schedule_at_col").jsonPrimitive.int)
            }.orEmpty(),
            lifecycleReducers = sections["LifeCycleReducers"]?.jsonArray?.map {
                RawLifecycleReducerDef(Lifecycle.valueOf(variant(it.obj("lifecycle_spec")).first), it.str("function_name"))
            }.orEmpty(),
            explicitNames = sections["ExplicitNames"]?.let(::explicitNames) ?: ExplicitNames(),
            viewPrimaryKeys = sections["ViewPrimaryKeys"]?.jsonArray?.map {
                RawViewPrimaryKeyDef(it.str("view_source_name"), it.obj("columns").jsonArray.map { c -> c.jsonPrimitive.content })
            }.orEmpty(),
            submodules = sections["Submodules"]?.jsonArray?.map {
                RawSubmodule(it.str("namespace"), parseV10(it.obj("module")))
            }.orEmpty(),
        )
    }

    internal fun algebraicType(json: JsonElement): AlgebraicType {
        val (tag, value) = variant(json)
        return when (tag) {
            "Ref" -> AlgebraicType.Ref(value.jsonPrimitive.int)
            "Product" -> AlgebraicType.Product(value.obj("elements").jsonArray.map(::member))
            "Sum" -> AlgebraicType.Sum(value.obj("variants").jsonArray.map(::member))
            "Array" -> AlgebraicType.Array(algebraicType(value))
            "String" -> AlgebraicType.Str
            else -> AlgebraicType.Primitive(
                PrimitiveKind.entries.firstOrNull { it.name == tag } ?: throw SchemaFormatException("Unknown algebraic type $tag"),
            )
        }
    }

    private fun member(json: JsonElement) =
        AlgebraicType.Member(option(json.obj("name"))?.jsonPrimitive?.content, algebraicType(json.obj("algebraic_type")))

    private fun params(json: JsonElement) = json.obj("params").obj("elements").jsonArray.map(::member)

    private fun typeDef(json: JsonElement): RawTypeDef {
        val name = json.obj("source_name")
        return RawTypeDef(
            scope = name.obj("scope").jsonArray.map { it.jsonPrimitive.content },
            name = name.str("source_name"),
            ref = json.obj("ty").jsonPrimitive.int,
            customOrdering = json.obj("custom_ordering").jsonPrimitive.content.toBoolean(),
        )
    }

    private fun tableDef(json: JsonElement) = RawTableDef(
        sourceName = json.str("source_name"),
        productTypeRef = json.obj("product_type_ref").jsonPrimitive.int,
        primaryKey = json.obj("primary_key").jsonArray.map { it.jsonPrimitive.int },
        indexes = json.obj("indexes").jsonArray.map { index ->
            val (kind, value) = variant(index.obj("algorithm"))
            RawIndexDef(
                sourceName = option(index.obj("source_name"))?.jsonPrimitive?.content,
                accessorName = option(index.obj("accessor_name"))?.jsonPrimitive?.content,
                algorithm = when (kind) {
                    "BTree" -> RawIndexAlgorithm.BTree(value.jsonArray.map { it.jsonPrimitive.int })
                    "Hash" -> RawIndexAlgorithm.Hash(value.jsonArray.map { it.jsonPrimitive.int })
                    "Direct" -> RawIndexAlgorithm.Direct(value.jsonPrimitive.int)
                    else -> throw SchemaFormatException("Unknown index algorithm $kind")
                },
            )
        },
        uniqueConstraints = json.obj("constraints").jsonArray.mapNotNull { constraint ->
            val (kind, value) = variant(constraint.obj("data"))
            if (kind == "Unique") value.obj("columns").jsonArray.map { it.jsonPrimitive.int } else null
        },
        isPublic = variant(json.obj("table_access")).first == "Public",
        isSystem = variant(json.obj("table_type")).first == "System",
        isEvent = json.obj("is_event").jsonPrimitive.content.toBoolean(),
    )

    private fun reducerDef(json: JsonElement) =
        RawReducerDef(json.str("source_name"), params(json), isPrivate = variant(json.obj("visibility")).first == "Private")

    private fun procedureDef(json: JsonElement) = RawProcedureDef(
        sourceName = json.str("source_name"),
        params = params(json),
        returnType = algebraicType(json.obj("return_type")),
        isPrivate = variant(json.obj("visibility")).first == "Private",
    )

    private fun viewDef(json: JsonElement) = RawViewDef(
        sourceName = json.str("source_name"),
        isPublic = json.obj("is_public").jsonPrimitive.content.toBoolean(),
        isAnonymous = json.obj("is_anonymous").jsonPrimitive.content.toBoolean(),
        params = params(json),
        returnType = algebraicType(json.obj("return_type")),
    )

    private fun explicitNames(json: JsonElement): ExplicitNames {
        val byKind = mutableMapOf<String, MutableMap<String, String>>()
        for (entry in json.obj("entries").jsonArray) {
            val (kind, value) = variant(entry)
            byKind.getOrPut(kind) { mutableMapOf() }[value.str("source_name")] = value.str("canonical_name")
        }
        return ExplicitNames(
            tables = byKind["Table"].orEmpty(),
            functions = byKind["Function"].orEmpty(),
            indexes = byKind["Index"].orEmpty(),
            namespaces = byKind["Namespace"].orEmpty(),
        )
    }

    /** A SATS-JSON sum value: its only key and the payload. */
    private fun variant(json: JsonElement): Pair<String, JsonElement> {
        val obj = json as? JsonObject ?: throw SchemaFormatException("Expected a sum value (one-key object), got $json")
        if (obj.size != 1) throw SchemaFormatException("Expected a sum value with one key, got keys ${obj.keys}")
        return obj.entries.single().toPair()
    }

    /** A SATS-JSON option: the `some` payload, or null for `none`. */
    private fun option(json: JsonElement): JsonElement? {
        val (tag, value) = variant(json)
        return when (tag) {
            "some" -> value
            "none" -> null
            else -> throw SchemaFormatException("Expected an option, got $tag")
        }
    }

    private fun JsonElement.obj(key: String): JsonElement =
        jsonObject[key] ?: throw SchemaFormatException("Missing field '$key' in ${toString().take(200)}")

    private fun JsonElement.str(key: String): String = obj(key).jsonPrimitive.content
}
