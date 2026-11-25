package com.clockworklabs.spacetimedb

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class SpacetimeDbPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("spacetimedb", SpacetimeDbExtension::class.java)

        val rootDir = project.rootProject.layout.projectDirectory
        ext.localConfig.convention(rootDir.file("spacetime.local.json"))
        ext.mainConfig.convention(rootDir.file("spacetime.json"))

        // Derive modulePath default from spacetime.json's "module-path", fall back to "spacetimedb"
        val configModulePath = readConfigField(rootDir.asFile, "module-path")
        ext.modulePath.convention(rootDir.dir(configModulePath ?: "spacetimedb"))

        val bindingsDir = project.layout.buildDirectory.dir("generated/spacetimedb/bindings")
        ext.outputDir.convention(bindingsDir)
        ext.generateOnCompile.convention(true)
        val configDir = project.layout.buildDirectory.dir("generated/spacetimedb/config")

        // Clean the Rust target directory when running `gradle clean`
        project.tasks.register("cleanSpacetimeModule", Delete::class.java) {
            it.group = "spacetimedb"
            it.description = "Clean SpacetimeDB module build artifacts"
            it.delete(ext.modulePath.map { dir -> dir.dir("target") })
        }
        project.plugins.withType(org.gradle.api.plugins.BasePlugin::class.java) {
            project.tasks.named("clean") { it.dependsOn("cleanSpacetimeModule") }
        }

        val generateTask = project.tasks.register("generateSpacetimeBindings", GenerateBindingsTask::class.java) {
            it.cli.set(ext.cli)
            it.standalone.set(ext.standalone)
            it.modulePath.set(ext.modulePath)
            it.schemaFile.set(ext.schemaFile)
            // Build output and dependencies are not sources.
            it.moduleSourceFiles.from(ext.modulePath.map { dir ->
                project.fileTree(dir) { tree -> tree.exclude("target", "node_modules", "dist") }
            })
            it.outputDir.set(ext.outputDir)
        }

        val configTask = project.tasks.register("generateSpacetimeConfig", GenerateConfigTask::class.java) {
            val localFile = ext.localConfig
            val mainFile = ext.mainConfig
            if (localFile.isPresent && localFile.get().asFile.exists()) it.localConfig.set(localFile)
            if (mainFile.isPresent && mainFile.get().asFile.exists()) it.mainConfig.set(mainFile)
            it.outputDir.set(configDir)
        }

        // Wire generated sources into Kotlin compilation, unless the bindings are checked in.
        project.afterEvaluate { if (ext.generateOnCompile.get()) wireIntoCompilation(project, ext, generateTask, configTask, configDir) }
    }

    private fun wireIntoCompilation(
        project: Project,
        ext: SpacetimeDbExtension,
        generateTask: TaskProvider<GenerateBindingsTask>,
        configTask: TaskProvider<GenerateConfigTask>,
        configDir: Provider<Directory>,
    ) {
        val bindingsDir = ext.outputDir
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            sourceSets.getByName("main").java.srcDir(bindingsDir)
            sourceSets.getByName("main").java.srcDir(configDir)

            project.tasks.named("compileKotlin") {
                it.dependsOn(generateTask)
                it.dependsOn(configTask)
            }
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            val kmpSourceSets = project.extensions.getByType(KotlinMultiplatformExtension::class.java).sourceSets
            kmpSourceSets.getByName("commonMain").kotlin.srcDir(bindingsDir)
            kmpSourceSets.getByName("commonMain").kotlin.srcDir(configDir)

            project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool::class.java).configureEach {
                it.dependsOn(generateTask)
                it.dependsOn(configTask)
            }
        }
    }

    /** Read a field from spacetime.local.json or spacetime.json in the given directory. */
    private fun readConfigField(dir: java.io.File, field: String): String? {
        for (name in listOf("spacetime.local.json", "spacetime.json")) {
            val file = dir.resolve(name)
            if (file.isFile) {
                val parsed = groovy.json.JsonSlurper().parseText(file.readText())
                val value = (parsed as? Map<*, *>)?.get(field) as? String
                if (value != null) return value
            }
        }
        return null
    }
}
