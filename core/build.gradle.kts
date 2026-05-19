plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Promoted to `api` because their types leak onto :core's public surface
    // (RemoteClient takes OkHttpClient.Builder, returns JsonRpcResponse carrying
    // JsonElement, exposes SharedFlow<JsonElement> for notifications). Consumers
    // (:app, :cli) need these symbols transitively to bind ViewModels/UIs/CLI
    // output without re-declaring the deps themselves.
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // Explicit kotlin-stdlib pin: OkHttp 5.x's transitive Kotlin runtime could
    // otherwise diverge from the compiler version. Propagated via `api` so
    // downstream `:app` / `:cli` see the same stdlib.
    api(libs.kotlin.stdlib)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform {
        // Integration tests (those tagged "integration") require a live editor
        // and a SPK_EDITOR_PAIRING_URL env var. They are excluded from the
        // default :core:test run; opt in with -DincludeTags=integration.
        val include = System.getProperty("includeTags")
        if (include.isNullOrBlank()) {
            excludeTags("integration")
        } else {
            includeTags(*include.split(",").toTypedArray())
        }
    }
    testLogging {
        events("passed", "failed", "skipped")
    }
}
