import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    alias(libs.plugins.javafx) apply false
    alias(libs.plugins.spotbugs) apply false
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

subprojects {
    apply(plugin = "java")
    apply(plugin = "checkstyle")
    apply(plugin = "com.github.spotbugs")

    group = "io.hl7sender"
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror"))
    }

    dependencies {
        "testImplementation"(platform(catalog.findLibrary("junit-bom").get()))
        "testImplementation"(catalog.findLibrary("junit-jupiter").get())
        "testImplementation"(catalog.findLibrary("assertj").get())
        "testRuntimeOnly"(catalog.findLibrary("junit-launcher").get())
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Long-running load tests run separately via the slowTest task.
            if (name == "test") {
                excludeTags("slow")
            }
        }
        systemProperty("hl7sender.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    extensions.configure<CheckstyleExtension> {
        toolVersion = catalog.findVersion("checkstyle").get().requiredVersion
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        maxWarnings = 0
    }

    extensions.configure<com.github.spotbugs.snom.SpotBugsExtension> {
        toolVersion.set(catalog.findVersion("spotbugs").get().requiredVersion)
        effort.set(Effort.DEFAULT)
        reportLevel.set(Confidence.MEDIUM)
        excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
    }

    // Static analysis gates production code; tests are covered by Checkstyle only.
    tasks.withType<SpotBugsTask>().configureEach {
        enabled = name == "spotbugsMain"
        reports.create("html") { required.set(true) }
    }
}
