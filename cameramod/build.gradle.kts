plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "io.martin.hypercameramod"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.rezikham.hypercameramod"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "1.0.9"
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
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
