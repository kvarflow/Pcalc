import java.net.URI
import java.net.URL
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.kvarflow.pcalc"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.kvarflow.pcalc"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.0-beta.3"
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
    androidResources {
        noCompress += listOf("png", "jpg", "webp")
        // The default also skips folders starting with "_", which would drop every trainer picture
        // (smogon/sprites keeps them under _uncategorized/). Skip only version-control clutter.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    // RetroArchSyncTest writes the /state it read here when asked (to compare with retroarch_sync.py).
    providers.gradleProperty("syncStateOut").orNull?.let { systemProperty("syncStateOut", it) }
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
 * Copies the Pokémon sprites (the May8th1995/sprites submodule, the set rr-calc shows) into the
 * APK's assets under sprites/, so the app can answer the calculator's sprite requests offline.
 */
abstract class CopySprites : DefaultTask() {
    @get:InputDirectory
    abstract val sprites: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        val root = sprites.get().asFile
        check(root.listFiles { f -> f.name.endsWith(".png") }.orEmpty().isNotEmpty()) {
            "No sprites in $root. Fetch the submodule:  git submodule update --init sprites"
        }
        fs.sync {
            from(root) { include("*.png") }
            into(outputDir.dir("sprites"))
        }
    }
}

/**
 * Packs the images the calculator loads from elsewhere by a fixed address (trainer pictures, item
 * icons, Professor Oak), which rr-calc's build lists in dist/offline-assets.json, under
 * remote/<host>/<path>, where the app answers those addresses from (MainActivity.bundledRemote).
 * Each is downloaded once into build/remote-cache and kept; the addresses pin a commit, so a cached
 * file never goes stale. A calculator built before offline-assets.json existed has nothing to pack.
 */
abstract class PackRemoteAssets : DefaultTask() {
    @get:InputFiles
    @get:Optional
    abstract val list: RegularFileProperty

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun pack() {
        val listFile = list.orNull?.asFile
        val urls = if (listFile != null && listFile.isFile) {
            Regex("\"(https://[^\"]+)\"").findAll(listFile.readText()).map { it.groupValues[1] }.toList()
        } else {
            emptyList()
        }
        val cache = cacheDir.get().asFile
        for (url in urls) {
            val uri = URI(url)
            val target = cache.resolve(uri.host + uri.path)
            if (target.isFile && target.length() > 0) continue
            target.parentFile.mkdirs()
            val partial = File(target.path + ".part")
            URL(url).openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            check(partial.renameTo(target)) { "Couldn't save $target" }
        }
        val wanted = urls.map { URI(it).let { u -> u.host + u.path } }.toSet()
        fs.sync {
            from(cache) { include { it.isDirectory || it.relativePath.pathString in wanted } }
            into(outputDir.dir("remote"))
            includeEmptyDirs = false
        }
        logger.lifecycle("Packed ${wanted.size} images the calculator loads from elsewhere")
    }
}

val packRemoteAssets = tasks.register<PackRemoteAssets>("packRemoteAssets") {
    list.set(calcDist.map { it.file("offline-assets.json") })
    cacheDir.set(layout.buildDirectory.dir("remote-cache"))
}

val copySprites = tasks.register<CopySprites>("copySprites") {
    sprites.set(rootProject.layout.projectDirectory.dir("sprites"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyCalc, CopyCalc::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(copySprites, CopySprites::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(packRemoteAssets, PackRemoteAssets::outputDir)
    }
}
