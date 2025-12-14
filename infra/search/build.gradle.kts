plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":infra:db"))
    implementation(project(":infra:ai"))
    implementation(libs.kotlinxSerialization)
    implementation(libs.exposedJdbc)
    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainersPostgres)
    testImplementation(libs.testcontainersJunit)
}
