import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.mp)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm("desktop") { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    androidLibrary {
        namespace = "gearfinder.ui"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }
    sourceSets {
        // Mac and Android both run on the JVM, so the whole UI lives in one folder compiled for both.
        val shared = "src/shared/kotlin"
        val uiDeps: org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler.() -> Unit = {
            api(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.coil.compose)
            implementation(libs.coil.okhttp)
        }
        named("desktopMain") {
            kotlin.srcDir(shared)
            dependencies { uiDeps(); implementation(compose.desktop.currentOs); implementation(libs.coroutines.swing) }
        }
        named("androidMain") {
            kotlin.srcDir(shared)
            dependencies { uiDeps() }
        }
    }
}

compose.desktop {
    application {
        mainClass = "gearfinder.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Gear Finder"
            packageVersion = "1.0.0"
            macOS { bundleID = "gearfinder.app" }
        }
    }
}
