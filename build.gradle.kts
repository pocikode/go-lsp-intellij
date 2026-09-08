plugins {
    java
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Compile against the user's installed IntelliJ IDEA instead of downloading an IDE into
        // Gradle's cache. Override platformPath when IDEA is installed somewhere else.
        local(providers.gradleProperty("platformPath").get())
        bundledModule("intellij.platform.testRunner")
        bundledModule("intellij.platform.smRunner")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    // Gradle 9 no longer puts the launcher on the test runtime classpath implicitly, and the
    // platform test framework's session listener still reaches for the JUnit 3 base class.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
    }
}

intellijPlatform {
    // The project has no GUI Designer forms and needs no bytecode instrumentation. Disabling it
    // also avoids resolving the version-matched Java compiler artifact for a local IDE build.
    instrumentCode = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        description = "Free Go language support for IntelliJ IDEA powered by the installed gopls language server and the IntelliJ LSP API."
    }
    pluginVerification {
        ides {
            // `current()` reuses the local platform dependency above. Do not add release selectors
            // here: they download full IDE distributions into Gradle's cache.
            current()
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.wrapper {
    gradleVersion = providers.gradleProperty("gradleVersion").get()
}
