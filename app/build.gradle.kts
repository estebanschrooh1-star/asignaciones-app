plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.estebangomez.asignaciones"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.estebangomez.asignaciones"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.5.0"
    }

    // Clave fija: permite instalar cada versión nueva encima de la anterior sin perder datos.
    signingConfigs {
        getByName("debug") {
            storeFile = file("asignaciones.keystore")
            storePassword = "asignaciones"
            keyAlias = "asignaciones"
            keyPassword = "asignaciones"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
