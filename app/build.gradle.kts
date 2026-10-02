import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.neuron.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neuron.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.1"

        ndk {
            // Local AI: 64-bit only — 32-bit ABIs would double build time and
            // most devices shipping since minSdk 26 are 64-bit.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Release + -O3 for llama.cpp/ggml: debug native builds are
                // 3-10x slower. CPP flags live in CMakeLists.txt; this sets
                // the toolchain-wide default for every ABI.
                cppFlags += "-O3"
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    // Vulkan GPU backend (milestone 5). CMake degrades to a
                    // CPU-only build when glslc is absent.
                    "-DGGML_VULKAN=ON"
                )
            }
        }
    }

    // Release signing is driven entirely by CI secrets/env — the keystore
    // NEVER lives in the repository. Locally or without secrets the release
    // build simply falls back to unsigned.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("NEURON_KEYSTORE_PATH")
            val ksPass = System.getenv("NEURON_KEYSTORE_PASSWORD")
            val aliasEnv = System.getenv("NEURON_KEY_ALIAS")
            val keyPass = System.getenv("NEURON_KEY_PASSWORD")
            val configured = !ksPath.isNullOrEmpty() && File(ksPath).exists() &&
                !ksPass.isNullOrEmpty() && !aliasEnv.isNullOrEmpty() &&
                !keyPass.isNullOrEmpty()
            if (configured) {
                storeFile = File(ksPath)
                storePassword = ksPass
                keyAlias = aliasEnv
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ksPath = System.getenv("NEURON_KEYSTORE_PATH")
            val configured = !ksPath.isNullOrEmpty() && File(ksPath).exists()
            if (configured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    // Local AI (on-device inference): llama.cpp via JNI. Sources are
    // commit-pinned and fetched at configure time; when the fetch fails
    // (offline runner) the bridge compiles to a stub and the Kotlin layer
    // reports the engine as unavailable — the app never breaks.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Keep the native inference library uncompressed in the APK so
        // Android can load it directly from the installed APK (extractNativeLibs=false).
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { task ->
            // Surface full assertion/exception details in CI logs — the
            // console summary only prints file:line otherwise.
            task.testLogging {
                events(
                    org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
                    org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
                )
                exceptionFormat =
                    org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStackTraces = true
                showCauses = true
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.jlatexmath.android)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
