import sipher.build.LangVariants
import sipher.build.SipherRepositories
import sipher.build.StripOnnxRuntimeNatives

// Sipher for Fabric on one Minecraft version (versions/<minecraft>-fabric). See settings.gradle.kts.

plugins {
    // Fabric Loom, remapping before 26.1 and unobfuscated from 26.1 on
    id("dev.kikugie.loom-back-compat")
}

val minecraft = sc.current.version
fun dep(name: String): String = sc.properties[name]
// Dev runs play in runs/<minecraft>-<loader>/; -PrunsDir=<dir> moves them (the runtime test plays in its own folder)
val devRuns = rootProject.file(findProperty("runsDir")?.toString() ?: "runs").resolve(sc.current.project)
val modId = property("mod_id") as String
val javaVersion = if (sc.current.parsed >= "26.1") 25 else 21

version = "${property("mod_version")}+$minecraft"
base.archivesName = "$modId-fabric"

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

val sherpaOnnx = "com.k2fsa.sherpa.onnx:sherpa-onnx-jvm:${property("sherpa_onnx_version")}"
val onnxRuntime = "com.microsoft.onnxruntime:onnxruntime:${property("onnxruntime_java_version")}"

dependencies {
    minecraft("com.mojang:minecraft:$minecraft")
    loomx.applyMojangMappings()
    // mod* configurations work for unobfuscated Minecraft too (loom-back-compat maps them)
    modImplementation("net.fabricmc:fabric-loader:${dep("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${dep("deps.fabric_api")}")
    modCompileOnly("maven.modrinth:modmenu:${dep("deps.modmenu")}")
    modLocalRuntime("maven.modrinth:modmenu:${dep("deps.modmenu")}")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:${property("voicechat_api_version")}")

    implementation(sherpaOnnx)
    implementation(onnxRuntime)
    include(sherpaOnnx)
    include(onnxRuntime)
}

// Simple Voice Chat for dev runs. Loaded like a mod from the mods folder, not from the classpath, so that Fabric Loader
// also loads the voicechat_api mod nested inside it.
val devMods = configurations.create("devMods") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    devMods("maven.modrinth:simple-voice-chat:${dep("deps.voicechat")}")
}

// Embed and run ONNX Runtime Java without its own natives: Sipher loads the libonnxruntime shipped by sherpa-onnx.
configurations.named("includeInternal") { attributes { attribute(StripOnnxRuntimeNatives.ATTRIBUTE, true) } }
configurations.named("runtimeClasspath") { attributes { attribute(StripOnnxRuntimeNatives.ATTRIBUTE, true) } }

loom {
    runConfigs {
        configureEach {
            systemProperties.put("fabric.addMods", provider { devMods.asPath })
        }
        named("client") {
            runDirectory = devRuns.resolve("client")
            // For testing: -PquickPlay=<world in saves/> or -PquickPlayServer=<address> joins right away as -Pusername,
            // -PdebugCaption=<text> fakes captions, -PdebugAudio=<16-bit mono WAV> plays a recording as the microphone.
            findProperty("username")?.let { programArguments.addAll("--username", it.toString()) }
            findProperty("quickPlay")?.let { programArguments.addAll("--quickPlaySingleplayer", it.toString()) }
            findProperty("quickPlayServer")?.let { programArguments.addAll("--quickPlayMultiplayer", it.toString()) }
            findProperty("debugCaption")?.let { systemProperties.put("sipher.debug.caption", it.toString()) }
            findProperty("debugAudio")?.let { systemProperties.put("sipher.debug.audio", rootProject.file(it).absolutePath) }
        }
        // A second player for local multiplayer tests (join a runServer on localhost).
        register("client2") {
            client()
            displayName = "Minecraft Client 2"
            runDirectory = devRuns.resolve("client2")
            programArguments.addAll("--username", "Dev2")
        }
        named("server") {
            runDirectory = devRuns.resolve("server")
            // For testing: -PdebugRelay logs every caption the server relays and to whom.
            if (hasProperty("debugRelay")) { systemProperties.put("sipher.debug.relay", "true") }
            programArguments.add("--nogui")
        }
    }
}

tasks.processResources {
    from(core.tasks.named("processResources"))
    LangVariants.copyInto(this, rootProject.file("src/main/resources/assets/sipher/lang"))

    val properties = mapOf(
        "mod_id" to modId,
        "mod_name" to project.property("mod_name"),
        "mod_license" to project.property("mod_license"),
        "mod_version" to project.property("mod_version"),
        "mod_authors" to project.property("mod_authors"),
        "mod_description" to project.property("mod_description"),
        "minecraft_version_range" to dep("mod.mc_compat"),
        // Sipher only uses long-stable loader API; Fabric API asks for whatever newer loader a Minecraft version needs.
        "fabric_loader_version" to "0.16.0",
        "voicechat_api_min_version" to project.property("voicechat_api_min_version"),
        "java_version" to javaVersion,
    )
    inputs.properties(properties)
    filesMatching(listOf("fabric.mod.json", "sipher.mixins.json")) { expand(properties) }
    exclude("META-INF/neoforge.mods.toml")
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
    from(loomx.modJar)
    into(rootProject.layout.buildDirectory.dir("dist"))
    val releases = sc.properties.rawOrNull("mod", "mc_releases")?.asList().orEmpty().map { it.toString() }
    val info = groovy.json.JsonOutput.toJson(mapOf("loader" to "fabric", "minecraft" to releases, "java" to javaVersion))
    inputs.property("info", info)
    doLast {
        destinationDir.resolve("${base.archivesName.get()}-$version.json").writeText(info)
    }
}
