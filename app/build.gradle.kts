import java.util.Properties


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.vulcanlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.vulcanlauncher"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.23"
        // On-device gesture check (app/src/androidTest); a plain Instrumentation, no test libraries.
        testInstrumentationRunner = "dev.vulcanlauncher.DeviceCheck"
    }

    // F-Droid rejects AGP's encrypted dependency metadata in the APK signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Release signing comes from keystore.properties (git-ignored, outside the repo's history). Without it,
    // assembleRelease still builds, unsigned.
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keys != null) create("release") {
            storeFile = file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }
    buildTypes {
        // Debug only: the en-XA pseudo-locale shows every translatable string accented, so hardcoded text stands out.
        debug { isPseudoLocalesEnabled = true }
        release {
            if (keys != null) signingConfig = signingConfigs.getByName("release")
            // Release only: the unminified dex carried the whole Kotlin stdlib (~2.5 MB); R8 keeps what's used.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        // Kotlin reflection metadata and build tooling files: nothing reads them without kotlin-reflect.
        resources.excludes += setOf("kotlin/**", "kotlin-tooling-metadata.json")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Internal null-check intrinsics; they mostly vanish under R8 anyway, so don't emit them.
        freeCompilerArgs += listOf("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests; Android supplies its own at runtime
}
