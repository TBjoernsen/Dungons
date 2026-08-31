import java.util.Properties

plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = property("pluginGroup") as String
version = property("pluginVersion") as String

repositories {
    mavenCentral()
    maven("https://maven.enginehub.org/repo/") {
        name = "enginehub"
    }
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    // WorldEdit supplies schematic decoding and encoding only. DungeonForge
    // performs every world block change itself through its tick-spread builder.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.19")
    // BetterModel renders custom mob models. Only BetterModelApplier touches
    // these types, so the plugin still loads when BetterModel is absent.
    compileOnly("io.github.toxicity188:bettermodel-bukkit-api:${property("betterModelVersion")}")
}

val javaVersion = (project.property("javaVersion") as String).toInt()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(javaVersion)
        options.compilerArgs.add("-Xlint:deprecation")
    }

    // Fills in ${version}, ${apiVersion} and ${mainClass} in plugin.yml
    processResources {
        val props = mapOf(
            "version" to project.version.toString(),
            "apiVersion" to (project.property("apiVersion") as String),
            "mainClass" to "nl.riddernix.dungeonforge.DungeonForgePlugin"
        )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    // ./gradlew runServer -> boots a real Paper server with this plugin installed
    runServer {
        minecraftVersion(project.property("runPaperVersion") as String)
    }
}

// ------------------------------------------------------------
//  Optional: ./gradlew deploy copies the jar to your test server.
//  Put the path in a file called 'local.properties':
//      serverPluginsDir=C:/servers/test/plugins
// ------------------------------------------------------------
val deploy by tasks.registering(Copy::class) {
    group = "dungeonforge"
    description = "Copies the built jar into your test server's plugins folder."

    val localProps = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { localProps.load(it) }
    }
    val target = localProps.getProperty("serverPluginsDir")

    onlyIf {
        if (target == null) {
            logger.lifecycle("No 'serverPluginsDir' in local.properties - skipping deploy.")
            false
        } else true
    }

    from(tasks.jar)
    if (target != null) into(target)
}

tasks.named("build") { finalizedBy(deploy) }
