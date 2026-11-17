plugins {
    id("com.android.application")
}

/** Verze aplikace: číslo sestavení a název, který se ukazuje v nastavení i v názvu APK. */
val pekeyVersionCode = 134
val pekeyVersionName = "1.34"

// APK vznikne jako Pekey-keyboard-<verze>-debug.apk
the<BasePluginExtension>().archivesName.set("Pekey-$pekeyVersionName")

android {
    namespace = "cz.pekey"
    compileSdk = 36

    defaultConfig {
        buildConfigField("String", "SPACE_LABEL", "\"Pekey\"")
        buildConfigField("boolean", "LANGUAGE_SWITCH", "true")
        applicationId = "cz.pekey"
        minSdk = 26
        targetSdk = 36
        versionCode = pekeyVersionCode
        versionName = pekeyVersionName
        ndk { abiFilters += "arm64-v8a" }
    }

    // Stejný ladicí klíč všude (CoGo i GitHub), aby se nové verze instalovaly přes staré.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/pekey-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures { buildConfig = true }

    androidResources { noCompress += "dict" }

    // Nativní knihovny (návrhy slov z AOSP LatinIME, hlas z whisper.cpp) sestavuje build na GitHubu.
    // Build v CoGo bez NDK projde taky, jen bez návrhů a hlasu (gesta fungují).
    if (System.getenv("CI") == "true" || file("src/main/cpp/whisper.cpp/CMakeLists.txt").exists()) {
        // na GitHubu NDK, které už je na stroji (bez stahování)
        System.getenv("PEKEY_NDK")?.takeIf { it.isNotBlank() }?.let { ndkVersion = it }
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
        defaultConfig {
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
                    cppFlags += "-O3"
                }
            }
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }

    // Do APK nevkládat zašifrovaný seznam závislostí určený pro Google.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
