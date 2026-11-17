plugins {
    id("com.android.application")
}

/** Verze aplikace: číslo sestavení a název, který se ukazuje v nastavení i v názvu APK. */
val pekeyVersionCode = 112
val pekeyVersionName = "1.12"

// APK vznikne jako Pekey-keyboard-<verze>-debug.apk
the<BasePluginExtension>().archivesName.set("Pekey-keyboard-$pekeyVersionName")

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
