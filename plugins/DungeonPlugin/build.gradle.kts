import java.util.Properties

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
    // The template geometry is deliberately Bukkit-free so the alignment
    // rules - the floor-transition case above all - are provable off-server.
    // The planner test loads the bundled config.yml through Bukkit's
    // YamlConfiguration, which needs no running server.
    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:${providers.gradleProperty("paperApiVersion").get()}")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

tasks.test {
    useJUnitPlatform()
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

// ------------------------------------------------------------
//  Optional: copy the built jar straight into a test server.
//  Put the path in a gitignored file called 'local.properties':
//      serverPluginsDir=C:/servers/test/plugins
//  Without it, build logs one line and the jar stays in build/libs -
//  "I see no change on the server" has twice meant exactly this.
// ------------------------------------------------------------
tasks.register<Copy>("deploy") {
    group = "dungeonplugin"
    description = "Copies the built jar into your test server's plugins folder."

    val localProps = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { stream -> localProps.load(stream) }
    }
    val target: String? = localProps.getProperty("serverPluginsDir")

    onlyIf {
        if (target == null) {
            logger.lifecycle("No 'serverPluginsDir' in local.properties - skipping deploy." +
                " The jar stays in build/libs and must reach the server by hand.")
            false
        } else true
    }

    from(tasks.jar)
    if (target != null) into(target)
}

tasks.named("build") { finalizedBy("deploy") }
