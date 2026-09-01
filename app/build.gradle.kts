plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rpgos.app"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.rpgos.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 157
        versionName = "1.3.0-alpha17-core54-bekko"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "RPGOS_BACKEND_URL", "\"https://YOUR-BACKEND.example\"")
        buildConfigField(
            "String",
            "RPGOS_UPDATE_FEED_URL",
            "\"https://api.github.com/repos/piotreksmaga-art/rpg-os-android/releases/latest\""
        )
        buildConfigField(
            "String",
            "RPGOS_CONTENT_UPDATE_URL",
            "\"https://raw.githubusercontent.com/piotreksmaga-art/rpg-os-android/master/content/channel-alpha.json\""
        )
        ndk {
            abiFilters += "arm64-v8a"
            // Release remains phone-focused ARM64. This opt-in ABI exists only so the Android
            // emulator can exercise ExecuTorch/XNNPACK natively instead of through ARM
            // translation, which XNNPACK correctly rejects as unsupported hardware.
            if (providers.gradleProperty("rpgosEmulatorX86").orNull == "true") {
                abiFilters += "x86_64"
            }
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_VULKAN=ON",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF"
                )
                cppFlags += listOf("-O3")
            }
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RPGOS_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RPGOS_KEYSTORE_PASSWORD")
                keyAlias = "rpgos"
                keyPassword = System.getenv("RPGOS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        create("labDebug") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            versionNameSuffix = "-lab"
            isDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    // Keep Android bytecode stable even when a newer Android Studio JBR runs Gradle.
    // Robolectric and the supported device range consume Java 17 class files; allowing
    // the host JDK to select the target made local and CI builds environment-dependent.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showExceptions = true
                showCauses = true
                showStackTraces = true
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("labDebug")) { variantBuilder ->
        // Custom build types do not receive a host-test component by default in AGP 9.
        // Stage-3 bridge tests must compile against lab-only sources without leaking them into release.
        (variantBuilder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
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
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.2")
    // Official ExecuTorch Android AAR packages the native CPU/XNNPACK runtime for on-device LLMs.
    implementation("org.pytorch:executorch-android:1.3.0")

    testImplementation("junit:junit:4.13.2")
    // Lab bridge contract tests exercise JSON codecs without an Android runtime.
    // Use the real JVM implementation so these tests do not depend on Robolectric's
    // emulated SDK classpath.
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")
}
