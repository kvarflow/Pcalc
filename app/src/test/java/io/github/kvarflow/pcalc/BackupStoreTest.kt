package io.github.kvarflow.pcalc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only names BackupStore makes are ever read or deleted, so a name from the page can't reach other files. */
class BackupStoreTest {
    @Test
    fun acceptsItsOwnNames() {
        for (reason in listOf("auto", "manual", "before-restore")) {
            assertTrue(BackupStore.isBackupName("pcalc-backup-2026-11-06_14-00-01-$reason.json"))
        }
    }

    @Test
    fun refusesAnythingElse() {
        listOf(
            "../shared_prefs/backups.xml",
            "pcalc-backup-2026-11-06_14-00-01-auto.json/../../x",
            "pcalc-backup-2026-11-06_14-00-01-other.json",
            "pcalc-backup-2026-11-06_14-00-01-auto.json.part",
            "/data/data/io.github.kvarflow.pcalc/files/secret",
            "",
        ).forEach { assertFalse(it, BackupStore.isBackupName(it)) }
    }
}
