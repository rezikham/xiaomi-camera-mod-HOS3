plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "io.martin.hypercameramod"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    // Version from CI: -PversionName=1.0.0 -PversionCode=100 (first run)
    // Stable: versionName=1.0.0, versionCode=100+run_number
    // CI: versionName=1.0.0-<versionCode>, versionCode=100+run_number
    val ciVersionName = project.findProperty("versionName")?.toString() ?: "1.0.0"
    val ciVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: 100

    defaultConfig {
        applicationId = "io.rezikham.hypercameramod"
        minSdk = 26
        targetSdk = 37
        versionCode = ciVersionCode
        versionName = ciVersionName
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
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
