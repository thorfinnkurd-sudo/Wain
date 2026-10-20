import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Configuration comes from environment variables (CI) or local.properties (local dev).
// Nothing secret is committed. See .env.example and README.md.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun cfg(name: String, default: String = ""): String =
    listOf(System.getenv(name), localProps.getProperty(name))
        .firstOrNull { !it.isNullOrBlank() }?.trim() ?: default

fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.wain.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wain.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MAP_STYLE_URL", q(cfg("WAIN_MAP_STYLE_URL")))
        buildConfigField("String", "MAP_STYLE_DARK_URL", q(cfg("WAIN_MAP_STYLE_DARK_URL")))
        buildConfigField("String", "ROUTING_URL", q(cfg("WAIN_ROUTING_URL")))
        buildConfigField("String", "ROUTING_API_KEY", q(cfg("WAIN_ROUTING_API_KEY")))
        buildConfigField("String", "GEOCODER_URL", q(cfg("WAIN_GEOCODER_URL")))
        buildConfigField("String", "OVERPASS_URL", q(cfg("WAIN_OVERPASS_URL")))
        buildConfigField("String", "SUPABASE_URL", q(cfg("WAIN_SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_ANON_KEY", q(cfg("WAIN_SUPABASE_ANON_KEY")))
        buildConfigField("String", "CONTACT", q(cfg("WAIN_CONTACT", "contact-not-configured")))
    }

    signingConfigs {
        create("release") {
            val path = cfg("WAIN_KEYSTORE_PATH")
            if (path.isNotEmpty()) {
                storeFile = file(path)
                storePassword = cfg("WAIN_KEYSTORE_PASSWORD")
                keyAlias = cfg("WAIN_KEY_ALIAS")
                keyPassword = cfg("WAIN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinking is intentionally off until a release build has been verified on a device.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (cfg("WAIN_KEYSTORE_PATH").isNotEmpty()) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk:11.5.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
