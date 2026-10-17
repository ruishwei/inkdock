plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.inkdock.reader"
    compileSdk = 35
    defaultConfig { applicationId = "org.inkdock.reader"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "0.1.0-alpha.3" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
