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
        // versionCode keeps counting from the MountBehave era (last release was 7): the
        // applicationId is unchanged, so a lower code would break upgrades for testers.
        versionCode = 8
        versionName = "0.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "bin"
    }
}
