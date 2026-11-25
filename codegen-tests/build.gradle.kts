plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.spacetimedb)
}

spacetimedb {
    modulePath.set(layout.projectDirectory.dir("spacetimedb"))
}

dependencies {
    implementation(project(":spacetimedb-sdk"))
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}
