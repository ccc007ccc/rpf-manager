package com.gtav.rpfmanager.astc

import java.io.IOException

/** SAF cannot atomically replace an existing name. Journal both renames and
 * retain the original until the published document has the validated digest. */
class AstcPublisher(private val store: AstcPublishStore, private val journal: AstcJournal) {
    fun replace(original: AstcDocument, temporary: AstcDocument, expectedHash: String, job: String) {
        val pending = journal.load()
        check(pending == null || (pending.phase == "staging" && pending.parent == original.parent && pending.temporaryName == temporary.name)) { "Recover the previous replacement before continuing" }
        require(original.rename && original.delete && temporary.rename && temporary.delete) { "This storage provider cannot safely replace RPF files" }
        val backupName = ".${original.name}.astc-backup-$job"
        check(store.find(original.parent, backupName) == null) { "Recovery backup already exists" }
        val current = store.find(original.parent, original.name)
        check(current != null && current.id == original.id && current.size == original.size && current.modified == original.modified) { "Source changed while converting" }
        var record = AstcReplacement(original.parent, original.name, backupName, temporary.name, expectedHash, temporary.size)
        journal.save(record)
        try {
            val backup = store.rename(original, backupName)
            record = record.copy(backupName = backup.name)
            journal.save(record)
            check(backup.name == backupName) { "Provider changed the backup name" }
            record = record.copy(phase = "backed-up")
            journal.save(record)
            check(store.find(original.parent, original.name) == null) { "Another file appeared at the source name" }
            val published = store.rename(temporary, original.name)
            record = record.copy(phase = "published", publishedId = published.id)
            journal.save(record)
            check(published.name == original.name && published.size == record.expectedSize && store.hash(published) == expectedHash) { "Published RPF failed verification" }
            journal.save(record.copy(phase = "verified"))
            store.delete(backup)
            journal.clear()
        } catch (error: Exception) {
            try { recover() } catch (recovery: Exception) { error.addSuppressed(recovery) }
            throw error
        }
    }

    /** Called before a new batch, including after process death. Ambiguous files
     * are preserved; an unrelated file at the original name is never removed. */
    fun recover(): Boolean {
        val record = journal.load() ?: return false
        val current = store.find(record.parent, record.originalName)
        val backup = store.find(record.parent, record.backupName)
        val temporary = store.find(record.parent, record.temporaryName)
        if (record.phase == "staging") {
            temporary?.let(store::delete)
            journal.clear()
            return true
        }
        if (current != null && current.size == record.expectedSize && store.hash(current) == record.expectedHash) {
            backup?.let(store::delete)
            temporary?.takeIf { it.id != current.id }?.let(store::delete)
            journal.clear()
            return true
        }
        if (backup != null) {
            if (current != null) {
                if (record.publishedId.isNotBlank() && current.id == record.publishedId) {
                    // Preserve the failed output too; restore the verified source.
                    val name = record.temporaryName + ".failed"
                    check(store.find(record.parent, name) == null) { "Recovery output name already exists" }
                    store.rename(current, name)
                } else {
                    throw IOException("Recovery backup preserved as ${record.backupName}; another file uses ${record.originalName}")
                }
            }
            val restored = store.rename(backup, record.originalName)
            check(restored.name == record.originalName) { "Provider could not restore the original name; keep the backup" }
            temporary?.let(store::delete)
            journal.clear()
            return true
        }
        if (record.phase == "prepared" && current != null) {
            temporary?.let(store::delete)
            journal.clear()
            return true
        }
        throw IOException("Replacement needs recovery: ${record.originalName}; no files were removed")
    }
}
