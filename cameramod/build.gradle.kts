plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "io.martin.hypercameramod"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    // Version from CI: -PversionName=1.0.9-19 -PversionCode=1119
    val ciVersionName = project.findProperty("versionName")?.toString() ?: "1.0.9"
    val ciVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: 11

    defaultConfig {
        applicationId = "io.martin.hypercameramod"
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
