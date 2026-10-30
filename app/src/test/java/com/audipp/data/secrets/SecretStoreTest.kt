package com.audipp.data.secrets

import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecretStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = "provider:account-private"
    private val kind = SecretKind.REFRESH_TOKEN
    private val token = "private-token-never-log".toByteArray()
    private val keys = TestKeys()
    private val root get() = File(temporary.root, "secrets")
    private fun store() = SecretStore(SecretFiles(root), keys)
    private fun record(accountId: String = account, type: SecretKind = kind) =
        File(File(root, SecretCipher.accountHash(accountId)), "${type.name}.bin")

    @Test fun encryptedValuesSurviveReopenAndOwnedBufferIsCleared() = runBlocking {
        val first = store()
        first.authorize(account, kind, token)
        val encrypted = record().readBytes()
        assertFalse(encrypted.toString(Charsets.UTF_8).contains(token.toString(Charsets.UTF_8)))
        assertFalse(root.walkTopDown().any { it.name.contains(account) })
        val reopened = store()
        val session = reopened.resume(account)
        var borrowed: ByteArray? = null
        reopened.use(session, kind) {
            assertArrayEquals(token, it)
            borrowed = it
        }
        assertTrue(borrowed!!.all { it == 0.toByte() })
        assertArrayEquals("private-token-never-log".toByteArray(), token)
    }

    @Test fun everyWriteHasANewNonceAndAllKindsRoundTrip() = runBlocking {
        val store = store()
        val session = store.authorize(account, kind, token)
        val nonces = mutableSetOf<List<Byte>>()
        repeat(64) {
            store.update(session, kind, token)
            nonces.add(record().readBytes().slice(4..15))
        }
        assertEquals(64, nonces.size)
        for (type in SecretKind.entries) {
            store.update(session, type, token)
            store.use(session, type) { assertArrayEquals(token, it) }
        }
    }

    @Test fun aadRejectsAnotherAccountAndRecordKindEvenWithTheSameKey() {
        val key = keys.create("shared")
        val encrypted = SecretCipher.encrypt(key, account, kind, token)
        assertThrows(GeneralSecurityException::class.java) {
            SecretCipher.decrypt(key, "another-account", kind, encrypted)
        }
        assertThrows(GeneralSecurityException::class.java) {
            SecretCipher.decrypt(key, account, SecretKind.ACCESS_TOKEN, encrypted)
        }
    }

    @Test fun tamperingTruncationAndUnknownVersionsRequireAuthentication() = runBlocking {
        for (damage in listOf<(ByteArray) -> ByteArray>(
            { it.apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() } },
            { it.copyOf(20) },
            { it.apply { this[3] = 2 } },
            { ByteArray(SecretCipher.MAX_RECORD_BYTES + 1) },
        )) {
            val store = store()
            val session = store.authorize(account, kind, token)
            record().writeBytes(damage(record().readBytes()))
            assertFailure(SecretFailure.AUTH_REQUIRED) { store.use(session, kind) { error("Must not expose plaintext") } }
            assertEquals(SecretState.AUTH_REQUIRED, session.state.value)
            assertSame(session, store.resume(account))
        }
    }

    @Test fun missingOrInvalidatedOrDifferentKeyRefusesRestoredCiphertext() = runBlocking {
        val store = store()
        val session = store.authorize(account, kind, token)
        keys.entries.clear()
        assertFailure(SecretFailure.AUTH_REQUIRED) { store.use(session, kind) {} }
        assertEquals(0, keys.entries.size)
        assertTrue(record().exists())
        val reopened = store()
        keys.create("audipp.secrets.v1.${SecretCipher.accountHash(account)}")
        assertFailure(SecretFailure.AUTH_REQUIRED) { reopened.use(reopened.resume(account), kind) {} }
        keys.findFailure = GeneralSecurityException("secret account path")
        val invalidated = store()
        assertFailure(SecretFailure.AUTH_REQUIRED) { invalidated.use(invalidated.resume(account), kind) {} }
    }

    @Test fun providerFailuresNeverFallBackToPlaintextOrExposeTheirCause() = runBlocking {
        keys.createFailure = ProviderException("private-token-never-log")
        val store = store()
        assertFailure(SecretFailure.AUTH_REQUIRED) { store.authorize(account, kind, token) }
        assertFalse(record().exists())
        assertEquals(SecretState.AUTH_REQUIRED, store.resume(account).state.value)
    }

    @Test fun logoutDeletesOnlyThatAccountsSecretsAndOldSessionsStayInvalid() = runBlocking {
        val localLibrary = temporary.newFile("library.db").apply { writeText("library and history") }
        val store = store()
        val first = store.authorize(account, kind, token)
        val other = store.authorize("other", kind, token)
        store.requireAuthentication(account)
        assertFalse(record().exists())
        assertEquals(1, keys.entries.size)
        assertEquals("library and history", localLibrary.readText())
        store.use(other, kind) { assertArrayEquals(token, it) }
        assertFailure(SecretFailure.AUTH_REQUIRED) { store.update(first, kind, token) }
        val replacement = store.authorize(account, kind, "replacement".toByteArray())
        assertFailure(SecretFailure.AUTH_REQUIRED) { store.update(first, kind, token) }
        store.use(replacement, kind) { assertEquals("replacement", it.toString(Charsets.UTF_8)) }
    }

    @Test(timeout = 10000) fun expiryCancelsPendingWorkAndRejectsLateRefreshAndClearsPlaintext() = runBlocking {
        val store = store()
        val session = store.authorize(account, kind, token)
        val started = CompletableDeferred<Unit>()
        var borrowed: ByteArray? = null
        val pending = async {
            store.use(session, kind) {
                borrowed = it
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        assertFailure(SecretFailure.AUTH_REQUIRED) { store.update(session, kind, token) }
                    }
                }
            }
        }
        started.await()
        store.requireAuthentication(account)
        pending.join()
        assertTrue(pending.isCancelled)
        assertTrue(borrowed!!.all { it == 0.toByte() })
        assertFalse(record().exists())
    }

    @Test(timeout = 10000) fun reauthorizationCancelsPreviousSessionWork() = runBlocking {
        val store = store()
        val old = store.authorize(account, kind, token)
        val started = CompletableDeferred<Unit>()
        val pending = async { store.use(old, kind) { started.complete(Unit); awaitCancellation() } }
        started.await()
        val current = store.authorize(account, kind, "new".toByteArray())
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(SecretState.AUTH_REQUIRED, old.state.value)
        store.use(current, kind) { assertEquals("new", it.toString(Charsets.UTF_8)) }
    }

    @Test fun absentSecretRequiresAuthenticationAndCannotCreateAKey() = runBlocking {
        val store = store()
        val session = store.resume(account)
        assertFailure(SecretFailure.AUTH_REQUIRED) { store.use(session, kind) {} }
        assertTrue(keys.entries.isEmpty())
        assertEquals(SecretState.AUTH_REQUIRED, session.state.value)
    }

    @Test fun ioFailuresAreClassifiedAndNeverReportedAsSuccessfulWrites() = runBlocking {
        root.writeText("not a directory")
        val store = store()
        assertFailure(SecretFailure.IO) { store.authorize(account, kind, token) }
        assertEquals(SecretState.AUTH_REQUIRED, store.resume(account).state.value)
        root.delete()
        val session = store.authorize(account, kind, token)
        record().delete()
        record().mkdir()
        assertFailure(SecretFailure.IO) { store.update(session, kind, token) }
        assertFalse(root.walkTopDown().any { it.name.startsWith("pending-") })
    }

    @Test fun inputLimitsAndSessionOwnershipAreEnforced() = runBlocking {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.resume(" ") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.resume("x".repeat(1025)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.resume("\uD800") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.authorize(account, kind, byteArrayOf()) } }
        val session = store.authorize(account, kind, token)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.update(session, kind, ByteArray(SecretCipher.MAX_SECRET_BYTES + 1)) }
        }
        val foreign = store()
        assertFailure(SecretFailure.AUTH_REQUIRED) { foreign.update(session, kind, token) }
        store.use(session, kind) { assertArrayEquals(token, it) }
    }

    @Test(timeout = 10000) fun callerCancellationClearsBufferAndAllowsRetry() = runBlocking {
        val store = store()
        val session = store.authorize(account, kind, token)
        val started = CompletableDeferred<Unit>()
        var borrowed: ByteArray? = null
        val pending = async {
            store.use(session, kind) { borrowed = it; started.complete(Unit); awaitCancellation() }
        }
        started.await()
        pending.cancelAndJoin()
        assertTrue(borrowed!!.all { it == 0.toByte() })
        assertTrue(session.pending.isEmpty())
        store.use(session, kind) { assertArrayEquals(token, it) }
    }

    @Test fun maximumSizeAndUnicodeAccountRoundTrip() = runBlocking {
        val store = store()
        val value = ByteArray(SecretCipher.MAX_SECRET_BYTES) { (it % 251).toByte() }
        val session = store.authorize("音楽:本人", kind, value)
        store.use(session, kind) { assertArrayEquals(value, it) }
        assertEquals(SecretCipher.MAX_RECORD_BYTES, record("音楽:本人").length().toInt())
        val maximumAccount = "x".repeat(1024)
        val maximumSession = store.authorize(maximumAccount, kind, token)
        store.use(maximumSession, kind) { assertArrayEquals(token, it) }
    }

    @Test fun failedKeyDeletionRemainsRevokedInMemoryUntilExplicitAuthorization() = runBlocking {
        val store = store()
        val session = store.authorize(account, kind, token)
        keys.deleteFailure = GeneralSecurityException("private alias")
        assertFailure(SecretFailure.AUTH_REQUIRED) { store.requireAuthentication(account) }
        assertSame(session, store.resume(account))
        assertFailure(SecretFailure.AUTH_REQUIRED) { store.use(session, kind) {} }
        keys.deleteFailure = null
        store.requireAuthentication(account)
        assertFalse(record().exists())
        assertTrue(keys.entries.isEmpty())
    }

    private suspend fun assertFailure(expected: SecretFailure, action: suspend () -> Unit) {
        try {
            action()
            throw AssertionError("Expected classified storage failure")
        } catch (error: SecretStorageException) {
            assertEquals(expected, error.failure)
            assertEquals("Secret storage: ${expected.name}", error.message)
            assertNull(error.cause)
            assertEquals(0, error.suppressed.size)
        }
    }

    private class TestKeys : SecretKeys {
        val entries = mutableMapOf<String, SecretKey>()
        var findFailure: GeneralSecurityException? = null
        var createFailure: ProviderException? = null
        var deleteFailure: GeneralSecurityException? = null
        override fun find(alias: String): SecretKey? {
            findFailure?.let { throw it }
            return entries[alias]
        }
        override fun create(alias: String): SecretKey {
            createFailure?.let { throw it }
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { entries[alias] = it }
        }
        override fun delete(alias: String) {
            deleteFailure?.let { throw it }
            entries.remove(alias)
        }
    }
}
