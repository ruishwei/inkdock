plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.inkdock.reader"
    compileSdk = 35
    // CI selects an existing runner NDK; never request AGP's absent default NDK.
    ndkVersion = providers.environmentVariable("INKDOCK_NDK_VERSION").orNull
        ?: throw GradleException("Set INKDOCK_NDK_VERSION to your already installed NDK revision. This experiment does not install an NDK or accept SDK licenses.")
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; applicationId = "org.inkdock.reader"; minSdk = 26; targetSdk = 35; versionCode = 7; versionName = "0.1.0-alpha.7-kokoro-buffer-experimental" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // Experimental audit APK retains exact audited ELF bytes and full symbols.
    packaging { jniLibs { keepDebugSymbols += "**/*.so" } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}

// A single stdlib verifier enforces both ABIs, source pin, ELF/notice/library hashes.
// Always run: existence-only or cached Gradle checks can approve stale native output.
val verifyKokoroRuntime by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/build-kokoro-native.py", "--verify-runtime")
}
tasks.named("preBuild").configure { dependsOn(verifyKokoroRuntime) }
