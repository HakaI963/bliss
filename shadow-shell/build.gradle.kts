plugins {
    id("com.android.application")
}

android {
    namespace = "com.silentauto.shell"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.silentauto.shell"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(project(":shadow-stubs"))
    implementation("com.google.code.gson:gson:2.11.0")
}
