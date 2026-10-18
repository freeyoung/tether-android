import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The release key stays on the build machine, never in git: the path of its
// properties file (storeFile, storePassword, keyAlias, keyPassword) is the
// Gradle property tether.keystore, in ~/.gradle/gradle.properties. Without
// it, a release build is not signed.
val keystore = providers.gradleProperty("tether.keystore").orNull?.let { path ->
    Properties().apply { file(path).inputStream().use { load(it) } }
}

// Each commit a higher version code, so a new APK installs over the old one.
fun git(vararg args: String): String = providers.exec {
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

android {
    namespace = "io.github.freeyoung.tether"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.freeyoung.tether"
        minSdk = 29
        targetSdk = 37
        versionCode = git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
        versionName = providers.gradleProperty("tether.version").orNull ?: "0.1.0-dev"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

// libtether.so: the engine, tether-core-go, for each ABI.
val buildNative = tasks.register<Exec>("buildNative") {
    description = "Builds libtether.so with scripts/build-native.sh."
    workingDir = rootDir
    commandLine("scripts/build-native.sh")
    inputs.files(fileTree("../go") { include("**/*.go", "**/*.c", "go.mod", "go.sum"); exclude("**/*_test.go") })
    outputs.dir("src/main/jniLibs")
}
tasks.named("preBuild") { dependsOn(buildNative) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
