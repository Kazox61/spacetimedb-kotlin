package com.clockworklabs.spacetimedb

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

abstract class SpacetimeDbExtension {
    /** The `spacetime` CLI. Default: the PATH, then `~/.local/bin/spacetime`. */
    abstract val cli: RegularFileProperty

    /** `spacetimedb-standalone`, which extracts the schema. Default: the `spacetime` install's `bin/current`. */
    abstract val standalone: RegularFileProperty

    /** The SpacetimeDB module (TypeScript or Rust). Default: "module-path" in spacetime.json, else "spacetimedb/". */
    abstract val modulePath: DirectoryProperty

    /**
     * A schema from `spacetimedb-standalone extract-schema`, used instead of building [modulePath].
     * For modules in other languages, or to generate without the SpacetimeDB tools.
     */
    abstract val schemaFile: RegularFileProperty

    /** Where the bindings go. Default: `build/generated/spacetimedb/bindings`. Its contents are replaced. */
    abstract val outputDir: DirectoryProperty

    /**
     * Generate before every Kotlin compilation and add [outputDir] to the sources (default true).
     * Set false for bindings checked into the source tree that are regenerated on demand
     * with `generateSpacetimeBindings`.
     */
    abstract val generateOnCompile: Property<Boolean>

    /** Path to spacetime.local.json. Defaults to "spacetime.local.json" in the root project. */
    abstract val localConfig: RegularFileProperty

    /** Path to spacetime.json. Defaults to "spacetime.json" in the root project. */
    abstract val mainConfig: RegularFileProperty
}
