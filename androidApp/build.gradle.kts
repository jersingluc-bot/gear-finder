plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "gearfinder.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "gearfinder.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources { excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") } }
}

dependencies {
    implementation(project(":ui"))
    implementation(libs.activity.compose)
}
