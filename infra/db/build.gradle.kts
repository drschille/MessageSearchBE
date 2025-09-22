plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.bundles.exposedAll)
    implementation(libs.hikariCP)
    implementation(libs.flywayCore)
    implementation(libs.postgresDriver)
    implementation(libs.kotlinxSerialization)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutines)
    testImplementation(libs.testcontainersPostgres)
    testImplementation(libs.testcontainersCore)
    testImplementation(libs.testcontainersJunit)
}
