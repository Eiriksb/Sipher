import sipher.build.LangVariants
import sipher.build.MinecraftSetupLock
import sipher.build.SipherRepositories
import sipher.build.StripOnnxRuntimeNatives

// Sipher for NeoForge on one Minecraft version (versions/<minecraft>-neoforge). See settings.gradle.kts.

plugins {
    id("net.neoforged.moddev") version "2.0.148"
}

val minecraft = sc.current.version
fun dep(name: String): String = sc.properties[name]
// Dev runs play in runs/<minecraft>-<loader>/; -PrunsDir=<dir> moves them (the runtime test plays in its own folder)
val devRuns = rootProject.file(findProperty("runsDir")?.toString() ?: "runs").resolve(sc.current.project)
val modId = property("mod_id") as String
val javaVersion = if (sc.current.parsed >= "26.1") 25 else 21

version = "${property("mod_version")}+$minecraft"
base.archivesName = "$modId-neoforge"

java.toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaVersion
}

// The Minecraft-independent half of Sipher is compiled into every mod jar, with its natives and models.
evaluationDependsOn(":core")
val core = project(":core")
sourceSets.main { java.srcDir(core.file("src/main/java")) }

SipherRepositories.add(repositories)
StripOnnxRuntimeNatives.register(dependencies)

neoForge {
    version = dep("deps.neoforge")

    runs {
        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = org.slf4j.event.Level.DEBUG
        }
        register("client") {
            client()
            gameDirectory = devRuns.resolve("client")
            // For testing: -PquickPlay=<world in saves/> or -PquickPlayServer=<address> joins right away as -Pusername,
            // -PdebugCaption=<text> fakes captions, -PdebugAudio=<16-bit mono WAV> plays a recording as the microphone.
            findProperty("username")?.let { programArguments.addAll("--username", it.toString()) }
            findProperty("quickPlay")?.let { programArguments.addAll("--quickPlaySingleplayer", it.toString()) }
            findProperty("quickPlayServer")?.let { programArguments.addAll("--quickPlayMultiplayer", it.toString()) }
            findProperty("debugCaption")?.let { systemProperty("sipher.debug.caption", it.toString()) }
            findProperty("debugAudio")?.let { systemProperty("sipher.debug.audio", rootProject.file(it).absolutePath) }
        }
        // A second player for local multiplayer tests (join a runServer on localhost).
        register("client2") {
            client()
            gameDirectory = devRuns.resolve("client2")
            programArguments.addAll("--username", "Dev2")
        }
        register("server") {
            server()
            programArgument("--nogui")
            gameDirectory = devRuns.resolve("server")
            // For testing: -PdebugRelay logs every caption the server relays and to whom.
            if (hasProperty("debugRelay")) { systemProperty("sipher.debug.relay", "true") }
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

val sherpaOnnx = property("sherpa_onnx_version") as String
val onnxRuntime = property("onnxruntime_java_version") as String
// Before NeoForge 21.9, libraries reach dev runs through a separate classpath; since then through the normal one.
val devLibraries = if (sc.current.parsed >= "1.21.9") "runtimeClasspath" else "additionalRuntimeClasspath"

dependencies {
    compileOnly("de.maxhenkel.voicechat:voicechat-api:${property("voicechat_api_version")}")
    runtimeOnly("maven.modrinth:simple-voice-chat:${dep("deps.voicechat")}")

    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-jvm:$sherpaOnnx")
    implementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntime")
    if (devLibraries != "runtimeClasspath") {
        devLibraries("com.k2fsa.sherpa.onnx:sherpa-onnx-jvm:$sherpaOnnx")
        devLibraries("com.microsoft.onnxruntime:onnxruntime:$onnxRuntime")
    }
    jarJar("com.k2fsa.sherpa.onnx:sherpa-onnx-jvm") { version { strictly("[$sherpaOnnx]") } }
    jarJar("com.microsoft.onnxruntime:onnxruntime") { version { strictly("[$onnxRuntime]") } }
}

// Embed and run ONNX Runtime Java without its own natives: Sipher loads the libonnxruntime shipped by sherpa-onnx.
configurations.named("jarJar") { attributes { attribute(StripOnnxRuntimeNatives.ATTRIBUTE, true) } }
configurations.named(devLibraries) { attributes { attribute(StripOnnxRuntimeNatives.ATTRIBUTE, true) } }

// Setting up Minecraft for a dozen versions at once runs the machine out of memory: one at a time.
val minecraftSetupLock = gradle.sharedServices.registerIfAbsent("sipherMinecraftSetup", MinecraftSetupLock::class) {
    maxParallelUsages = 1
}
tasks.named<net.neoforged.nfrtgradle.CreateMinecraftArtifacts>("createMinecraftArtifacts") {
    usesService(minecraftSetupLock)
    dependsOn("stonecutterGenerate")
    // Recompiling Minecraft needs a JDK (not a JRE) for its Java version, 25 from 26.1 on; the default is Java 21.
    javaExecutable = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(javaVersion) }
        .map { it.metadata.installationPath.file("bin/java").asFile.absolutePath }
}

tasks.processResources {
    from(core.tasks.named("processResources"))
    LangVariants.copyInto(this, rootProject.file("src/main/resources/assets/sipher/lang"))

    // NeoForge 21.1.256 needs at least 21.1.0, 26.1.2.114 at least 26.1.2.0
    val neoforge = dep("deps.neoforge").substringBefore('-').split('.').dropLast(1) + "0"
    val properties = mapOf(
        "mod_id" to modId,
        "mod_name" to project.property("mod_name"),
        "mod_license" to project.property("mod_license"),
        "mod_version" to project.property("mod_version"),
        "mod_authors" to project.property("mod_authors"),
        "mod_description" to project.property("mod_description"),
        "minecraft_version_range" to dep("mod.mc_compat"),
        "neo_version_range" to "[${neoforge.joinToString(".")},)",
        "voicechat_api_version_range" to "[${project.property("voicechat_api_min_version")},)",
        "java_version" to javaVersion,
        // NeoForge 26.2 renamed the logo to a square icon (or a banner) and warns about the old key
        "logo_key" to if (sc.current.parsed >= "26.2") "iconFile" else "logoFile",
    )
    inputs.properties(properties)
    filesMatching(listOf("META-INF/neoforge.mods.toml", "sipher.mixins.json")) { expand(properties) }
    exclude("fabric.mod.json")
}

tasks.jar {
    from(rootProject.file("LICENSE")) { into("META-INF"); rename { "LICENSE_$modId" } }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}

// build/dist/ in the root project: every jar, each with a .json saying which Minecraft releases and loader it is for.
// The release workflow publishes from there.
tasks.register<Copy>("dist") {
    group = "build"
    description = "Copies the mod jar and its release metadata to build/dist/."
    from(tasks.jar)
    into(rootProject.layout.buildDirectory.dir("dist"))
    val releases = sc.properties.rawOrNull("mod", "mc_releases")?.asList().orEmpty().map { it.toString() }
    val info = groovy.json.JsonOutput.toJson(mapOf("loader" to "neoforge", "minecraft" to releases, "java" to javaVersion))
    inputs.property("info", info)
    doLast {
        destinationDir.resolve("${base.archivesName.get()}-$version.json").writeText(info)
    }
}
