plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.3.10"
}

android {
    namespace = "com.rpgos.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rpgos.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 144
        versionName = "1.2.0-alpha5-temp-gm144"
    }

    signingConfigs {
        create("release") {
            val keystorePath = providers.environmentVariable("RPGOS_KEYSTORE_PATH").orNull
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("RPGOS_KEYSTORE_PASSWORD").orNull
                keyAlias = "rpgos-alpha"
                keyPassword = providers.environmentVariable("RPGOS_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
