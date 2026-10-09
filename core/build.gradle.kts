import sipher.build.NativeIndex
import sipher.build.SipherRepositories
import sipher.build.StripOnnxRuntimeNatives
import sipher.build.VerifiedDownload
import sipher.build.VerifyChecksums

// Everything in Sipher that doesn't touch Minecraft: speech recognition, translation, language packs, natives, settings
// and caption routing. Each mod build (versions/<minecraft>-<loader>) compiles these sources and packages these
// resources into its own jar; the tests run here once, without Minecraft.

plugins {
    `java-library`
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

SipherRepositories.add(repositories)

val sherpaOnnx = property("sherpa_onnx_version") as String
val onnxRuntime = property("onnxruntime_java_version") as String

// Platforms Sipher ships natives for. Names follow sherpa-onnx's native-lib jars.
val nativePlatforms = listOf("linux-x64", "linux-aarch64", "osx-x64", "osx-aarch64", "win-x64", "win-arm64")

val sherpaNatives by configurations.registering {
    isCanBeConsumed = false
    isTransitive = false
}
// The original (unstripped) jars that end up inside Sipher, for checksum verification.
val thirdPartyJars by configurations.registering {
    isCanBeConsumed = false
    isTransitive = false
}

StripOnnxRuntimeNatives.register(dependencies)

dependencies {
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-jvm:$sherpaOnnx")
    implementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntime")
    // Provided by Minecraft at runtime; the oldest versions any supported Minecraft ships.
    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("org.slf4j:slf4j-api:2.0.9")
    compileOnly("org.jetbrains:annotations:24.1.0")

    nativePlatforms.forEach { platform ->
        sherpaNatives("com.k2fsa.sherpa.onnx:sherpa-onnx-native-lib-$platform:$sherpaOnnx")
    }
    thirdPartyJars("com.k2fsa.sherpa.onnx:sherpa-onnx-jvm:$sherpaOnnx")
    thirdPartyJars("com.microsoft.onnxruntime:onnxruntime:$onnxRuntime")

    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.jetbrains:annotations:24.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.9")
}

// Run ONNX Runtime Java without its own natives: Sipher loads the libonnxruntime shipped by sherpa-onnx.
configurations.named("testRuntimeClasspath") { attributes { attribute(StripOnnxRuntimeNatives.ATTRIBUTE, true) } }

// ---------------------------------------------------------------------------------------------------------------
// Natives: sherpa-onnx (+ its libonnxruntime) for every platform, plus our ONNX Runtime JNI glue built against it.
// ---------------------------------------------------------------------------------------------------------------

val verifyThirdPartyJars = tasks.register<VerifyChecksums>("verifyThirdPartyJars") {
    group = "sipher"
    description = "Checks third-party jars packaged into Sipher against gradle/third-party-checksums.txt."
    files.from(sherpaNatives, thirdPartyJars)
    checksums = rootProject.layout.projectDirectory.file("gradle/third-party-checksums.txt")
}

tasks.named("compileJava") { dependsOn(verifyThirdPartyJars) }

val nativesStaging = layout.buildDirectory.dir("generated/sipher/natives")

val stageNatives = tasks.register<Sync>("stageNatives") {
    group = "sipher"
    description = "Collects the native libraries for every supported platform."
    dependsOn(verifyThirdPartyJars)
    into(nativesStaging.map { it.dir("sipher/natives") })
    nativePlatforms.forEach { platform ->
        from({ zipTree(sherpaNatives.get().files.first { it.name.contains("native-lib-$platform-") }) }) {
            include("sherpa-onnx/native/$platform/*")
            eachFile { path = "$platform/$name" }
            includeEmptyDirs = false
        }
        from(rootProject.file("natives/onnxruntime4j_jni/$platform")) {
            exclude("*.txt", "*.md")
            into(platform)
        }
    }
}

val indexNatives = tasks.register<NativeIndex>("indexNatives") {
    group = "sipher"
    dependsOn(stageNatives)
    nativesDirectory = nativesStaging.map { it.dir("sipher/natives") }
    indexFile = layout.buildDirectory.file("generated/sipher/natives-index/sipher/natives/index.txt")
}

// ---------------------------------------------------------------------------------------------------------------
// Built-in models: English speech recognition (Moonshine Tiny, MIT) and voice activity detection (Silero, MIT).
// Everything else is an opt-in download made from inside the game.
// ---------------------------------------------------------------------------------------------------------------

data class BuiltinModel(val dir: String, val file: String, val url: String, val sha256: String)

val moonshine = "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/resolve/d1e6c30921780b8508d04b492dfb3ce8a51605d4"
val builtinModels = listOf(
    BuiltinModel("moonshine-tiny-en", "encoder_model.ort", "$moonshine/encoder_model.ort",
        "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221"),
    BuiltinModel("moonshine-tiny-en", "decoder_model_merged.ort", "$moonshine/decoder_model_merged.ort",
        "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0"),
    BuiltinModel("moonshine-tiny-en", "tokens.txt", "$moonshine/tokens.txt",
        "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
    BuiltinModel("moonshine-tiny-en", "LICENSE", "$moonshine/LICENSE",
        "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd"),
    BuiltinModel("silero-vad", "silero_vad.onnx", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
)

val modelDownloads = builtinModels.map { model ->
    val taskName = "download_" + "${model.dir}_${model.file}".replace(Regex("[^A-Za-z0-9]"), "_")
    tasks.register<VerifiedDownload>(taskName) {
        group = "sipher"
        url = model.url
        sha256 = model.sha256
        destination = layout.buildDirectory.file("downloads/models/${model.dir}/${model.file}")
    }
}

val indexBuiltinModels = tasks.register<NativeIndex>("indexBuiltinModels") {
    group = "sipher"
    dependsOn(modelDownloads)
    nativesDirectory = layout.buildDirectory.dir("downloads/models")
    indexFile = layout.buildDirectory.file("generated/sipher/models-index/sipher/models/index.txt")
}

sourceSets.main {
    resources.srcDir(stageNatives.map { nativesStaging.get() })
    resources.srcDir(indexNatives.map { layout.buildDirectory.dir("generated/sipher/natives-index").get() })
    resources.srcDir(indexBuiltinModels.map { layout.buildDirectory.dir("generated/sipher/models-index").get() })
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(modelDownloads)
    from(layout.buildDirectory.dir("downloads/models")) {
        into("sipher/models")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("sipher.test.dir", layout.buildDirectory.dir("test-sipher").get().asFile.absolutePath)
    systemProperty("sipher.test.lang", rootProject.file("src/main/resources/assets/sipher/lang").absolutePath)
    // Optional: -PmarianTestModel=<dir> runs the translation test against an exported OPUS-MT model.
    findProperty("marianTestModel")?.let { systemProperty("sipher.test.marian", rootProject.file(it).absolutePath) }
    // Optional: -PliveDownload=<language> installs that pack from the published GitHub release.
    findProperty("liveDownload")?.let { systemProperty("sipher.test.liveDownload", it) }
    // Optional: -PpacksDir=.cache/packs/out [-PpacksAudio=.cache/packs/work] runs every locally built language pack.
    findProperty("packsDir")?.let { packs ->
        systemProperty("sipher.test.packs", rootProject.file(packs).absolutePath)
        systemProperty("sipher.test.packsAudio", rootProject.file(findProperty("packsAudio") ?: packs).absolutePath)
    }
}
