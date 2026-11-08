plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bakctv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bakctv.app"
        minSdk = 26          // 안드로이드 8.0 이상
        targetSdk = 34      // 35로 올리면 화면이 상태바 아래까지 늘어나므로 34 유지
        // GitHub Actions 에서 빌드 번호를 받아 자동으로 버전을 올림 (업데이트 설치 가능하도록)
        val buildNo = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionCode = buildNo
        versionName = "1.$buildNo"
    }

    // 서명 키: GitHub Secrets 로 전달됨. 항상 같은 키로 서명해야 덮어쓰기 업데이트가 됨
    val keystoreFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "bakctv"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
