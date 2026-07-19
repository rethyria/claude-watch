plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.claudewatch.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.claudewatch.wear"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    // Ambient support (issue #24): AmbientLifecycleObserver flags wrist-down
    // so Halo can freeze animations and dim instead of burning the display.
    implementation(libs.wear)
    // The OngoingActivity chip riding the foreground service's notification
    // (issue #24), plus androidx.core explicitly for NotificationCompat /
    // ServiceCompat rather than leaning on a transitive version.
    implementation(libs.wear.ongoing)
    // On-wrist text entry (pairing form): RemoteInputIntentHelper launches the
    // Wear system input activity so the committed string comes back as an
    // activity result — inline Compose text editing does not survive the
    // watch's IME (see HaloOfflineScreen).
    implementation(libs.wear.input)
    // Glanceables (issue #28): the ProtoLayout Tile (HaloTileService) and the
    // SHORT_TEXT complication (HaloComplicationService). tiles 1.4 + proto-
    // layout 1.2 are a matched pair — see the version catalog's warning
    // before touching either. concurrent-futures is only for
    // CallbackToFutureAdapter (TileService speaks ListenableFuture and Guava's
    // Futures.immediateFuture is NOT on the classpath — tiles ships just the
    // listenablefuture stub artifact).
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)
    implementation(libs.watchface.complications.data.source)
    implementation(libs.concurrent.futures)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    // DataStore with a custom (encrypting) Serializer — the Proto-DataStore
    // mechanism (single typed object, atomic writes) without protobuf codegen.
    implementation(libs.datastore)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // Virtual-time tests for the collector's debounced visibility edge
    // (#59): the flap filter is a real delay that Unconfined cannot pin.
    testImplementation(libs.coroutines.test)
    // Real org.json for local unit tests (the mockable android.jar stubs it out).
    testImplementation(libs.org.json)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Hosts createComposeRule content for the pager test (no Activity of its own).
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // GrantPermissionRule for POST_NOTIFICATIONS (issue #24): pairing starts
    // the FGS, whose permission ask would otherwise cover the compose tree
    // with a system dialog mid-flow.
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.okhttp)
    // On-device fake bridge for the dictation flow test (stubbed recognizer
    // result → real POST → ack-gated echo) — no real bridge needed.
    androidTestImplementation(libs.okhttp.mockwebserver)
}
