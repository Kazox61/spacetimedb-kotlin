package io.github.kazox61.spacetimedb.codegen.model

import io.github.kazox61.spacetimedb.codegen.schema.Lifecycle
import io.github.kazox61.spacetimedb.codegen.schema.PrimitiveKind

/**
 * A module as the Kotlin emitter sees it: names resolved, special types recognized, visibility settled.
 * Mirrors the parts of upstream's validated `ModuleDef` that the old Rust generator read.
 *
 * Every item has two names: [TableModel.name] and friends are canonical, used on the wire;
 * `accessorName` is the source name, used for generated identifiers.
 *
 * Ordering follows upstream: [reducers], [procedures], [views] and [submodules] keep schema order;
 * [tables] and [types] carry no meaningful order (the emitter sorts them), and [TableModel.indexes]
 * are sorted by name.
 */
public data class ModuleModel(
    /** Canonical namespace segments, empty for the root module. */
    val path: List<String>,
    /** Accessor namespace segments, empty for the root module. */
    val accessorPath: List<String>,
    val tables: List<TableModel>,
    val views: List<ViewModel>,
    val reducers: List<ReducerModel>,
    val procedures: List<ProcedureModel>,
    val types: List<TypeModel>,
    /** Named type definitions by typespace index. */
    val typeDefs: Map<Int, TypeDefModel>,
    /** Submodules by canonical namespace. */
    val submodules: List<Pair<String, ModuleModel>>,
) {
    /** The `"a.b."` prefix of names in this module, empty for the root. */
    val pathPrefix: String get() = path.joinToString("") { "$it." }
}

public data class TableModel(
    val name: String,
    val accessorName: String,
    val productTypeRef: Int,
    val primaryKey: Int?,
    val isEvent: Boolean,
    val isPublic: Boolean,
    val columns: List<ColumnModel>,
    val indexes: List<IndexModel>,
)

public data class ColumnModel(val name: String, val accessorName: String)

public data class IndexModel(
    val name: String,
    /** Null for system-generated indexes, which get no client accessor. */
    val accessorName: String?,
    val algorithm: IndexKind,
    val columns: List<Int>,
    val isUnique: Boolean,
)

public enum class IndexKind { BTree, Hash, Direct }

public data class ViewModel(
    val name: String,
    val accessorName: String,
    /** Parameter names as written. */
    val params: List<String?>,
    val paramsForGenerate: List<Field>,
    /** The view as the emitter treats it: a table without indexes. */
    val asTable: TableModel,
)

public data class ReducerModel(
    val name: String,
    val accessorName: String,
    val params: List<Field>,
    val lifecycle: Lifecycle?,
    val isPrivate: Boolean,
)

public data class ProcedureModel(
    val name: String,
    val accessorName: String,
    val params: List<Field>,
    val returnType: TypeUse,
    val isPrivate: Boolean,
)

/** A named type: `scope` segments then the name, after case conversion. */
public data class TypeModel(val nameSegments: List<String>, val ref: Int)

public data class Field(val name: String, val type: TypeUse)

public sealed interface TypeDefModel {
    public data class Product(val elements: List<Field>, val recursive: Boolean) : TypeDefModel
    public data class Sum(val variants: List<Field>, val recursive: Boolean) : TypeDefModel

    /** A sum whose variants all carry no data. */
    public data class PlainEnum(val variants: List<String>) : TypeDefModel
}

/** A use of a type, with SpacetimeDB's special types recognized. Upstream: `AlgebraicTypeUse`. */
public sealed interface TypeUse {
    /** A named type; always one of [ModuleModel.typeDefs]. */
    public data class Ref(val index: Int) : TypeUse
    public data class Array(val element: TypeUse) : TypeUse
    public data class Option(val inner: TypeUse) : TypeUse
    public data class Result(val ok: TypeUse, val err: TypeUse) : TypeUse
    public data object ScheduleAt : TypeUse
    public data object Identity : TypeUse
    public data object ConnectionId : TypeUse
    public data object Timestamp : TypeUse
    public data object TimeDuration : TypeUse
    public data object Uuid : TypeUse
    public data object Unit : TypeUse
    public data object Never : TypeUse
    public data object Str : TypeUse
    public data class Primitive(val kind: PrimitiveKind) : TypeUse
}
