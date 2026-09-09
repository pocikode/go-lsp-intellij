plugins {
    java
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

import java.io.File

fun findLocalIntelliJ(): String {
    val home = File(System.getProperty("user.home"))
    val candidates = buildList {
        val explicit = System.getenv("INTELLIJ_PLATFORM_PATH")
        if (!explicit.isNullOrBlank()) add(File(explicit))

        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            System.getenv("LOCALAPPDATA")?.let { add(File(it, "JetBrains/Installations")) }
            System.getenv("PROGRAMFILES")?.let { add(File(it, "JetBrains/IntelliJ IDEA")) }
        } else {
            add(File(home, ".local/share/JetBrains/Toolbox/apps/intellij-idea"))
            add(File(home, ".local/share/JetBrains/Toolbox/apps/IDEA-U"))
            add(File(home, ".local/share/JetBrains/Toolbox/apps/IDEA-C"))
            add(File(home, "Applications/IntelliJ IDEA.app"))
            add(File("/Applications/IntelliJ IDEA.app"))
        }
    }

    val installation = candidates.asSequence()
        .flatMap { candidate ->
            sequenceOf(candidate) + if (candidate.isDirectory) {
                candidate.listFiles()?.asSequence()?.filter { it.isDirectory } ?: emptySequence()
            } else {
                emptySequence()
            }
        }
        .firstOrNull {
            File(it, "product-info.json").isFile ||
                File(it, "lib/idea.jar").isFile ||
                File(it, "lib/platform-loader.jar").isFile
        }

    return installation?.absolutePath
        ?: error(
            "IntelliJ IDEA installation not found. Set -PplatformPath=/path/to/IntelliJ IDEA " +
                "or INTELLIJ_PLATFORM_PATH."
        )
}

val platformPath = providers.gradleProperty("platformPath")
    .orElse(providers.environmentVariable("INTELLIJ_PLATFORM_PATH"))
    .orElse(provider { findLocalIntelliJ() })

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
        // Gradle's cache. The path can be supplied explicitly or discovered from common installs.
        local(platformPath.get())
        bundledModule("intellij.platform.testRunner")
        bundledModule("intellij.platform.smRunner")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
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
    signing {
        certificateChainFile = layout.file(
            providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map { file(it) }
        )
        privateKeyFile = layout.file(
            providers.environmentVariable("PRIVATE_KEY_FILE").map { file(it) }
        )
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.environmentVariable("PUBLISH_CHANNEL")
            .map { listOf(it) }
            .orElse(listOf("default"))
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named("verifyPluginSignature") {
    dependsOn("signPlugin")
}

tasks.wrapper {
    gradleVersion = providers.gradleProperty("gradleVersion").get()
}
