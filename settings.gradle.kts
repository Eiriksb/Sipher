pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
    }
}

plugins {
    // One source tree, built for every Minecraft version and mod loader below (https://stonecutter.kikugie.dev/).
    id("dev.kikugie.stonecutter") version "0.9.8"
    // Picks the right Fabric Loom for obfuscated (before 26.1) and unobfuscated Minecraft.
    id("dev.kikugie.loom-back-compat") version "0.4.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // Creates versions/<project>-<loader>, built with build.<loader>.gradle.kts against Minecraft <minecraft>.
        fun build(project: String, minecraft: String = project) {
            for (loader in listOf("fabric", "neoforge")) {
                version("$project-$loader", minecraft).buildscript("build.$loader.gradle.kts")
            }
        }

        build("1.21.1")
        build("1.21.4")
        build("1.21.5")
        build("1.21.8")
        build("1.21.10")
        build("1.21.11")
        build("26.1", minecraft = "26.1.2")
        build("26.2")
        build("26.3")
        vcsVersion = "1.21.1-neoforge"
    }
}

rootProject.name = "sipher"

// Speech, translation, natives and settings: everything that doesn't touch Minecraft. Built and tested once.
include("core")
