// Platform JPEG decoder baseline (plan Task 3): BitmapFactory and ImageDecoder, measured with
// Microbenchmark on the reference device. Not part of the shipped app.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.androidx.benchmark)
}

val repoRoot = rootProject.projectDir
val inputsDir = layout.buildDirectory.dir("codec-inputs")

// The inputs are the exact bytes the Rust benchmark decodes (codec-bench generates them deterministically).
val exportCodecInputs by tasks.registering(Exec::class) {
    workingDir = repoRoot
    commandLine("cargo", "run", "--release", "-p", "codec-bench", "--", "--export", inputsDir.get().asFile.absolutePath)
    outputs.dir(inputsDir)
    // The directory is the task's only output: cargo's own up-to-date check handles the Rust side.
    inputs.files(fileTree(repoRoot.resolve("tools/codec-bench")) { exclude("target") })
    inputs.files(fileTree(repoRoot.resolve("tools/gen-corpus")) { exclude("target") })
}

android {
    namespace = "com.proviewer.codecbench"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }

    sourceSets.getByName("androidTest") {
        assets.directories.add(inputsDir.get().asFile.absolutePath)
    }
}

// Packaging the androidTest assets needs the generated inputs.
tasks.matching { it.name.endsWith("AndroidTestAssets") }.configureEach { dependsOn(exportCodecInputs) }

dependencies {
    androidTestImplementation(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
