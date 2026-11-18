plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val repoRoot = rootProject.projectDir
val uniffiOut = layout.buildDirectory.dir("generated/uniffi/kotlin")
val psArgs = listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File")

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

    buildFeatures { compose = true }

    sourceSets.getByName("main") {
        kotlin.directories.add(uniffiOut.get().asFile.absolutePath)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
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
}
