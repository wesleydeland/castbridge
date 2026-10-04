plugins {
    id("com.android.application")
}

android {
    namespace = "com.castbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.castbridge"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "1.0.2"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DAIRPLAY_BUILD_EXAMPLE=OFF",
                    "-DAIRPLAY_BUILD_TESTS=OFF"
                )
                // Reproducible builds: keep absolute build paths out of the .so,
                // and let the linker produce deterministic output.
                cFlags += listOf(
                    "-ffile-prefix-map=${rootDir}=.",
                    "-fdebug-prefix-map=${rootDir}=."
                )
                cppFlags += listOf(
                    "-ffile-prefix-map=${rootDir}=.",
                    "-fdebug-prefix-map=${rootDir}=."
                )
                targets.add("native_sender")
            }
        }
    }

    // NDK r27 — adjust if sdkmanager installs a different revision.
    ndkVersion = "27.2.12479018"

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

    signingConfigs {
        // Release signing comes from machine-level gradle properties
        // (RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS /
        // RELEASE_KEY_PASSWORD — see README.md). Absent = debug-signed, which is
        // also what reproducible-builders (e.g. F-Droid) want: an unsigned APK.
        val ks = project.findProperty("RELEASE_STORE_FILE") as String?
        if (ks != null) {
            create("release") {
                storeFile = file(ks)
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // F-Droid's source scanner rejects APKs carrying Google's
            // "dependency metadata" signing block (added by AGP 4.2+), which
            // would otherwise block reproducible builds. No runtime effect.
            dependenciesInfo {
                includeInApk = false
                includeInBundle = false
            }
            if (project.findProperty("RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
