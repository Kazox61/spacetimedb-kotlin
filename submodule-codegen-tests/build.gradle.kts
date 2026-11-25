plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.spacetimedb)
}

// Bindings for the TypeScript test module, which mounts a submodule as `myLib`, from its captured schema.
spacetimedb {
    schemaFile.set(rootProject.layout.projectDirectory.file("fixtures/module-test-ts/schema.json"))
}

dependencies {
    implementation(project(":spacetimedb-sdk"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}
