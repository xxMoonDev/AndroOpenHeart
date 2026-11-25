import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: put storeFile / storePassword / keyAlias / keyPassword in keystore.properties next to
// settings.gradle.kts (never commit it, nor the keystore). Without it, release builds use the debug key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.rootssh"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.rootssh"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        viewBinding = true
    }
    lint {
        // READ_LOGS / DUMP / WRITE_SECURE_SETTINGS are granted over ADB (pm grant) on purpose
        disable += setOf("ProtectedPermissions", "QueryAllPackagesPermission", "ScopedStorage")
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.md",
                "META-INF/versions/**",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // SSH server
    implementation("org.apache.sshd:sshd-core:2.12.1")
    // Lets clients log in with ed25519 keys.
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.slf4j:slf4j-nop:1.7.36")

    // Shizuku: run non-root sessions as the ADB shell user (uid 2000)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
