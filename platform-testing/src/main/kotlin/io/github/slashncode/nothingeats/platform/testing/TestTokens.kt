package io.github.slashncode.nothingeats.platform.testing

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.github.slashncode.nothingeats.platform.http.auth.JwtConfig
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Signs access tokens like the identity service does, with a throwaway key, and provides the
 * matching [jwtAuth] — route tests get real token verification without a running identity service.
 */
class TestTokens(
    val config: JwtConfig = JwtConfig(),
) {
    val keyId = "test-key"

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val algorithm = Algorithm.RSA256(publicKey, keyPair.private as RSAPrivateKey)

    val keys =
        JwkProvider { kid ->
            if (kid != keyId) throw SigningKeyNotFoundException("No key $kid", null)
            Jwk.fromValues(
                mapOf(
                    "kty" to "RSA",
                    "kid" to keyId,
                    "alg" to "RS256",
                    "use" to "sig",
                    "n" to base64Url(publicKey.modulus),
                    "e" to base64Url(publicKey.publicExponent),
                ),
            )
        }

    val jwtAuth = JwtAuth(config, keys)

    fun accessToken(
        userId: String = "user-1",
        roles: Set<String> = emptySet(),
        locale: String = "de",
        expiresIn: Duration = 15.minutes,
        issuer: String = config.issuer,
        audience: String = config.audience,
        keyId: String = this.keyId,
    ): String {
        val now = Instant.now()
        return JWT
            .create()
            .withKeyId(keyId)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withClaim("roles", roles.toList())
            .withClaim("locale", locale)
            .withIssuedAt(now)
            .withExpiresAt(now.plus(expiresIn.toJavaDuration()))
            .sign(algorithm)
    }

    private fun base64Url(value: BigInteger): String {
        val bytes = value.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val KEY_SIZE = 2048
    }
}
