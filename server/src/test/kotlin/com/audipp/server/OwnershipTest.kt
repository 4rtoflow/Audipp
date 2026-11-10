package com.audipp.server

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.postgresql.ds.PGSimpleDataSource
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OwnershipTest {
    companion object {
        private const val issuer = "https://identity.example.test/realms/audipp"
        private const val audience = "audipp-api"
        private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val algorithm = Algorithm.RSA256(key.public as RSAPublicKey, key.private as java.security.interfaces.RSAPrivateKey)
        private val publicKey = key.public as RSAPublicKey
        private fun encoded(number: java.math.BigInteger) = Base64.getUrlEncoder().withoutPadding().encodeToString(
            number.toByteArray().let { if (it[0] == 0.toByte()) it.drop(1).toByteArray() else it }
        )
        private val jwk = Jwk.fromValues(mapOf("kty" to "RSA", "kid" to "fixture", "alg" to "RS256",
            "n" to encoded(publicKey.modulus), "e" to encoded(publicKey.publicExponent)))
        private val provider = JwkProvider { id ->
            if (id != "fixture") throw SigningKeyNotFoundException("missing", null)
            jwk
        }
        private val verifier = accessTokenVerifier(issuer, audience, provider)
        private lateinit var pg: EmbeddedPostgres
        private lateinit var source: PGSimpleDataSource
        private lateinit var database: Database

        @JvmStatic @BeforeClass fun startPostgres() {
            pg = EmbeddedPostgres.builder().setPort(0).start()
            pg.postgresDatabase.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("CREATE ROLE business LOGIN PASSWORD 'local-test-only'")
                    it.execute("CREATE ROLE identity_store LOGIN PASSWORD 'local-auth-test-only'")
                    it.execute("CREATE DATABASE business OWNER business")
                    it.execute("CREATE DATABASE identity_store OWNER identity_store")
                    it.execute("REVOKE CONNECT ON DATABASE identity_store FROM PUBLIC")
                    it.execute("REVOKE CONNECT ON DATABASE business FROM PUBLIC")
                }
            }
            source = PGSimpleDataSource().apply {
                serverNames = arrayOf("localhost"); portNumbers = intArrayOf(pg.port)
                databaseName = "business"; user = "business"; password = "local-test-only"
                connectTimeout = 2; socketTimeout = 5
            }
            database = Database(source)
            runBlocking { database.verifySeparation("identity_store", "identity_store"); database.migrate() }
        }
        @JvmStatic @AfterClass fun stopPostgres() { if (::pg.isInitialized) pg.close() }
    }

    @Before fun clearOwners() {
        source.connection.use { it.createStatement().use { statement -> statement.execute("TRUNCATE devices, identities, users") } }
    }

    private fun token(subject: String? = "alice", tokenIssuer: String = issuer, tokenAudience: String = audience,
                      expires: Instant? = Instant.now().plusSeconds(300), type: String = "Bearer",
                      signer: Algorithm = algorithm, kid: String = "fixture", future: Boolean = false): String =
        JWT.create().withKeyId(kid).withIssuer(tokenIssuer).withAudience(tokenAudience).withSubject(subject)
            .withClaim("typ", type).withClaim("email", "same@example.test").withClaim("user_id", "client-forged-user")
            .apply { expires?.let { withExpiresAt(Date.from(it)) }; if (future) withNotBefore(Date.from(Instant.now().plusSeconds(300))) }
            .sign(signer)

    private fun count(table: String): Int = source.connection.use { connection ->
        connection.createStatement().use { it.executeQuery("SELECT count(*) FROM $table").use { rows -> rows.next(); rows.getInt(1) } }
    }

    @Test fun `same verified identity resolves consistently and session retry preserves device revision`() = testApplication {
        application { ownershipApi(database, verifier) }
        val id = UUID.randomUUID()
        suspend fun session() = client.post("/v1/auth/session") {
            bearerAuth(token()); contentType(ContentType.Application.Json); setBody("{\"device_id\":\"$id\"}")
        }
        val first = session()
        assertEquals(HttpStatusCode.OK, first.status)
        val text = first.bodyAsText()
        assertEquals(text, session().bodyAsText())
        val session = Json.parseToJsonElement(text).jsonObject
        val user = session.getValue("user").jsonObject
        assertNotEquals("client-forged-user", user.getValue("user_id").jsonPrimitive.content)
        assertTrue(user.getValue("revision").jsonPrimitive.long > 0)
        assertTrue(user.getValue("created_at").jsonPrimitive.content.endsWith("Z"))
        val me = client.get("/v1/me") { bearerAuth(token()) }
        assertEquals(user, Json.parseToJsonElement(me.bodyAsText()))
        val device = client.get("/v1/me/devices/$id") { bearerAuth(token()) }
        assertEquals(session.getValue("device"), Json.parseToJsonElement(device.bodyAsText()))
        assertEquals("no-store", me.headers[HttpHeaders.CacheControl])
        assertEquals(1, count("users")); assertEquals(1, count("identities")); assertEquals(1, count("devices"))
        assertFalse(text.contains("same@example.test")); assertFalse(text.contains(issuer)); assertFalse(text.contains(token()))
    }

    @Test fun `foreign owner cannot read or claim device and client owner selectors are rejected`() = testApplication {
        application { ownershipApi(database, verifier) }
        val id = UUID.randomUUID()
        suspend fun session(subject: String, body: String = "{\"device_id\":\"$id\"}") = client.post("/v1/auth/session") {
            bearerAuth(token(subject)); contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.OK, session("alice").status)
        assertEquals(HttpStatusCode.Forbidden, session("bob").status)
        assertEquals(1, count("users")) // Failed first-login registration rolls back the new identity too.
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/me/devices/$id") { bearerAuth(token("bob")) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/me/devices/${UUID.randomUUID()}") { bearerAuth(token("bob")) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/me?user_id=alice") { bearerAuth(token("bob")) }.status)
        assertEquals(HttpStatusCode.BadRequest, session("bob", "{\"device_id\":\"$id\",\"user_id\":\"alice\"}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/users/alice") { bearerAuth(token("bob")) }.status)
        assertEquals(1, count("devices"))
    }

    @Test fun `missing forged expired wrong issuer audience type and malformed credentials are refused before DB`() = testApplication {
        application { ownershipApi(database, verifier) }
        val badTokens = listOf("", "not-a-jwt", token(subject = null), token(subject = " "), token(subject = "s".repeat(256)), token(subject = "bad\u0000subject"),
            token(tokenIssuer = "https://attacker.test"), token(tokenAudience = "another-api"), token(expires = null),
            token(expires = Instant.now().minusSeconds(5)), token(type = "ID"), token(future = true),
            token(signer = Algorithm.HMAC256("forged-fixture")), token(kid = "unknown"),
            token(signer = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().let {
                Algorithm.RSA256(it.public as RSAPublicKey, it.private as java.security.interfaces.RSAPrivateKey)
            }))
        for (bad in badTokens) {
            val response = client.get("/v1/me") { if (bad.isNotEmpty()) bearerAuth(bad) }
            assertEquals(bad.take(12), HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.headers[HttpHeaders.WWWAuthenticate]?.startsWith("Bearer") == true)
            assertFalse(response.bodyAsText().contains(bad.takeIf { it.isNotEmpty() } ?: "same@example.test"))
        }
        assertEquals(0, count("users"))
    }

    @Test fun `key service outage is retryable and never creates an owner`() = testApplication {
        val unavailable = accessTokenVerifier(issuer, audience, JwkProvider { throw com.auth0.jwk.NetworkException("fixture outage", null) })
        application { ownershipApi(database, unavailable) }
        val response = client.get("/v1/me") { bearerAuth(token()) }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("1", response.headers[HttpHeaders.RetryAfter])
        assertEquals("{\"error\":\"temporarily_unavailable\"}", response.bodyAsText())
        assertEquals(0, count("users"))
    }

    @Test fun `matching email never merges accounts and issuer subject pair is unique under concurrent first login`() = runBlocking {
        val alice = database.transaction { database.resolve(it, Identity(issuer, "alice")) }
        val others = listOf(Identity(issuer, "bob"), Identity("https://other.test/realm", "alice"))
            .map { identity -> database.transaction { database.resolve(it, identity) } }
        assertEquals(3, (others + alice).map { it.user_id }.toSet().size)
        val races = (1..16).map { async { database.transaction { database.resolve(it, Identity(issuer, "race")) } } }.awaitAll()
        assertEquals(1, races.map { it.user_id }.toSet().size)
        assertEquals(4, count("users")); assertEquals(4, count("identities"))
        try {
            database.transaction { connection ->
                connection.prepareStatement("INSERT INTO identities (issuer, subject, user_id) VALUES (?, ?, ?)").use {
                    it.setString(1, issuer); it.setString(2, "alice"); it.setObject(3, UUID.fromString(others[0].user_id)); it.executeUpdate()
                }
            }
            fail("duplicate identity accepted")
        } catch (expected: java.sql.SQLException) { assertEquals("23505", expected.sqlState) }
        assertEquals(alice, database.transaction { database.resolve(it, Identity(issuer, "alice")) })
    }

    @Test fun `same email in signed tokens creates separate users and disabled user is forbidden`() = testApplication {
        application { ownershipApi(database, verifier) }
        val a = client.get("/v1/me") { bearerAuth(token("alice")) }.bodyAsText()
        val b = client.get("/v1/me") { bearerAuth(token("bob")) }.bodyAsText()
        assertNotEquals(a, b); assertEquals(2, count("users"))
        source.connection.use { it.createStatement().use { statement -> statement.execute("UPDATE users SET active = false") } }
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/me") { bearerAuth(token()) }.status)
        assertEquals(2, count("users"))
    }

    @Test fun `invalid and oversized JSON cannot allocate identity or device`() = testApplication {
        application { ownershipApi(database, verifier) }
        for (body in listOf("{}", "[]", "{", "{\"device_id\":\"1-1-1-1-1\"}", "{\"device_id\":null}",
            "{\"device_id\":\"${UUID.randomUUID()}\",\"revision\":999}")) {
            assertEquals(HttpStatusCode.BadRequest, client.post("/v1/auth/session") {
                bearerAuth(token()); contentType(ContentType.Application.Json); setBody(body)
            }.status)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/v1/auth/session") {
            bearerAuth(token()); contentType(ContentType.Application.Json); setBody(" ".repeat(1025))
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/auth/session") {
            bearerAuth(token()); setBody("{\"device_id\":\"${UUID.randomUUID()}\"}")
        }.status)
        assertEquals(0, count("users")); assertEquals(0, count("devices"))
    }

    @Test fun `database lock timeout rolls back first login and retry succeeds without partial state`() = testApplication {
        application { ownershipApi(database, verifier) }
        val id = UUID.randomUUID()
        suspend fun session() = client.post("/v1/auth/session") {
            bearerAuth(token()); contentType(ContentType.Application.Json); setBody("{\"device_id\":\"$id\"}")
        }
        source.connection.use { lock ->
            lock.autoCommit = false
            lock.createStatement().use { it.execute("LOCK TABLE devices IN ACCESS EXCLUSIVE MODE") }
            val response = session()
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("1", response.headers[HttpHeaders.RetryAfter])
            assertEquals("{\"error\":\"temporarily_unavailable\"}", response.bodyAsText())
            assertEquals(0, count("users")); assertEquals(0, count("identities"))
            lock.rollback()
        }
        assertEquals(HttpStatusCode.OK, session().status)
        assertEquals(1, count("users")); assertEquals(1, count("devices"))
    }

    @Test fun `cancellation before commit rolls back owner and device and can be retried`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val id = UUID.randomUUID()
        val identity = Identity(issuer, "cancelled")
        val job = launch {
            database.transaction {
                database.registerDevice(it, database.resolve(it, identity), id)
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            withContext(Dispatchers.IO) { check(started.await(5, TimeUnit.SECONDS)) }
            job.cancel()
        } finally { release.countDown() }
        job.join()
        assertEquals(0, count("users")); assertEquals(0, count("identities")); assertEquals(0, count("devices"))
        database.transaction { database.registerDevice(it, database.resolve(it, identity), id) }
        assertEquals(1, count("users")); assertEquals(1, count("devices"))
    }

    @Test fun `native HTTPS listener serves authenticated JSON and rejects plaintext and untrusted certificates`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("audipp-tls-test-")
        val store = directory.resolve("fixture.p12")
        val password = "local-tls-test-only"
        val process = ProcessBuilder(
            java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", password,
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost"
        ).redirectErrorStream(true).start()
        try {
            check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0)
            val keyStore = java.security.KeyStore.getInstance("PKCS12").apply {
                java.nio.file.Files.newInputStream(store).use { load(it, password.toCharArray()) }
            }
            val trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
            val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            val server = httpsServer(database, verifier, keyStore, "fixture", password.toCharArray(), "127.0.0.1", 0).start()
            try {
                val connectors = server.engine.resolvedConnectors()
                assertEquals(1, connectors.size)
                assertEquals(io.ktor.server.engine.ConnectorType.HTTPS, connectors.single().type)
                val port = connectors.single().port
                val http = java.net.http.HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(3)).build()
                val request = java.net.http.HttpRequest.newBuilder(java.net.URI("https://localhost:$port/v1/me"))
                    .timeout(java.time.Duration.ofSeconds(3)).header("Authorization", "Bearer " + token()).build()
                val response = withContext(Dispatchers.IO) { http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()) }
                assertEquals(200, response.statusCode())
                assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/json"))
                assertTrue(response.sslSession().get().protocol in setOf("TLSv1.2", "TLSv1.3"))
                assertEquals(1, count("users"))
                try {
                    withContext(Dispatchers.IO) { java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString()) }
                    fail("untrusted certificate accepted")
                } catch (_: javax.net.ssl.SSLHandshakeException) { }
                try {
                    val plain = java.net.http.HttpRequest.newBuilder(java.net.URI("http://localhost:$port/v1/me")).timeout(java.time.Duration.ofSeconds(3)).build()
                    withContext(Dispatchers.IO) { http.send(plain, java.net.http.HttpResponse.BodyHandlers.ofString()) }
                    fail("plaintext request accepted")
                } catch (_: java.io.IOException) { }
            } finally { server.stop(0, 1000) }
        } finally {
            process.destroyForcibly()
            java.nio.file.Files.deleteIfExists(store)
            java.nio.file.Files.deleteIfExists(directory)
        }
    }

    @Test fun `failed migration leaves neither partial schema nor version and retry applies once`() = runBlocking {
        source.connection.use { it.createStatement().use { statement -> statement.execute("CREATE SCHEMA migration_failure; CREATE TABLE migration_failure.users (collision integer)") } }
        val migrationSource = PGSimpleDataSource().apply {
            serverNames = arrayOf("localhost"); portNumbers = intArrayOf(pg.port)
            databaseName = "business"; user = "business"; password = "local-test-only"; currentSchema = "migration_failure"
        }
        val isolated = Database(migrationSource)
        try {
            try { isolated.migrate(); fail("conflicting schema accepted") } catch (expected: java.sql.SQLException) { assertEquals("42P07", expected.sqlState) }
            migrationSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT to_regclass('migration_failure.server_revision'), to_regclass('migration_failure.schema_migrations')").use {
                        assertTrue(it.next()); assertNull(it.getString(1)); assertNull(it.getString(2))
                    }
                    statement.execute("DROP TABLE users")
                }
            }
            isolated.migrate()
            isolated.migrate()
            migrationSource.connection.use { connection ->
                connection.createStatement().use { it.executeQuery("SELECT count(*) FROM schema_migrations").use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) } }
            }
        } finally { source.connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA migration_failure CASCADE") } } }
    }

    @Test fun `migration restart is idempotent checksum changes refused and auth DB cannot be accessed`() = runBlocking {
        database.migrate()
        assertEquals(1, count("schema_migrations"))
        source.connection.use { it.createStatement().use { statement -> statement.execute("UPDATE schema_migrations SET sha256 = 'tampered'") } }
        try { database.migrate(); fail("changed migration accepted") } catch (_: IllegalStateException) { }
        source.connection.use { it.createStatement().use { statement -> statement.execute("DELETE FROM schema_migrations") } }
        // Restore the checksum without replaying DDL against the existing schema.
        val sql = checkNotNull(javaClass.getResource("/db/migration/V001__ownership.sql")).readText()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(sql.toByteArray()).joinToString("") { "%02x".format(it) }
        source.connection.use { it.prepareStatement("INSERT INTO schema_migrations VALUES (1, ?)").use { s -> s.setString(1, hash); s.executeUpdate() } }
        try {
            database.verifySeparation("business", "business"); fail("same DB/user accepted")
        } catch (_: IllegalStateException) { }
        val auth = PGSimpleDataSource().apply {
            serverNames = arrayOf("localhost"); portNumbers = intArrayOf(pg.port)
            databaseName = "identity_store"; user = "business"; password = "local-test-only"
        }
        try { auth.connection.use { }; fail("auth database accessible") } catch (expected: java.sql.SQLException) { assertEquals("42501", expected.sqlState) }
        val superuser = Database(pg.postgresDatabase)
        try { superuser.verifySeparation("identity_store", "identity_store"); fail("superuser accepted") } catch (_: IllegalStateException) { }
    }
}
