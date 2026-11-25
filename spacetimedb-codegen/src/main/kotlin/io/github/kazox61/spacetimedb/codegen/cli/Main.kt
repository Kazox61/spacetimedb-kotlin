package io.github.kazox61.spacetimedb.codegen.cli

import io.github.kazox61.spacetimedb.codegen.Bindings
import io.github.kazox61.spacetimedb.codegen.SpacetimeToolException
import io.github.kazox61.spacetimedb.codegen.SpacetimeTools
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Usage: spacetimedb-codegen --out-dir <dir> (--module-path <dir> | --schema <file>) [options]

Generates Kotlin client bindings for a SpacetimeDB module.

  --out-dir <dir>         Where to write the bindings (its contents are replaced)
  --module-path <dir>     A TypeScript or Rust module: runs `spacetime build`, then extract-schema
  --schema <file>         A schema from `spacetimedb-standalone extract-schema` ("-" for stdin)
  --skip-build            With --module-path: use the existing build output
  --spacetime <file>      The spacetime CLI (default: PATH, then ~/.local/bin/spacetime)
  --standalone <file>     spacetimedb-standalone (default: the spacetime install's bin/current)
  --spacetime-version <v> With --schema: the SpacetimeDB version it came from (default ${SpacetimeTools.TESTED_VERSION})
"""

public fun main(args: Array<String>) {
    val options = mutableMapOf<String, String>()
    var skipBuild = false
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "-h", "--help" -> {
                print(USAGE)
                return
            }
            "--skip-build" -> skipBuild = true
            "--out-dir", "--module-path", "--schema", "--spacetime", "--standalone", "--spacetime-version" ->
                options[arg] = args.getOrNull(++i) ?: fail("$arg needs a value")
            else -> fail("Unknown argument: $arg")
        }
        i++
    }
    val outDir = File(options["--out-dir"] ?: fail("--out-dir is required"))
    val module = options["--module-path"]
    val schema = options["--schema"]
    if ((module == null) == (schema == null)) fail("Give either --module-path or --schema")

    try {
        val files = if (schema != null) {
            val json = if (schema == "-") generateSequence(::readLine).joinToString("\n") else File(schema).readText()
            Bindings.generate(json, options["--spacetime-version"] ?: SpacetimeTools.TESTED_VERSION)
        } else {
            val tools = SpacetimeTools.locate(options["--spacetime"]?.let(::File), options["--standalone"]?.let(::File))
                ?: fail("Could not find spacetime and spacetimedb-standalone. Install SpacetimeDB or pass --spacetime and --standalone.")
            val version = tools.version()
            if (version != SpacetimeTools.TESTED_VERSION) {
                System.err.println("warning: SpacetimeDB $version; this generator is tested with ${SpacetimeTools.TESTED_VERSION}")
            }
            Bindings.generateFromModule(File(module!!), tools, build = !skipBuild)
        }
        Bindings.write(files, outDir)
        println("Wrote ${files.size} files to $outDir")
    } catch (e: SpacetimeToolException) {
        fail(e.message ?: "SpacetimeDB tool failed")
    } catch (e: IllegalArgumentException) {
        fail("Invalid schema: ${e.message}")
    }
}

private fun fail(message: String): Nothing {
    System.err.println("error: $message")
    System.err.println("Run with --help for usage.")
    exitProcess(2)
}
