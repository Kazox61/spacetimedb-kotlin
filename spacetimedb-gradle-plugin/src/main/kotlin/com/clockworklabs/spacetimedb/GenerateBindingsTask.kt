package com.clockworklabs.spacetimedb

import io.github.kazox61.spacetimedb.codegen.Bindings
import io.github.kazox61.spacetimedb.codegen.SpacetimeToolException
import io.github.kazox61.spacetimedb.codegen.SpacetimeTools
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates the Kotlin client bindings: builds the module with `spacetime build`, reads its schema with
 * `spacetimedb-standalone extract-schema`, and runs the generator in-process.
 */
abstract class GenerateBindingsTask : DefaultTask() {
    @get:Internal
    abstract val cli: RegularFileProperty

    @get:Internal
    abstract val standalone: RegularFileProperty

    @get:Internal
    abstract val modulePath: DirectoryProperty

    /** The module's sources, so the task reruns when they change. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleSourceFiles: ConfigurableFileCollection

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val schemaFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "spacetimedb"
        description = "Generate SpacetimeDB Kotlin client bindings"
    }

    @TaskAction
    fun generate() {
        val outDir = outputDir.get().asFile
        try {
            val files = if (schemaFile.isPresent) {
                Bindings.generate(schemaFile.get().asFile.readText(), SpacetimeTools.TESTED_VERSION)
            } else {
                val moduleDir = modulePath.get().asFile
                if (!moduleDir.isDirectory) {
                    throw GradleException(
                        "SpacetimeDB module directory not found at '${moduleDir.absolutePath}'. " +
                            "Set it with: spacetimedb { modulePath.set(file(\"/path/to/module\")) }",
                    )
                }
                val tools = SpacetimeTools.locate(cli.orNull?.asFile, standalone.orNull?.asFile)
                    ?: throw GradleException(
                        "Could not find the SpacetimeDB tools (spacetime and spacetimedb-standalone). " +
                            "Install SpacetimeDB from https://spacetimedb.com/install or set " +
                            "spacetimedb { cli.set(file(...)); standalone.set(file(...)) }",
                    )
                val version = tools.version()
                if (version != SpacetimeTools.TESTED_VERSION) {
                    logger.warn("SpacetimeDB $version: the Kotlin generator is tested with ${SpacetimeTools.TESTED_VERSION}.")
                }
                Bindings.generateFromModule(moduleDir, tools)
            }
            Bindings.write(files, outDir)
        } catch (e: SpacetimeToolException) {
            throw GradleException(e.message ?: "SpacetimeDB tool failed", e)
        }
    }
}
