plugins {
    alias(libs.plugins.android.application)
    // No org.jetbrains.kotlin.android here: AGP 9's built-in Kotlin supersedes it.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aucampro.recorder"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.aucampro.recorder"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // 実機で発見(2026-07-20): インストール済みAPKのlastUpdateTimeが、調査中に
        // 参照していた最新コミットの3分前だった — ソースとインストール済みAPKの不一致に
        // 気づくのに手動でのadb dumpsys調査が必要だった。以後この手間を避けるため、
        // ビルド時点のgit状態をAPKへ焼き込む(BuildInfo.logStartup()で起動時に出力)。
        val gitSha = providers.exec {
            commandLine("git", "rev-parse", "--short=12", "HEAD")
        }.standardOutput.asText.get().trim()
        val gitDirty = providers.exec {
            commandLine("git", "status", "--porcelain")
        }.standardOutput.asText.get().isNotBlank()
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
        buildConfigField("long", "BUILD_TIME_MILLIS", "${System.currentTimeMillis()}L")

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
            // x86_64 is added here (debug-only) so the app's native (Oboe/JNI) libs can load
            // on the standard x86_64 emulator system image used for local/CI emulator
            // verification. This matches the judgment log's intent (docs/ARCHITECTURE.md
            // §前提・判断ログ #6: "x86_64はエミュレータ検証時のみ補助的に含める") — release
            // and the base defaultConfig ABI set remain arm64-v8a only.
            ndk {
                abiFilters += "x86_64"
            }
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
                    arguments += listOf("-DAUCAMPRO_ENABLE_SANITIZERS=OFF")
                }
            }
        }
        release {
            isMinifyEnabled = false
            // Debug-signed so this build type is directly `adb install`-able for local
            // on-device perf comparisons (real-device "モッサリ" investigation: debug's
            // isDebuggable=true disables several ART JIT optimizations independent of any
            // app-code fix, so testing whether release actually feels smoother is the
            // fastest way to tell how much of the sluggishness is debug-build overhead vs.
            // genuine SoC thermal throttling). Replace with a real release signingConfig
            // before this build type is ever used for an actual distributable artifact.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DAUCAMPRO_ENABLE_SANITIZERS=OFF")
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
        buildConfig = true
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
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
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
