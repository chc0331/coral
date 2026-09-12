plugins {
    id("com.android.application")
}

android {
    namespace = "com.korailauto.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.korailauto.reader"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
