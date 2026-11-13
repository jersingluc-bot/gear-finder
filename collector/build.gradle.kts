import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
application { mainClass = "gearfinder.collector.MainKt"; applicationDefaultJvmArgs = listOf("-Xmx5g") }
dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
tasks.test { useJUnitPlatform() }
