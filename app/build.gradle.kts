plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Payload produced by scripts/build-payload.sh (native executables, rootfs, loader).
// Kept outside the source tree; see PLAN.md.
val payloadDir = rootProject.layout.projectDirectory.dir("build/payload")

android {
    namespace = "ro.cobrabm.fexdroid"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "ro.cobrabm.fexdroid"
        minSdk = 31
        versionCode = 1
        versionName = "0.1.0-phase0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }

    // Two flavors so the recon screen can measure what each targetSdk allows (PLAN.md D2).
    // legacy = targetSdk 28: exec/mmap(PROT_EXEC) from app data allowed (Termux/Winlator style).
    //          Owns the canonical package name: the rootfs/glibc are built for
    //          /data/data/ro.cobrabm.fexdroid/files/rootfs (scripts/config.sh).
    // modern = targetSdk 36: W^X enforced for app data.
    flavorDimensions += "target"
    productFlavors {
        create("legacy") {
            dimension = "target"
            targetSdk = 28
            versionNameSuffix = "-legacy"
        }
        create("modern") {
            dimension = "target"
            targetSdk = 36
            applicationIdSuffix = ".modern"
            versionNameSuffix = "-modern"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    sourceSets["main"].jniLibs.directories += payloadDir.dir("jniLibs").asFile.path
    sourceSets["main"].assets.directories += payloadDir.dir("assets").asFile.path

    // Executables shipped as lib*.so must be extracted to nativeLibraryDir to be exec'able.
    packaging { jniLibs { useLegacyPackaging = true } }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
}
