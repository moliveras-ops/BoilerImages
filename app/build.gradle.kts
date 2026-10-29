plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cl.iqs.fotosap"
    compileSdk = 34

    defaultConfig {
        applicationId = "cl.iqs.fotosap"
        minSdk = 29          // Android 10 o superior
        targetSdk = 34
        versionCode = 13
        versionName = "2.2"
    }

    // Clave fija: así cada APK nuevo se instala encima del anterior sin desinstalar
    signingConfigs {
        create("fija") {
            storeFile = rootProject.file("keystore/boilerimage.jks")
            storePassword = "boilerimage"
            keyAlias = "boilerimage"
            keyPassword = "boilerimage"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fija") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Reconocimiento de texto sin internet (modelo incluido en la app)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
