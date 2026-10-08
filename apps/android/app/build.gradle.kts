import java.security.KeyStore
import java.security.MessageDigest
import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.artifact.SingleArtifact
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

abstract class VerifyDebugKeystore : DefaultTask() {
    @get:InputFile
    abstract val keystoreFile: RegularFileProperty

    @get:Input
    abstract val expectedSha256: Property<String>

    @TaskAction
    fun verify() {
        val file = keystoreFile.get().asFile
        check(file.isFile) { "The shared Android debug keystore is missing at ${file.path}." }
        val keystore = KeyStore.getInstance("PKCS12")
        file.inputStream().use { input ->
            keystore.load(input, "android".toCharArray())
        }
        val certificate = checkNotNull(keystore.getCertificate("androiddebugkey")) {
            "The shared Android debug certificate alias is missing."
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { byte -> "%02X".format(byte) }
        check(actual == expectedSha256.get()) {
            "The Android debug certificate fingerprint does not match the documented identity."
        }
    }
}

abstract class VerifyReleaseIsolation @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assetsDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val checker: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val prototypeManifests: ConfigurableFileCollection

    @get:Input
    abstract val applicationId: Property<String>

    @TaskAction
    fun verify() {
        val arguments = listOf("uv", "run", checker.get().asFile.absolutePath,
            "--manifest", manifestFile.get().asFile.absolutePath,
            "--assets", assetsDirectory.get().asFile.absolutePath,
            "--application-id", applicationId.get()) + prototypeManifests.files.sortedBy { it.path }.flatMap {
                listOf("--forbidden-manifest", it.absolutePath)
            }
        execOperations.exec { commandLine(arguments) }
    }
}

android {
    namespace = "net.fstab.tachiai"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "net.fstab.tachiai"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("diagnostic") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".diagnostic"
            versionNameSuffix = "-diagnostic"
            matchingFallbacks += "debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("diagnostic")) { variant ->
        checkNotNull(variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]).enable = true
    }
    onVariants(selector().withBuildType("release")) { variant ->
        tasks.register<VerifyReleaseIsolation>("verifyReleaseIsolation") {
            group = "verification"
            description = "Check release manifest/assets exclude debug prototype and browser-lab entry points."
            applicationId.set(variant.applicationId)
            manifestFile.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            assetsDirectory.set(variant.artifacts.get(SingleArtifact.ASSETS))
            checker.set(rootProject.layout.projectDirectory.file("../../scripts/verify-release-isolation.py"))
            prototypeManifests.from(layout.projectDirectory.file("src/debug/AndroidManifest.xml"),
                layout.projectDirectory.file("src/diagnostic/AndroidManifest.xml"))
        }
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.webkit)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    debugImplementation(libs.media3.exoplayer.dash)
    debugImplementation(libs.okhttp)
    // Generated by the pinned routebridge build, never checked into Git/APK assets.
    debugImplementation(files(rootProject.layout.projectDirectory.file("routebridge/build/routebridge.aar")))
    implementation(libs.media3.ui)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testDebugImplementation(libs.json)
    testDebugImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

val verifySharedDebugKeystore = tasks.register<VerifyDebugKeystore>("verifySharedDebugKeystore") {
    keystoreFile.fileValue(File(System.getProperty("user.home"), ".android/debug.keystore"))
    expectedSha256.set("A258F5F71E7D828F51A3AAF6AA94F97B9DBFBD64E0CE08394A59C120914A7A17")
}

tasks.configureEach {
    if (name == "validateSigningDebug" || name == "validateSigningDiagnostic") dependsOn(verifySharedDebugKeystore)
}
