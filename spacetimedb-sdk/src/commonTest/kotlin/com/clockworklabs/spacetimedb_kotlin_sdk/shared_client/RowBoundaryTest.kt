package com.clockworklabs.spacetimedb_kotlin_sdk.shared_client

import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.bsatn.BsatnWriter
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.protocol.BsatnRowList
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.protocol.RowSizeHint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Since SpacetimeDB 2.16, row boundaries in a row list are part of the protocol: a module can append
 * columns, so a client built against an older schema sees rows longer than its decoder reads.
 */
class RowBoundaryTest {

    /** A [SampleRow] as a newer module sends it, with an extra trailing column. */
    private fun SampleRow.encodeWithExtraColumn(extra: Long = 99L): ByteArray {
        val writer = BsatnWriter()
        writer.writeRawBytes(encode())
        writer.writeI64(extra)
        return writer.toByteArray()
    }

    @Test
    fun `rows with an unknown trailing column decode from their own boundaries`() {
        val rows = listOf(SampleRow(1, "Alice"), SampleRow(2, "Bob"), SampleRow(3, "Carol"))
        val cache = createSampleCache()

        cache.applyInserts(STUB_CTX, buildRowList(*rows.map { it.encodeWithExtraColumn() }.toTypedArray()))

        assertEquals(rows.toSet(), cache.all().toSet())
    }

    @Test
    fun `fixed size rows with an unknown trailing column decode from their own boundaries`() {
        // Same-length names, so every encoded row has the same size.
        val rows = listOf(SampleRow(1, "Ann"), SampleRow(2, "Bob"))
        val encoded = rows.map { it.encodeWithExtraColumn() }
        val writer = BsatnWriter()
        encoded.forEach { writer.writeRawBytes(it) }
        val rowList = BsatnRowList(RowSizeHint.FixedSize(encoded.first().size.toUShort()), writer.toByteArray())
        val cache = createSampleCache()

        cache.applyInserts(STUB_CTX, rowList)

        assertEquals(rows.toSet(), cache.all().toSet())
    }

    @Test
    fun `content keyed rows with an unknown trailing column insert and delete`() {
        val row = SampleRow(7, "Dana")
        val cache = TableCache.withContentKey(::decodeSampleRow)

        cache.applyInserts(STUB_CTX, buildRowList(row.encodeWithExtraColumn(), SampleRow(8, "Eve").encodeWithExtraColumn()))
        val parsed = cache.parseDeletes(buildRowList(row.encodeWithExtraColumn()))
        cache.applyDeletes(STUB_CTX, parsed)

        assertEquals(listOf(SampleRow(8, "Eve")), cache.all())
    }

    @Test
    fun `row offsets past the end of the data are rejected`() {
        val rowList = BsatnRowList(RowSizeHint.RowOffsets(listOf(0uL, 100uL)), SampleRow(1, "Alice").encode())

        assertFailsWith<IllegalArgumentException> { createSampleCache().applyInserts(STUB_CTX, rowList) }
    }
}
