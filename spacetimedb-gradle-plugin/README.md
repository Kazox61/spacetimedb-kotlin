# SpacetimeDB Gradle Plugin

Gradle plugin for SpacetimeDB Kotlin projects. Generates Kotlin client bindings and build-time configuration from your SpacetimeDB module.

It needs a stock SpacetimeDB install (2.16.3): `spacetime build` builds the module, `spacetimedb-standalone
extract-schema` prints its schema, and the generator (`spacetimedb-codegen`) runs in-process.

## Setup

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("/path/to/spacetimedb-kotlin/spacetimedb-gradle-plugin")
}

// build.gradle.kts
plugins {
    id("io.github.kazox61.spacetimedb")
}
```

## Configuration

```kotlin
spacetimedb {
    // Path to the SpacetimeDB module directory.
    // Default: read from "module-path" in spacetime.json, falls back to "spacetimedb/"
    modulePath.set(file("server"))

    // Or generate from a saved `extract-schema` output instead of building the module
    schemaFile.set(file("schema.json"))

    // The tools (default: the PATH, ~/.local/bin/spacetime, and the install's bin/current)
    cli.set(file("/path/to/spacetime"))
    standalone.set(file("/path/to/spacetimedb-standalone"))

    // Checked-in bindings: write them here and generate only when the task is run explicitly.
    // The folder's contents are replaced; the task refuses a folder holding other files.
    outputDir.set(file("src/commonMain/kotlin/module_bindings"))
    generateOnCompile.set(false)

    // Config file paths (default: spacetime.local.json and spacetime.json in root project)
    localConfig.set(file("spacetime.local.json"))
    mainConfig.set(file("spacetime.json"))
}
```

## Generated Files

### Bindings (`build/generated/spacetimedb/bindings/`)

Kotlin data classes, table handles, reducer stubs, and query builders generated from your module's schema (or in `outputDir`).

### SpacetimeConfig (`build/generated/spacetimedb/config/SpacetimeConfig.kt`)

Build-time constants extracted from `spacetime.local.json` / `spacetime.json`:

```kotlin
package module_bindings

object SpacetimeConfig {
    const val DATABASE_NAME: String = "my-app"      // from "database" field
    const val MODULE_PATH: String = "./spacetimedb"  // from "module-path" field
}
```

Fields are only included when present in the config. `spacetime.local.json` takes priority over `spacetime.json`.

## Tasks

| Task | Description |
|------|-------------|
| `generateSpacetimeBindings` | Builds the module, extracts its schema and generates the Kotlin bindings. Wired into `compileKotlin` unless `generateOnCompile` is false. |
| `generateSpacetimeConfig` | Generates `SpacetimeConfig.kt` from project config. Wired into `compileKotlin` unless `generateOnCompile` is false. |
| `cleanSpacetimeModule` | Deletes `spacetimedb/target/` (Rust build cache). Runs as part of `gradle clean`. |

## Notes

- **`gradle clean` triggers a full Rust recompilation** on the next build, since `cleanSpacetimeModule` deletes the Cargo `target/` directory. To clean only Kotlin artifacts:
  ```
  gradle clean -x cleanSpacetimeModule
  ```
- The plugin detects module source changes and re-generates bindings automatically.
- Both `org.jetbrains.kotlin.jvm` and `org.jetbrains.kotlin.multiplatform` are supported.
