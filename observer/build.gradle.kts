plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "org.tessera.observer"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.tessera.observer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-candidate"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}
