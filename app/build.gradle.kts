plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "io.martin.hyperfilters"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.martin.hyperfilters"
        minSdk = 26
        targetSdk = 37
        versionCode = 48
        versionName = "1.9.1"
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
}
