plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(project(":backend"))
    implementation(libs.ktorServerTests)
    implementation(libs.testcontainersPostgres)
    testImplementation(kotlin("test"))
}
