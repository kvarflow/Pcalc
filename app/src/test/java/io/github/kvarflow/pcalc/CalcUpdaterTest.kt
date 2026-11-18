package io.github.kvarflow.pcalc

import io.github.kvarflow.pcalc.CalcUpdater.Result
import io.github.kvarflow.pcalc.CalcUpdater.Version
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A calculator is only used if it's signed, newer, meant for this app and exactly what was signed. */
class CalcUpdaterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val otherKeys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val bundled = Version("a".repeat(40), 1_000)
    private val newer = "b".repeat(40)
    private val files = mutableMapOf<String, ByteArray>()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun publish(
        zip: ByteArray = zip("index.html" to "<p>new</p>", "js/calc.js" to "1"),
        serial: Long = 2_000,
        minApp: Int = 5,
        signWith: KeyPair = keys,
        sha256Of: ByteArray = zip,
    ) {
        val name = "calc-${newer.take(7)}.zip"
        val manifest = JSONObject()
            .put("format", 1).put("commit", newer).put("serial", serial).put("minApp", minApp)
            .put("file", name).put("size", zip.size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(sha256Of).joinToString("") { "%02x".format(it) })
            .toString().toByteArray()
        files[CalcUpdater.MANIFEST] = manifest
        files[CalcUpdater.MANIFEST + ".sig"] = Signature.getInstance("SHA256withECDSA").run {
            initSign(signWith.private)
            update(manifest)
            sign()
        }
        files[name] = zip
    }

    private fun updater(root: File = folder.root, appVersion: Int = 5) = CalcUpdater(
        root, appVersion, bundled,
        publicKey = Base64.getEncoder().encodeToString(keys.public.encoded),
        baseUrl = "https://example.invalid/calculator",
        fetch = { url, _ -> files[url.substringAfterLast('/')]?.inputStream() ?: throw IOException("offline") },
    )

    @Test
    fun installsANewerSignedCalculator() {
        publish()
        val updater = updater()
        assertEquals(Result.Updated(Version(newer, 2_000)), updater.check(0))
        val (version, dir) = updater.installed()!!
        assertEquals(newer, version.commit)
        assertEquals("<p>new</p>", File(dir, "index.html").readText())
        assertEquals(Result.UpToDate, updater.check(1))
    }

    @Test
    fun refusesAnotherKeysSignature() {
        publish(signWith = otherKeys)
        val result = updater().check(0)
        assertTrue(result is Result.Failed)
        assertNull(updater().installed())
    }

    @Test
    fun ignoresOlderOrSameCalculators() {
        publish(serial = 1_000)
        assertEquals(Result.UpToDate, updater().check(0))
        assertNull(updater().installed())
    }

    @Test
    fun waitsForANewEnoughApp() {
        publish(minApp = 6)
        assertEquals(Result.UpToDate, updater(appVersion = 5).check(0))
        assertNull(updater().installed())
    }

    @Test
    fun refusesADownloadThatIsntTheSignedFile() {
        publish(sha256Of = "something else".toByteArray())
        assertTrue(updater().check(0) is Result.Failed)
        assertNull(updater().installed())
    }

    @Test
    fun refusesFilesOutsideItsFolder() {
        publish(zip = zip("index.html" to "x", "../../escaped.txt" to "x"))
        assertTrue(updater().check(0) is Result.Failed)
        assertNull(updater().installed())
        assertFalse(File(folder.root.parentFile, "escaped.txt").exists())
    }

    @Test
    fun offlineTriesAgainButAnAnswerWaitsADay() {
        val updater = updater()
        assertTrue(updater.check(10) is Result.Failed)
        assertTrue(updater.due(11))
        publish(serial = 1_000)
        updater.check(20)
        assertFalse(updater.due(21))
        assertTrue(updater.due(20 + CalcUpdater.CHECK_EVERY_MS))
    }

    @Test
    fun theApksOwnCalculatorWinsOnceItsNewer() {
        publish()
        updater().check(0)
        val newerApk = CalcUpdater(folder.root, 5, Version("c".repeat(40), 3_000))
        assertNull(newerApk.installed())
        newerApk.cleanUp(null)
        assertFalse(File(folder.root, newer).exists())
    }
}

/**
 * With -PcalcSim=<folder> holding what the Calculator workflow publishes (calc-manifest.json, its .sig
 * and the zip), checks the app accepts it with its real key: the workflow and the app agree.
 */
class CalcUpdaterPublishedTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun acceptsWhatTheWorkflowPublishes() {
        val published = System.getProperty("calcSim")?.let(::File) ?: return
        val updater = CalcUpdater(
            folder.root, 5, Version("0".repeat(40), 0),
            fetch = { url, _ -> File(published, url.substringAfterLast('/')).inputStream() },
        )
        val result = updater.check(0)
        assertTrue(result.toString(), result is Result.Updated)
        assertTrue(File(updater.installed()!!.second, "index.html").isFile)
    }
}
