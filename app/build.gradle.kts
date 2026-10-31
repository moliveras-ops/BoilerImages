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
        versionCode = 28
        versionName = "3.7"
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
    // Realidad aumentada para medir distancias y áreas
    implementation("com.google.ar:core:1.44.0")
    // Cámara propia (linterna, lentes, ráfaga, HDR/Noche si el teléfono lo ofrece)
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-extensions:$camerax")
}
