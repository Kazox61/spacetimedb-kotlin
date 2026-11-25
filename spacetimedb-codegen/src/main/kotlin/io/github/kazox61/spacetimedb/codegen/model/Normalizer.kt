package io.github.kazox61.spacetimedb.codegen.model

import io.github.kazox61.spacetimedb.codegen.CaseConversion
import io.github.kazox61.spacetimedb.codegen.schema.AlgebraicType
import io.github.kazox61.spacetimedb.codegen.schema.CaseConversionPolicy
import io.github.kazox61.spacetimedb.codegen.schema.PrimitiveKind
import io.github.kazox61.spacetimedb.codegen.schema.RawIndexAlgorithm
import io.github.kazox61.spacetimedb.codegen.schema.RawModuleDef
import io.github.kazox61.spacetimedb.codegen.schema.RawTableDef
import io.github.kazox61.spacetimedb.codegen.schema.RawViewDef

/**
 * Turns a [RawModuleDef] into the [ModuleModel] the emitter reads, following upstream's V10
 * validation (`crates/schema/src/def/validate/v10.rs`) and `TypespaceForGenerate`
 * (`crates/schema/src/type_for_generate.rs`), SpacetimeDB 2.16.
 *
 * The schema comes from a module the server already accepted, so this doesn't repeat upstream's
 * error checks. It throws [InvalidSchemaException] only where it can't go on.
 */
public object Normalizer {
    public class InvalidSchemaException(message: String) : IllegalArgumentException(message)

    public fun normalize(raw: RawModuleDef): ModuleModel = applyNamespace(validate(raw, mountAccessor = null), emptyList(), emptyList())

    /** One module on its own, with root-relative names. Upstream: `validate`. */
    private fun validate(raw: RawModuleDef, mountAccessor: String?): ModuleModel {
        val names = Names(raw.caseConversionPolicy)
        val types = TypeUses(raw.typespace, raw.types.map { it.ref }.toSet())

        val tables = raw.tables.map { tableModel(it, raw, names) }
        val reducerNames = raw.reducers.map { names.resolve(it.sourceName, raw.explicitNames.functions) }.toSet()
        val procedureNames = raw.procedures.map { names.resolve(it.sourceName, raw.explicitNames.functions) }.toSet()

        // Scheduled functions and lifecycle reducers are not client-callable.
        val scheduled = raw.schedules.map { names.resolve(it.functionName, raw.explicitNames.functions) }
        val scheduledReducers = scheduled.filter { it in reducerNames }.toSet()
        val scheduledProcedures = scheduled.filter { it !in reducerNames && it in procedureNames }.toSet()
        val lifecycles = raw.lifecycleReducers.associate { names.resolve(it.functionName, raw.explicitNames.functions) to it.lifecycle }

        val reducers = raw.reducers.map { reducer ->
            val name = names.resolve(reducer.sourceName, raw.explicitNames.functions)
            val lifecycle = lifecycles[name]
            ReducerModel(
                name = name,
                accessorName = reducer.sourceName,
                params = reducer.params.map { names.param(it, types) },
                lifecycle = lifecycle,
                isPrivate = reducer.isPrivate || lifecycle != null || name in scheduledReducers,
            )
        }
        val procedures = raw.procedures.map { procedure ->
            val name = names.resolve(procedure.sourceName, raw.explicitNames.functions)
            ProcedureModel(
                name = name,
                accessorName = procedure.sourceName,
                params = procedure.params.map { names.param(it, types) },
                returnType = types.use(procedure.returnType),
                isPrivate = procedure.isPrivate || name in scheduledProcedures,
            )
        }
        val views = raw.views.map { viewModel(it, raw, tables, names, types) }

        val typeModels = raw.types.map { TypeModel(it.scope.map(names::typeName) + names.typeName(it.name), it.ref) }
        val typeDefs = types.definitions()

        val submodules = raw.submodules.map { sub ->
            val namespace = raw.explicitNames.namespaces[sub.namespace] ?: names.convert(sub.namespace)
            namespace to validate(sub.module, mountAccessor = sub.namespace)
        }

        return ModuleModel(
            path = emptyList(),
            accessorPath = listOfNotNull(mountAccessor),
            tables = tables,
            views = views,
            reducers = reducers,
            procedures = procedures,
            types = typeModels,
            typeDefs = typeDefs,
            submodules = submodules,
        )
    }

    /**
     * Qualify names by the namespace each module is mounted under. Only reducer names carry it;
     * other names get the module's [ModuleModel.pathPrefix] where they are used. Upstream:
     * `ModuleDef::apply_namespace`.
     */
    private fun applyNamespace(module: ModuleModel, path: List<String>, accessorPath: List<String>): ModuleModel =
        module.copy(
            path = path,
            accessorPath = accessorPath,
            reducers = module.reducers.map { it.copy(name = (path + it.name).joinToString(".")) },
            submodules = module.submodules.map { (namespace, sub) ->
                val accessor = sub.accessorPath.lastOrNull() ?: namespace
                namespace to applyNamespace(sub, path + namespace, accessorPath + accessor)
            },
        )

    private fun tableModel(table: RawTableDef, raw: RawModuleDef, names: Names): TableModel {
        val name = names.resolve(table.sourceName, raw.explicitNames.tables)
        val elements = productAt(raw, table.productTypeRef, table.sourceName)
        // Index names use the case-converted column names, or `col_<n>` for unnamed columns.
        fun columnName(col: Int) = elements.getOrNull(col)?.name?.let(names::convert) ?: "col_$col"
        val uniqueColumnSets = table.uniqueConstraints.map { it.distinct().sorted() }

        val indexes = table.indexes.map { index ->
            val (kind, label) = when (index.algorithm) {
                is RawIndexAlgorithm.BTree -> IndexKind.BTree to "btree"
                is RawIndexAlgorithm.Hash -> IndexKind.Hash to "hash"
                is RawIndexAlgorithm.Direct -> IndexKind.Direct to "direct"
            }
            val columns = index.algorithm.columns
            val explicit = index.sourceName?.let { raw.explicitNames.indexes[it] }
            IndexModel(
                name = explicit ?: "${name}_${columns.joinToString("_") { columnName(it) }}_idx_$label",
                accessorName = index.accessorName,
                algorithm = kind,
                columns = columns,
                isUnique = uniqueColumnSets.any { it == columns },
            )
        }.sortedBy { it.name }

        return TableModel(
            name = name,
            accessorName = table.sourceName,
            productTypeRef = table.productTypeRef,
            primaryKey = table.primaryKey.firstOrNull(),
            isEvent = table.isEvent,
            isPublic = table.isPublic,
            columns = names.columns(elements, table.sourceName),
            indexes = indexes,
        )
    }

    private fun viewModel(view: RawViewDef, raw: RawModuleDef, tables: List<TableModel>, names: Names, types: TypeUses): ViewModel {
        val (productTypeRef, isQuery) = viewReturnProductTypeRef(view.returnType)
            ?: throw InvalidSchemaException("View ${view.sourceName} doesn't return rows of a named product type")
        val columns = names.columns(productAt(raw, productTypeRef, view.sourceName), view.sourceName)

        // A primary key the module declared for the view, only for views without parameters.
        val declared = raw.viewPrimaryKeys
            .firstOrNull { it.viewSourceName == view.sourceName }
            ?.takeIf { it.columns.size == 1 && view.params.isEmpty() }
            ?.let { pk -> columns.indexOfFirst { it.accessorName == pk.columns.single() }.takeIf { it >= 0 } }
        // A query view without parameters inherits the primary key of the tables with its row type,
        // if they agree on one.
        val inherited = if (declared == null && isQuery && view.params.isEmpty()) {
            tables.filter { it.productTypeRef == productTypeRef }.mapNotNull { it.primaryKey }.distinct().singleOrNull()
        } else {
            null
        }

        val name = names.resolve(view.sourceName, raw.explicitNames.functions)
        return ViewModel(
            name = name,
            accessorName = view.sourceName,
            params = view.params.map { it.name },
            paramsForGenerate = view.params.map { names.param(it, types) },
            asTable = TableModel(
                name = name,
                accessorName = view.sourceName,
                productTypeRef = productTypeRef,
                primaryKey = declared ?: inherited,
                isEvent = false,
                isPublic = view.isPublic,
                columns = columns,
                indexes = emptyList(),
            ),
        )
    }

    /** Upstream: `extract_view_return_product_type_ref`. The flag is true for query views. */
    private fun viewReturnProductTypeRef(type: AlgebraicType): Pair<Int, Boolean>? {
        if (type is AlgebraicType.Product && type.elements.size == 1 && type.elements[0].name == "__query__") {
            (type.elements[0].type as? AlgebraicType.Ref)?.let { return it.index to true }
        }
        val inner = Shapes.optionOf(type) ?: (type as? AlgebraicType.Array)?.element
        return (inner as? AlgebraicType.Ref)?.let { it.index to false }
    }

    private fun productAt(raw: RawModuleDef, ref: Int, owner: String): List<AlgebraicType.Member> =
        (raw.typespace.getOrNull(ref) as? AlgebraicType.Product)?.elements
            ?: throw InvalidSchemaException("$owner refers to type $ref, which is not a product type")

    /** Name rules for one module's [CaseConversionPolicy]. */
    private class Names(private val policy: CaseConversionPolicy) {
        fun convert(s: String): String = when (policy) {
            CaseConversionPolicy.SnakeCase -> CaseConversion.snake(s)
            CaseConversionPolicy.None -> s
        }

        /** The module's explicit name if it gave one, otherwise the case-converted source name. */
        fun resolve(source: String, explicit: Map<String, String>): String = explicit[source] ?: convert(source)

        /** Type names become PascalCase under any policy but `None`. */
        fun typeName(s: String): String = if (policy == CaseConversionPolicy.None) s else CaseConversion.pascal(s)

        fun columns(elements: List<AlgebraicType.Member>, owner: String) = elements.mapIndexed { i, element ->
            val accessor = element.name ?: throw InvalidSchemaException("Column $i of $owner has no name")
            ColumnModel(name = convert(accessor), accessorName = accessor)
        }

        fun param(param: AlgebraicType.Member, types: TypeUses): Field {
            val name = param.name ?: throw InvalidSchemaException("Unnamed parameter")
            return Field(convert(name), types.use(param.type))
        }
    }

    /**
     * Recognizes uses of types: special types by shape, named types by reference.
     * Upstream: `TypespaceForGenerateBuilder`. A reference to a typespace entry that isn't a named
     * type is replaced by that entry's own use.
     */
    private class TypeUses(private val typespace: List<AlgebraicType>, private val definitions: Set<Int>) {
        private val resolved = mutableMapOf<Int, TypeUse>()
        private val resolving = mutableSetOf<Int>()

        // Checked in upstream's order (`parse_use`).
        fun use(type: AlgebraicType): TypeUse {
            Shapes.newtypeOrEmpty(type)?.let { return it }
            Shapes.optionOf(type)?.let { return TypeUse.Option(use(it)) }
            Shapes.resultOf(type)?.let { (ok, err) -> return TypeUse.Result(use(ok), use(err)) }
            if (Shapes.isScheduleAt(type)) return TypeUse.ScheduleAt
            return structural(type)
        }

        private fun structural(type: AlgebraicType): TypeUse = when (type) {
            is AlgebraicType.Ref -> ref(type.index)
            is AlgebraicType.Array -> TypeUse.Array(use(type.element))
            AlgebraicType.Str -> TypeUse.Str
            is AlgebraicType.Primitive -> TypeUse.Primitive(type.kind)
            is AlgebraicType.Product, is AlgebraicType.Sum ->
                throw InvalidSchemaException("An unnamed product or sum type can't be used here: $type")
        }

        private fun ref(index: Int): TypeUse {
            if (index in definitions) return TypeUse.Ref(index)
            resolved[index]?.let { return it }
            val target = typespace.getOrNull(index) ?: throw InvalidSchemaException("Invalid type reference $index")
            if (target == AlgebraicType.Ref(index) || !resolving.add(index)) {
                throw InvalidSchemaException("Recursive type reference $index")
            }
            try {
                return use(target).also { resolved[index] = it }
            } finally {
                resolving.remove(index)
            }
        }

        /** Every named type's definition, marked recursive where it can reach itself. */
        fun definitions(): Map<Int, TypeDefModel> {
            val fields = definitions.associateWith { ref ->
                val members = when (val type = typespace.getOrNull(ref)) {
                    is AlgebraicType.Product -> type.elements
                    is AlgebraicType.Sum -> type.variants
                    else -> throw InvalidSchemaException("Type $ref must be a product or sum type to be named")
                }
                members.map { Field(it.name ?: throw InvalidSchemaException("Unnamed member in type $ref"), use(it.type)) }
            }
            val edges = fields.mapValues { (_, members) -> members.flatMap { refsIn(it.type) }.toSet() }
            fun reachesItself(start: Int): Boolean {
                val seen = mutableSetOf<Int>()
                val stack = ArrayDeque(edges.getValue(start))
                while (stack.isNotEmpty()) {
                    val next = stack.removeLast()
                    if (next == start) return true
                    if (seen.add(next)) stack.addAll(edges[next].orEmpty())
                }
                return false
            }
            return fields.mapValues { (ref, members) ->
                when (typespace[ref]) {
                    is AlgebraicType.Product -> TypeDefModel.Product(members, reachesItself(ref))
                    else -> if (members.all { it.type == TypeUse.Unit }) {
                        TypeDefModel.PlainEnum(members.map { it.name })
                    } else {
                        TypeDefModel.Sum(members, reachesItself(ref))
                    }
                }
            }
        }

        private fun refsIn(use: TypeUse): List<Int> = when (use) {
            is TypeUse.Ref -> listOf(use.index)
            is TypeUse.Array -> refsIn(use.element)
            is TypeUse.Option -> refsIn(use.inner)
            is TypeUse.Result -> refsIn(use.ok) + refsIn(use.err)
            else -> emptyList()
        }
    }

    /** Shape checks for SpacetimeDB's special types. Upstream: `sats` `is_identity` and friends. */
    private object Shapes {
        /** The special product newtypes, unit and never. */
        fun newtypeOrEmpty(type: AlgebraicType): TypeUse? = when {
            isNewtype(type, "__connection_id__", AlgebraicType.Primitive(PrimitiveKind.U128)) -> TypeUse.ConnectionId
            isNewtype(type, "__identity__", AlgebraicType.Primitive(PrimitiveKind.U256)) -> TypeUse.Identity
            isTimestamp(type) -> TypeUse.Timestamp
            isTimeDuration(type) -> TypeUse.TimeDuration
            isNewtype(type, "__uuid__", AlgebraicType.Primitive(PrimitiveKind.U128)) -> TypeUse.Uuid
            type is AlgebraicType.Product && type.elements.isEmpty() -> TypeUse.Unit
            type is AlgebraicType.Sum && type.variants.isEmpty() -> TypeUse.Never
            else -> null
        }

        fun isScheduleAt(type: AlgebraicType): Boolean {
            val variants = (type as? AlgebraicType.Sum)?.variants ?: return false
            return variants.size == 2 &&
                variants[0].name == "Interval" && isTimeDuration(variants[0].type) &&
                variants[1].name == "Time" && isTimestamp(variants[1].type)
        }

        fun optionOf(type: AlgebraicType): AlgebraicType? {
            val variants = (type as? AlgebraicType.Sum)?.variants ?: return null
            if (variants.size != 2) return null
            val (some, none) = variants
            val noneIsUnit = none.type is AlgebraicType.Product && (none.type as AlgebraicType.Product).elements.isEmpty()
            return if (noneIsUnit && some.name == "some" && none.name == "none") some.type else null
        }

        fun resultOf(type: AlgebraicType): Pair<AlgebraicType, AlgebraicType>? {
            val variants = (type as? AlgebraicType.Sum)?.variants ?: return null
            if (variants.size != 2) return null
            return if (variants[0].name == "ok" && variants[1].name == "err") variants[0].type to variants[1].type else null
        }

        private fun isTimestamp(type: AlgebraicType) =
            isNewtype(type, "__timestamp_micros_since_unix_epoch__", AlgebraicType.Primitive(PrimitiveKind.I64))

        private fun isTimeDuration(type: AlgebraicType) =
            isNewtype(type, "__time_duration_micros__", AlgebraicType.Primitive(PrimitiveKind.I64))

        private fun isNewtype(type: AlgebraicType, tag: String, inner: AlgebraicType): Boolean =
            type is AlgebraicType.Product && type.elements.size == 1 &&
                type.elements[0].name == tag && type.elements[0].type == inner
    }
}
