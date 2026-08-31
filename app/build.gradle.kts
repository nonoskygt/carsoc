plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nonosky.s2000dash"
    compileSdk = 34

    defaultConfig {
        // El applicationId lo pone cada sabor. Ver `productFlavors`.
        // El radio corre Android 11 (API 30). minSdk 21 deja margen por si
        // el tablero acaba en otro head unit mas viejo.
        minSdk = 21
        targetSdk = 34
        versionCode = 200
        versionName = "1.0"
    }

    /**
     * DOS CARROS, UNA BASE. Los sabores son hermanos, no forks.
     *
     * Comparten TODO menos cuatro cosas, que viven en `src/<sabor>/`:
     *   EngineConstants.kt   los numeros del motor (F20C vs K24A4)
     *   PerfilVehiculo.kt    quien es el carro y que hardware lleva
     *   res/raw/dtc.txt      la tabla de averias de ESE motor
     *   res/values/strings   el nombre de la app
     *
     * ⚠️ applicationId DISTINTO y OBLIGATORIO. Con el mismo, el actualizador
     * de un carro se traga el APK del otro, lo rechaza por paquete, y se
     * queda sin actualizar para siempre sin que nadie se entere. Ya estaba
     * apuntado como trampa antes de que existiera el segundo carro.
     */
    flavorDimensions += "carro"

    productFlavors {
        create("element") {
            dimension = "carro"
            applicationId = "com.nonosky.inmyelement"
            // El token del descubrimiento UDP tambien va por carro, por la
            // misma razon que el applicationId.
            buildConfigField("String", "TOKEN_DESCUBRIMIENTO", "\"INMYELEMENT\"")
            buildConfigField("String", "CARRO", "\"element\"")
            versionNameSuffix = "-element"
        }
        create("s2000") {
            dimension = "carro"
            applicationId = "com.nonosky.s2000dash"
            buildConfigField("String", "TOKEN_DESCUBRIMIENTO", "\"S2000DASH\"")
            buildConfigField("String", "CARRO", "\"s2000\"")
            versionNameSuffix = "-s2000"
        }
    }

    buildFeatures {
        buildConfig = true
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
        getByName("element").java.srcDirs("src/element/kotlin")
        getByName("s2000").java.srcDirs("src/s2000/kotlin")
        // Las pruebas que afirman cifras de UN motor viven con su sabor.
        getByName("testElement").java.srcDirs("src/testElement/kotlin")
        getByName("testS2000").java.srcDirs("src/testS2000/kotlin")
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
