// :shared — pure-JVM protocol layer consumed by :app (and later :phone):
// typed wire models for the bridge's /v1 SSE contract plus the pure event
// reducer that folds frames into session state. No Android dependencies, so
// everything here runs as plain JVM unit tests against the fixture corpus.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // api: JsonObject appears in the wire-model surface (tool_input passthrough).
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
