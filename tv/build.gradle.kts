import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The television app is released from the same commit, and signed with the same
// key, as the phone app. A machine with neither the environment variables nor
// keystore.properties still builds; it just produces an unsigned release APK.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFile: String? = signingValue("KEYSTORE_FILE", "storeFile")
val releaseStorePassword: String? = signingValue("KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias: String? = signingValue("KEY_ALIAS", "keyAlias")
val releaseKeyPassword: String? = signingValue("KEY_PASSWORD", "keyPassword")
val hasReleaseKeystore: Boolean = releaseStoreFile != null && file(releaseStoreFile).exists()

android {
    namespace = "nz.mckenzie.frigo.tv"
    compileSdk = 36

    defaultConfig {
        // Its own id, so the TV app and the phone app sit on neighbouring shelves
        // and either can be updated without the other.
        applicationId = "nz.mckenzie.frigo.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // The pairing code is logged in a debug build, which is how the pairing
        // is tried from a computer without a phone in the room.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.runtime.ktx)
    debugImplementation(libs.compose.tooling)

    implementation(libs.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // The big picture is the camera's own H.264, played as it arrives.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // The pairing code is drawn here, and read on the phone.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
