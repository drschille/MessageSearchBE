plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktorClientCore)
    implementation(libs.ktorClientApache)
    implementation(libs.kotlinxSerialization)
    testImplementation(kotlin("test"))
}

