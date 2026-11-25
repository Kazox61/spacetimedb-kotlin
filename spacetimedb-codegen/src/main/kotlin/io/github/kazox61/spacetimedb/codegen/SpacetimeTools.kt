package io.github.kazox61.spacetimedb.codegen

import java.io.File
import java.util.concurrent.TimeUnit

/** A built module: the file `extract-schema` reads and how to read it. */
public data class ModuleArtifact(val file: File, val hostType: String)

/** Thrown when a SpacetimeDB tool is missing or fails; the message says what to do. */
public class SpacetimeToolException(message: String) : RuntimeException(message)

/**
 * The stock SpacetimeDB tools: `spacetime` builds the module, `spacetimedb-standalone extract-schema`
 * prints its schema. No fork needed.
 */
public class SpacetimeTools(
    /** The `spacetime` CLI. */
    public val spacetime: File,
    /** `spacetimedb-standalone`, which the `spacetime` installer puts in `bin/current`. */
    public val standalone: File,
) {
    /** Runs `spacetime build` on the module. */
    public fun build(moduleDir: File) {
        run(listOf(spacetime.path, "build", "--module-path", moduleDir.absolutePath), moduleDir, "spacetime build")
    }

    /** The module's schema as RawModuleDef JSON. */
    public fun extractSchema(artifact: ModuleArtifact): String = run(
        listOf(standalone.path, "extract-schema", "--host-type", artifact.hostType, artifact.file.absolutePath),
        artifact.file.parentFile,
        "spacetimedb-standalone extract-schema",
    )

    /** The SpacetimeDB version of [standalone], e.g. `2.16.3`. */
    public fun version(): String {
        val out = run(listOf(standalone.path, "--version"), null, "spacetimedb-standalone --version")
        return Regex("""spacetimedb-lib version (\S+?);""").find(out)?.groupValues?.get(1)
            ?: throw SpacetimeToolException("Unexpected `spacetimedb-standalone --version` output: $out")
    }

    public companion object {
        /** The SpacetimeDB version the generator is tested against (`fixtures/`). */
        public const val TESTED_VERSION: String = "2.16.3"

        /**
         * Finds the tools: the given paths, else the `spacetime` installer's locations, else the PATH.
         * Returns null when one of them can't be found.
         */
        public fun locate(spacetime: File? = null, standalone: File? = null): SpacetimeTools? {
            val home = File(System.getProperty("user.home"))
            val windows = System.getProperty("os.name").startsWith("Windows")
            val exe = if (windows) ".exe" else ""
            val dataDir = if (windows) {
                File(System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").path, "SpacetimeDB")
            } else {
                home.resolve(".local/share/spacetime")
            }
            val cli = spacetime ?: onPath("spacetime$exe") ?: home.resolve(".local/bin/spacetime$exe").takeIf { it.canExecute() }
            val server = standalone
                ?: dataDir.resolve("bin/current/spacetimedb-standalone$exe").takeIf { it.canExecute() }
                ?: onPath("spacetimedb-standalone$exe")
            return if (cli != null && server != null) SpacetimeTools(cli, server) else null
        }

        /** Where `spacetime build` puts the module: `dist/bundle.js` for TypeScript, `<crate>.wasm` for Rust. */
        public fun artifact(moduleDir: File): ModuleArtifact {
            val cargoToml = moduleDir.resolve("Cargo.toml")
            if (moduleDir.resolve("package.json").isFile && !cargoToml.isFile) {
                val bundle = moduleDir.resolve("dist/bundle.js")
                if (!bundle.isFile) throw SpacetimeToolException("No build output at $bundle. Did `spacetime build` succeed?")
                return ModuleArtifact(bundle, "js")
            }
            if (cargoToml.isFile) {
                val crate = crateName(cargoToml).replace('-', '_')
                val wasm = "wasm32-unknown-unknown/release/$crate.wasm"
                // The module's own target dir, or a workspace's further up.
                val candidates = (System.getenv("CARGO_TARGET_DIR")?.let { listOf(File(it).resolve(wasm)) } ?: emptyList()) +
                    generateSequence(moduleDir.absoluteFile) { it.parentFile }.map { it.resolve("target/$wasm") }
                val found = candidates.filter { it.isFile }.maxByOrNull { it.lastModified() }
                    ?: throw SpacetimeToolException("No $crate.wasm found for $moduleDir. Did `spacetime build` succeed?")
                return ModuleArtifact(found, "wasm")
            }
            throw SpacetimeToolException(
                "$moduleDir is not a TypeScript (package.json) or Rust (Cargo.toml) module. " +
                    "For other module languages, generate from a schema file instead.",
            )
        }

        private fun crateName(cargoToml: File): String {
            var inPackage = false
            for (raw in cargoToml.readLines()) {
                val line = raw.trim()
                if (line.startsWith("[")) inPackage = line == "[package]"
                if (inPackage) Regex("""^name\s*=\s*"([^"]+)"""").find(line)?.let { return it.groupValues[1] }
            }
            throw SpacetimeToolException("No [package] name in $cargoToml")
        }

        private fun onPath(name: String): File? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }

        /** Runs a command and returns its stdout; on failure the error includes its stderr. */
        private fun run(command: List<String>, dir: File?, what: String): String {
            val stderr = File.createTempFile("spacetime", ".log")
            try {
                val process = try {
                    ProcessBuilder(command).directory(dir).redirectError(stderr).start()
                } catch (e: java.io.IOException) {
                    throw SpacetimeToolException("Could not run $what (${command.first()}): ${e.message}")
                }
                process.outputStream.close()
                val out = process.inputStream.bufferedReader().readText()
                process.waitFor(10, TimeUnit.MINUTES)
                if (process.exitValue() != 0) {
                    throw SpacetimeToolException(
                        "$what failed (exit ${process.exitValue()}):\n${(stderr.readText() + out).trim().takeLast(4000)}",
                    )
                }
                return out
            } finally {
                stderr.delete()
            }
        }
    }
}
