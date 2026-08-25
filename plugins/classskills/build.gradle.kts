import org.gradle.api.tasks.compile.JavaCompile

plugins {
    kotlin("jvm") version "2.1.10"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.thorb"
version = "0.14.30"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation(kotlin("stdlib"))
    // Precompiled from the user-provided Paper-26 dialog handoff. It is bundled by Shadow;
    // the source remains under src/main/java for rebuilding once the Paper-26 API is available.
    implementation(files("libs/classskills-dialog-prebuilt.jar"))
}

kotlin {
    // Paper runs plugins on Java 21. The optional property permits a newer local compiler
    // while --release below keeps the produced JAR Java-21-compatible.
    jvmToolchain(providers.gradleProperty("buildJdk").orNull?.toIntOrNull() ?: 25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        exclude("dev/thorb/classskills/menu/**")
        options.release.set(21)
    }

    shadowJar {
        archiveClassifier.set("")
        // Paper does not ship Kotlin as a plugin library. Bundle the standard library with this plugin.
        // Do not relocate it: older Shadow/ASM combinations cannot rewrite Kotlin's metadata arrays.
    }

    build {
        dependsOn(shadowJar)
    }
}
