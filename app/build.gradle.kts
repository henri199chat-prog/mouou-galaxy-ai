import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: the keystore lives in the private repo henri199chat-prog/app-keys,
// cloned next to this repo (../app-keys/mouou/keystore.properties). Never commit it here.
val keystorePropsFile = file(
    (project.findProperty("mououKeystoreProps") as String?) ?: "${rootDir}/../app-keys/mouou/keystore.properties"
)
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.glteam.teambubbles"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.glteam.teambubbles"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "2.2.9"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                val store = File(keystoreProps.getProperty("storeFile"))
                storeFile = if (store.isAbsolute) store else File(keystorePropsFile.parentFile, store.path)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            resValue("string", "app_name", "Mouou Galaxy AI")
            resValue("string", "screen_service_label", "Mouou Galaxy AI – الشاشة")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        // Test builds install next to the real app (own package and name), so Ayman keeps his working app.
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Mouou تجربة")
            resValue("string", "screen_service_label", "Mouou تجربة – الشاشة")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// A release signed with another key cannot update the installed app: refuse to build one without the real key.
gradle.taskGraph.whenReady {
    if (!keystorePropsFile.exists() && allTasks.any { it.project == project && it.name.contains("Release") }) {
        throw GradleException("Release keystore not found: $keystorePropsFile. Clone app-keys next to this repo. Never sign with a new key.")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json in JVM tests (android.jar only has stubs)
}
