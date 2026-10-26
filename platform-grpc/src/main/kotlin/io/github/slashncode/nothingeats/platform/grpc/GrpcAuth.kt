package io.github.slashncode.nothingeats.platform.grpc

import io.github.slashncode.nothingeats.platform.http.auth.AuthenticatedUser
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status

/** The caller of the current gRPC call, from the access token the client forwarded. */
object GrpcCaller {
    internal val USER: Context.Key<AuthenticatedUser> = Context.key("nothingeats-user")

    /** Inside a handler of a method that is not public. grpc-kotlin carries the gRPC context into the coroutine. */
    fun current(): AuthenticatedUser = USER.get() ?: error("No caller; is this method public?")

    fun currentOrNull(): AuthenticatedUser? = USER.get()
}

/**
 * Secure by default, like the HTTP routes: every method needs `authorization: Bearer <token>`,
 * verified against the identity service's JWKS, except the full method names in [publicMethods]
 * (and the health service). Callers forward the end user's token, so each service decides itself.
 */
class AuthInterceptor(
    private val auth: JwtAuth,
    private val publicMethods: Set<String> = emptySet(),
) : ServerInterceptor {
    override fun <Req, Resp> interceptCall(
        call: ServerCall<Req, Resp>,
        headers: Metadata,
        next: ServerCallHandler<Req, Resp>,
    ): ServerCall.Listener<Req> {
        val method = call.methodDescriptor.fullMethodName
        val token =
            headers
                .get(AUTHORIZATION)
                ?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
                ?.substring(BEARER.length)
                ?.trim()
        val user = token?.let { auth.verify(it) }
        if (user == null) {
            if (method in publicMethods || method.startsWith(HEALTH)) return next.startCall(call, headers)
            call.close(Status.UNAUTHENTICATED.withDescription("A valid access token is required"), Metadata())
            return object : ServerCall.Listener<Req>() {}
        }
        return Contexts.interceptCall(Context.current().withValue(GrpcCaller.USER, user), call, headers, next)
    }

    companion object {
        val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        private const val BEARER = "Bearer "
        private const val HEALTH = "grpc.health.v1.Health/"
    }
}
