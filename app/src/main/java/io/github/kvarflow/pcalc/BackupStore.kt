package io.github.kvarflow.pcalc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the calculator's backups live on the device.
 *
 * By default that is the app's own folder (Android/data/<app>/files/backups), which needs no
 * permission and survives updates. The user can point it at a folder of their own (Android's folder
 * picker), which also survives the app being uninstalled. If that folder can't be written (moved,
 * permission revoked, card removed), backups fall back to the app's folder rather than being lost.
 *
 * Only names this class makes (pcalc-backup-<time>-<reason>.json) are ever read or deleted, so a
 * name from the page can't reach any other file.
 */
class BackupStore(private val context: Context) {

    class Backup(val name: String, val modified: Long)

    private val prefs = context.getSharedPreferences("backups", Context.MODE_PRIVATE)

    private val appDir: File
        get() = (context.getExternalFilesDir("backups") ?: File(context.filesDir, "backups")).apply { mkdirs() }

    /** The chosen folder, if one was chosen and the app may still use it. */
    private val chosenFolder: DocumentFile?
        get() {
            val uri = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse) ?: return null
            val granted = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
            if (!granted) return null
            return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canWrite() }
        }

    /** A name for the place backups go now, for the page to show. */
    fun folderName(): String? = chosenFolder?.name

    fun lastBackup(): Long = prefs.getLong(KEY_LAST, 0)

    /** Keeps `uri` (from the folder picker) as the backup folder from now on. */
    fun chooseFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Let go of an earlier folder, then keep this one across restarts.
        prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)?.let { old ->
            if (old != uri) runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        context.contentResolver.takePersistableUriPermission(uri, flags)
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
    }

    /**
     * Writes a backup and returns its name. `reason` is auto, manual or before-restore. Old automatic
     * backups beyond [KEEP_AUTO] are deleted. Falls back to the app's folder if the chosen one fails.
     */
    fun write(text: String, reason: String): String {
        require(reason in REASONS) { "unknown reason" }
        require(text.length <= MAX_SIZE) { "backup too large" }
        val name = "pcalc-backup-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + "-$reason.json"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val folder = chosenFolder
        val wrote = folder != null && runCatching {
            val file = folder.createFile("application/json", name) ?: error("couldn't create")
            context.contentResolver.openOutputStream(file.uri, "w")!!.use { it.write(bytes) }
        }.isSuccess
        if (!wrote) {
            val tmp = File(appDir, "$name.part")
            tmp.writeBytes(bytes)
            check(tmp.renameTo(File(appDir, name))) { "couldn't save the backup" }
        }
        prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        prune()
        return name
    }

    /** Every backup, newest first: the chosen folder's and the app folder's. */
    fun list(): List<Backup> {
        val found = mutableMapOf<String, Backup>()
        appDir.listFiles()?.forEach { if (isBackupName(it.name)) found[it.name] = Backup(it.name, it.lastModified()) }
        chosenFolder?.let { folder ->
            runCatching {
                folder.listFiles().forEach { file ->
                    val name = file.name ?: return@forEach
                    if (isBackupName(name)) found[name] = Backup(name, file.lastModified())
                }
            }
        }
        // The name starts with the time, so sorting by name sorts by time.
        return found.values.sortedByDescending { it.name }
    }

    /** A backup's text, from the chosen folder or the app's. */
    fun read(name: String): String {
        require(isBackupName(name)) { "not a backup" }
        chosenFolder?.let { folder ->
            val file = runCatching { folder.findFile(name) }.getOrNull()
            if (file != null) {
                return context.contentResolver.openInputStream(file.uri)!!.use { input ->
                    readLimited(input.readBytes())
                }
            }
        }
        val file = File(appDir, name)
        check(file.isFile) { "not found" }
        return readLimited(file.readBytes())
    }

    private fun readLimited(bytes: ByteArray): String {
        check(bytes.size <= MAX_SIZE) { "backup too large" }
        return String(bytes, Charsets.UTF_8)
    }

    /** Keeps the newest [KEEP_AUTO] automatic backups in each place; manual ones are never deleted. */
    private fun prune() {
        appDir.listFiles()?.filter { isBackupName(it.name) && it.name.endsWith("-auto.json") }
            ?.sortedByDescending { it.name }?.drop(KEEP_AUTO)?.forEach { it.delete() }
        chosenFolder?.let { folder ->
            runCatching {
                folder.listFiles().filter { (it.name ?: "").let { n -> isBackupName(n) && n.endsWith("-auto.json") } }
                    .sortedByDescending { it.name }.drop(KEEP_AUTO)
                    .forEach { DocumentsContract.deleteDocument(context.contentResolver, it.uri) }
            }
        }
    }

    companion object {
        const val KEEP_AUTO = 10
        private const val MAX_SIZE = 20 * 1024 * 1024
        private const val KEY_FOLDER = "folder"
        private const val KEY_LAST = "last"
        private val REASONS = setOf("auto", "manual", "before-restore")
        private val NAME = Regex("""pcalc-backup-\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-(auto|manual|before-restore)\.json""")

        fun isBackupName(name: String) = NAME.matches(name)
    }
}
