import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun props(name: String) = Properties().apply {
    val f = rootProject.file(name)
    if (f.exists()) f.inputStream().use { load(it) }
}

val keystoreProps = props("keystore.properties")
// Optional: server settings baked into a private build (secrets.properties is never committed).
// The public build has none; they are entered in the app's Settings.
val secrets = props("secrets.properties")
fun secret(k: String) = "\"" + (secrets.getProperty(k) ?: "").replace("\"", "") + "\""

android {
    namespace = "studio.alans.shade"
    compileSdk = 35

    defaultConfig {
        applicationId = "studio.alans.shade"
        minSdk = 31
        targetSdk = 35
        // A release = bump both and add a RELEASE_NOTES.md line.
        versionCode = 3
        versionName = "0.3.0"
        buildConfigField("String", "SERVER_URL", secret("SERVER_URL"))
        buildConfigField("String", "SERVER_TOKEN", secret("SERVER_TOKEN"))
    }

    signingConfigs {
        create("release") {
            // CI: SHADE_KEYSTORE + SHADE_KEYSTORE_PASSWORD from repo secrets. Locally: keystore.properties.
            val ciStore = System.getenv("SHADE_KEYSTORE")
            if (!ciStore.isNullOrBlank()) {
                storeFile = file(ciStore)
                storePassword = System.getenv("SHADE_KEYSTORE_PASSWORD")
                keyAlias = "shade"
                keyPassword = System.getenv("SHADE_KEYSTORE_PASSWORD")
            } else if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (!System.getenv("SHADE_KEYSTORE").isNullOrBlank() || keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
