import groovy.json.JsonSlurper

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            val metadata = providers.exec {
                workingDir(rootDir)
                commandLine(
                    "cargo",
                    "metadata",
                    "--format-version",
                    "1",
                    "--locked",
                    "--filter-platform",
                    "aarch64-linux-android",
                    "--manifest-path",
                    "native/Cargo.toml",
                )
            }.standardOutput.asText.get()
            val packages = JsonSlurper().parseText(metadata) as Map<*, *>
            val verifier = (packages["packages"] as List<*>)
                .map { it as Map<*, *> }
                .first { it["name"] == "rustls-platform-verifier-android" }
            val manifest = file(verifier["manifest_path"] as String)
            url = uri(manifest.parentFile.resolve("maven"))
            metadataSources { mavenPom(); artifact() }
        }
    }
}

rootProject.name = "yamu-android"
include(":app")
