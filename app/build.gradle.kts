plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.damhee.trigger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.damhee.trigger"
        minSdk = 26
        targetSdk = 34
        // GitHub에서 빌드할 때마다 번호가 올라가서, 새 버전이 기존 앱 위에 업데이트로 설치됨
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("mine") {
            storeFile = rootProject.file("trigger.keystore")
            storePassword = "trigger123"
            keyAlias = "trigger"
            keyPassword = "trigger123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("mine")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("mine")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
