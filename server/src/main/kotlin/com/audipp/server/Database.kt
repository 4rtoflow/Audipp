package com.audipp.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlin.coroutines.coroutineContext

class Forbidden : RuntimeException()

data class Identity(val issuer: String, val subject: String) {
    init {
        require(issuer.isNotBlank() && issuer.toByteArray().size <= 1024)
        require(subject.isNotBlank() && subject.length <= 255 && subject.none { it.isISOControl() })
    }
}

class Database(private val source: DataSource) {
    private val io = Dispatchers.IO.limitedParallelism(8)

    suspend fun <T> transaction(block: (Connection) -> T): T = withContext(io) {
        source.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use {
                    it.execute("SET LOCAL statement_timeout = '2s'")
                    it.execute("SET LOCAL lock_timeout = '500ms'")
                    it.execute("SET LOCAL TIME ZONE 'UTC'")
                }
                coroutineContext.ensureActive()
                val result = block(connection)
                coroutineContext.ensureActive()
                connection.commit()
                result
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    suspend fun migrate() = transaction { connection ->
        connection.createStatement().use { statement ->
            statement.execute("SELECT pg_advisory_xact_lock(34001)")
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version integer PRIMARY KEY, sha256 text NOT NULL)")
        }
        val sql = checkNotNull(javaClass.getResource("/db/migration/V001__ownership.sql")).readText()
        val digest = MessageDigest.getInstance("SHA-256").digest(sql.toByteArray()).joinToString("") { "%02x".format(it) }
        val previous = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT sha256 FROM schema_migrations WHERE version = 1").use { if (it.next()) it.getString(1) else null }
        }
        if (previous == null) {
            connection.createStatement().use { it.execute(sql) }
            connection.prepareStatement("INSERT INTO schema_migrations VALUES (1, ?)").use {
                it.setString(1, digest)
                it.executeUpdate()
            }
        } else {
            check(previous == digest) { "Migration checksum mismatch" }
        }
    }

    suspend fun verifySeparation(authDatabase: String, authUser: String) = transaction { connection ->
        connection.prepareStatement("""
            SELECT current_database() <> ?, current_user <> ?, NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole,
                   NOT has_database_privilege(current_user, ?, 'CONNECT'), NOT pg_has_role(current_user, ?, 'MEMBER')
            FROM pg_roles WHERE rolname = current_user
        """.trimIndent()).use { statement ->
            statement.setString(1, authDatabase)
            statement.setString(2, authUser)
            statement.setString(3, authDatabase)
            statement.setString(4, authUser)
            statement.executeQuery().use { result ->
                check(result.next() && (1..5).all { result.getBoolean(it) }) { "Business/auth database separation is required" }
            }
        }
    }

    fun resolve(connection: Connection, identity: Identity): UserResponse {
        // Serialize first-login races before allocating a user; email is deliberately absent from the key.
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))").use {
            it.setString(1, identity.issuer)
            it.setString(2, identity.subject)
            it.execute()
        }
        val userId = connection.prepareStatement("SELECT user_id FROM identities WHERE issuer = ? AND subject = ?").use {
            it.setString(1, identity.issuer)
            it.setString(2, identity.subject)
            it.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        } ?: UUID.randomUUID().also { id ->
            connection.prepareStatement("INSERT INTO users (user_id) VALUES (?)").use { it.setObject(1, id); it.executeUpdate() }
            connection.prepareStatement("INSERT INTO identities (issuer, subject, user_id) VALUES (?, ?, ?)").use {
                it.setString(1, identity.issuer); it.setString(2, identity.subject); it.setObject(3, id); it.executeUpdate()
            }
        }
        return connection.prepareStatement("SELECT revision, created_at, active FROM users WHERE user_id = ? FOR SHARE").use {
            it.setObject(1, userId)
            it.executeQuery().use { rows ->
                check(rows.next())
                if (!rows.getBoolean("active")) throw Forbidden()
                UserResponse(userId.toString(), rows.getLong("revision"), rows.getTimestamp("created_at").toInstant().toString())
            }
        }
    }

    fun registerDevice(connection: Connection, user: UserResponse, deviceId: UUID): DeviceResponse {
        connection.prepareStatement("INSERT INTO devices (device_id, user_id) VALUES (?, ?) ON CONFLICT (device_id) DO NOTHING").use {
            it.setObject(1, deviceId); it.setObject(2, UUID.fromString(user.user_id)); it.executeUpdate()
        }
        return device(connection, user, deviceId)
    }

    fun device(connection: Connection, user: UserResponse, deviceId: UUID): DeviceResponse =
        connection.prepareStatement("SELECT revision, created_at FROM devices WHERE device_id = ? AND user_id = ?").use {
            it.setObject(1, deviceId); it.setObject(2, UUID.fromString(user.user_id))
            it.executeQuery().use { rows ->
                if (!rows.next()) throw Forbidden()
                DeviceResponse(deviceId.toString(), rows.getLong("revision"), rows.getTimestamp("created_at").toInstant().toString())
            }
        }
}
