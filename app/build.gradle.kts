plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.spectra00.oplushotspotfix"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.spectra00.oplushotspotfix"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Compile-time only: LSPosed injects the real de.robv.android.xposed.*
    // classes into the hooked process at runtime. Never bundled into the
    // built APK, so it can't collide with LSPosed's own injected classes.
    compileOnly(project(":xposed-stub"))
}
