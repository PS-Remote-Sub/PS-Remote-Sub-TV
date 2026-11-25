import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: keystore.properties at the repo root (gitignored), else PSRS_TV_KEYSTORE* env vars, else unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)

android {
    namespace = "th.psrs.tvoverlay"
    compileSdk = 37

    defaultConfig {
        applicationId = "th.psrs.tvoverlay"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"
    }

    val releaseStore = signingValue("storeFile", "PSRS_TV_KEYSTORE")
    val releaseStorePassword = signingValue("storePassword", "PSRS_TV_KEYSTORE_PASSWORD")
    val releaseKeyAlias = signingValue("keyAlias", "PSRS_TV_KEY_ALIAS")
    val releaseKeyPassword = signingValue("keyPassword", "PSRS_TV_KEY_PASSWORD")
    val canSignRelease = releaseStore != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(releaseStore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
