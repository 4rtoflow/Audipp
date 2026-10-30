package com.audipp.server

import com.auth0.jwk.JwkException
import com.auth0.jwk.NetworkException
import com.auth0.jwk.RateLimitReachedException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.JWTVerifier
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.sql.SQLException
import java.util.UUID

@Serializable data class UserResponse(val user_id: String, val revision: Long, val created_at: String)
@Serializable data class DeviceResponse(val device_id: String, val revision: Long, val created_at: String)
@Serializable data class SessionResponse(val user: UserResponse, val device: DeviceResponse)
@Serializable data class SessionRequest(val device_id: String)
@Serializable data class ErrorResponse(val error: String)
private data class Owner(val identity: Identity)
private class PayloadTooLarge : RuntimeException()
private class KeysUnavailable : RuntimeException()
private val PrivateResponses = createApplicationPlugin("PrivateResponses") {
    onCall { it.response.headers.append(HttpHeaders.CacheControl, "no-store") }
}

fun Application.ownershipApi(database: Database, verifier: JWTVerifier) {
    install(PrivateResponses)
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        status(HttpStatusCode.Unauthorized) { call, status -> call.respond(status, ErrorResponse("unauthorized")) }
        exception<Throwable> { call, failure ->
            val (status, code) = when (failure) {
                is CancellationException -> throw failure
                is Forbidden -> HttpStatusCode.Forbidden to "forbidden"
                is PayloadTooLarge -> HttpStatusCode.PayloadTooLarge to "payload_too_large"
                is IllegalArgumentException, is SerializationException, is BadRequestException, is java.nio.charset.CharacterCodingException -> HttpStatusCode.BadRequest to "invalid_request"
                is SQLException, is KeysUnavailable -> HttpStatusCode.ServiceUnavailable to "temporarily_unavailable"
                else -> HttpStatusCode.InternalServerError to "internal_error"
            }
            if (status == HttpStatusCode.ServiceUnavailable) call.response.headers.append(HttpHeaders.RetryAfter, "1")
            call.respond(status, ErrorResponse(code))
        }
    }
    val authIo = Dispatchers.IO.limitedParallelism(8)
    install(Authentication) {
        bearer("keycloak") {
            realm = "audipp"
            authenticate { credential ->
                withContext(authIo) {
                    try {
                        if (credential.token.length > 8192) return@withContext null
                        val token = verifier.verify(credential.token)
                        val subject = token.subject
                        if (subject.isNullOrBlank() || subject.length > 255 || subject.any { it.isISOControl() }) null
                        else Owner(Identity(token.issuer, subject))
                    } catch (_: NetworkException) {
                        throw KeysUnavailable()
                    } catch (_: RateLimitReachedException) {
                        throw KeysUnavailable()
                    } catch (_: JwkException) {
                        null
                    } catch (_: JWTVerificationException) {
                        null
                    }
                }
            }
        }
    }
    routing {
        authenticate("keycloak") {
            get("/v1/me") {
                call.checkQuery()
                val identity = call.principal<Owner>()!!.identity
                call.respond(database.transaction { database.resolve(it, identity) })
            }
            post("/v1/auth/session") {
                call.checkQuery()
                require(call.request.contentType().withoutParameters() == ContentType.Application.Json)
                val bytes = call.receiveChannel().readRemaining(1025).readByteArray()
                if (bytes.size > 1024) throw PayloadTooLarge()
                val request = Json.decodeFromString<SessionRequest>(bytes.decodeToString(throwOnInvalidSequence = true))
                val deviceId = canonicalUuid(request.device_id)
                val identity = call.principal<Owner>()!!.identity
                call.respond(database.transaction {
                    val user = database.resolve(it, identity)
                    SessionResponse(user, database.registerDevice(it, user, deviceId))
                })
            }
            get("/v1/me/devices/{device_id}") {
                call.checkQuery()
                val id = canonicalUuid(call.parameters["device_id"]!!)
                val identity = call.principal<Owner>()!!.identity
                call.respond(database.transaction { database.device(it, database.resolve(it, identity), id) })
            }
        }
    }
}

private fun ApplicationCall.checkQuery() {
    require(request.queryParameters.isEmpty())
}

private fun canonicalUuid(value: String): UUID = UUID.fromString(value).also {
    require(it.toString() == value)
}
