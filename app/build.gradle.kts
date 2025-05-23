plugins {
    id("com.android.application")
}

android {
    namespace = "com.barak.drivesync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.barak.drivesync"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation(libs.appcompat.v161)
    implementation(libs.material.v1110)
    implementation(libs.constraintlayout)
    implementation(libs.activity)

    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor(libs.compiler)

    implementation(libs.play.services.auth)
    implementation(libs.play.services.base)

    implementation(libs.google.api.client.android)
    implementation("com.google.api-client:google-api-client-gson:1.35.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230815-2.0.0")

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}