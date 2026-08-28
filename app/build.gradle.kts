plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nonosky.s2000dash"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nonosky.s2000dash"
        // El radio corre Android 11 (API 30). minSdk 21 deja margen por si
        // el tablero acaba en otro head unit mas viejo.
        minSdk = 21
        targetSdk = 34
        versionCode = 79
        versionName = "7.5"
    }

    buildTypes {
        release {
            // Sin ofuscar: el APK se instala a mano y la reflexion del
            // fallback RFCOMM no vale la pena arriesgarla por unos KB.
            isMinifyEnabled = false
            // Firma de debug tambien en release: no hay Play Store de por
            // medio, el APK se copia al radio y se instala a mano.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // No hay codigo nativo, asi que el APK sirve para cualquier ABI —
    // incluido el armeabi-v7a de 32 bits del rk3326 del radio.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
