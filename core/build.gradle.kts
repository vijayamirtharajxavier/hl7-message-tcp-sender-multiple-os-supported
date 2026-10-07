plugins {
    `java-library`
    `java-test-fixtures`
}

description = "HL7 v2 parsing, MLLP transport, ACK handling, durable delivery queue and the test listener. No UI dependencies."

dependencies {
    api(libs.hapi.base)
    runtimeOnly(libs.bundles.hapi.structures)
    api(libs.slf4j.api)
    implementation(libs.jackson.databind)
    implementation(libs.sqlite.jdbc)
    implementation(libs.java.keyring)
    implementation(libs.angus.mail)
    implementation(libs.rhino)
    implementation(libs.postgresql)

    testRuntimeOnly(libs.logback.classic)
}

tasks.processResources {
    val appVersion = project.version.toString()
    inputs.property("version", appVersion)
    filesMatching("**/version.properties") {
        expand("version" to appVersion)
    }
}

// Load tests (e.g. 10,000 messages) tagged "slow"; excluded from `test`, run in CI on Linux only.
val slowTest = tasks.register<Test>("slowTest") {
    description = "Runs long-running load tests tagged 'slow'."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("slow")
    }
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
