plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kokkoro.clanbattle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kokkoro.clanbattle"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-native"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets["main"].assets.srcDirs("../../assets", "src/main/assets")

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    testImplementation("junit:junit:4.13.2")
    testImplementation(files("libs/pngj-2.1.0.jar"))
}
