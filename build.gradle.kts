plugins {
    alias(libs.plugins.agp.app)
}

import java.io.FileInputStream
import java.util.Properties

android {
    namespace = "io.martin.hypercameramod"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "27.0.12077973"

    // Read version from version.properties, auto-increment versionCode on each build
    val versionProps = Properties()
    val versionFile = rootProject.file("version.properties")
    if (versionFile.exists()) {
        versionProps.load(FileInputStream(versionFile))
    }
    val baseVersion = versionProps.getProperty("baseVersion", "1.0.0")
    var versionCode = versionProps.getProperty("versionCode", "100").toInt()

    // Allow CI override via -PversionCode
    val ciVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull()
    if (ciVersionCode != null) {
        versionCode = ciVersionCode
    }

    // Increment versionCode for next build (local builds only, not CI)
    val isCi = project.findProperty("CI") != null || System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
    if (!isCi) {
        versionCode++
        versionProps.setProperty("versionCode", versionCode.toString())
        versionProps.store(versionFile.outputStream(), "Auto-incremented by Gradle")
    }

    val computedVersionName = "$baseVersion-$versionCode"

    defaultConfig {
        applicationId = "io.rezikham.hypercameramod"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCode
        versionName = computedVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs["debug"]
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
