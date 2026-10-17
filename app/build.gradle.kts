plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
// Explicit packaging-only opt-in. The default dual-ABI audit/device build is unchanged.
val arm64Distribution = providers.gradleProperty("inkdockArm64Distribution").orNull.let {
    require(it == null || it == "true" || it == "false") { "inkdockArm64Distribution must be true or false" }
    it == "true"
}
android {
    namespace = "org.inkdock.reader"
    compileSdk = 35
    // CI selects an existing runner NDK; never request AGP's absent default NDK.
    ndkVersion = providers.environmentVariable("INKDOCK_NDK_VERSION").orNull
        ?: throw GradleException("Set INKDOCK_NDK_VERSION to your already installed NDK revision. This experiment does not install an NDK or accept SDK licenses.")
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; applicationId = "org.inkdock.reader"; minSdk = 26; targetSdk = 35; versionCode = 8; versionName = "0.1.0-alpha.8-kokoro-streaming-experimental" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    if (arm64Distribution) {
        defaultConfig { ndk { abiFilters += "arm64-v8a" } }
        sourceSets.getByName("main") {
            jniLibs.setSrcDirs(listOf(rootProject.file("build/kokoro-arm64/staging/jniLibs")))
            assets.srcDir(rootProject.file("build/kokoro-arm64/staging/assets"))
        }
    }
    // Keep exact audited bytes: raw symbols by default, separately stripped copies when opted in.
    packaging { jniLibs { keepDebugSymbols += "**/*.so" } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}

// Default gate still enforces both raw ABIs. The separate distribution gate preserves that baseline.
// Always run: existence-only or cached Gradle checks can approve stale native output.
val verifyKokoroRuntime by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", if (arm64Distribution) "scripts/prepare-kokoro-arm64.py" else "scripts/build-kokoro-native.py", "--verify-runtime")
}
tasks.named("preBuild").configure { dependsOn(verifyKokoroRuntime) }
