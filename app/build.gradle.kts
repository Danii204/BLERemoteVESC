import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La clave de firma de las versiones publicadas NO esta en el repositorio.
// Se lee de ~/.gradle/gradle.properties (BLEREMOTEVESC_STORE_FILE, BLEREMOTEVESC_STORE_PASSWORD,
// BLEREMOTEVESC_KEY_ALIAS, BLEREMOTEVESC_KEY_PASSWORD). Sin ella, `assembleRelease` falla y
// `assembleDebug` sigue funcionando con la clave de depuracion.
val firma = Properties().apply {
    val f = File(System.getProperty("user.home"), ".gradle/gradle.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "xyz.danilab.bleremotevesc"
    compileSdk = 34

    defaultConfig {
        applicationId = "xyz.danilab.bleremotevesc"
        minSdk = 26
        targetSdk = 34
        // Mantener alineado con el fichero VERSION y con la etiqueta vX.Y.Z-beta.
        versionCode = 4
        versionName = "0.3.0-beta.4"
    }

    signingConfigs {
        create("release") {
            val ruta = firma.getProperty("BLEREMOTEVESC_STORE_FILE")
            if (ruta != null) {
                storeFile = file(ruta)
                storePassword = firma.getProperty("BLEREMOTEVESC_STORE_PASSWORD")
                keyAlias = firma.getProperty("BLEREMOTEVESC_KEY_ALIAS")
                keyPassword = firma.getProperty("BLEREMOTEVESC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.drawerlayout:drawerlayout:1.1.1")
}
