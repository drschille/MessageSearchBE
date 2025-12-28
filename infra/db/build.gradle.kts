plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    api(project(":core"))
    api(libs.bundles.exposedAll)
    implementation(libs.hikariCP)
    implementation(libs.flywayCore)
    implementation(libs.flywayPostgres)
    implementation(libs.postgresDriver)
    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxDatetime)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutines)
    testImplementation(libs.testcontainersPostgres)
    testImplementation(libs.testcontainersCore)
    testImplementation(libs.testcontainersJunit)
}
