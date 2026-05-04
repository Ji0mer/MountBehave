plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.onstepcontroller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.onstepcontroller"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.5"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
