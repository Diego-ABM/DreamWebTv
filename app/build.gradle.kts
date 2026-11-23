plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.dreamweb"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.dreamweb"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            // R8 code shrinking + resource shrinking en un flag (AGP 9.2.1, R10)
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        // Requerido por Robolectric para resolver recursos en tests JVM (R12)
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.hls)
    implementation(libs.androidx.media3.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // SQLite real en JVM para los tests de managers (FR-016, R12)
    testImplementation(libs.robolectric)
}
