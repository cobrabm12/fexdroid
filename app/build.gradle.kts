plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Payload produced by scripts/build-payload.sh (native executables, rootfs, loader).
// Kept outside the source tree; see PLAN.md.
val payloadDir = rootProject.layout.projectDirectory.dir("build/payload")

// The commit this APK is built from: the in-app updater compares it with the published build's.
val gitSha: String = providers.environmentVariable("GITHUB_SHA").orElse(
    providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }
).get()

// Version: MAJOR.MINOR say where the project is (0.x: for testers; 1.0: for everyone), the
// third number is the count of commits, so every published build has a higher one than the
// builds before it. It is the versionCode too: Android refuses to install a lower one.
val versionBase = "0.3"
val versionStage = "alpha"
val commitCount: Int = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.map { it.trim().toInt() }.get()
// A shallow clone counts one commit: the build would be refused as a downgrade on every phone.
require(commitCount >= 70) { "git history is incomplete ($commitCount commits): fetch it all (fetch-depth: 0)" }

android {
    namespace = "ro.cobrabm.fexdroid"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "ro.cobrabm.fexdroid"
        // 28 (Android 9) so more phones can at least run the compatibility check; the
        // Linux environment itself is verified on Android 14 (NOTES.md N-021).
        minSdk = 28
        versionCode = commitCount
        versionName = "$versionBase.$commitCount-$versionStage"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
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

    // Published builds (.github/workflows/full-apk.yml) are signed with the project's own key,
    // the same on every run: Android installs an update only over an app signed with the same
    // key. Without FEXDROID_KEYSTORE (a developer's PC, a fork) the debug key of that machine.
    val publishKeystore = providers.environmentVariable("FEXDROID_KEYSTORE").orNull
    val publishPassword = providers.environmentVariable("FEXDROID_KEYSTORE_PASSWORD").orNull
    signingConfigs {
        if (publishKeystore != null && publishPassword != null) {
            create("published") {
                storeFile = file(publishKeystore)
                storePassword = publishPassword
                keyAlias = "fexdroid"
                keyPassword = publishPassword
            }
        }
    }
    val signing = signingConfigs.findByName("published") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug { signingConfig = signing }
        release {
            isMinifyEnabled = false
            signingConfig = signing
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    sourceSets["main"].jniLibs.directories += payloadDir.dir("jniLibs").asFile.path
    sourceSets["main"].assets.directories += payloadDir.dir("assets").asFile.path

    // Executables shipped as lib*.so must be extracted to nativeLibraryDir to be exec'able.
    packaging { jniLibs { useLegacyPackaging = true } }

    buildFeatures { compose = true; buildConfig = true }
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

// For the release's description (.github/workflows/full-apk.yml).
tasks.register("printVersion") {
    val name = "$versionBase.$commitCount-$versionStage"
    doLast { println(name) }
}
