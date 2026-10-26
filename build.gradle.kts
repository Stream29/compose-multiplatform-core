@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    `maven-publish`
}

group = "org.jetbrains.compose.ui"
// No official-coordinate or uncommitted publication default.
version = providers.gradleProperty("forkVersion").get().also {
    check(it.matches(Regex("""1\.11\.1-kodex\.[0-9a-f]{12}"""))) {
        "A reviewed immutable producer commit/version is required."
    }
}

// Gradle writes candidates to a task-local repository only. The separate publisher
// validates the complete payload and refuses an existing or partially uploaded version.
tasks.withType<org.gradle.api.publish.maven.tasks.AbstractPublishToMaven>().configureEach {
    doFirst {
        check(name == "publishWasmJsPublicationToForkStagingRepository") {
            "Only the Wasm leaf may be staged; use the immutable publisher for remote uploads."
        }
    }
}

publishing {
    repositories {
        maven {
            name = "forkStaging"
            url = layout.buildDirectory.dir("fork-staging").get().asFile.toURI()
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Compose UI Wasm — Kodex accessibility repair")
            description.set("Compose UI 1.11.1 with the pinned Kodex browser semantics repair.")
            url.set("https://github.com/Stream29/compose-multiplatform-core/tree/kodex-ui-wasm")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
        }
    }
}

check(libs.versions.compose.multiplatform.get() == "1.11.1") {
    "Review the Compose UI source pin, build dependencies and compatibility patch before upgrading."
}

@CacheableTask
abstract class PrepareComposeUi : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val upstreamArchive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val compatibilityPatch: RegularFileProperty

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @get:Inject
    protected abstract val archives: ArchiveOperations

    @get:Inject
    protected abstract val fileSystem: FileSystemOperations

    private fun hash(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @TaskAction
    fun prepare() {
        val archive = upstreamArchive.get().asFile
        check(hash(archive.readBytes()) == "4f3d83e83ebdfd860eb7778daacea502035036b67735641909dce01c3b193a66") {
            "Unexpected Compose UI 1.11.1 source archive."
        }
        fileSystem.sync {
            from(archives.zipTree(archive))
            into(destination)
            include(
                "commonMain/**", "skikoMain/**", "nonJvmMain/**", "webMain/**", "wasmJsMain/**",
                "META-INF/androidx/compose/ui/ui/LICENSE.txt",
            )
        }
        check(destination.file("META-INF/androidx/compose/ui/ui/LICENSE.txt").get().asFile.isFile)
        val source = destination.file(
            "webMain/androidx/compose/ui/platform/accessibility/ComposeWebSemanticsListener.kt",
        ).get().asFile
        check(hash(source.readBytes()) == "ecce5cef68bc5d67530a4a67031c3e3efea3d55ac199bfedc5ac4460725c0384")
        val original = source.readLines()
        val patch = compatibilityPatch.get().asFile.readLines()
        check(patch.take(2) == listOf("--- upstream/ComposeWebSemanticsListener.kt", "+++ patched/ComposeWebSemanticsListener.kt"))
        val result = mutableListOf<String>()
        val header = Regex("""@@ -(\d+),(\d+) \+(\d+),(\d+) @@.*""")
        var sourceIndex = 0
        var patchIndex = 2
        while (patchIndex < patch.size) {
            val match = checkNotNull(header.matchEntire(patch[patchIndex++])) { "Invalid compatibility patch hunk." }
            val (oldStart, oldCount, newStart, newCount) = match.destructured.toList().map(String::toInt)
            check(oldStart - 1 >= sourceIndex)
            result.addAll(original.subList(sourceIndex, oldStart - 1))
            sourceIndex = oldStart - 1
            check(result.size == newStart - 1)
            var consumed = 0
            var produced = 0
            while (patchIndex < patch.size && !patch[patchIndex].startsWith("@@")) {
                val line = patch[patchIndex++]
                check(line.isNotEmpty() && line[0] in " +-") { "Invalid compatibility patch line." }
                val content = line.drop(1)
                if (line[0] != '+') {
                    check(sourceIndex < original.size && original[sourceIndex++] == content) {
                        "Compose UI patch context no longer matches."
                    }
                    consumed++
                }
                if (line[0] != '-') { result.add(content); produced++ }
            }
            check(consumed == oldCount && produced == newCount)
        }
        result.addAll(original.subList(sourceIndex, original.size))
        val bytes = (result.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        check(hash(bytes) == "ee47fa5d840930be2b3bc9ef30293caa774a872d930c4eb9c9a4496b667f84df") {
            "Unexpected patched Compose UI source."
        }
        source.writeBytes(bytes)
    }
}

val upstreamSources = configurations.create("upstreamSources") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    resolutionStrategy.useGlobalDependencySubstitutionRules = false
}
dependencies {
    upstreamSources("org.jetbrains.compose.ui:ui-wasm-js:1.11.1:sources@jar")
}
val prepareComposeUi = tasks.register<PrepareComposeUi>("prepareComposeUi") {
    upstreamArchive.set(layout.file(upstreamSources.elements.map { it.single().asFile }))
    compatibilityPatch.set(layout.projectDirectory.file("semantics.patch"))
    destination.set(layout.buildDirectory.dir("generated/upstream"))
}

tasks.withType<Jar>().matching { it.name == "wasmJsSourcesJar" }.configureEach {
    from("LICENSE.txt") { into("META-INF/androidx/compose/ui/ui") }
    from("README.md") {
        into("META-INF/androidx/compose/ui/ui")
        rename { "KODEX-NOTICE.md" }
    }
}

plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
    providers.gradleProperty("kodex.nodeCommand").orNull?.let { executable ->
        extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec> {
            download.set(false)
            command.set(executable)
        }
    }
}

// The UI project itself has no Maven UI component for Compose's runtime detection.
afterEvaluate {
    tasks.named("unpackSkikoWasmRuntime") {
        setOnlyIf("The compatibility module's raster tests require Skiko") { true }
    }
}

kotlin {
    wasmJs {
        browser { testTask { useKarma { useChromeHeadless() } } }
        compilerOptions { moduleName.set("org.jetbrains.compose.ui:ui") }
    }
    applyDefaultHierarchyTemplate()
    compilerOptions {
        // Match upstream source-language semantics; use the product's compiler plugin.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-XXLanguage:+AllowAnyAsAnActualTypeForExpectInterface",
            "-XXLanguage:+JsAllowImplementingFunctionInterface",
        )
        optIn.addAll(
            "kotlin.RequiresOptIn", "kotlin.experimental.ExperimentalTypeInference",
            "kotlin.contracts.ExperimentalContracts", "kotlin.js.ExperimentalWasmJsInterop",
            "androidx.compose.ui.ExperimentalComposeUiApi", "androidx.compose.ui.InternalComposeUiApi",
            "androidx.compose.runtime.InternalComposeApi", "androidx.compose.runtime.ExperimentalComposeApi",
            "org.jetbrains.skiko.ExperimentalSkikoApi", "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
        commonMain {
            kotlin.srcDir(prepareComposeUi.flatMap { it.destination.dir("commonMain") })
            dependencies {
                api("androidx.annotation:annotation:1.9.1")
                api("androidx.compose.runtime:runtime-retain:1.11.2")
                api("androidx.savedstate:savedstate-compose:1.4.0")
                api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6")
                api("org.jetbrains.compose.runtime:runtime-saveable:1.11.1")
                api("org.jetbrains.compose.runtime:runtime:1.11.1")
                api("org.jetbrains.compose.ui:ui-backhandler:1.11.1")
                api("org.jetbrains.compose.ui:ui-geometry:1.11.1")
                api("org.jetbrains.compose.ui:ui-graphics:1.11.1")
                api("org.jetbrains.compose.ui:ui-text:1.11.1")
                api("org.jetbrains.compose.ui:ui-unit:1.11.1")
                api("org.jetbrains.compose.ui:ui-util:1.11.1")
                api("org.jetbrains.skiko:skiko:0.144.6")
                api("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
                implementation("androidx.collection:collection:1.5.0")
                implementation("androidx.navigationevent:navigationevent:1.0.1")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.6")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.9.6")
                implementation("org.jetbrains.compose.annotation-internal:annotation:1.10.0")
                implementation("org.jetbrains.compose.collection-internal:collection:1.10.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:atomicfu:0.28.0")
            }
        }
        val skiko = maybeCreate("skikoMain").apply {
            dependsOn(commonMain.get())
            kotlin.srcDir(prepareComposeUi.flatMap { it.destination.dir("skikoMain") })
        }
        val nonJvm = maybeCreate("nonJvmMain").apply {
            dependsOn(skiko)
            kotlin.srcDir(prepareComposeUi.flatMap { it.destination.dir("nonJvmMain") })
        }
        maybeCreate("webMain").apply {
            dependsOn(nonJvm)
            kotlin.srcDir(prepareComposeUi.flatMap { it.destination.dir("webMain") })
        }
        wasmJsMain {
            kotlin.srcDir(prepareComposeUi.flatMap { it.destination.dir("wasmJsMain") })
            dependencies { implementation("org.jetbrains.skiko:skiko-js-wasm-runtime:0.144.6") }
        }
    }
}
