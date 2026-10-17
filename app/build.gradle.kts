plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.inkdock.reader"
    compileSdk = 35
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; applicationId = "org.inkdock.reader"; minSdk = 26; targetSdk = 35; versionCode = 4; versionName = "0.1.0-alpha.4" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}
