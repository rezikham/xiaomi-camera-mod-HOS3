plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "io.martin.hyperfilters"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    // Version from CI: -PversionName=1.9.2-19 -PversionCode=4919
    def ciVersionName = project.findProperty("versionName") ?: "1.9.2"
    def ciVersionCode = project.findProperty("versionCode")?.toInteger() ?: 49

    defaultConfig {
        applicationId = "io.rezikham.hyperfilters"
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
}
