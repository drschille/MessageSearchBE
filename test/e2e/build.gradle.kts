plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(project(":app"))
    implementation(libs.ktorServerTests)
    implementation(libs.testcontainersPostgres)
    testImplementation(kotlin("test"))
}

