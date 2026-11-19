import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables so no key or password is ever in the repo.
val ksPath: String? = System.getenv("CHARLIE_KEYSTORE")
val ksPass: String? = System.getenv("CHARLIE_KEYSTORE_PASS")

android {
    namespace = "com.todd.charlie"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.todd.charlie"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            if (ksPath != null && ksPass != null) {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = "charlie"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksPath != null && ksPass != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.environment("RELAY_TEST_URL", System.getenv("RELAY_TEST_URL") ?: "")
            it.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
