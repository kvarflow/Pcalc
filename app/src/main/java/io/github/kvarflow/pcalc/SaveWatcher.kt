package io.github.kvarflow.pcalc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.security.MessageDigest

/**
 * The game's save file the calculator imports by itself (rr-calc's auto-import, save_import.js).
 *
 * The user picks it once (RetroArch keeps it as <game>.srm in its saves folder); the app keeps the
 * permission to read it. While the calculator is open the app looks at it every few seconds and,
 * when it has changed, hands its bytes to the page, which imports them as Import .sav would (only
 * when it's the same run; anything else it leaves for Import .sav). The app never writes to it.
 */
class SaveWatcher(private val context: Context) {

    class Snapshot(val name: String, val version: String, val modified: Long, val size: Long, val bytes: ByteArray)

    private val prefs = context.getSharedPreferences("save", Context.MODE_PRIVATE)
    private var lastVersion: String? = null

    val uri: Uri? get() = prefs.getString(KEY_URI, null)?.let(Uri::parse)
    val name: String? get() = if (uri != null) prefs.getString(KEY_NAME, null) else null

    /** Keeps `uri` (from the file picker) as the save to watch. */
    fun choose(uri: Uri) {
        stop()
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "save"
        prefs.edit().putString(KEY_URI, uri.toString()).putString(KEY_NAME, name).apply()
        lastVersion = null
    }

    fun stop() {
        uri?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit().remove(KEY_URI).remove(KEY_NAME).apply()
        lastVersion = null
    }

    /** Forgets what was last handed over, so the next [changed] hands the file over again (a new page). */
    fun resend() {
        lastVersion = null
    }

    /**
     * The file, if it changed since it was last handed over (or [resend] was called); null if not, or if
     * there is no file. Throws when it can't be read (moved, permission gone).
     */
    fun changed(): Snapshot? {
        val uri = uri ?: return null
        var modified = 0L
        var size = -1L
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, OpenableColumns.SIZE), null, null, null,
        )?.use {
            if (it.moveToFirst()) {
                if (!it.isNull(0)) modified = it.getLong(0)
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        // Where the time and size are known, they say whether it changed without reading it.
        val cheap = if (modified > 0 && size >= 0) "$modified:$size" else null
        if (cheap != null && cheap == lastVersion) return null
        check(size <= MAX_SIZE) { "that file is too big to be a save" }
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        check(bytes.size <= MAX_SIZE) { "that file is too big to be a save" }
        val version = cheap ?: MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (version == lastVersion) return null
        lastVersion = version
        return Snapshot(name ?: "save", version, modified, bytes.size.toLong(), bytes)
    }

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        // A GBA save is 128 KB; nothing bigger is one.
        private const val MAX_SIZE = 1024 * 1024L
    }
}
