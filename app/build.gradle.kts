plugins {
    id("com.android.application")
}

/** Verze aplikace: číslo sestavení a název, který se ukazuje v nastavení i v názvu APK. */
val pekeyVersionCode = 124
val pekeyVersionName = "1.24"

// APK vznikne jako Pekey-keyboard-<verze>-debug.apk
the<BasePluginExtension>().archivesName.set("Pekey-$pekeyVersionName")

android {
    namespace = "cz.pekey"
    compileSdk = 36

    defaultConfig {
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

    androidResources { noCompress += "dict" }

    // Hlasové zadávání (whisper.cpp): jen když je stažený zdroj whisper.cpp (build na GitHubu).
    // Build v CoGo bez něj projde, jen bez hlasu.
    if (file("src/main/cpp/whisper.cpp/CMakeLists.txt").exists()) {
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
