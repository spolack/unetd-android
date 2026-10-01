plugins {
    alias(libs.plugins.android.application)
    // No kotlin-android plugin: AGP 9 compiles Kotlin itself, and applying it is an error.
    alias(libs.plugins.kotlin.compose)
}

// Kept in gradle.properties because it is load-bearing beyond naming: wireguard-go
// bakes its UAPI socket directory in at link time as /data/data/<pkg>/cache/wireguard,
// and unetd must be built with a matching RUNSTATEDIR for wg-user.c to find it.
val unetdPackageName: String = providers.gradleProperty("unetd.packageName").get()

android {
    namespace = unetdPackageName
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    // Latest LTS NDK (CLAUDE.md: always the latest stable). AGP downloads it on
    // demand when it is missing from the SDK.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = unetdPackageName
        // 26 gives us VpnService.Builder.setMetered() and foreground service types.
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0-dev"

        ndk {
            // Go (libwg-go) and the NDK agree on these three; x86 is dropped as
            // no current device ships it.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // AGP only builds the targets it is told about; libwg-go is a
                // custom target (Go), so it has to be listed to be built at all.
                targets += listOf("unet-android", "libwg-go")
                // libwg-go is cross-compiled by Go from inside the CMake build;
                // point it at a specific Go if `go` is not the one on PATH.
                arguments += "-DGO_EXECUTABLE=${System.getenv("GO_EXECUTABLE") ?: "go"}"
                // 16 KiB page sizes: NDK r28+ aligns for this by default; be explicit.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    // libubox + json-c + unetd + unet-dht + the JNI facade -> libunet-android.so,
    // and wireguard-go + its JNI glue -> libwg-go.so. See native/CMakeLists.txt.
    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Left unsigned: CI publishes the debug APK for now.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
