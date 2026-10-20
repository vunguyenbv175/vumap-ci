plugins {
    alias(libs.plugins.android.application)
    // NOTE: no `kotlin-android` plugin — AGP 9 compiles Kotlin sources built-in.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "vn.vumap"
    compileSdk = 36

    defaultConfig {
        applicationId = "vn.vumap"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-p0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Kotlin jvmTarget defaults to compileOptions.targetCompatibility under AGP built-in
    // Kotlin, so no kotlinOptions/compilerOptions block is needed.
    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            java.directories.add("../feature/signs/src/main/java")
        }
    }
}

dependencies {
    // MapLibre Native map rendering (OpenFreeMap style at runtime; no map/analytics SDK besides this)
    implementation(libs.maplibre.sdk)
    // GPS location provider (on-device; no network use by this dep for location fixes)
    implementation(libs.play.services.location)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    // On-device settings persistence only (no cloud sync)
    implementation(libs.datastore.preferences)
    // HTTP networking for P0 routing providers (OSRM, Valhalla, ORS)
    implementation(libs.okhttp)
    // Plus Codes (Open Location Code) decoding for shared-location search input
    implementation(libs.openlocationcode)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.json.org)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
}
