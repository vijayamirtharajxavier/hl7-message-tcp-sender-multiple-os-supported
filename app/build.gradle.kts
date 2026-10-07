plugins {
    application
    alias(libs.plugins.javafx)
}

description = "JavaFX desktop application."

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.controls")
}

application {
    mainClass.set("io.hl7sender.app.Launcher")
    applicationName = "hl7-sender"
}

dependencies {
    implementation(project(":core"))
    implementation(libs.richtextfx)
    // The Logs tab reads log events from an in-memory appender.
    implementation(libs.logback.classic)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.testfx.junit5)
    testImplementation(libs.hamcrest)
    testRuntimeOnly(libs.monocle)
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    // Run UI tests without a display: TestFX + Monocle headless platform.
    systemProperty("testfx.robot", "glass")
    systemProperty("testfx.headless", "true")
    systemProperty("glass.platform", "Monocle")
    systemProperty("monocle.platform", "Headless")
    systemProperty("prism.order", "sw")
    // The first UI test also starts JavaFX and loads the app; on Windows CI runners that can pass TestFX's 30 s limit.
    systemProperty("testfx.setup.timeout", "90000")
    // Tests must not prompt for (or write to) the real OS keychain.
    systemProperty("hl7sender.secrets", "file")
    // Tests check the in-app notification; never start AWT or touch the real system tray.
    systemProperty("hl7sender.noOsNotifications", "true")
}

tasks.test {
    System.getProperty("hl7sender.screenshotDir")?.let { systemProperty("hl7sender.screenshotDir", it) }
}

// ---------------------------------------------------------------------------------------------
// Packaging: a trimmed Java runtime (jlink) plus native installers (jpackage).
//
//   ./gradlew :app:jpackageImage       app folder with the "HL7 Sender" app and the hl7send CLI
//   ./gradlew :app:jpackageInstaller   installer for this OS (-PinstallerType=msi|exe|dmg|pkg|deb|rpm)
//
// macOS signing: set MAC_SIGNING_IDENTITY (and optionally MAC_SIGNING_KEYCHAIN) in the environment.

val cliRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies {
    cliRuntime(project(":cli"))
}

val osName: String = System.getProperty("os.name").lowercase()
val isWindows = osName.contains("win")
val isMac = osName.contains("mac") || osName.contains("darwin")
val packageName = if (isWindows || isMac) "HL7 Sender" else "hl7-sender"
// jpackage needs a plain number (1.2.3-SNAPSHOT -> 1.2.3); macOS also requires the first number to be at least 1.
val numericVersion = project.version.toString().substringBefore('-')
val jpackageDir = layout.buildDirectory.dir("jpackage")
val packagingSrc = layout.projectDirectory.dir("src/packaging")

/** JDK modules the app and CLI need: `jdeps --print-module-deps` plus modules loaded reflectively. */
val runtimeModules = listOf(
    "java.base", "java.desktop", "java.logging", "java.management", "java.naming", "java.net.http",
    "java.security.sasl", "java.sql", "java.xml", "jdk.charsets", "jdk.dynalink", "jdk.crypto.ec", "jdk.httpserver",
    "jdk.jfr", "jdk.localedata", "jdk.net", "jdk.random", "jdk.security.auth", "jdk.unsupported", "jdk.zipfs")

fun jdkTool(name: String): String {
    val home = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }.get().metadata.installationPath.asFile
    return home.resolve("bin").resolve(if (isWindows) "$name.exe" else name).absolutePath
}

val stageJpackageInput by tasks.registering(Sync::class) {
    description = "Collects the app and CLI jars and their dependencies for jpackage."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    from(cliRuntime)
    // MIT: the license travels with every copy (it ends up in the app folder of each installer).
    from(rootProject.file("LICENSE"))
    into(jpackageDir.map { it.dir("input") })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val jlinkRuntime by tasks.registering(Exec::class) {
    description = "Builds a trimmed Java runtime with only the modules the app needs."
    val out = jpackageDir.get().dir("runtime").asFile
    inputs.property("modules", runtimeModules)
    outputs.dir(out)
    doFirst { delete(out) }
    executable = jdkTool("jlink")
    args("--add-modules", runtimeModules.joinToString(","), "--include-locales=en,es",
        "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
        "--output", out.absolutePath)
}

fun macSigningArgs(): List<String> {
    val identity = System.getenv("MAC_SIGNING_IDENTITY")
    if (!isMac || identity.isNullOrBlank()) return emptyList()
    val args = mutableListOf("--mac-sign", "--mac-signing-key-user-name", identity)
    System.getenv("MAC_SIGNING_KEYCHAIN")?.takeIf { it.isNotBlank() }
        ?.let { args += listOf("--mac-signing-keychain", it) }
    return args
}

val jpackageImage by tasks.registering(Exec::class) {
    description = "Builds the application folder: the desktop app, the hl7send CLI and the Java runtime."
    group = "distribution"
    dependsOn(stageJpackageInput, jlinkRuntime)
    val input = jpackageDir.get().dir("input").asFile
    val runtime = jpackageDir.get().dir("runtime").asFile
    val dest = jpackageDir.get().dir("image").asFile
    val cliProps = jpackageDir.get().file("hl7send.properties").asFile
    inputs.dir(input)
    inputs.dir(runtime)
    inputs.dir(packagingSrc)
    outputs.dir(dest)
    doFirst {
        delete(dest)
        cliProps.writeText(listOf(
            "main-class=io.hl7sender.cli.Hl7SendCli",
            "win-console=true",
            // A console tool: no menu entries or desktop shortcuts.
            "win-menu=false",
            "win-shortcut=false",
            "linux-shortcut=false",
            "description=HL7 Sender command line").joinToString("\n", postfix = "\n"))
    }
    executable = jdkTool("jpackage")
    val icon = packagingSrc.file(
        if (isWindows) "hl7-sender.ico" else if (isMac) "hl7-sender.icns" else "hl7-sender.png")
    args("--type", "app-image", "--name", packageName, "--app-version", numericVersion,
        "--vendor", "HL7 Sender", "--description", "Send HL7 v2 messages over MLLP/TCP and track acknowledgments",
        "--copyright", "Copyright (c) 2026 Vijay Amirtharaj Xavier. MIT License.",
        "--input", input.absolutePath, "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", "io.hl7sender.app.Launcher", "--runtime-image", runtime.absolutePath,
        "--icon", icon.asFile.absolutePath, "--add-launcher", "hl7send=${cliProps.absolutePath}",
        "--dest", dest.absolutePath)
    if (isMac) {
        args("--mac-package-identifier", "io.hl7sender.app", "--mac-package-name", "HL7 Sender")
        args(macSigningArgs())
    }
}

val jpackageInstaller by tasks.registering(Exec::class) {
    description = "Builds a native installer for this OS from the application folder."
    group = "distribution"
    dependsOn(jpackageImage)
    val type = providers.gradleProperty("installerType")
        .getOrElse(if (isWindows) "msi" else if (isMac) "dmg" else "deb")
    val image = jpackageDir.get().dir("image").asFile
    val dest = jpackageDir.get().dir("installer").asFile
    inputs.dir(image)
    // The Linux package scripts and desktop entry template, so editing them rebuilds the installer.
    inputs.dir(packagingSrc)
    inputs.property("type", type)
    outputs.dir(dest)
    executable = jdkTool("jpackage")
    val appImage = image.resolve(if (isMac) "$packageName.app" else packageName)
    args("--type", type, "--app-image", appImage.absolutePath, "--name", packageName,
        "--app-version", numericVersion, "--vendor", "HL7 Sender",
        "--description", "Send HL7 v2 messages over MLLP/TCP and track acknowledgments",
        "--about-url", "https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported",
        "--dest", dest.absolutePath)
    when {
        isWindows -> args("--win-menu", "--win-menu-group", "HL7 Sender", "--win-shortcut",
            "--win-dir-chooser", "--win-per-user-install",
            // Keep this UUID: it lets new versions upgrade old ones in place.
            "--win-upgrade-uuid", "3b7c1f3e-5f0e-4f55-9a5e-2d1b0c9a7e41")
        isMac -> {
            args("--mac-package-identifier", "io.hl7sender.app", "--mac-package-name", "HL7 Sender")
            args(macSigningArgs())
        }
        else -> args("--resource-dir", packagingSrc.dir("linux").asFile.absolutePath,
            "--icon", packagingSrc.file("hl7-sender.png").asFile.absolutePath,
            "--linux-shortcut", "--linux-menu-group", "Development", "--linux-app-category", "devel",
            "--linux-package-name", "hl7-sender", "--linux-deb-maintainer", "hl7-sender@users.noreply.github.com",
            "--linux-rpm-license-type", "MIT")
    }
}
