plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val signingPath = System.getenv("CODEX_SIGNING_KEYSTORE_PATH")
val signingStorePassword = System.getenv("CODEX_SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("CODEX_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("CODEX_SIGNING_KEY_PASSWORD")

android {
    namespace = "com.codexedge.glow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codexedge.glow"
        minSdk = 26
        targetSdk = 28
        versionCode = 15
        versionName = "2.5.5-correlated-refusal-candidate"
    }

    // The APK must remain debuggable for the existing local run-as based MCP
    // bridge. Android's generated debug keystore is never used in CI.
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
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
