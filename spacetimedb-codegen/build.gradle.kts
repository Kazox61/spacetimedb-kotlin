// Kotlin client code generator: SpacetimeDB module schema (RawModuleDef V10 JSON) → Kotlin bindings.
// Plain JVM library, used by the Gradle plugin and a small CLI.
plugins {
    alias(libs.plugins.kotlinJvm)
    application
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

application {
    mainClass = "io.github.kazox61.spacetimedb.codegen.cli.MainKt"
    applicationName = "spacetimedb-codegen"
}

java {
    withSourcesJar()
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
    repositories {
        maven {
            name = "community"
            url = uri(providers.gradleProperty("kotlinSdkRepository").getOrElse(layout.buildDirectory.dir("repository").get().asFile.toURI().toString()))
            if (providers.gradleProperty("kotlinSdkRepository").orNull?.startsWith("https://") == true) {
                credentials {
                    username = providers.environmentVariable("MAVEN_USERNAME").orNull
                    password = providers.environmentVariable("MAVEN_PASSWORD").orNull
                }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // Golden fixtures live at the repository root.
    systemProperty("fixtures.dir", rootDir.resolve("../fixtures").absolutePath)
    inputs.dir(rootDir.resolve("../fixtures")).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
}
