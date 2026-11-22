import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The name shown in "Made by ...". It is the project's author by default. To show a different name on your
// own build, add a line like  credit.name=Your Name  to local.properties (that file is never committed).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val creditName: String = localProps.getProperty("credit.name", "Nayam Amarshe")

android {
    namespace = "com.family.photoframe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.family.photoframe"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "CREDIT_NAME", "\"" + creditName.replace("\"", "") + "\"")
    }
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    lint {
        // targetSdk is 28 on purpose, so the app behaves on old tablets. This check only matters for the Play Store.
        disable += "ExpiredTargetSdkVersion"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
