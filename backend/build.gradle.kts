plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    application
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    // Project "app" depends on project "utils". (Project paths are separated with ":", so ":utils" refers to the top-level "utils" project.)
    implementation(project(":core"))
    implementation(project(":infra:db"))
    implementation(project(":infra:ai"))
    implementation(project(":infra:search"))
    implementation(libs.bundles.ktorServer)
    implementation(libs.logbackClassic)
    implementation(libs.hikariCP)
    implementation(libs.flywayCore)
    implementation(libs.postgresDriver)
    implementation(libs.kotlinxSerialization)
    implementation(libs.micrometerRegistryPrometheus)
    implementation(libs.snakeYaml)
    implementation(libs.javaJwt)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktorServerTests)
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "org.themessagesearch.app.ServerKt"
}

tasks.register<Exec>("issueJwt") {
    group = "application"
    description = "Issue a local JWT for development."
    commandLine("python3", "${rootDir}/scripts/issue-jwt.py")
}
