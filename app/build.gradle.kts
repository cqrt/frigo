import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from environment variables (CI) or a local, gitignored
// keystore.properties. A machine with neither still builds; it just produces an
// unsigned release APK.
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
    namespace = "nz.mckenzie.frigo"
    compileSdk = 36

    defaultConfig {
        applicationId = "nz.mckenzie.frigo"
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // The tests read the app's own strings, so they need the resources
            // built the way the app has them.
            isIncludeAndroidResources = true
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
    // material3 does not pull the icon set in, and the bottom bar needs three.
    implementation(libs.compose.icons.core)
    implementation(libs.compose.activity)
    implementation(libs.compose.navigation)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.tooling)

    implementation(libs.datastore.preferences)

    // The app's own language, on phones old and new.
    implementation(libs.androidx.appcompat)

    // The lock's prompt, which asks for a fingerprint and a PIN on every version
    // the app runs on.
    implementation(libs.androidx.biometric)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
}
