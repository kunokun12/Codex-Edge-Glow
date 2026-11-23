plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// CI supplies the same persistent identity used by Companion. Local builds may
// use Android's normal debug key, but release/test artifacts are pinned and
// verified by the signing workflows before upload.
val signingPath = System.getenv("CODEX_SIGNING_KEYSTORE_PATH")
val signingStorePassword = System.getenv("CODEX_SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("CODEX_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("CODEX_SIGNING_KEY_PASSWORD")
val sourceCommit = System.getenv("TESSERA_SOURCE_COMMIT")?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "unknown"

android {
    namespace = "org.tessera.observer"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.tessera.observer"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-schema3-evidence"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TESSERA_SOURCE_COMMIT", "\"$sourceCommit\"")
    }
    buildFeatures { buildConfig = true }
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
    testImplementation("org.robolectric:robolectric:4.14.1")
