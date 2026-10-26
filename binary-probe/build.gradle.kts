@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    kotlin("multiplatform") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.11.1"
}

val forkVersion = providers.gradleProperty("forkVersion").get()
val forkKlib = layout.projectDirectory.file(
    "../build/verified-maven/org/jetbrains/compose/ui/ui-wasm-js/$forkVersion/ui-wasm-js-$forkVersion.klib",
)
configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.compose.ui:ui-wasm-js:$forkVersion")
}

kotlin {
    wasmJs { browser { testTask { useKarma { useChromeHeadless() } } } }
    sourceSets {
        commonMain.dependencies { implementation("org.jetbrains.compose.ui:ui:1.11.1") }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
        wasmJsTest {
            kotlin.srcDir("../src/wasmJsTest/kotlin")
            dependencies { implementation("org.jetbrains.skiko:skiko-js-wasm-runtime:0.144.6") }
        }
    }
    targets.named("wasmJs") {
        compilations.named("test") {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add("-Xfriend-modules=${forkKlib.asFile.absolutePath}")
            }
        }
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

val verifyBinarySelection = tasks.register("verifyBinarySelection") {
    doLast {
        for (name in listOf("wasmJsCompileClasspath", "wasmJsTestCompileClasspath")) {
            val components = configurations.getByName(name).incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? ModuleComponentIdentifier }
            check(components.any {
                it.group == "org.jetbrains.compose.ui" && it.module == "ui" && it.version == "1.11.1"
            }) { "The UI root must stay official in $name." }
            check(components.any {
                it.group == "org.jetbrains.compose.ui" && it.module == "ui-wasm-js" && it.version == forkVersion
            }) { "The immutable Wasm leaf was not selected in $name." }
            check(components.none {
                it.group == "org.jetbrains.compose.ui" && it.module != "ui-wasm-js" && it.version.contains("-kodex.")
            }) { "Only the Wasm leaf may be replaced." }
            println("$name: official root plus $forkVersion Wasm leaf")
        }
    }
}

tasks.named("wasmJsBrowserTest") { dependsOn(verifyBinarySelection) }
