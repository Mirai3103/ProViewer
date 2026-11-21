plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val repoRoot = rootProject.projectDir

// Short commit hash baked into every benchmark result; "unknown" when git is unavailable.
val gitSha: String = runCatching {
    val exec = providers.exec {
        workingDir = repoRoot
        commandLine("git", "rev-parse", "--short", "HEAD")
    }
    exec.result.get() // throws on a non-zero exit, caught below
    exec.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
val uniffiOut = layout.buildDirectory.dir("generated/uniffi/kotlin")
val psArgs = listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File")

// Bench-only corpus generator (debug + benchmark build types). The release build type compiles
// the `nobench` stub source set instead and never packages libproviewer_benchgen.so.
val benchKotlinDir = layout.projectDirectory.dir("src/bench/kotlin")
val benchJniLibsDir = layout.buildDirectory.dir("benchJniLibs")
val nobenchKotlinDir = layout.projectDirectory.dir("src/nobench/kotlin")

// Always regenerate bindings and rebuild the native library so a stale .so or
// stale Kotlin bindings can never reach the APK (plan Review Focus 1).
val genBindings by tasks.registering(Exec::class) {
    workingDir = repoRoot
    commandLine(psArgs + listOf("scripts/gen-bindings.ps1", "-OutDir", uniffiOut.get().asFile.absolutePath))
    outputs.dir(uniffiOut)
    outputs.upToDateWhen { false }
}

val buildCore by tasks.registering(Exec::class) {
    workingDir = repoRoot
    commandLine(psArgs + listOf("scripts/build-core.ps1", "-Profile", "release"))
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))
    outputs.dir(benchJniLibsDir)
    outputs.upToDateWhen { false }
}

tasks.named("preBuild") { dependsOn(genBindings, buildCore) }

android {
    namespace = "com.proviewer.app"
    compileSdk = 37

    defaultConfig {
        // PLACEHOLDER: permanent once uploaded to Play. Owner must confirm before first upload.
        applicationId = "com.proviewer.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        // Minified, non-debuggable, profileable: what Macrobenchmark and perf-capture measure.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        // For BuildConfig (GIT_SHA), consumed by the benchmark report (Task 10).
        buildConfig = true
    }

    sourceSets.getByName("main") {
        kotlin.directories.add(uniffiOut.get().asFile.absolutePath)
    }
    // Bench code (CorpusGenerator + VariantFeatures) and the bench-only .so are compiled into
    // the debug and benchmark build types only.
    sourceSets.getByName("debug") {
        kotlin.directories.add(benchKotlinDir.asFile.absolutePath)
        jniLibs.directories.add(benchJniLibsDir.get().asFile.absolutePath)
    }
    sourceSets.getByName("benchmark") {
        kotlin.directories.add(benchKotlinDir.asFile.absolutePath)
        jniLibs.directories.add(benchJniLibsDir.get().asFile.absolutePath)
    }
    // The release build type gets the no-benchmark stub instead.
    sourceSets.getByName("release") {
        kotlin.directories.add(nobenchKotlinDir.asFile.absolutePath)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Zoom gestures for the reader pages (draw-phase transforms + pan-to-edge pager hand-off).
    implementation(libs.telephoto.zoomable)
    // uniffi's Kotlin runtime. @aar so the Android-specific libjnidispatch.so is packaged.
    implementation("${libs.jna.get()}@aar")

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    // Espresso < 3.7 crashes on Android 16 / API 37 (InputManager.getInstance removed).
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // JVM unit tests: ReaderLogic and PageWindow (fakes + kotlinx-coroutines-test). Coroutines
    // core is already on the classpath transitively (Compose runtime / activity), so only the
    // test artifact is added here.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for the JVM: BenchmarkJson's report is round-tripped through JSONObject in
    // unit tests, and android.jar's stub copy throws "not mocked" outside an Android runtime.
    testImplementation(libs.org.json)
}
