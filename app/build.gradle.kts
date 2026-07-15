import java.io.FileInputStream
import java.util.Properties
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity

val signingPropertiesFile = rootProject.file("keystore.properties")
val signingProperties = signingPropertiesFile.takeIf { it.isFile }?.let { propertiesFile ->
    Properties().apply {
        FileInputStream(propertiesFile).use { input -> load(input) }
    }
}

fun Properties.required(name: String): String =
    getProperty(name)?.takeIf(String::isNotBlank)
        ?: error("Missing $name in ${signingPropertiesFile.path}")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val requestedAbi = providers.gradleProperty("yamu.abi").orNull
require(requestedAbi == null || requestedAbi in supportedAbis) {
    "Unsupported yamu.abi=$requestedAbi; expected one of ${supportedAbis.joinToString()}"
}
val selectedAbis = requestedAbi?.let(::listOf) ?: supportedAbis
val yamuVersionName = providers.gradleProperty("yamu.versionName").getOrElse("0.1.4")
val ffmpegRevision =
    rootProject.file("vendor/ffmpeg-sys-next/FFMPEG_REVISION").readText().trim().also { revision ->
        require(revision.matches(Regex("[0-9a-f]{40}"))) {
            "FFMPEG_REVISION must contain a lowercase 40-character Git commit"
        }
    }
val rustJniLibsDir =
    layout.buildDirectory.dir("rustNative/${requestedAbi ?: "all"}/jniLibs")
val rustNdkVersion = "29.0.14033849"
val rustSdkDir =
    run {
        val localProperties = rootProject.file("local.properties")
        val configuredSdk =
            localProperties.takeIf { it.isFile }?.let { file ->
                Properties().apply { file.inputStream().use { input -> load(input) } }
                    .getProperty("sdk.dir")
            }
        (configuredSdk ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
            ?.let(::file)
            ?: error("Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME")
    }
val rustNdkDir =
    (System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT"))
        ?.let(::file)
        ?.takeIf { it.isDirectory }
        ?.absolutePath
        ?: rustSdkDir.resolve("ndk/$rustNdkVersion").absolutePath
val nativeCrateDir = rootProject.file("native")

android {
    namespace = "dev.okhsunrog.yamu"
    compileSdk = 37
    ndkVersion = rustNdkVersion

    defaultConfig {
        applicationId = "dev.okhsunrog.yamu"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = yamuVersionName
        buildConfigField("String", "FFMPEG_REVISION", "\"$ffmpegRevision\"")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*selectedAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("shared") {
            signingProperties?.let { properties ->
                keyAlias = properties.required("keyAlias")
                keyPassword = properties.required("password")
                storeFile = file(properties.required("storeFile"))
                storePassword = properties.required("password")
            }
        }
    }

    buildTypes {
        release {
            if (signingProperties != null) {
                signingConfig = signingConfigs.getByName("shared")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            if (signingProperties != null) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    sourceSets["main"].jniLibs.directories.add(rustJniLibsDir.get().asFile.absolutePath)
    sourceSets["main"].assets.directories.add(rootProject.file("LICENSES").absolutePath)
}

base {
    archivesName = "yamu-downloader-$yamuVersionName"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("rustls:rustls-platform-verifier:0.1.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

val buildRust = tasks.register<Exec>("buildRust") {
    group = "build"
    description = "Builds the Yamu JNI library with cargo-ndk."
    workingDir = nativeCrateDir
    environment("ANDROID_NDK_HOME", rustNdkDir)
    environment("NDK_HOME", rustNdkDir)
    inputs.dir(nativeCrateDir.resolve("src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(nativeCrateDir.resolve("Cargo.toml"))
    inputs.file(nativeCrateDir.resolve("Cargo.lock"))
    inputs.dir(rootProject.file("vendor/ffmpeg-sys-next"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("vendor/mp3lame-sys"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("abis", selectedAbis)
    outputs.dir(rustJniLibsDir)
    commandLine(
        buildList {
            add("cargo")
            add("ndk")
            selectedAbis.forEach { abi ->
                add("-t")
                add(abi)
            }
            addAll(
                listOf(
                    "-P", "26",
                    "-o", rustJniLibsDir.get().asFile.absolutePath,
                    "build", "--release", "--locked",
                ),
            )
        },
    )
}

tasks.named("preBuild").configure {
    dependsOn(buildRust)
}
