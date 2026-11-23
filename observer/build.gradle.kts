plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// CI supplies the same persistent identity used by Companion. Local builds may
// use Android's normal debug key, but release/test artifacts are pinned and
// verified by the signing workflows before upload.
val signingPath = System.getenv("CODEX_SIGNING_KEYSTORE_PATH")
val signingStorePassword = System.getenv("CODEX_SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("CODEX_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("CODEX_SIGNING_KEY_PASSWORD")

android {
    namespace = "org.tessera.observer"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.tessera.observer"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-candidate-ipc-diagnostics"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            if (!signingPath.isNullOrBlank()) {
                storeFile = file(signingPath)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}
