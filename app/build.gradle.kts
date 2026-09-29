import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ibytsync.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ibytsync.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.1"
    }

    base {
        archivesName.set("ytsync-for-ibroadcast")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { keystoreProps.load(it) }
    }

    signingConfigs {
        create("release") {
            val keyStorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
                ?: keystoreProps.getProperty("storeFile")
            val keyStorePass: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                ?: keystoreProps.getProperty("storePassword")
            val keyAliasStr: String? = System.getenv("RELEASE_KEY_ALIAS")
                ?: keystoreProps.getProperty("keyAlias")
            val keyPassStr: String? = System.getenv("RELEASE_KEY_PASSWORD")
                ?: keystoreProps.getProperty("keyPassword")

            if (!keyStorePath.isNullOrEmpty() && file(keyStorePath).exists()) {
                storeFile = file(keyStorePath)
                storePassword = keyStorePass
                keyAlias = keyAliasStr
                keyPassword = keyPassStr
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
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
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.material:material-icons-core:1.6.8")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
}
