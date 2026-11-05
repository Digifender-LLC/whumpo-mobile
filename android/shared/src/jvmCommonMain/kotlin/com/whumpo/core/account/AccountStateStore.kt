package com.whumpo.core.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * Encrypted, account-scoped local state (drafts, the native session and pending
 * operations). Exactly one account may hold state at a time:
 *
 * - [activate] with a different account erases every other account's state first;
 * - [signOut] erases all state and destroys the encryption key;
 * - unreadable state (lost key, tampering, restored files) is erased, never trusted.
 *
 * [root] must be an app-private, no-backup directory. All methods do blocking file I/O;
 * call them off the main thread.
 */
class AccountStateStore(
    private val root: File,
    private val box: SecretBox,
) {
    private val active = MutableStateFlow<AccountScope?>(null)

    /** The account whose state is currently readable, or null when signed out. */
    val activeAccount: StateFlow<AccountScope?> = active.asStateFlow()

    /** Loads the persisted active account. Corrupt or undecryptable state is erased. */
    @Synchronized
    fun restore() {
        val marker = File(root, ACTIVE_MARKER)
        if (!marker.exists()) {
            active.value = null
            return
        }
        val restored = try {
            AccountScope.of(String(box.open(marker.readBytes(), MARKER_AAD), Charsets.UTF_8))
        } catch (error: Exception) {
            null
        }
        if (restored == null) {
            eraseAll()
        }
        active.value = restored
    }

    /**
     * Makes [account] the owner of local state. Switching from another account erases
     * that account's drafts, session and pending operations before anything is written.
     */
    @Synchronized
    fun activate(account: AccountScope) {
        if (active.value == account) return
        eraseAll()
        root.mkdirs()
        writeAtomically(
            File(root, ACTIVE_MARKER),
            box.seal(account.accountId.toByteArray(Charsets.UTF_8), MARKER_AAD),
        )
        active.value = account
    }

    /** Erases every account's state and destroys the key. Safe to call when signed out. */
    @Synchronized
    fun signOut() {
        eraseAll()
        box.destroyKey()
        active.value = null
    }

    /**
     * Stores [value] for [owner]. Returns false, writing nothing, when [owner] is no
     * longer the active account, so late work from a previous account can never land in
     * the next account's state.
     */
    @Synchronized
    fun write(owner: AccountScope, slot: StateSlot, value: String): Boolean {
        if (active.value != owner) return false
        val dir = File(root, owner.storageKey).apply { mkdirs() }
        writeAtomically(
            File(dir, slot.fileName),
            box.seal(value.toByteArray(Charsets.UTF_8), associatedData(owner, slot)),
        )
        return true
    }

    /** Returns [owner]'s value, or null if absent or [owner] is not active. Unreadable records are deleted. */
    @Synchronized
    fun read(owner: AccountScope, slot: StateSlot): String? {
        if (active.value != owner) return null
        val file = File(File(root, owner.storageKey), slot.fileName)
        if (!file.exists()) return null
        return try {
            String(box.open(file.readBytes(), associatedData(owner, slot)), Charsets.UTF_8)
        } catch (error: Exception) {
            file.delete()
            null
        }
    }

    /** Deletes [owner]'s record; returns false when [owner] is not active. */
    @Synchronized
    fun remove(owner: AccountScope, slot: StateSlot): Boolean {
        if (active.value != owner) return false
        File(File(root, owner.storageKey), slot.fileName).delete()
        return true
    }

    /** The slot names [owner] holds for [kind], or empty when [owner] is not active. */
    @Synchronized
    fun list(owner: AccountScope, kind: StateKind): List<String> {
        if (active.value != owner) return emptyList()
        val prefix = "${kind.fileTag}-"
        return File(root, owner.storageKey).listFiles().orEmpty()
            .map { it.name }
            .filter { it.startsWith(prefix) && it.endsWith(".bin") }
            .map { it.removePrefix(prefix).removeSuffix(".bin") }
            .filter { StateSlot.isValidName(it) }
            .sorted()
    }

    private fun eraseAll() {
        if (root.exists() && !root.deleteRecursively()) {
            throw IOException("Could not erase account state")
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not store account state")
        }
    }

    private fun associatedData(account: AccountScope, slot: StateSlot): ByteArray =
        "whumpo-state-v1:${account.storageKey}:${slot.fileName}".toByteArray(Charsets.UTF_8)

    private companion object {
        const val ACTIVE_MARKER = "active.bin"
        val MARKER_AAD = "whumpo-state-v1:active".toByteArray(Charsets.UTF_8)
    }
}
