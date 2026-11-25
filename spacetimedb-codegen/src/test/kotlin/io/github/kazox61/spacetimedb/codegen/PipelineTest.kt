package io.github.kazox61.spacetimedb.codegen

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PipelineTest {
    private val tmp: File = Files.createTempDirectory("codegen-pipeline").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun file(path: String, content: String = "") = tmp.resolve(path).apply {
        parentFile.mkdirs()
        writeText(content)
    }

    @Test
    fun `TypeScript module output is dist bundle`() {
        file("ts/package.json", "{}")
        val bundle = file("ts/dist/bundle.js")
        assertEquals(ModuleArtifact(bundle, "js"), SpacetimeTools.artifact(tmp.resolve("ts")))
    }

    @Test
    fun `Rust module output is found in its own or a workspace target dir`() {
        file("ws/crates/my-module/Cargo.toml", "[package]\nname = \"my-module\"\n\n[dependencies]\nname = \"x\"\n")
        val wasm = file("ws/target/wasm32-unknown-unknown/release/my_module.wasm")
        assertEquals(ModuleArtifact(wasm, "wasm"), SpacetimeTools.artifact(tmp.resolve("ws/crates/my-module")))
    }

    @Test
    fun `missing build output and other languages are reported`() {
        file("rs/Cargo.toml", "[package]\nname = \"m\"\n")
        file("cs/module.csproj")
        assertFailsWith<SpacetimeToolException> { SpacetimeTools.artifact(tmp.resolve("rs")) }
        assertFailsWith<SpacetimeToolException> { SpacetimeTools.artifact(tmp.resolve("cs")) }
    }

    @Test
    fun `write replaces old bindings`() {
        val out = tmp.resolve("bindings")
        val files = Bindings.generate(Fixtures.schema("stuno"), "2.16.3")
        Bindings.write(files, out)
        val stale = file("bindings/OldTableHandle.kt", files.getValue("Types.kt"))
        Bindings.write(files, out)
        assertTrue(!stale.exists())
        assertEquals(files.keys, out.walkTopDown().filter { it.isFile }.map { it.relativeTo(out).invariantSeparatorsPath }.toSet())
    }

    @Test
    fun `write refuses a folder with files it did not generate`() {
        val handWritten = file("src/App.kt", "package app\n")
        assertFailsWith<SpacetimeToolException> { Bindings.write(mapOf("Module.kt" to ""), tmp.resolve("src")) }
        assertTrue(handWritten.exists())
    }
}
