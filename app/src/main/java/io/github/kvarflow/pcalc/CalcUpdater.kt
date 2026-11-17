package io.github.kvarflow.pcalc

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipInputStream

/**
 * Keeps the calculator up to date without a new APK.
 *
 * The APK carries a copy of the calculator (rr-calc) from when it was built. Once a day, while the app
 * is open and there is a connection, this asks Pcalc's "calculator" release on GitHub for the newest
 * one (the Calculator workflow publishes it whenever rr-calc changes). A new calculator is used only
 * if:
 *  - its manifest is signed with Pcalc's calculator key (the public half is [PUBLIC_KEY]; the private
 *    half is only in the repository's secrets), so nobody else can hand the app code to run;
 *  - it's newer than the one in use ([Version.serial], the rr-calc commit's time), so an old signed
 *    calculator can't be passed off as an update;
 *  - this app is new enough for it (minApp);
 *  - the download is exactly the file the manifest names (size and SHA-256), and unpacks safely.
 * It's unpacked next to the one in use and switched to when the page reloads ([apply]) or the app
 * next starts. If anything is wrong the app keeps what it has, and with no connection it simply
 * carries on offline. The calculator's saved data (its box, runs, settings) doesn't move: it belongs
 * to the page's address, which stays the same.
 *
 * Plain files and no Android types, so it can be tested on the JVM.
 */
class CalcUpdater(
    /** The app's private folder for downloaded calculators (filesDir/calc). */
    private val root: File,
    /** This app's versionCode, for the manifest's minApp. */
    private val appVersion: Int,
    /** The calculator inside the APK. */
    private val bundled: Version,
    private val publicKey: String = PUBLIC_KEY,
    private val baseUrl: String = BASE_URL,
    /** Fetches a URL's body, refusing more than `limit` bytes. */
    private val fetch: (url: String, limit: Long) -> InputStream = ::httpGet,
) {
    /** A calculator build: its rr-calc commit and that commit's time (seconds), which orders them. */
    data class Version(val commit: String, val serial: Long)

    sealed class Result {
        object UpToDate : Result()
        data class Updated(val version: Version) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val stateFile = File(root, "state.properties")

    /** The downloaded calculator to use, if it's newer than the APK's; null for the APK's own. */
    fun installed(): Pair<Version, File>? {
        val state = readState()
        val commit = state.getProperty("commit") ?: return null
        val serial = state.getProperty("serial")?.toLongOrNull() ?: return null
        val dir = File(root, commit)
        if (serial <= bundled.serial || !COMMIT.matches(commit) || !File(dir, "index.html").isFile) return null
        return Version(commit, serial) to dir
    }

    /** The calculator in use: the downloaded one, or the APK's. */
    fun current(): Version = installed()?.first ?: bundled

    /** Removes downloaded calculators other than [keep] (and leftovers of unfinished downloads). */
    fun cleanUp(keep: File?) {
        root.listFiles()?.forEach { file ->
            if (file != stateFile && file.canonicalFile != keep?.canonicalFile) file.deleteRecursively()
        }
        if (keep == null) stateFile.delete()
    }

    /** When it last heard from GitHub (ms), or null if never. */
    fun lastCheck(): Long? = readState().getProperty("lastCheck")?.toLongOrNull()

    /** Time to ask again: never asked, a day since, or the clock went back. */
    fun due(now: Long): Boolean = lastCheck()?.let { now - it !in 0 until CHECK_EVERY_MS } ?: true

    /**
     * Looks for a newer calculator and, if there is one, downloads and unpacks it ready for use.
     * Never throws: problems come back as [Result.Failed], and the calculator in use stays.
     */
    fun check(now: Long): Result {
        var offline = false
        val result = try {
            update()
        } catch (e: IOException) {
            // No connection (or it dropped): ask again next time rather than tomorrow.
            offline = true
            Result.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (!offline) runCatching { writeState { setProperty("lastCheck", now.toString()) } }
        return result
    }

    private fun update(): Result {
        val manifestBytes = fetch("$baseUrl/$MANIFEST", MAX_MANIFEST).use { it.readBytes() }
        val signature = fetch("$baseUrl/$MANIFEST.sig", MAX_SIGNATURE).use { it.readBytes() }
        if (!verify(manifestBytes, signature)) throw IllegalStateException("$REFUSED its signature isn't Pcalc's")
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        if (manifest.optInt("format") != 1) throw IllegalStateException("$REFUSED a manifest this app doesn't understand")
        val version = Version(manifest.getString("commit"), manifest.getLong("serial"))
        val file = manifest.getString("file")
        val size = manifest.getLong("size")
        val sha256 = manifest.getString("sha256").lowercase()
        if (!COMMIT.matches(version.commit) || !FILE.matches(file) || !SHA256.matches(sha256) || size !in 1..MAX_ZIP) {
            throw IllegalStateException("$REFUSED a malformed manifest")
        }
        if (version.serial <= current().serial) return Result.UpToDate
        if (manifest.optInt("minApp", 0) > appVersion) return Result.UpToDate

        root.mkdirs()
        val zip = File(root, ".download.zip")
        val unpacked = File(root, ".unpacking")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            fetch("$baseUrl/$file", size + 1).use { input ->
                zip.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > size) throw IllegalStateException("$REFUSED the download is bigger than the manifest says")
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                    if (total != size) throw IllegalStateException("the download stopped early")
                }
            }
            if (hex(digest.digest()) != sha256) throw IllegalStateException("$REFUSED the download isn't the file the manifest names")
            unpacked.deleteRecursively()
            unzip(zip, unpacked)
            if (!File(unpacked, "index.html").isFile) throw IllegalStateException("$REFUSED the download has no calculator in it")
            val dir = File(root, version.commit)
            dir.deleteRecursively()
            if (!unpacked.renameTo(dir)) throw IllegalStateException("couldn't put the new calculator in place")
            writeState {
                setProperty("commit", version.commit)
                setProperty("serial", version.serial.toString())
            }
            return Result.Updated(version)
        } finally {
            zip.delete()
            unpacked.deleteRecursively()
        }
    }

    private fun verify(data: ByteArray, signature: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    } catch (e: Exception) {
        false
    }

    /** Unpacks [zip] into [into]: only plain files inside it, no more than MAX_UNPACKED bytes. */
    private fun unzip(zip: File, into: File) {
        val base = into.canonicalFile
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { entries ->
            while (true) {
                val entry = entries.nextEntry ?: break
                val target = File(base, entry.name).canonicalFile
                if (!target.path.startsWith(base.path + File.separator)) {
                    throw IllegalStateException("$REFUSED the download has a file outside its folder")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = entries.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNPACKED) throw IllegalStateException("$REFUSED the download unpacks too big")
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    private fun readState(): Properties = Properties().apply {
        runCatching { stateFile.inputStream().use { load(it) } }
    }

    private fun writeState(change: Properties.() -> Unit) {
        val state = readState().apply(change)
        root.mkdirs()
        val temp = File(root, ".state.tmp")
        temp.outputStream().use { state.store(it, null) }
        if (!temp.renameTo(stateFile)) {
            stateFile.delete()
            temp.renameTo(stateFile)
        }
    }

    companion object {
        /** Pcalc's calculator key (EC P-256, X.509, base64). The Calculator workflow signs with its private half. */
        const val PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEeXUHhBs9Hl11+38TKI0BpoFkPawdyp9+9o5DrQuXJF4bygMxMe9OsiJeuMzs3kftbF1dryAgwfUVjElTSQWN1w=="
        const val BASE_URL = "https://github.com/kvarflow/Pcalc/releases/download/calculator"
        const val MANIFEST = "calc-manifest.json"
        const val CHECK_EVERY_MS = 20 * 60 * 60 * 1000L

        private const val REFUSED = "Not used:"
        private const val MAX_MANIFEST = 16 * 1024L
        private const val MAX_SIGNATURE = 1024L
        private const val MAX_ZIP = 80L * 1024 * 1024
        private const val MAX_UNPACKED = 250L * 1024 * 1024
        private val COMMIT = Regex("[0-9a-f]{40}")
        private val FILE = Regex("calc-[0-9a-f]{7,40}\\.zip")
        private val SHA256 = Regex("[0-9a-f]{64}")

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** GET over HTTPS (GitHub sends release files on from github.com to its download host). */
        fun httpGet(url: String, limit: Long): InputStream {
            var location = URL(url)
            repeat(5) {
                require(location.protocol == "https") { "only https" }
                val connection = (location.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Pcalc")
                }
                when (val code = connection.responseCode) {
                    in 300..399 -> {
                        location = URL(location, connection.getHeaderField("Location") ?: error("redirect without a Location"))
                        connection.disconnect()
                    }
                    200 -> {
                        val length = connection.contentLengthLong
                        if (length > limit) {
                            connection.disconnect()
                            throw IllegalStateException("$REFUSED the file is bigger than expected")
                        }
                        return LimitedStream(connection.inputStream, limit)
                    }
                    404 -> throw IllegalStateException("no calculator published yet")
                    else -> throw IllegalStateException("GitHub answered $code")
                }
            }
            throw IllegalStateException("too many redirects")
        }
    }

    private class LimitedStream(private val inner: InputStream, private val limit: Long) : InputStream() {
        private var read = 0L
        override fun read(): Int {
            val b = inner.read()
            if (b >= 0 && ++read > limit) throw IllegalStateException("$REFUSED the file is bigger than expected")
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) {
                read += n
                if (read > limit) throw IllegalStateException("$REFUSED the file is bigger than expected")
            }
            return n
        }

        override fun close() = inner.close()
    }
}
