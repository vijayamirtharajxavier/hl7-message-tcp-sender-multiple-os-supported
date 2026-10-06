plugins {
    application
}

description = "Command-line interface: send, validate and run the test listener without a UI."

application {
    mainClass.set("io.hl7sender.cli.Hl7SendCli")
    applicationName = "hl7send"
}

dependencies {
    implementation(project(":core"))
    implementation(libs.picocli)
    implementation(libs.jackson.databind)
    runtimeOnly(libs.logback.classic)
}

distributions {
    main {
        contents {
            from(rootProject.file("LICENSE"))
        }
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.test {
    // Tests must not prompt for (or write to) the real OS keychain.
    systemProperty("hl7sender.secrets", "file")
}
