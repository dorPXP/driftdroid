plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.driftdroid.android"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.driftdroid.android"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags += "-std=c++20"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.28.3"
        }
    }

    sourceSets {
        getByName("main") {
            // cacert.pem: the TLS root bundle network_ssl.cpp needs; same file desktop builds copy
            // next to the executable (runtime/cmake/PublicProducts.cmake).
            assets.srcDir("../../runtime/assets/certs")
            // initial_pipeline_cache.db: shader recipes imported on first launch
            // (aurora-main/lib/gfx/pipeline_cache.cpp).
            assets.srcDir("../../runtime/assets/pipeline")
        }
    }

    androidResources {
        // SQLite reads the pipeline seed with random access through SDL's asset stream.
        noCompress += "db"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so release-type builds can be installed and measured
            // directly. Android only enables CheckJNI (and its per-call class/field validation,
            // ~10% of the UI thread in a debug profile) for debuggable builds, so performance
            // testing has to happen here, not on the debug variant. Replace this with a real
            // release keystore when one exists - see the release notes in the README.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
