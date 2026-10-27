import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

// The CI run number is monotonically increasing across builds in this repo.
// Keep the applicationId unchanged so Android sees a regular app update.
val motoVersionCode = System.getenv("MOTO_GPS_VERSION_CODE")
    ?.toIntOrNull()?.takeIf { it > 0 } ?: 2

// For updateable APKs, configure a persistent signing key in GitHub Secrets.
// No private keys or passwords are stored in source control.
val signingStorePath = System.getenv("MOTO_GPS_KEYSTORE_PATH")
val signingStorePassword = System.getenv("MOTO_GPS_KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("MOTO_GPS_KEY_ALIAS")
val signingKeyPassword = System.getenv("MOTO_GPS_KEY_PASSWORD")
val persistentSigning = listOf(
    signingStorePath, signingStorePassword, signingKeyAlias, signingKeyPassword
).all { !it.isNullOrBlank() }
if (!signingStorePath.isNullOrBlank() && !persistentSigning) {
    error("Firma Android incompleta: configurare tutte le credenziali del keystore")
}


android {
    namespace = "com.motogps.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.motogps.android"
        minSdk = 26
        targetSdk = 35
        versionCode = motoVersionCode
        versionName = "1.0.$motoVersionCode"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (persistentSigning) {
            create("motoGpsPersistent") {
                storeFile = file(requireNotNull(signingStorePath))
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Keep using the familiar debug artifact, but sign it with the
            // same private key on every CI run once the secrets are configured.
            if (persistentSigning) {
                signingConfig = signingConfigs.getByName("motoGpsPersistent")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (persistentSigning) {
                signingConfig = signingConfigs.getByName("motoGpsPersistent")
            }
        }
    }

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

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    implementation("org.json:json:20231013")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("io.github.rallista:valhalla-mobile:0.6.4")
    implementation("io.github.rallista:valhalla-models:0.5.2")
    implementation("io.github.rallista:valhalla-models-config:0.5.2")
    implementation("com.stadiamaps:osrm-openapi:0.0.10")
    // Version 1.5.7-12 uses an Android SDK compatible with compileSdk 36.
    // Android AAR includes native Zstandard decoder used by imported .tar.zst.
    implementation("com.github.luben:zstd-jni:1.5.7-12@aar")
    testImplementation("com.github.luben:zstd-jni:1.5.7-12")
}
