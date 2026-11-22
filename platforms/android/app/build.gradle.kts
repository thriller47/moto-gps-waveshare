plugins {
    id("com.android.application")
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
    // zstd-jni >=1.5.7-13 requires Android API 37 for AAR metadata.
    compileSdk = 37
    // NDK r28+ links 16 KiB-compatible ELF LOAD segments by default.
    ndkVersion = "29.0.14206865"

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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// AGP 9 built-in Kotlin inherits Java 17 from android.compileOptions.

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
    // Newer upstream prebuilt wrapper (NDK 29 build); must pass APK ELF audit.
    implementation("io.github.rallista:valhalla-mobile:0.6.6")
    implementation("io.github.rallista:valhalla-models:0.5.2")
    implementation("io.github.rallista:valhalla-models-config:0.5.2")
    implementation("com.stadiamaps:osrm-openapi:0.0.10")
    // 1.5.7-13 upgrades the Android AAR build to NDK 29. Older -12 emits
    // Android 16 KiB page-size compatibility warnings on ARM64.
    // Keep Android and JVM test versions in sync.
    implementation("com.github.luben:zstd-jni:1.5.7-13@aar")
    testImplementation("com.github.luben:zstd-jni:1.5.7-13")
}
