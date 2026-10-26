pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

val forkVersion = providers.gradleProperty("forkVersion").get()
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri("../build/verified-maven") } }
            filter { includeVersion("org.jetbrains.compose.ui", "ui-wasm-js", forkVersion) }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "compose-ui-binary-probe"
