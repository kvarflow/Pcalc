import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** The rr-calc submodule's commit and its time (seconds); "unknown", 0 if it can't be read. */
val calcCommit: Provider<Pair<String, Long>> = providers.exec {
    commandLine("git", "-C", rootProject.file("rr-calc").path, "log", "-1", "--format=%H %ct")
    isIgnoreExitValue = true
}.standardOutput.asText.map { out ->
    val parts = out.trim().split(" ")
    if (parts.size == 2 && parts[0].matches(Regex("[0-9a-f]{40}"))) parts[0] to parts[1].toLong() else "unknown" to 0L
}

android {
    namespace = "io.github.kvarflow.pcalc"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.kvarflow.pcalc"
        // Android 9: the first with APK signing key rotation (see README, Signing).
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.0-beta.5"

        // The calculator packed into this APK: its rr-calc commit, and that commit's time, which
        // orders calculator builds (CalcUpdater uses a downloaded one only if it's newer).
        buildConfigField("String", "CALC_COMMIT", "\"${calcCommit.get().first}\"")
        buildConfigField("long", "CALC_SERIAL", "${calcCommit.get().second}L")
    }

    signingConfigs {
        // A fixed key kept in the repo, so every build (local or CI) can be installed over the last
        // one. It is a sideloading key, not a secret: see README.
        create("sideload") {
            storeFile = rootProject.file("keystore/pcalc-sideload.jks")
            storePassword = "pcalc-sideload"
            keyAlias = "pcalc"
            keyPassword = "pcalc-sideload"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.getByName("sideload")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The calculator's pages and data are packed as-is; compressing them saves little.
    // From minSdk 28 the code (classes.dex) is stored uncompressed by default, which adds ~3.5 MB to
    // a sideloaded APK; keep it compressed.
    packaging {
        dex {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += listOf("png", "jpg", "webp")
        // Pack every file the calculator has (the default skips folders starting with "_");
        // skip only version-control clutter.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
    // The real org.json for unit tests (Android's own is only a stub off the device).
    testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach {
    // RetroArchSyncTest writes the /state it read here when asked (to compare with retroarch_sync.py).
    providers.gradleProperty("syncStateOut").orNull?.let { systemProperty("syncStateOut", it) }
    // CalcUpdaterPublishedTest checks a folder of published calculator files when given one.
    providers.gradleProperty("calcSim").orNull?.let { systemProperty("calcSim", it) }
}

/**
 * Copies the built calculator (rr-calc's `node build` output) into the APK's assets.
 * By default it reads the rr-calc submodule's dist/; `-PcalcDist=<dir>` points it elsewhere.
 */
abstract class CopyCalc : DefaultTask() {
    @get:InputDirectory
    abstract val dist: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        val root = dist.get().asFile
        check(root.resolve("index.html").isFile) {
            "No built calculator in $root. Build it first:  cd rr-calc && npm install && node build"
        }
        fs.sync {
            from(root) {
                // The service worker caches the site for offline use in a browser; the app ships
                // the files, so it is left out (and the app answers sw.js with 404).
                exclude("sw.js", ".nojekyll", "**/__pycache__/**")
                // As rr-calc's publish-pages leaves out: build inputs, tests and type/map files.
                exclude("**/*.template.html", "calc/test/**", "oms.html", "**/*.map", "**/*.d.ts")
            }
            into(outputDir)
        }
    }
}

val calcDist: Provider<Directory> = providers.gradleProperty("calcDist")
    .map { rootProject.layout.projectDirectory.dir(it) }
    .orElse(rootProject.layout.projectDirectory.dir("rr-calc/dist"))

val copyCalc = tasks.register<CopyCalc>("copyCalc") {
    dist.set(calcDist)
}

/**
 * The calculator as Pcalc's in-app updates ship it: exactly what copyCalc packs into the APK, zipped
 * (build/calc-bundle/calc.zip). The Calculator workflow builds it from rr-calc's newest commit.
 */
tasks.register<Zip>("calcBundle") {
    from(copyCalc.flatMap { it.outputDir })
    archiveFileName.set("calc.zip")
    destinationDirectory.set(layout.buildDirectory.dir("calc-bundle"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyCalc, CopyCalc::outputDir)
    }
}
