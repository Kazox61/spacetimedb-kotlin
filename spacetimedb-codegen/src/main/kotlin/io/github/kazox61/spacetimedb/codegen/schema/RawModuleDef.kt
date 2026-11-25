package io.github.kazox61.spacetimedb.codegen.schema

/**
 * The module definition as the server describes it (`RawModuleDefV10`), limited to what client code
 * generation needs. Parsed from the JSON that `spacetimedb-standalone extract-schema` prints, or that
 * `GET /v1/database/<db>/schema?version=10` returns. Nothing here is validated or renamed yet;
 * see [io.github.kazox61.spacetimedb.codegen.model.Normalizer] for that.
 */
public data class RawModuleDef(
    val caseConversionPolicy: CaseConversionPolicy,
    val typespace: List<AlgebraicType>,
    val types: List<RawTypeDef>,
    val tables: List<RawTableDef>,
    val reducers: List<RawReducerDef>,
    val procedures: List<RawProcedureDef>,
    val views: List<RawViewDef>,
    val schedules: List<RawScheduleDef>,
    val lifecycleReducers: List<RawLifecycleReducerDef>,
    val explicitNames: ExplicitNames,
    val viewPrimaryKeys: List<RawViewPrimaryKeyDef>,
    val submodules: List<RawSubmodule>,
)

public enum class CaseConversionPolicy { None, SnakeCase }

/** A SATS type as written in the schema. Product and sum members are named by `name`, if at all. */
public sealed interface AlgebraicType {
    public data class Ref(val index: Int) : AlgebraicType
    public data class Product(val elements: List<Member>) : AlgebraicType
    public data class Sum(val variants: List<Member>) : AlgebraicType
    public data class Array(val element: AlgebraicType) : AlgebraicType
    public data object Str : AlgebraicType
    public data class Primitive(val kind: PrimitiveKind) : AlgebraicType

    /** A product element or a sum variant. */
    public data class Member(val name: String?, val type: AlgebraicType)
}

public enum class PrimitiveKind { Bool, I8, U8, I16, U16, I32, U32, I64, U64, I128, U128, I256, U256, F32, F64 }

public data class RawTypeDef(val scope: List<String>, val name: String, val ref: Int, val customOrdering: Boolean)

public data class RawTableDef(
    val sourceName: String,
    val productTypeRef: Int,
    val primaryKey: List<Int>,
    val indexes: List<RawIndexDef>,
    /** Column lists of the table's unique constraints, as written. */
    val uniqueConstraints: List<List<Int>>,
    val isPublic: Boolean,
    val isSystem: Boolean,
    val isEvent: Boolean,
)

public data class RawIndexDef(val sourceName: String?, val accessorName: String?, val algorithm: RawIndexAlgorithm)

public sealed interface RawIndexAlgorithm {
    public val columns: List<Int>

    public data class BTree(override val columns: List<Int>) : RawIndexAlgorithm
    public data class Hash(override val columns: List<Int>) : RawIndexAlgorithm
    public data class Direct(val column: Int) : RawIndexAlgorithm {
        override val columns: List<Int> get() = listOf(column)
    }
}

public data class RawReducerDef(val sourceName: String, val params: List<AlgebraicType.Member>, val isPrivate: Boolean)

public data class RawProcedureDef(
    val sourceName: String,
    val params: List<AlgebraicType.Member>,
    val returnType: AlgebraicType,
    val isPrivate: Boolean,
)

public data class RawViewDef(
    val sourceName: String,
    val isPublic: Boolean,
    val isAnonymous: Boolean,
    val params: List<AlgebraicType.Member>,
    val returnType: AlgebraicType,
)

public data class RawScheduleDef(val tableName: String, val functionName: String, val scheduleAtColumn: Int)

public enum class Lifecycle { Init, OnConnect, OnDisconnect }

public data class RawLifecycleReducerDef(val lifecycle: Lifecycle, val functionName: String)

/** Canonical names the module chose itself, by source name. They take precedence over case conversion. */
public data class ExplicitNames(
    val tables: Map<String, String> = emptyMap(),
    val functions: Map<String, String> = emptyMap(),
    val indexes: Map<String, String> = emptyMap(),
    val namespaces: Map<String, String> = emptyMap(),
)

public data class RawViewPrimaryKeyDef(val viewSourceName: String, val columns: List<String>)

public data class RawSubmodule(val namespace: String, val module: RawModuleDef)
