plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val relayAccessToken = localProperties.getProperty("relay.access.token").orEmpty()
val relayAccessTokenLiteral = relayAccessToken
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "\\r")
    .replace("\n", "\\n")

// Opt-in simplified UI for a one-off personal build: hides the relay address
// and access token fields so the values injected through local.properties are
// never displayed. Enabled with -PrelaySimpleUi=true; default builds are unchanged.
val relaySimpleUi = (project.findProperty("relaySimpleUi") as String?)?.trim()?.toBoolean() ?: false
// Optional build-time default relay address. Empty (the default) means a plain
// build prefills nothing, so no server address is baked into the APK.
val relayBaseUrl = localProperties.getProperty("relay.baseUrl").orEmpty()
val relayBaseUrlLiteral = relayBaseUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "\\r")
    .replace("\n", "\\n")


if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.nogirelay.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nogirelay.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "0.6"

        buildConfigField("String", "DEFAULT_RELAY_URL", "\"$relayBaseUrlLiteral\"")
        buildConfigField("String", "RELAY_ACCESS_TOKEN", "\"$relayAccessTokenLiteral\"")
        buildConfigField("boolean", "SIMPLE_UI", relaySimpleUi.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
