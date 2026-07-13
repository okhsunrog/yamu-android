import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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
            abiFilters += listOf("arm64-v8a", "x86_64")
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

    buildFeatures {
        compose = true
    }
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
}

val buildRust = tasks.register<Exec>("buildRust") {
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build-rust.sh")
    inputs.files(
        rootProject.fileTree("native/src"),
        rootProject.file("native/Cargo.toml"),
        rootProject.file("native/Cargo.lock"),
        rootProject.file("../ya-music/Cargo.toml"),
        rootProject.file("../ya-music/Cargo.lock"),
    )
    inputs.dir(rootProject.file("../ya-music/src"))
    inputs.dir(rootProject.file("vendor/ffmpeg-sys-next"))
    inputs.dir(rootProject.file("vendor/mp3lame-sys"))
    outputs.files(
        file("src/main/jniLibs/arm64-v8a/libya_mus_downloader.so"),
        file("src/main/jniLibs/x86_64/libya_mus_downloader.so"),
    )
}

tasks.named("preBuild").configure {
    dependsOn(buildRust)
}
