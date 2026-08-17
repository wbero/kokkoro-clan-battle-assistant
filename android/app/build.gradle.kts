import java.io.FileInputStream
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releasePropertiesPath = providers.environmentVariable("KOKKORO_KEYSTORE_PROPERTIES")
    .orElse(providers.gradleProperty("kokkoroKeystoreProperties"))
    .orNull
val releasePropertiesFile = releasePropertiesPath?.let(::file)
val releaseProperties = Properties()
val hasReleaseKeystore = releasePropertiesFile?.isFile == true
val generatedChangelogAssets = layout.buildDirectory.dir("generated/changelogAssets")

if (hasReleaseKeystore) {
    FileInputStream(releasePropertiesFile!!).use(releaseProperties::load)
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
        require(!releaseProperties.getProperty(key).isNullOrBlank()) {
            "Missing `$key` in release keystore properties"
        }
    }
}

android {
    namespace = "com.kokkoro.clanbattle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kokkoro.clanbattle"
        minSdk = 26
        targetSdk = 35
        versionCode = 30103
        versionName = "3.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets["main"].assets.srcDirs("../../assets", "src/main/assets", generatedChangelogAssets)
    sourceSets["androidTest"].assets.srcDir(layout.buildDirectory.dir("generated/ubVideoReplayAssets"))

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                val configuredStore = File(releaseProperties.getProperty("storeFile"))
                storeFile = if (configuredStore.isAbsolute) configuredStore
                    else releasePropertiesFile!!.parentFile.resolve(configuredStore.path)
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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

val syncChangelogAsset by tasks.registering(Copy::class) {
    from(rootProject.file("../CHANGELOG.md"))
    into(generatedChangelogAssets)
}

tasks.named("preBuild").configure {
    dependsOn(syncChangelogAsset)
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        doFirst {
            require(hasReleaseKeystore) {
                "Release signing requires KOKKORO_KEYSTORE_PROPERTIES or -PkokkoroKeystoreProperties"
            }
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation(files("libs/pngj-2.1.0.jar"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
