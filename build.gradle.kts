import io.papermc.paperweight.userdev.ReobfArtifactConfiguration
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "dev.shadowcore"
version = "3.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
}

dependencies {
    // Paper dev bundle — Mojang-mapped server internals for 1.21.11.
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")

    // ProtocolLib is used solely for outbound packet rewriting of presentation
    // surfaces (tab list, entity metadata skin overlay, disguise flags).
    // The actual *control* of inbound/outbound packets between the controller
    // connection and the mounted ServerPlayer happens at the NMS layer, not
    // through ProtocolLib, because we need reliable ordering and construction
    // of CLIENTBOUND packets in the same form vanilla produces them.
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
}

// Reobfuscation disabled — Paper 26.1+ does not remap plugins to Spigot mappings.
// Paper 1.21.11 is below that threshold, but we ship Mojang-mapped only and
// declare it in the manifest.
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    manifest {
        attributes["paperweight-mappings-namespace"] = "mojang"
    }
}

tasks.runServer {
    minecraftVersion("1.21.11")
}

tasks.register("runPaper") {
    group = "paper"
    description = "Alias for runServer to make Paper dev startup explicit."
    dependsOn(tasks.runServer)
}

tasks.register("verifyDependencies") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Resolves key classpaths to verify dependency repositories and coordinates."

    doLast {
        val configurationsToVerify = listOf(
            configurations.compileClasspath.get(),
            configurations.runtimeClasspath.get(),
            configurations.testCompileClasspath.get(),
            configurations.testRuntimeClasspath.get()
        ).filter { it.isCanBeResolved }

        configurationsToVerify.forEach { configuration ->
            logger.lifecycle("Verifying dependency graph for configuration: ${configuration.name}")
            configuration.resolve()
        }
    }
}

tasks.check {
    dependsOn("verifyDependencies")
}
