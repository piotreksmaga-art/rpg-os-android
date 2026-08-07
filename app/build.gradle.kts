plugins {
    id("com.android.application")
}

android {
    namespace = "com.rpgos.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rpgos.app"
        minSdk = 28
        targetSdk = 37
        versionCode = 100
        versionName = "1.0.0"
        buildConfigField("String", "RPGOS_BACKEND_URL", "\"http://127.0.0.1:8000\"")
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")
}
