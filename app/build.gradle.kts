import java.util.Properties

plugins {
    id("com.android.application")
}

val envValues = rootProject.file(".env").takeIf { it.isFile }?.readLines()?.mapNotNull { line ->
    val clean = line.substringBefore('#').trim()
    if (clean.isEmpty() || !clean.contains('=')) null else clean.substringBefore('=').trim() to clean.substringAfter('=').trim()
}?.toMap().orEmpty()
val adsEnabled = envValues["Ads"]?.equals("true", ignoreCase = true) == true
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val adsterraScriptUrl = localProperties.getProperty("adsterra.scriptUrl", "").trim()
require(!adsEnabled || adsterraScriptUrl.startsWith("https://")) { "Ads=True requires an https:// adsterra.scriptUrl in local.properties" }
fun quotedBuildConfig(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val generatedOffsetsDir = layout.buildDirectory.dir("generated/source/offsets/java")
val generateOffsets by tasks.registering {
    val local = rootProject.file(".env")
    val example = rootProject.file(".env.example")
    inputs.file(if (local.isFile) local else example)
    outputs.dir(generatedOffsetsDir)
    doLast {
        val source = if (local.isFile) local else example
        val values = source.readLines().mapNotNull { line ->
            val clean = line.substringBefore('#').trim()
            if (clean.isEmpty() || !clean.contains('=')) null else clean.substringBefore('=').trim() to clean.substringAfter('=').trim()
        }.filter { it.first.matches(Regex("offsets_\\d+")) }
        require(values.isNotEmpty()) { "No offsets_N values found in ${source.name}" }
        val expectedKeys = example.readLines().mapNotNull { line -> line.substringBefore('=').trim().takeIf { it.matches(Regex("offsets_\\d+")) } }
        val suppliedKeys = values.map { it.first }
        require(suppliedKeys.toSet()==expectedKeys.toSet()) { "The local .env must contain every ID from .env.example exactly once" }
        require(suppliedKeys.size==suppliedKeys.toSet().size) { "Duplicate offsets_N IDs in ${source.name}" }
        val missing = values.filterNot { it.second.matches(Regex("-?\\d+")) }.map { it.first }
        require(missing.isEmpty()) { "Offsets are not bundled with this repository. Fill .env before building; missing: ${missing.joinToString()}" }
        val target = generatedOffsetsDir.get().file("io/github/tuxkoh/bcsfe/core/Offsets.java").asFile
        target.parentFile.mkdirs()
        target.writeText(buildString {
            appendLine("package io.github.tuxkoh.bcsfe.core;")
            appendLine("final class Offsets {")
            appendLine("    private Offsets() {}")
            values.forEach { (name, value) -> appendLine("    static final int $name = $value;") }
            appendLine("}")
        })
    }
}

android {
    namespace = "io.github.tuxkoh.bcsfe"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.tuxkoh.bcsfe"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "0.1.11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "ADS_ENABLED", adsEnabled.toString())
        buildConfigField("String", "ADSTERRA_SCRIPT_URL", quotedBuildConfig(adsterraScriptUrl))
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

android.sourceSets.getByName("main").java.srcDir(generatedOffsetsDir)
tasks.configureEach { if (name.startsWith("compile") && name.contains("JavaWithJavac")) dependsOn(generateOffsets) }

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.activity:activity:1.8.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}
