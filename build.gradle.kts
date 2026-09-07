import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    kotlin("jvm") version "2.1.21"
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
        // The IntelliJ LSP API ships only in the IntelliJ IDEA (Ultimate) distribution, so the
        // plugin is compiled against IU. Since 2025.2 the API works without a paid license.
        intellijIdeaUltimate(providers.gradleProperty("platformVersion").get())
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    // Gradle 9 no longer puts the launcher on the test runtime classpath implicitly, and the
    // platform test framework's session listener still reaches for the JUnit 3 base class.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
    }
}

intellijPlatform {
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
            select {
                sinceBuild = providers.gradleProperty("pluginSinceBuild")
                untilBuild = "262.*"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.wrapper {
    gradleVersion = providers.gradleProperty("gradleVersion").get()
}
