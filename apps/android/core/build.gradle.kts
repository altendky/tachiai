import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "net.fstab.tachiai"
version = "0.1.0"

val repositoryRoot = layout.projectDirectory.dir("../../..")
val generatedCore = repositoryRoot.dir("build/core")
val prepareCore = tasks.register<Exec>("prepareCore") {
    group = "build"
    description = "Build the pinned Rust library and generate its Kotlin bindings."
    workingDir(repositoryRoot)
    commandLine("bash", repositoryRoot.file("scripts/build-android-core.sh").asFile.absolutePath)
    // Cargo owns dependency/incremental tracking. Always invoke its checked build,
    // so changed or absent native outputs cannot silently reuse stale bindings.
}

android {
    namespace = "net.fstab.tachiai.core"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    defaultConfig {
        minSdk = 26
        ndk.abiFilters += setOf("arm64-v8a", "x86_64")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory(generatedCore.dir("bindings/kotlin").asFile.absolutePath)
        variant.sources.jniLibs?.addStaticSourceDirectory(generatedCore.dir("native/android").asFile.absolutePath)
        variant.sources.assets?.addStaticSourceDirectory(generatedCore.dir("licenses").asFile.absolutePath)
    }
}

dependencies {
    api("${libs.jna.get()}@aar")
    testImplementation(libs.jna)
}

tasks.configureEach {
    if (name == "preBuild" || name == "sourceReleaseJar") dependsOn(prepareCore)
}

tasks.withType<Test>().configureEach {
    dependsOn(prepareCore)
    systemProperty("jna.library.path", generatedCore.dir("native/host").asFile.absolutePath)
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                artifactId = "tachiai-core"
                from(components["release"])
            }
        }
    }
}
