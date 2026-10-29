plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Lee google-services.json y genera la configuración de Firebase.
    alias(libs.plugins.google.services)
}

android {
    namespace = "ar.edu.utn.frsfco.finanzas"
    compileSdk = 34

    defaultConfig {
        applicationId = "ar.edu.utn.frsfco.finanzas"
        applicationIdSuffix = ".v4"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // El org.json de las pruebas locales es un molde vacío; este es el real.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.recyclerview)
    implementation(libs.coroutines.play.services)

    // Firebase. La lista de materiales evita declarar la versión de cada biblioteca.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    // Analytics no se invoca desde el código: alcanza con incluirlo para que
    // registre aperturas y sesiones, que es la parte de "medir" del trabajo.
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.config)

    // TP7: lectura de tickets. El modelo de texto va dentro de la app y corre en el teléfono.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Entrada con Google
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.auth)
    implementation(libs.googleid)
}
