// Lets Gradle download the right JDK by itself when you don't have it
// installed. Minecraft 26.x runs on Java 25.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "DungeonForge"
