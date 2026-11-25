// Kotlin client code generator: SpacetimeDB module schema (RawModuleDef V10 JSON) → Kotlin bindings.
// Plain JVM library, used by the Gradle plugin and a small CLI.
plugins {
    alias(libs.plugins.kotlinJvm)
    `maven-publish`
}

group = providers.gradleProperty("kotlinSdkGroup").getOrElse("io.github.kazox61.spacetimedb")
version = providers.gradleProperty("kotlinSdkVersion").getOrElse("0.1.0-dev")

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    // Golden fixtures live at the repository root.
    systemProperty("fixtures.dir", rootDir.resolve("../fixtures").absolutePath)
    inputs.dir(rootDir.resolve("../fixtures")).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
}
