package com.audipp.server

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.RSAKeyProvider
import java.net.URI
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

fun accessTokenVerifier(issuer: String, audience: String, provider: JwkProvider) = JWT.require(
    Algorithm.RSA256(object : RSAKeyProvider {
        override fun getPublicKeyById(keyId: String?): RSAPublicKey = provider.get(keyId).publicKey as RSAPublicKey
        override fun getPrivateKey(): RSAPrivateKey? = null
        override fun getPrivateKeyId(): String? = null
    })
).withIssuer(issuer).withAudience(audience).withClaim("typ", "Bearer").withClaimPresence("exp").build()

fun keycloakKeys(issuer: String): JwkProvider {
    require(issuer.toByteArray().size <= 1024)
    val uri = URI(issuer)
    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
    return JwkProviderBuilder(URI("${issuer.trimEnd('/')}/protocol/openid-connect/certs").toURL())
        .cached(10, 1, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .timeouts(1_000, 2_000)
        .build()
}
