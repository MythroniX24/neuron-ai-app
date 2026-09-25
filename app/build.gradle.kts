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
    }

    // Release signing is driven entirely by CI secrets/env — the keystore
    // NEVER lives in the repository. Locally or without secrets the release
    // build simply falls back to unsigned.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("NEURON_KEYSTORE_PATH")
            val ksPassword = System.getenv("NEURON_KEYSTORE_PASSWORD")
            val alias = System.getenv("NEURON_KEY_ALIAS")
            val keyPassword = System.getenv("NEURON_KEY_PASSWORD")
            val configured = !ksPath.isNullOrEmpty() && java.io.File(ksPath).exists() &&
                !ksPassword.isNullOrEmpty() && !alias.isNullOrEmpty() &&
                !keyPassword.isNullOrEmpty()
            if (configured) {
                storeFile = java.io.File(ksPath)
                storePassword = ksPassword
                keyAlias = alias
                keyPassword = keyPassword
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
            val configured = !ksPath.isNullOrEmpty() && java.io.File(ksPath).exists()
            if (configured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
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
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
