plugins {
    alias(libs.plugins.android.application)
    // No org.jetbrains.kotlin.android here: AGP 9's built-in Kotlin supersedes it.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.procamera.recorder"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.procamera.recorder"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64-v8a is the sole shipping ABI: minSdk 29 has no meaningful 32-bit-only
        // device population left, so armeabi-v7a is excluded per spec §4.8.
        // x86_64 is intentionally NOT included here; it is only needed for emulator-based
        // instrumented tests, which are built as a separate debug-only concern (see
        // docs/ARCHITECTURE.md 前提・判断ログ) rather than shipped in the release ABI set.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fno-exceptions")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            externalNativeBuild {
                cmake {
                    // ASan/UBSan are debug-only per spec §4.6; see cpp/CMakeLists.txt
                    // for the actual sanitizer flag wiring (CMAKE_BUILD_TYPE=Debug gate).
                    //
                    // Temporarily OFF for on-device debug APKs: ASan-instrumented .so
                    // dlopen()-fails on a real device (UnsatisfiedLinkError: libclang_rt
                    // .asan-aarch64-android.so not found) without a wrap.sh that packages
                    // the sanitizer runtime and LD_PRELOADs it — see docs/ARCHITECTURE.md's
                    // judgment log. Host GTest builds (app/src/main/cpp/test/CMakeLists.txt)
                    // remain ASan/UBSan-instrumented and unaffected by this. Re-enable once
                    // Phase4/5's wrap.sh task lands.
                    arguments += listOf("-DPROCAMERA_ENABLE_SANITIZERS=OFF")
                }
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DPROCAMERA_ENABLE_SANITIZERS=OFF")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        prefab = true // required to consume Oboe's prefab (CMake find_package) package
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.oboe)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling.debug)
}
