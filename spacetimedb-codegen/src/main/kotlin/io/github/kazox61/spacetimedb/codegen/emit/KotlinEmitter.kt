package io.github.kazox61.spacetimedb.codegen.emit

import io.github.kazox61.spacetimedb.codegen.CaseConversion
import io.github.kazox61.spacetimedb.codegen.model.Field
import io.github.kazox61.spacetimedb.codegen.model.IndexKind
import io.github.kazox61.spacetimedb.codegen.model.ModuleModel
import io.github.kazox61.spacetimedb.codegen.model.ProcedureModel
import io.github.kazox61.spacetimedb.codegen.model.ReducerModel
import io.github.kazox61.spacetimedb.codegen.model.TableModel
import io.github.kazox61.spacetimedb.codegen.model.TypeDefModel
import io.github.kazox61.spacetimedb.codegen.model.TypeUse
import io.github.kazox61.spacetimedb.codegen.schema.Lifecycle
import io.github.kazox61.spacetimedb.codegen.schema.PrimitiveKind

/** What the generated `Module.kt` says about the generator that made it. */
public data class GeneratorInfo(
    /** The comment line after the `package` line of `Module.kt`. */
    val versionComment: String,
    /** `RemoteModule.cliVersion`. */
    val cliVersion: String,
)

/**
 * Writes the Kotlin client bindings for a module: file path (relative to the output folder) to content.
 *
 * A port of the old generator (`crates/codegen/src/kotlin.rs` in the SpacetimeDB fork) that produces the
 * same files. Only public items are generated, as with the CLI's default.
 */
public object KotlinEmitter {
    public fun generate(module: ModuleModel, info: GeneratorInfo): Map<String, String> {
        val files = LinkedHashMap<String, String>()
        fun add(file: Pair<String, String>) {
            files[file.first] = file.second
        }

        val root = Scope(module)
        root.publicTables.forEach { add(root.tableFile(it)) }
        module.views.forEach { add(root.tableFile(it.asTable)) }
        for (child in root.descendants) {
            child.publicTables.forEach { add(child.scoped(child.tableFile(it))) }
            child.module.views.forEach { add(child.scoped(child.tableFile(it.asTable))) }
        }
        root.reducers.forEach { add(root.reducerFile(it)) }
        root.procedures.forEach { add(root.procedureFile(it)) }
        for (child in root.descendants) {
            child.module.reducers.filter { !it.isPrivate }.forEach { add(child.scoped(child.reducerFile(it))) }
        }
        for (child in root.descendants) {
            child.procedures.forEach { add(child.scoped(child.procedureFile(it))) }
        }

        add(root.typesFile())
        add(root.remoteTablesFile())
        add(root.remoteReducersFile())
        add(root.remoteProceduresFile())
        add(root.moduleFile(info))
        for (child in root.descendants) {
            add(child.typesFile())
            add(child.remoteTablesFile())
            add(child.remoteReducersFile())
            add(child.remoteProceduresFile())
            add(child.queryBuilderFile())
        }
        return files
    }
}

private const val SDK = "com.clockworklabs.spacetimedb_kotlin_sdk.shared_client"

private val KOTLIN_HARD_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
    "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
    "val", "var", "when", "while",
)

/** Backtick-escapes Kotlin hard keywords. */
internal fun kotlinIdent(name: String): String = if (name in KOTLIN_HARD_KEYWORDS) "`$name`" else name

private fun camel(s: String) = CaseConversion.camel(s)
private fun pascal(s: String) = CaseConversion.pascal(s)
private fun screamingSnake(s: String) = CaseConversion.words(s).joinToString("_") { it.uppercase() }
private fun camelIdent(s: String) = kotlinIdent(camel(s))

/** Rust orders strings by their UTF-8 bytes. */
private val byteOrder = Comparator<String> { a, b ->
    val x = a.encodeToByteArray()
    val y = b.encodeToByteArray()
    for (i in 0 until minOf(x.size, y.size)) {
        val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
        if (c != 0) return@Comparator c
    }
    x.size - y.size
}

/** Lexicographic order of segment lists, like Rust slices. */
private val segmentOrder = Comparator<List<String>> { a, b ->
    for (i in 0 until minOf(a.size, b.size)) {
        val c = byteOrder.compare(a[i], b[i])
        if (c != 0) return@Comparator c
    }
    a.size - b.size
}

private val TypeUse.isByteArray get() = this is TypeUse.Array && element == TypeUse.Primitive(PrimitiveKind.U8)

/** Option, Array and Result need several lines to decode. */
private val TypeUse.isSimpleDecode get() = this !is TypeUse.Option && this !is TypeUse.Array && this !is TypeUse.Result

/** A table or view as the module-wide listings see it. */
private data class Listed(val name: String, val accessorName: String, val productTypeRef: Int)

/** One module (root or submodule) and everything the emitter derives from it. */
private class Scope(val module: ModuleModel) {
    val isRoot = module.accessorPath.isEmpty()
    val pkg = (listOf("module_bindings") + module.accessorPath.map(::kotlinIdent)).joinToString(".")
    val children: List<Scope> = module.submodules.map { Scope(it.second) }

    /** Submodules at every depth, parents before their children. */
    val descendants: List<Scope> get() = children.flatMap { listOf(it) + it.descendants }

    /** The accessor a parent uses for this module. */
    val mountName get() = kotlinIdent(module.accessorPath.last())

    /** `.a.b`: the member chain from the root to this module. */
    val accessorChain = module.accessorPath.joinToString("") { ".${kotlinIdent(it)}" }

    val publicTables = module.tables.filter { it.isPublic }.sortedWith(compareBy(byteOrder) { it.name })
    val reducers = module.reducers.filter { it.lifecycle != Lifecycle.Init && !it.isPrivate }
    val procedures = module.procedures.sortedWith(compareBy(byteOrder) { it.name }).filter { !it.isPrivate }

    /** Public tables and all views, by accessor name. */
    val listed: List<Listed> = (
        module.tables.filter { it.isPublic }.map { Listed(it.name, it.accessorName, it.productTypeRef) } +
            module.views.map { Listed(it.name, it.accessorName, it.asTable.productTypeRef) }
        ).sortedWith(compareBy(byteOrder) { it.accessorName })

    private val typeNames: Map<Int, String> =
        module.types.associate { t -> t.ref to t.nameSegments.joinToString("") { pascal(it) } }

    fun typeName(ref: Int): String = typeNames[ref] ?: error("No named type for ref $ref")

    fun wireName(name: String) = module.pathPrefix + name

    fun isParameterizedView(name: String) = module.views.any { it.name == name && it.params.isNotEmpty() }

    fun isEventTable(name: String) = module.tables.any { it.name == name && it.isEvent }

    fun scoped(file: Pair<String, String>): Pair<String, String> =
        if (isRoot) file else "${module.accessorPath.joinToString("/")}/${file.first}" to file.second

    private fun scopedName(filename: String) = scoped(filename to "").first

    // --- Types ---

    fun kotlinType(ty: TypeUse): String = when (ty) {
        TypeUse.Unit -> "Unit"
        TypeUse.Never -> "Nothing"
        TypeUse.Identity -> "Identity"
        TypeUse.ConnectionId -> "ConnectionId"
        TypeUse.Timestamp -> "Timestamp"
        TypeUse.TimeDuration -> "TimeDuration"
        TypeUse.ScheduleAt -> "ScheduleAt"
        TypeUse.Uuid -> "SpacetimeUuid"
        is TypeUse.Option -> "${kotlinType(ty.inner)}?"
        is TypeUse.Result -> "SpacetimeResult<${kotlinType(ty.ok)}, ${kotlinType(ty.err)}>"
        is TypeUse.Primitive -> when (ty.kind) {
            PrimitiveKind.Bool -> "Boolean"
            PrimitiveKind.I8 -> "Byte"
            PrimitiveKind.U8 -> "UByte"
            PrimitiveKind.I16 -> "Short"
            PrimitiveKind.U16 -> "UShort"
            PrimitiveKind.I32 -> "Int"
            PrimitiveKind.U32 -> "UInt"
            PrimitiveKind.I64 -> "Long"
            PrimitiveKind.U64 -> "ULong"
            PrimitiveKind.I128 -> "Int128"
            PrimitiveKind.U128 -> "UInt128"
            PrimitiveKind.I256 -> "Int256"
            PrimitiveKind.U256 -> "UInt256"
            PrimitiveKind.F32 -> "Float"
            PrimitiveKind.F64 -> "Double"
        }
        TypeUse.Str -> "String"
        is TypeUse.Array -> if (ty.isByteArray) "ByteArray" else "List<${kotlinType(ty.element)}>"
        is TypeUse.Ref -> typeName(ty.index)
    }

    private fun importFor(ty: TypeUse): String? = when (ty) {
        TypeUse.Identity -> "$SDK.type.Identity"
        TypeUse.ConnectionId -> "$SDK.type.ConnectionId"
        TypeUse.Timestamp -> "$SDK.type.Timestamp"
        TypeUse.TimeDuration -> "$SDK.type.TimeDuration"
        TypeUse.ScheduleAt -> "$SDK.type.ScheduleAt"
        TypeUse.Uuid -> "$SDK.type.SpacetimeUuid"
        is TypeUse.Result -> "$SDK.SpacetimeResult"
        is TypeUse.Primitive -> when (ty.kind) {
            PrimitiveKind.I128 -> "$SDK.Int128"
            PrimitiveKind.U128 -> "$SDK.UInt128"
            PrimitiveKind.I256 -> "$SDK.Int256"
            PrimitiveKind.U256 -> "$SDK.UInt256"
            else -> null
        }
        else -> null
    }

    fun collectImports(ty: TypeUse, into: MutableSet<String>) {
        importFor(ty)?.let(into::add)
        when (ty) {
            is TypeUse.Result -> {
                collectImports(ty.ok, into)
                collectImports(ty.err, into)
            }
            is TypeUse.Option -> collectImports(ty.inner, into)
            is TypeUse.Array -> collectImports(ty.element, into)
            else -> {}
        }
    }

    fun CodeWriter.printImports(types: Iterable<TypeUse>) {
        val imports = sortedSetOf(byteOrder)
        types.forEach { collectImports(it, imports) }
        imports.forEach { line("import $it") }
    }

    fun CodeWriter.header() {
        line("// THIS FILE IS AUTOMATICALLY GENERATED BY SPACETIMEDB. EDITS TO THIS FILE")
        line("// WILL NOT BE SAVED. MODIFY TABLES IN YOUR MODULE SOURCE CODE INSTEAD.")
        line()
        line("@file:Suppress(\"UNUSED\", \"SpellCheckingInspection\")")
        line()
        line("package $pkg")
    }

    // --- BSATN ---

    fun CodeWriter.encodeField(name: String, ty: TypeUse) {
        when (ty) {
            is TypeUse.Primitive -> when (ty.kind) {
                PrimitiveKind.I128, PrimitiveKind.U128, PrimitiveKind.I256, PrimitiveKind.U256 -> line("$name.encode(writer)")
                else -> line("writer.write${primitiveSuffix(ty.kind)}($name)")
            }
            TypeUse.Str -> line("writer.writeString($name)")
            TypeUse.Identity, TypeUse.ConnectionId, TypeUse.Timestamp, TypeUse.TimeDuration, TypeUse.Uuid,
            TypeUse.ScheduleAt, is TypeUse.Ref,
            -> line("$name.encode(writer)")
            is TypeUse.Option -> {
                line("if ($name != null) {")
                indent()
                line("writer.writeSumTag(0u)")
                encodeField(name, ty.inner)
                dedent()
                line("} else {")
                indent()
                line("writer.writeSumTag(1u)")
                dedent()
                line("}")
            }
            is TypeUse.Array -> if (ty.isByteArray) {
                line("writer.writeByteArray($name)")
            } else {
                line("writer.writeArrayLen($name.size)")
                block("for (elem in $name) {") { encodeField("elem", ty.element) }
            }
            is TypeUse.Result -> block("when ($name) {") {
                block("is SpacetimeResult.Ok -> {") {
                    line("writer.writeSumTag(0u)")
                    encodeField("$name.value", ty.ok)
                }
                block("is SpacetimeResult.Err -> {") {
                    line("writer.writeSumTag(1u)")
                    encodeField("$name.error", ty.err)
                }
            }
            TypeUse.Unit -> {}
            TypeUse.Never -> line("// Never type — unreachable")
        }
    }

    private fun primitiveSuffix(kind: PrimitiveKind) = when (kind) {
        PrimitiveKind.Bool -> "Bool"
        PrimitiveKind.F32 -> "F32"
        PrimitiveKind.F64 -> "F64"
        else -> kind.name
    }

    fun decodeExpr(ty: TypeUse): String = when (ty) {
        is TypeUse.Primitive -> when (ty.kind) {
            PrimitiveKind.I128 -> "Int128.decode(reader)"
            PrimitiveKind.U128 -> "UInt128.decode(reader)"
            PrimitiveKind.I256 -> "Int256.decode(reader)"
            PrimitiveKind.U256 -> "UInt256.decode(reader)"
            else -> "reader.read${primitiveSuffix(ty.kind)}()"
        }
        TypeUse.Str -> "reader.readString()"
        TypeUse.Identity -> "Identity.decode(reader)"
        TypeUse.ConnectionId -> "ConnectionId.decode(reader)"
        TypeUse.Timestamp -> "Timestamp.decode(reader)"
        TypeUse.TimeDuration -> "TimeDuration.decode(reader)"
        TypeUse.ScheduleAt -> "ScheduleAt.decode(reader)"
        TypeUse.Uuid -> "SpacetimeUuid.decode(reader)"
        is TypeUse.Ref -> "${typeName(ty.index)}.decode(reader)"
        TypeUse.Unit -> "Unit"
        TypeUse.Never -> "error(\"Never type\")"
        is TypeUse.Option, is TypeUse.Array, is TypeUse.Result -> ""
    }

    fun CodeWriter.decodeField(name: String, ty: TypeUse) {
        when (ty) {
            is TypeUse.Option -> if (ty.inner.isSimpleDecode) {
                line("val $name = if (reader.readSumTag().toInt() == 0) ${decodeExpr(ty.inner)} else null")
            } else {
                block("val $name = if (reader.readSumTag().toInt() == 0) {", "} else null") {
                    decodeField("__inner", ty.inner)
                    line("__inner")
                }
            }
            is TypeUse.Array -> when {
                ty.isByteArray -> line("val $name = reader.readByteArray()")
                ty.element.isSimpleDecode -> line("val $name = List(reader.readArrayLen()) { ${decodeExpr(ty.element)} }")
                else -> {
                    line("val __${name}Len = reader.readArrayLen()")
                    block("val $name = buildList(__${name}Len) {") {
                        block("repeat(__${name}Len) {") {
                            decodeField("__elem", ty.element)
                            line("add(__elem)")
                        }
                    }
                }
            }
            is TypeUse.Result -> block("val $name = when (reader.readSumTag().toInt()) {") {
                decodeBranch("0", "Ok", "__ok", ty.ok)
                decodeBranch("1", "Err", "__err", ty.err)
                line("else -> error(\"Unknown Result tag\")")
            }
            else -> line("val $name = ${decodeExpr(ty)}")
        }
    }

    private fun CodeWriter.decodeBranch(tag: String, case: String, temp: String, ty: TypeUse) {
        if (ty.isSimpleDecode) {
            line("$tag -> SpacetimeResult.$case(${decodeExpr(ty)})")
        } else {
            block("$tag -> {") {
                decodeField(temp, ty)
                line("SpacetimeResult.$case($temp)")
            }
        }
    }

    /** `data class {name}Args(...)` with encode and decode, for reducer and procedure arguments. */
    private fun CodeWriter.argsClass(doc: String, name: String, params: List<Field>) {
        line(doc)
        line("data class ${name}Args(")
        indent()
        params.forEachIndexed { i, p ->
            line("val ${camelIdent(p.name)}: ${kotlinType(p.type)}${if (i + 1 < params.size) "," else ""}")
        }
        dedent()
        line(") {")
        indent()
        line("/** Encodes these arguments to BSATN. */")
        block("fun encode(): ByteArray {") {
            line("val writer = BsatnWriter()")
            params.forEach { encodeField(camelIdent(it.name), it.type) }
            line("return writer.toByteArray()")
        }
        line()
        block("companion object {") {
            line("/** Decodes [${name}Args] from BSATN. */")
            block("fun decode(reader: BsatnReader): ${name}Args {") {
                params.forEach { decodeField(camelIdent(it.name), it.type) }
                line("return ${name}Args(${params.joinToString(", ") { camelIdent(it.name) }})")
            }
        }
        dedent()
        line("}")
    }

    // --- Item files ---

    fun tableFile(table: TableModel): Pair<String, String> {
        val out = CodeWriter()
        out.header()
        out.line()

        val elements = (module.typeDefs[table.productTypeRef] as TypeDefModel.Product).elements
        val typeName = typeName(table.productTypeRef)
        val tablePascal = pascal(table.accessorName)
        val isEvent = table.isEvent
        val indexes = table.indexes.sortedWith(compareBy(byteOrder) { it.name })
        val named = indexes.filter { it.accessorName != null }
        val hasUnique = !isEvent && named.any { it.isUnique }
        val hasBTree = !isEvent && named.any { !it.isUnique }
        val ixCols = if (isEvent) emptySet() else indexes.filter { it.algorithm == IndexKind.BTree }.flatMap { it.columns }.toSortedSet()
        val hasPk = table.primaryKey != null
        val marker = when {
            isEvent -> "RemoteEventTable"
            hasPk -> "RemotePersistentTableWithPrimaryKey"
            else -> "RemotePersistentTable"
        }

        with(out) {
            if (hasBTree) line("import $SDK.BTreeIndex")
            line("import $SDK.Col")
            line("import $SDK.DbConnection")
            line("import $SDK.EventContext")
            line("import $SDK.InternalSpacetimeApi")
            if (ixCols.isNotEmpty()) line("import $SDK.IxCol")
            line("import $SDK.$marker")
            line("import $SDK.TableCache")
            if (hasUnique) line("import $SDK.UniqueIndex")
            line("import $SDK.protocol.QueryResult")
            printImports(elements.map { it.type })
            line()

            val wire = wireName(table.name)
            line("/** Client-side handle for the `$wire` table. */")
            line("@OptIn(InternalSpacetimeApi::class)")
            line("class ${tablePascal}TableHandle internal constructor(")
            indent()
            line("private val conn: DbConnection,")
            line("private val tableCache: TableCache<$typeName, *>,")
            dedent()
            line(") : $marker<$typeName> {")
            indent()

            block("companion object {") {
                line("const val TABLE_NAME = \"$wire\"")
                line()
                elements.forEach { line("const val FIELD_${screamingSnake(it.name)} = \"${it.name}\"") }
                line()
                block("fun createTableCache(): TableCache<$typeName, *> {") {
                    if (table.primaryKey != null) {
                        val pk = camelIdent(table.columns[table.primaryKey].accessorName)
                        line("return TableCache.withPrimaryKey({ reader -> $typeName.decode(reader) }) { row -> row.$pk }")
                    } else {
                        line("return TableCache.withContentKey { reader -> $typeName.decode(reader) }")
                    }
                }
            }
            line()

            if (!isEvent) {
                line("override fun count(): Int = tableCache.count()")
                line("override fun all(): List<$typeName> = tableCache.all()")
                line("override fun iter(): Sequence<$typeName> = tableCache.iter()")
                line()
            }

            val cb = "(EventContext, $typeName) -> Unit"
            val updateCb = "(EventContext, $typeName, $typeName) -> Unit"
            line("override fun onInsert(cb: $cb) { tableCache.onInsert(cb) }")
            line("override fun removeOnInsert(cb: $cb) { tableCache.removeOnInsert(cb) }")
            if (!isEvent) {
                line("override fun onDelete(cb: $cb) { tableCache.onDelete(cb) }")
                if (hasPk) line("override fun onUpdate(cb: $updateCb) { tableCache.onUpdate(cb) }")
                line("override fun onBeforeDelete(cb: $cb) { tableCache.onBeforeDelete(cb) }")
                line()
                line("override fun removeOnDelete(cb: $cb) { tableCache.removeOnDelete(cb) }")
                if (hasPk) line("override fun removeOnUpdate(cb: $updateCb) { tableCache.removeOnUpdate(cb) }")
                line("override fun removeOnBeforeDelete(cb: $cb) { tableCache.removeOnBeforeDelete(cb) }")
            }
            line()

            fun column(col: Int) = elements[col].let { camelIdent(it.name) to kotlinType(it.type) }
            for (index in named) {
                val name = camelIdent(index.accessorName!!)
                val cls = if (index.isUnique) "UniqueIndex" else "BTreeIndex"
                val cols = index.columns.map(::column)
                val (keyType, keyExpr) = when (cols.size) {
                    1 -> cols[0].second to "it.${cols[0].first}"
                    2 -> "Pair<${cols[0].second}, ${cols[1].second}>" to "Pair(it.${cols[0].first}, it.${cols[1].first})"
                    3 -> "Triple<${cols.joinToString(", ") { it.second }}>" to
                        "Triple(${cols.joinToString(", ") { "it.${it.first}" }})"
                    else -> "List<Any?>" to "listOf(${cols.joinToString(", ") { "it.${it.first}" }})"
                }
                line("val $name = $cls<$typeName, $keyType>(tableCache) { $keyExpr }")
                line()
            }

            dedent()
            line("}")
            line()

            fun valueType(ty: TypeUse) = kotlinType(if (ty is TypeUse.Option) ty.inner else ty)
            line("@OptIn(InternalSpacetimeApi::class)")
            block("class ${tablePascal}Cols(tableName: String) {") {
                elements.forEach {
                    line("val ${camelIdent(it.name)} = Col<$typeName, ${valueType(it.type)}>(tableName, \"${it.name}\")")
                }
            }
            line()

            if (ixCols.isNotEmpty()) {
                line("@OptIn(InternalSpacetimeApi::class)")
                block("class ${tablePascal}IxCols(tableName: String) {") {
                    elements.forEachIndexed { i, it ->
                        if (i in ixCols) {
                            line("val ${camelIdent(it.name)} = IxCol<$typeName, ${valueType(it.type)}>(tableName, \"${it.name}\")")
                        }
                    }
                }
            } else {
                line("class ${tablePascal}IxCols")
            }
        }
        return "${tablePascal}TableHandle.kt" to out.toString()
    }

    fun reducerFile(reducer: ReducerModel): Pair<String, String> {
        val out = CodeWriter()
        val name = pascal(reducer.accessorName)
        with(out) {
            header()
            line()
            line("import $SDK.bsatn.BsatnReader")
            line("import $SDK.bsatn.BsatnWriter")
            printImports(reducer.params.map { it.type })
            line()
            if (reducer.params.isNotEmpty()) {
                argsClass("/** Arguments for the `${reducer.name}` reducer. */", name, reducer.params)
                line()
            }
            line("/** Constants for the `${reducer.name}` reducer. */")
            block("object ${name}Reducer {") { line("const val REDUCER_NAME = \"${reducer.name}\"") }
        }
        return "${name}Reducer.kt" to out.toString()
    }

    fun procedureFile(procedure: ProcedureModel): Pair<String, String> {
        val out = CodeWriter()
        val name = pascal(procedure.accessorName)
        val wire = wireName(procedure.name)
        with(out) {
            header()
            line()
            line("import $SDK.bsatn.BsatnReader")
            line("import $SDK.bsatn.BsatnWriter")
            printImports(procedure.params.map { it.type } + procedure.returnType)
            if (procedure.params.isNotEmpty()) {
                argsClass("/** Arguments for the `$wire` procedure. */", name, procedure.params)
                line()
            }
            block("object ${name}Procedure {") {
                line("const val PROCEDURE_NAME = \"$wire\"")
                line("// Returns: ${kotlinType(procedure.returnType)}")
            }
        }
        return "${name}Procedure.kt" to out.toString()
    }

    // --- Types.kt ---

    fun typesFile(): Pair<String, String> {
        val out = CodeWriter()
        out.header()
        out.line()

        val types = module.types.sortedWith { a, b ->
            val c = segmentOrder.compare(a.nameSegments.dropLast(1), b.nameSegments.dropLast(1))
            if (c != 0) c else byteOrder.compare(a.nameSegments.last(), b.nameSegments.last())
        }
        val imports = sortedSetOf(byteOrder, "$SDK.bsatn.BsatnReader", "$SDK.bsatn.BsatnWriter")
        for (t in types) {
            when (val def = module.typeDefs.getValue(t.ref)) {
                is TypeDefModel.Product -> def.elements.forEach { collectImports(it.type, imports) }
                is TypeDefModel.Sum -> def.variants.forEach { collectImports(it.type, imports) }
                is TypeDefModel.PlainEnum -> {}
            }
        }
        imports.forEach { out.line("import $it") }
        out.line()

        val reducerTypeNames = module.reducers.map { pascal(it.accessorName) }.toSet()
        for (t in types) {
            val name = t.nameSegments.joinToString("") { pascal(it) }
            if (name in reducerTypeNames) continue
            when (val def = module.typeDefs.getValue(t.ref)) {
                is TypeDefModel.Product -> out.productType(name, def.elements)
                is TypeDefModel.Sum -> out.sumType(name, def.variants)
                is TypeDefModel.PlainEnum -> out.plainEnum(name, def.variants)
            }
        }
        return scopedName("Types.kt") to out.toString()
    }

    private fun CodeWriter.productType(name: String, elements: List<Field>) {
        line("/** Data type `$name` from the module schema. */")
        if (elements.isEmpty()) {
            block("data object $name {") {
                line("/** Encodes this value to BSATN. */")
                line("fun encode(writer: BsatnWriter) { }")
                line()
                line("/** Decodes a [$name] from BSATN. */")
                line("fun decode(reader: BsatnReader): $name = $name")
            }
            line()
            return
        }
        line("data class $name(")
        indent()
        elements.forEachIndexed { i, e ->
            line("val ${camelIdent(e.name)}: ${kotlinType(e.type)}${if (i + 1 < elements.size) "," else ""}")
        }
        dedent()
        line(") {")
        indent()
        line("/** Encodes this value to BSATN. */")
        block("fun encode(writer: BsatnWriter) {") { elements.forEach { encodeField(camelIdent(it.name), it.type) } }
        line()
        block("companion object {") {
            line("/** Decodes a [$name] from BSATN. */")
            block("fun decode(reader: BsatnReader): $name {") {
                elements.forEach { decodeField(camelIdent(it.name), it.type) }
                line("return $name(${elements.joinToString(", ") { camelIdent(it.name) }})")
            }
        }
        if (elements.any { it.type.isByteArray }) {
            line()
            block("override fun equals(other: Any?): Boolean {") {
                line("if (this === other) return true")
                line("if (other !is $name) return false")
                for (e in elements) {
                    val f = camelIdent(e.name)
                    if (e.type.isByteArray) {
                        line("if (!$f.contentEquals(other.$f)) return false")
                    } else {
                        line("if ($f != other.$f) return false")
                    }
                }
                line("return true")
            }
            line()
            block("override fun hashCode(): Int {") {
                line("var result = 0")
                for (e in elements) {
                    val f = camelIdent(e.name)
                    line("result = 31 * result + $f.${if (e.type.isByteArray) "contentHashCode" else "hashCode"}()")
                }
                line("return result")
            }
        }
        dedent()
        line("}")
        line()
    }

    private fun CodeWriter.sumType(name: String, variants: List<Field>) {
        require(variants.size <= 256) { "Sum type `$name` has ${variants.size} variants, but BSATN sum tags are limited to 256" }
        val variantNames = variants.map { pascal(it.name) }
        // A payload type named like a variant would resolve to the variant inside the sealed interface.
        fun qualified(base: String) = if (base in variantNames) "$pkg.$base" else base

        line("/** Sum type `$name` from the module schema. */")
        block("sealed interface $name {") {
            variants.forEachIndexed { i, v ->
                if (v.type == TypeUse.Unit) {
                    line("data object ${variantNames[i]} : $name")
                } else {
                    line("data class ${variantNames[i]}(val value: ${qualified(kotlinType(v.type))}) : $name")
                }
            }
            line()
            block("fun encode(writer: BsatnWriter) {") {
                block("when (this) {") {
                    variants.forEachIndexed { i, v ->
                        if (v.type == TypeUse.Unit) {
                            line("is ${variantNames[i]} -> writer.writeSumTag(${i}u)")
                        } else {
                            block("is ${variantNames[i]} -> {") {
                                line("writer.writeSumTag(${i}u)")
                                encodeField("value", v.type)
                            }
                        }
                    }
                }
            }
            line()
            block("companion object {") {
                block("fun decode(reader: BsatnReader): $name {") {
                    block("return when (val tag = reader.readSumTag().toInt()) {") {
                        variants.forEachIndexed { i, v ->
                            val variant = variantNames[i]
                            when {
                                v.type == TypeUse.Unit -> line("$i -> $variant")
                                v.type.isSimpleDecode -> {
                                    val ty = v.type
                                    val expr = if (ty is TypeUse.Ref && typeName(ty.index) in variantNames) {
                                        "$pkg.${typeName(ty.index)}.decode(reader)"
                                    } else {
                                        decodeExpr(ty)
                                    }
                                    line("$i -> $variant($expr)")
                                }
                                else -> block("$i -> {") {
                                    decodeField("__value", v.type)
                                    line("$variant(__value)")
                                }
                            }
                        }
                        line("else -> error(\"Unknown $name tag: \$tag\")")
                    }
                }
            }
        }
        line()
    }

    private fun CodeWriter.plainEnum(name: String, variants: List<String>) {
        require(variants.size <= 256) { "Enum `$name` has ${variants.size} variants, but BSATN sum tags are limited to 256" }
        line("/** Enum type `$name` from the module schema. */")
        block("enum class $name {") {
            variants.forEachIndexed { i, v -> line("${pascal(v)}${if (i + 1 < variants.size) "," else ";"}") }
            line()
            block("fun encode(writer: BsatnWriter) {") { line("writer.writeSumTag(ordinal.toUByte())") }
            line()
            block("companion object {") {
                block("fun decode(reader: BsatnReader): $name {") {
                    line("val tag = reader.readSumTag().toInt()")
                    line("return entries.getOrElse(tag) { error(\"Unknown $name tag: \$tag\") }")
                }
            }
        }
        line()
    }

    // --- RemoteTables.kt ---

    fun remoteTablesFile(): Pair<String, String> {
        val out = CodeWriter()
        with(out) {
            header()
            line()
            line("import $SDK.ClientCache")
            line("import $SDK.DbConnection")
            line("import $SDK.InternalSpacetimeApi")
            if (isRoot) line("import $SDK.ModuleTables")
            line()
            line("/** Generated table accessors for all tables in this module. */")
            line("@OptIn(InternalSpacetimeApi::class)")
            line("class RemoteTables internal constructor(")
            indent()
            line("private val conn: DbConnection,")
            line("private val clientCache: ClientCache,")
            dedent()
            block(")${if (isRoot) " : ModuleTables" else ""} {") {
                for (child in children) {
                    line("val ${child.mountName}: ${child.pkg}.RemoteTables by lazy { ${child.pkg}.RemoteTables(conn, clientCache) }")
                    line()
                }
                for (t in listed) {
                    val pascalName = pascal(t.accessorName)
                    block("val ${camelIdent(t.accessorName)}: ${pascalName}TableHandle by lazy {") {
                        line("@Suppress(\"UNCHECKED_CAST\")")
                        block("val cache = clientCache.getOrCreateTable<${typeName(t.productTypeRef)}>(${pascalName}TableHandle.TABLE_NAME) {") {
                            line("${pascalName}TableHandle.createTableCache()")
                        }
                        line("${pascalName}TableHandle(conn, cache)")
                    }
                    line()
                }
            }
        }
        return scopedName("RemoteTables.kt") to out.toString()
    }

    // --- RemoteReducers.kt ---

    fun remoteReducersFile(): Pair<String, String> {
        val out = CodeWriter()
        out.header()
        out.line()

        val imports = sortedSetOf(
            byteOrder,
            "$SDK.CallbackList",
            "$SDK.DbConnection",
            "$SDK.EventContext",
            "$SDK.InternalSpacetimeApi",
            "$SDK.Status",
        )
        if (isRoot) imports += "$SDK.ModuleReducers"
        reducers.forEach { r -> r.params.forEach { collectImports(it.type, imports) } }

        with(out) {
            imports.forEach { line("import $it") }
            line()
            line("/** Generated reducer call methods and callback registration. */")
            line("@OptIn(InternalSpacetimeApi::class)")
            line("class RemoteReducers internal constructor(")
            indent()
            line("private val conn: DbConnection,")
            if (!isRoot) line("private val reportUnhandled: (EventContext.Reducer<*>) -> Unit,")
            dedent()
            line(")${if (isRoot) " : ModuleReducers" else ""} {")
            indent()

            val reportUnhandled = if (isRoot) "onUnhandledReducerErrorCallbacks.forEach { it(ctx) }" else "reportUnhandled(ctx)"
            for (child in children) {
                val report = if (isRoot) "{ ctx -> onUnhandledReducerErrorCallbacks.forEach { it(ctx) } }" else "reportUnhandled"
                line("val ${child.mountName}: ${child.pkg}.RemoteReducers = ${child.pkg}.RemoteReducers(conn, $report)")
                line()
            }
            val submodulesHandle = children.joinToString(" || ") { "${it.mountName}.handleReducerEvent(ctx)" }

            for (r in reducers) {
                if (r.lifecycle != null) continue
                val camelName = camelIdent(r.accessorName)
                val pascalName = pascal(r.accessorName)
                if (r.params.isEmpty()) {
                    block("fun $camelName(callback: ((EventContext.Reducer<Unit>) -> Unit)? = null) {") {
                        line("conn.callReducer(${pascalName}Reducer.REDUCER_NAME, ByteArray(0), Unit, callback)")
                    }
                } else {
                    val params = r.params.joinToString(", ") { "${camelIdent(it.name)}: ${kotlinType(it.type)}" }
                    block("fun $camelName($params, callback: ((EventContext.Reducer<${pascalName}Args>) -> Unit)? = null) {") {
                        line("val args = ${pascalName}Args(${r.params.joinToString(", ") { camelIdent(it.name) }})")
                        line("conn.callReducer(${pascalName}Reducer.REDUCER_NAME, args.encode(), args, callback)")
                    }
                }
                line()
            }

            for (r in reducers) {
                val pascalName = pascal(r.accessorName)
                val argsType = if (r.params.isEmpty()) "Unit" else "${pascalName}Args"
                val cbType = "(${(listOf("EventContext.Reducer<$argsType>") + r.params.map { kotlinType(it.type) }).joinToString(", ")}) -> Unit"
                line("private val on${pascalName}Callbacks = CallbackList<$cbType>()")
                line()
                block("fun on$pascalName(cb: $cbType) {") { line("on${pascalName}Callbacks.add(cb)") }
                line()
                block("fun removeOn$pascalName(cb: $cbType) {") { line("on${pascalName}Callbacks.remove(cb)") }
                line()
            }

            if (isRoot) {
                line("private val onUnhandledReducerErrorCallbacks = CallbackList<(EventContext.Reducer<*>) -> Unit>()")
                line()
                line("/** Register a callback for reducer errors with no specific handler. */")
                block("fun onUnhandledReducerError(cb: (EventContext.Reducer<*>) -> Unit) {") {
                    line("onUnhandledReducerErrorCallbacks.add(cb)")
                }
                line()
                block("fun removeOnUnhandledReducerError(cb: (EventContext.Reducer<*>) -> Unit) {") {
                    line("onUnhandledReducerErrorCallbacks.remove(cb)")
                }
                line()
            }

            val owned = submodulesHandle.ifEmpty { "false" }
            if (isRoot) {
                line("internal fun handleReducerEvent(ctx: EventContext.Reducer<*>) {")
            } else {
                line("internal fun handleReducerEvent(ctx: EventContext.Reducer<*>): Boolean {")
            }
            indent()
            if (!isRoot && reducers.isEmpty()) {
                line("return $owned")
            } else {
                block("when (ctx.reducerName) {") {
                    for (r in reducers) {
                        val pascalName = pascal(r.accessorName)
                        block("${pascalName}Reducer.REDUCER_NAME -> {") {
                            line("if (on${pascalName}Callbacks.isNotEmpty()) {")
                            indent()
                            line("@Suppress(\"UNCHECKED_CAST\")")
                            if (r.params.isEmpty()) {
                                line("val typedCtx = ctx as EventContext.Reducer<Unit>")
                                line("on${pascalName}Callbacks.forEach { it(typedCtx) }")
                            } else {
                                line("val typedCtx = ctx as EventContext.Reducer<${pascalName}Args>")
                                val args = (listOf("typedCtx") + r.params.map { "typedCtx.args.${camelIdent(it.name)}" }).joinToString(", ")
                                line("on${pascalName}Callbacks.forEach { it($args) }")
                            }
                            dedent()
                            line("} else if (ctx.status is Status.Failed) {")
                            indent()
                            line(reportUnhandled)
                            dedent()
                            line("}")
                        }
                    }
                    if (isRoot) {
                        block("else -> {") {
                            if (submodulesHandle.isEmpty()) {
                                line("if (ctx.status is Status.Failed) {")
                            } else {
                                line("if (!($submodulesHandle) && ctx.status is Status.Failed) {")
                            }
                            indent()
                            line(reportUnhandled)
                            dedent()
                            line("}")
                        }
                    } else {
                        line("else -> return $owned")
                    }
                }
                if (!isRoot) line("return true")
            }
            dedent()
            line("}")
            dedent()
            line("}")
        }
        return scopedName("RemoteReducers.kt") to out.toString()
    }

    // --- RemoteProcedures.kt ---

    fun remoteProceduresFile(): Pair<String, String> {
        val out = CodeWriter()
        out.header()
        out.line()

        val imports = sortedSetOf(byteOrder, "$SDK.DbConnection", "$SDK.InternalSpacetimeApi")
        if (isRoot) imports += "$SDK.ModuleProcedures"
        if (procedures.isNotEmpty()) {
            imports += listOf(
                "$SDK.EventContext",
                "$SDK.ProcedureError",
                "$SDK.SdkResult",
                "$SDK.bsatn.BsatnWriter",
                "$SDK.bsatn.BsatnReader",
                "$SDK.protocol.ServerMessage",
                "$SDK.protocol.ProcedureStatus",
            )
        }
        for (p in procedures) {
            p.params.forEach { collectImports(it.type, imports) }
            collectImports(p.returnType, imports)
        }

        with(out) {
            imports.forEach { line("import $it") }
            line()
            line("/** Generated procedure call methods and callback registration. */")
            line("@OptIn(InternalSpacetimeApi::class)")
            line("class RemoteProcedures internal constructor(")
            indent()
            line("private val conn: DbConnection,")
            dedent()
            block(")${if (isRoot) " : ModuleProcedures" else ""} {") {
                for (child in children) {
                    line("val ${child.mountName}: ${child.pkg}.RemoteProcedures = ${child.pkg}.RemoteProcedures(conn)")
                    line()
                }
                for (p in procedures) {
                    val pascalName = pascal(p.accessorName)
                    val returnType = p.returnType
                    val isUnit = returnType == TypeUse.Unit
                    val resultType = if (isUnit) "Unit" else kotlinType(returnType)
                    val callbackType = "((EventContext.Procedure, SdkResult<$resultType, ProcedureError>) -> Unit)?"
                    val params = p.params.joinToString(", ") { "${camelIdent(it.name)}: ${kotlinType(it.type)}" }
                    val signature = if (params.isEmpty()) "callback: $callbackType = null" else "$params, callback: $callbackType = null"
                    block("fun ${camelIdent(p.accessorName)}($signature) {") {
                        val argsExpr = if (p.params.isEmpty()) {
                            "ByteArray(0)"
                        } else {
                            line("val args = ${pascalName}Args(${p.params.joinToString(", ") { camelIdent(it.name) }})")
                            "args.encode()"
                        }
                        block("val wrappedCallback = callback?.let { userCb ->") {
                            block("{ ctx: EventContext.Procedure, msg: ServerMessage.ProcedureResultMsg ->") {
                                block("when (val status = msg.status) {") {
                                    block("is ProcedureStatus.Returned -> {") {
                                        when {
                                            isUnit -> line("userCb(ctx, SdkResult.Success(Unit))")
                                            returnType.isSimpleDecode -> {
                                                line("val reader = BsatnReader(status.value)")
                                                line("userCb(ctx, SdkResult.Success(${decodeExpr(returnType)}))")
                                            }
                                            else -> {
                                                line("val reader = BsatnReader(status.value)")
                                                decodeField("__retVal", returnType)
                                                line("userCb(ctx, SdkResult.Success(__retVal))")
                                            }
                                        }
                                    }
                                    block("is ProcedureStatus.InternalError -> {") {
                                        line("userCb(ctx, SdkResult.Failure(ProcedureError.InternalError(status.message)))")
                                    }
                                }
                            }
                        }
                        line("conn.callProcedure(${pascalName}Procedure.PROCEDURE_NAME, $argsExpr, wrappedCallback)")
                    }
                    line()
                }
            }
        }
        return scopedName("RemoteProcedures.kt") to out.toString()
    }

    // --- Module.kt and the query builder ---

    /** Views with arguments that all have SQL literals: the ones the query builder can call. */
    private val callableViews get() = module.views.filter { v ->
        v.params.isNotEmpty() && v.paramsForGenerate.all { sqlLiteral(it.type) != null }
    }

    private fun CodeWriter.viewQueryImports() {
        if (callableViews.isNotEmpty()) {
            line("import $SDK.SqlLit")
            line("import $SDK.ViewCall")
            printImports(callableViews.flatMap { v -> v.paramsForGenerate.map { it.type } })
        }
    }

    fun moduleFile(info: GeneratorInfo): Pair<String, String> {
        val out = CodeWriter()
        val scopes = listOf(this) + descendants
        with(out) {
            header()
            line(info.versionComment)
            line()
            line()
            for (name in listOf(
                "ClientCache", "DbConnection", "DbConnectionView", "EventContext", "InternalSpacetimeApi",
                "ModuleAccessors", "ModuleDescriptor", "Query", "SubscriptionBuilder", "Table",
            )) {
                line("import $SDK.$name")
            }
            viewQueryImports()
            line()

            line("/**")
            line(" * Module metadata generated by the SpacetimeDB CLI.")
            line(" * Contains version info and the names of all tables, reducers, and procedures.")
            line(" */")
            line("@OptIn(InternalSpacetimeApi::class)")
            block("object RemoteModule : ModuleDescriptor {") {
                line("override val cliVersion: String = \"${info.cliVersion}\"")
                line()
                block("val tableNames: List<String> = listOf(", ")") {
                    scopes.forEach { s -> s.listed.forEach { line("\"${s.wireName(it.name)}\",") } }
                }
                line()
                block("override val subscribableTableNames: List<String> = listOf(", ")") {
                    for (s in scopes) {
                        for (t in s.listed) {
                            if (!s.isEventTable(t.name) && !s.isParameterizedView(t.name)) line("\"${s.wireName(t.name)}\",")
                        }
                    }
                }
                line()
                block("val reducerNames: List<String> = listOf(", ")") {
                    scopes.forEach { s -> s.reducers.filter { it.lifecycle == null }.forEach { line("\"${it.name}\",") } }
                }
                line()
                block("val procedureNames: List<String> = listOf(", ")") {
                    scopes.forEach { s -> s.procedures.forEach { line("\"${s.wireName(it.name)}\",") } }
                }
                line()
                block("override fun registerTables(cache: ClientCache) {") {
                    for (s in scopes) {
                        val prefix = if (s.isRoot) "" else "${s.pkg}."
                        for (t in s.listed) {
                            val handle = "$prefix${pascal(t.accessorName)}TableHandle"
                            line("cache.register($handle.TABLE_NAME, $handle.createTableCache())")
                        }
                    }
                }
                line()
                block("override fun createAccessors(conn: DbConnection): ModuleAccessors {") {
                    block("return ModuleAccessors(", ")") {
                        line("tables = RemoteTables(conn, conn.clientCache),")
                        line("reducers = RemoteReducers(conn),")
                        line("procedures = RemoteProcedures(conn),")
                    }
                }
                line()
                block("override fun handleReducerEvent(conn: DbConnection, ctx: EventContext.Reducer<*>) {") {
                    line("conn.reducers.handleReducerEvent(ctx)")
                }
            }
            line()

            fun extension(doc: String, declaration: String, getter: String) {
                line("/**")
                line(" * $doc")
                line(" */")
                line(declaration)
                indent()
                line(getter)
                dedent()
                line()
            }
            for (receiver in listOf("DbConnection", "DbConnectionView")) {
                extension("Typed table accessors for this module's tables.", "val $receiver.db: RemoteTables", "get() = moduleTables as RemoteTables")
                extension(
                    "Typed reducer call functions for this module's reducers.",
                    "val $receiver.reducers: RemoteReducers",
                    "get() = moduleReducers as RemoteReducers",
                )
                extension(
                    "Typed procedure call functions for this module's procedures.",
                    "val $receiver.procedures: RemoteProcedures",
                    "get() = moduleProcedures as RemoteProcedures",
                )
            }
            extension("Typed table accessors available directly on event context.", "val EventContext.db: RemoteTables", "get() = connection.db")
            extension(
                "Typed reducer call functions available directly on event context.",
                "val EventContext.reducers: RemoteReducers",
                "get() = connection.reducers",
            )
            extension(
                "Typed procedure call functions available directly on event context.",
                "val EventContext.procedures: RemoteProcedures",
                "get() = connection.procedures",
            )

            for (doc in listOf(
                "/**",
                " * Registers this module's tables with the connection builder.",
                " * Call this on the builder to enable typed [db], [reducers], and [procedures] accessors.",
                " *",
                " * Example:",
                " * ```kotlin",
                " * val conn = DbConnection.Builder()",
                " *     .withUri(\"ws://localhost:3000\")",
                " *     .withDatabaseName(\"my_module\")",
                " *     .withModuleBindings()",
                " *     .build()",
                " * ```",
                " */",
            )) {
                line(doc)
            }
            line("@OptIn(InternalSpacetimeApi::class)")
            block("fun DbConnection.Builder.withModuleBindings(): DbConnection.Builder {") { line("return withModule(RemoteModule)") }
            line()

            queryBuilder()
            line()

            for (doc in listOf(
                "/**",
                " * Add a type-safe table query to this subscription.",
                " *",
                " * Example:",
                " * ```kotlin",
                " * conn.subscriptionBuilder()",
                " *     .addQuery { qb -> qb.player() }",
                " *     .addQuery { qb -> qb.player().where { c -> c.health.gt(50) } }",
                " *     .subscribe()",
                " * ```",
                " */",
            )) {
                line(doc)
            }
            block("fun SubscriptionBuilder.addQuery(build: (QueryBuilder) -> Query<*>): SubscriptionBuilder {") {
                line("return addQuery(build(QueryBuilder()).toSql())")
            }
            line()

            line("/**")
            line(" * Subscribe to all persistent tables in this module.")
            line(" * Event tables are excluded because the server does not support subscribing to them.")
            if (scopes.any { s -> s.module.views.any { it.params.isNotEmpty() } }) {
                line(" * Views requiring arguments must be subscribed to explicitly.")
            }
            line(" */")
            block("fun SubscriptionBuilder.subscribeToAllTables(): $SDK.SubscriptionHandle {") {
                line("val qb = QueryBuilder()")
                for (s in scopes) {
                    for (t in s.listed) {
                        if (!s.isEventTable(t.name) && !s.isParameterizedView(t.name)) {
                            line("addQuery(qb${s.accessorChain}.${camelIdent(t.accessorName)}().toSql())")
                        }
                    }
                }
                line("return subscribe()")
            }
        }
        return "Module.kt" to out.toString()
    }

    fun queryBuilderFile(): Pair<String, String> {
        val out = CodeWriter()
        with(out) {
            header()
            line()
            line("import $SDK.Table")
            viewQueryImports()
            line()
            queryBuilder()
        }
        return scopedName("QueryBuilder.kt") to out.toString()
    }

    private fun CodeWriter.queryBuilder() {
        line("/**")
        line(" * Type-safe query builder for this module's tables.")
        line(" * Supports WHERE predicates and semi-joins.")
        line(" */")
        if (module.views.any { it.params.isNotEmpty() }) line("@OptIn($SDK.InternalSpacetimeApi::class)")
        block("class QueryBuilder {") {
            children.forEach { line("val ${it.mountName} = ${it.pkg}.QueryBuilder()") }
            for (t in listed) {
                val wire = wireName(t.name)
                val typeName = typeName(t.productTypeRef)
                val pascalName = pascal(t.accessorName)
                val method = camelIdent(t.accessorName)
                val view = module.views.find { it.name == t.name && it.params.isNotEmpty() }
                if (view != null) {
                    // Views whose arguments have no SQL literal representation are raw-SQL only.
                    val params = view.paramsForGenerate
                    if (params.all { sqlLiteral(it.type) != null }) {
                        val signature = params.joinToString(", ") { "${camelIdent(it.name)}: ${kotlinType(it.type)}" }
                        val args = params.joinToString(", ") { "SqlLit.${sqlLiteral(it.type)}(${camelIdent(it.name)})" }
                        line("fun $method($signature): ViewCall<$typeName> = ViewCall(\"$wire\", listOf($args))")
                    }
                    continue
                }
                val hasIx = module.tables.find { it.name == t.name }?.indexes?.any { it.algorithm == IndexKind.BTree } == true
                val ixCols = if (hasIx) "${pascalName}IxCols(\"$wire\")" else "${pascalName}IxCols()"
                line(
                    "fun $method(): Table<$typeName, ${pascalName}Cols, ${pascalName}IxCols> = " +
                        "Table(\"$wire\", ${pascalName}Cols(\"$wire\"), $ixCols)",
                )
            }
        }
    }
}

/** The `SqlLit` constructor for a view argument type, or null if the server has no literal for it. */
private fun sqlLiteral(ty: TypeUse): String? = when (ty) {
    is TypeUse.Primitive -> when (ty.kind) {
        PrimitiveKind.Bool -> "bool"
        PrimitiveKind.I8 -> "byte"
        PrimitiveKind.U8 -> "ubyte"
        PrimitiveKind.I16 -> "short"
        PrimitiveKind.U16 -> "ushort"
        PrimitiveKind.I32 -> "int"
        PrimitiveKind.U32 -> "uint"
        PrimitiveKind.I64 -> "long"
        PrimitiveKind.U64 -> "ulong"
        PrimitiveKind.I128 -> "int128"
        PrimitiveKind.U128 -> "uint128"
        PrimitiveKind.I256 -> "int256"
        PrimitiveKind.U256 -> "uint256"
        PrimitiveKind.F32, PrimitiveKind.F64 -> null
    }
    TypeUse.Str -> "string"
    TypeUse.Identity -> "identity"
    TypeUse.ConnectionId -> "connectionId"
    TypeUse.Timestamp -> "timestamp"
    TypeUse.Uuid -> "uuid"
    else -> if (ty.isByteArray) "bytes" else null
}
