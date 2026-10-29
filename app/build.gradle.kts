import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Lee google-services.json y genera la configuración de Firebase.
    alias(libs.plugins.google.services)
}

// Las claves de los servicios de monetización viven en local.properties, que no se versiona.
val propiedadesLocales = Properties().apply {
    val archivo = rootProject.file("local.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}
fun clave(nombre: String, porDefecto: String) = propiedadesLocales.getProperty(nombre, porDefecto)

android {
    namespace = "ar.edu.utn.frsfco.finanzas"
    compileSdk = 34

    defaultConfig {
        applicationId = "ar.edu.utn.frsfco.finanzas"
        applicationIdSuffix = ".v5"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Identificadores de muestra de Google: sirven anuncios de prueba sin cuenta.
        manifestPlaceholders["admobAppId"] = clave("admob.appId", "ca-app-pub-3940256099942544~3347511713")
        buildConfigField("String", "ADMOB_BANNER_ID", "\"" + clave("admob.bannerId", "ca-app-pub-3940256099942544/9214589741") + "\"")
    }

    buildTypes {
        debug {
            // La clave de la tienda de prueba de RevenueCat solo existe en depuración.
            buildConfigField("String", "RC_API_KEY", "\"" + clave("revenuecat.testKey", "") + "\"")
        }
        release {
            buildConfigField("String", "RC_API_KEY", "\"\"")
        }
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
        buildConfig = true
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

    // TP4: anuncios y suscripciones.
    implementation(libs.play.services.ads)
    implementation(libs.revenuecat.purchases)

    // Entrada con Google
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.auth)
    implementation(libs.googleid)
}
