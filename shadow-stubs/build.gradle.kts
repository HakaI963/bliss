plugins {
    id("com.android.library")
}

android {
    namespace = "com.shadowbliss.stubs"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
