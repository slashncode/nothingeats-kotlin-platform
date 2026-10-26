package io.github.slashncode.nothingeats.platform.http.auth

import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.Payload
import com.auth0.jwt.interfaces.RSAKeyProvider
import io.github.slashncode.nothingeats.platform.http.respondProblem
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import java.net.URI
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Defaults point at the identity service behind the local gateway. */
data class JwtConfig(
    val issuer: String = "http://nothingeats.localhost/api/identity",
    val audience: String = "nothingeats",
    val jwksUrl: String = "http://nothingeats.localhost/api/identity/.well-known/jwks.json",
    val leeway: Duration = 30.seconds,
)

/** The caller of an authenticated request, taken from a verified access token. */
data class AuthenticatedUser(
    val id: String,
    val roles: Set<String>,
    val locale: String?,
)

val ApplicationCall.user: AuthenticatedUser
    get() = principal<AuthenticatedUser>() ?: error("No authenticated user; is the route inside authenticated {}?")

/**
 * Verifies RS256 access tokens in this service, against the identity service's JWKS:
 * signature by exact `kid` (unknown kids trigger one rate-limited refresh, never a fallback key),
 * `iss`, `aud` and `exp`. No gateway header is trusted. [verify] does the same for transports other
 * than Ktor (gRPC metadata).
 */
class JwtAuth(
    private val config: JwtConfig,
    private val keys: JwkProvider = defaultJwkProvider(config.jwksUrl),
) {
    internal fun install(application: Application) {
        application.install(Authentication) {
            jwt(SCHEME) {
                realm = "nothingeats"
                verifier(keys, config.issuer) {
                    withAudience(config.audience)
                    acceptLeeway(config.leeway.inWholeSeconds)
                }
                validate { credential -> credential.payload.user() }
                challenge { _, _ ->
                    call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"nothingeats\"")
                    call.respondProblem(HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "A valid access token is required")
                }
            }
        }
    }

    private val verifier =
        JWT
            .require(Algorithm.RSA256(JwksKeys(keys)))
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .acceptLeeway(config.leeway.inWholeSeconds)
            .build()

    /** The user of a raw access token, or null when it is invalid, expired or signed by an unknown key. */
    fun verify(token: String): AuthenticatedUser? =
        try {
            verifier.verify(token).user()
        } catch (e: JWTVerificationException) {
            null
        }

    private fun Payload.user(): AuthenticatedUser? {
        val subject = subject ?: return null
        return AuthenticatedUser(
            id = subject,
            roles = getClaim("roles").asList(String::class.java).orEmpty().toSet(),
            locale = getClaim("locale").asString(),
        )
    }

    // Public keys by exact kid; signing is the identity service's job.
    private class JwksKeys(
        private val keys: JwkProvider,
    ) : RSAKeyProvider {
        // java-jwt turns IllegalStateException into a failed verification; a JwkException would escape.
        override fun getPublicKeyById(keyId: String?): RSAPublicKey =
            try {
                keys.get(keyId).publicKey as RSAPublicKey
            } catch (e: JwkException) {
                throw IllegalStateException("No signing key $keyId", e)
            }

        override fun getPrivateKey(): RSAPrivateKey? = null

        override fun getPrivateKeyId(): String? = null
    }

    companion object {
        const val SCHEME = "jwt"

        private fun defaultJwkProvider(url: String): JwkProvider =
            JwkProviderBuilder(URI(url).toURL())
                .cached(CACHED_KEYS, 1, TimeUnit.HOURS)
                .rateLimited(REFRESHES_PER_MINUTE, 1, TimeUnit.MINUTES)
                .build()

        private const val CACHED_KEYS = 10L
        private const val REFRESHES_PER_MINUTE = 10L
    }
}
