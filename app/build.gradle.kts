import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
}

android {
    namespace = "dev.okhsunrog.yamusdownloader"
    compileSdk = 37
    ndkVersion = "29.0.14033849"

    defaultConfig {
        applicationId = "dev.okhsunrog.yamusdownloader"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val buildRust = tasks.register<Exec>("buildRust") {
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build-rust.sh")
    inputs.files(
        rootProject.fileTree("native/src"),
        rootProject.file("native/Cargo.toml"),
        rootProject.file("native/Cargo.lock"),
    )
    inputs.dir(rootProject.file("../ya-music/src"))
    outputs.files(
        file("src/main/jniLibs/arm64-v8a/libya_mus_downloader.so"),
        file("src/main/jniLibs/x86_64/libya_mus_downloader.so"),
    )
}

tasks.named("preBuild").configure {
    dependsOn(buildRust)
}
