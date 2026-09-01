plugins {
    kotlin("jvm") version "2.4.10"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/") // WorldEdit
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paperApiVersion").get()}")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.19")
    compileOnly("io.github.toxicity188:bettermodel-bukkit-api:3.3.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

// The Kotlin stdlib is not shaded: plugin.yml declares it under `libraries:`,
// so Paper's library loader resolves it from Maven Central at first startup.
tasks.processResources {
    val properties = mapOf(
        "version" to version.toString(),
        "apiVersion" to providers.gradleProperty("apiVersion").get()
    )
    inputs.properties(properties)
    filesMatching("plugin.yml") { expand(properties) }
}
