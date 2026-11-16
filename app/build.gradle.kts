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
val apkSrc = layout.buildDirectory.dir("outputs/apk/debug")
val apkOut = rootProject.layout.projectDirectory.dir("apk").asFile
println("Pekey $pekeyVersionName: APK se po buildu zkopíruje do $apkOut")

afterEvaluate {
    tasks.named("assembleDebug") {
        doLast {
            val src = apkSrc.get().asFile
            val apks = src.listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
            if (apks.isEmpty()) {
                println("Pekey: v $src není žádné APK (obsah: ${src.list()?.joinToString().orEmpty()})")
            }
            apkOut.mkdirs()
            for (apk in apks) {
                apk.copyTo(File(apkOut, apk.name), overwrite = true)
                println("Pekey: ${apk.name} → $apkOut")
            }
        }
    }
}
