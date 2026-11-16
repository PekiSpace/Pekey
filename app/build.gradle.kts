plugins {
    id("com.android.application")
}

/** Verze aplikace: číslo sestavení a název, který se ukazuje v nastavení i v názvu APK. */
val pekeyVersionCode = 109
val pekeyVersionName = "1.09"

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

    androidResources { noCompress += "dict" }
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
// Hotové APK se po assembleDebug zkopíruje do složky apk/ v kořeni projektu,
// kam vidí i správce souborů (vlastní výstupy buildu má CoGo ve svém soukromém úložišti).
val exportApk = tasks.register<Copy>("exportApk") {
    from(layout.buildDirectory.dir("outputs/apk/debug")) { include("*.apk") }
    into(rootProject.layout.projectDirectory.dir("apk"))
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(exportApk) }
