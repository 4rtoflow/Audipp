package com.audipp.server

import com.auth0.jwt.interfaces.JWTVerifier
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.security.KeyStore

fun main() {
    runBlocking {
        fun env(name: String) = requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) { "Missing $name" }
        val source = PGSimpleDataSource().apply {
            serverNames = arrayOf(env("AUDIPP_DB_HOST"))
            portNumbers = intArrayOf(env("AUDIPP_DB_PORT").toInt())
            databaseName = env("AUDIPP_DB_NAME")
            user = env("AUDIPP_DB_USER")
            password = env("AUDIPP_DB_PASSWORD")
            connectTimeout = 2
            socketTimeout = 5
            sslMode = env("AUDIPP_DB_SSLMODE").also { require(it in setOf("verify-full", "disable")) }
        }
        val database = Database(source)
        database.verifySeparation(env("AUDIPP_AUTH_DB_NAME"), env("AUDIPP_AUTH_DB_USER"))
        database.migrate()
        val issuer = env("AUDIPP_ISSUER")
        val verifier = accessTokenVerifier(issuer, env("AUDIPP_AUDIENCE"), keycloakKeys(issuer))
        val storePassword = env("AUDIPP_TLS_PASSWORD").toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            File(env("AUDIPP_TLS_STORE")).inputStream().use { load(it, storePassword) }
        }
        val alias = env("AUDIPP_TLS_ALIAS")
        val bindHost = env("AUDIPP_BIND_HOST")
        val bindPort = env("AUDIPP_TLS_PORT").toInt().also { require(it in 1024..65535) }
        httpsServer(database, verifier, keyStore, alias, storePassword, bindHost, bindPort).start(wait = true)
    }
}

fun httpsServer(database: Database, verifier: JWTVerifier, keyStore: KeyStore, alias: String,
                password: CharArray, bindHost: String, bindPort: Int) = embeddedServer(Netty, configure = {
    sslConnector(keyStore, alias, { password }, { password }) {
        host = bindHost
        port = bindPort
        enabledProtocols = listOf("TLSv1.3", "TLSv1.2")
    }
}) { ownershipApi(database, verifier) }
