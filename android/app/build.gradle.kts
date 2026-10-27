plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jacob.bluey"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jacob.bluey"
        minSdk = 26
        // 35 (Android 15). Targeting 37+ turns on Android 17's local-network permission;
        // the USB link uses localhost, but revisit this when Wi-Fi pairing comes back.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    // QR scanning through Google Play services: no camera permission needed in this app.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is stubbed in local unit tests; use the real one there.
    testImplementation("org.json:json:20240303")
}
