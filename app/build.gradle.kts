plugins {
    id("com.android.application")
}

android {
    namespace = "cz.pekey"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.pekey"
        minSdk = 26
        targetSdk = 36
        versionCode = 109
        versionName = "1.09"
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