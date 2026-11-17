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
        versionCode = 13
        versionName = "1.2.5"

        // Google connectors: the Android OAuth client is matched by package
        // name + signing certificate, so it is never passed in code. The WEB
        // client ID is the serverClientId used for offline access, and the
        // backend URL is the deployed Cloudflare Worker (public, overridable
        // per build via -PneuronBackendUrl=... or NEURON_BACKEND_URL).
        val googleWebClientId =
            "106446745670-velmqq3ugbqlcelpf5e6b56kblb99us7.apps.googleusercontent.com"
        val googleBackendUrl = (System.getenv("NEURON_BACKEND_URL")
            ?: project.findProperty("neuronBackendUrl")
            ?: "https://neuron-ai-backend.omgupta240511.workers.dev")
            .toString().trimEnd('/')
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("String", "GOOGLE_BACKEND_URL", "\"$googleBackendUrl\"")

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
                    // The NDK's libvulkan stub for API 26 lacks Vulkan 1.1
                    // symbols (vkGetPhysicalDeviceFeatures2...) that ggml's
                    // Vulkan backend calls — link against the API 28 stub
                    // instead. Only libggml-vulkan.so is affected; the main
                    // chain never links it (runtime dlopen), and on Android
                    // 8.x devices that fail to load it the registry simply
                    // has no Vulkan backend — CPU inference is unaffected.
                    "-DANDROID_PLATFORM=android-28"
                )
                // GGML_VULKAN is set INSIDE CMakeLists (arm64-only, guarded by
                // a glslc probe) — passing it here would enable the backend
                // for x86_64 too, where the emulator build must stay CPU-only.
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
        // CI signs the DEBUG artifact with the same key as the release build
        // (the keystore arrives through env from GitHub Secrets). Google
        // Sign-In matches the Android OAuth client on package name + the
        // APK's signing SHA-1, and a per-runner debug key changes that SHA-1
        // whenever the keystore cache misses — the registered fingerprint then
        // goes stale and consent fails with result 0. One long-lived key keeps
        // the fingerprint valid forever. Locally (no env) the normal debug
        // keystore is used.
        debug {
            val ksPath = System.getenv("NEURON_KEYSTORE_PATH")
            val ksPass = System.getenv("NEURON_KEYSTORE_PASSWORD")
            val aliasEnv = System.getenv("NEURON_KEY_ALIAS")
            val keyPass = System.getenv("NEURON_KEY_PASSWORD")
            val stable = !ksPath.isNullOrEmpty() && File(ksPath).exists() &&
                !ksPass.isNullOrEmpty() && !aliasEnv.isNullOrEmpty() &&
                !keyPass.isNullOrEmpty()
            if (stable) signingConfig = signingConfigs.getByName("release")
        }
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
        buildConfig = true
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
        // The .so files MUST land on disk at install time.
        //
        // ggml is built with GGML_BACKEND_DL=ON, so libggml-cpu-<isa>.so and
        // libggml-vulkan.so are NOT linked into libneuron_llama.so — ggml
        // finds and dlopen()s them by scanning a DIRECTORY at load time
        // (ggml_backend_load_all_from_path, fed with
        // ApplicationInfo.nativeLibraryDir). With useLegacyPackaging=false the
        // libs stay Stored inside the APK, are mmap'd straight out of it by
        // the linker, and that directory is never created — so the scan finds
        // nothing, ggml_backend_reg_count() stays 0, and every model load dies
        // with "no backends are loaded. hint: use ggml_backend_load() ...".
        // llama.cpp's own Android binding sets android:extractNativeLibs=true
        // for exactly this reason. (Only the .so entry points are affected;
        // the far bigger Vulkan lib compresses well, so the APK gets smaller.)
        jniLibs {
            useLegacyPackaging = true
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

    // Google Identity Services AuthorizationClient: offline access via a
    // server auth code. No google-services.json and no client secret on device.
    implementation(libs.play.services.auth)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
