plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.codexedge.glow"; compileSdk = 35
    defaultConfig { applicationId = "com.codexedge.glow"; minSdk = 26; targetSdk = 28; versionCode = 3; versionName = "1.2-session-lease" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
