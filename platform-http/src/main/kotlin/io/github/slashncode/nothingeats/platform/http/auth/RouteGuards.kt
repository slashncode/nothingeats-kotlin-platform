package io.github.slashncode.nothingeats.platform.http.auth

import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.AuthenticationRouteSelector
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.getAllRoutes

/*
 * Secure by default: every route must sit inside public {}, authenticated {} or requireRole {}.
 * verifyRouteGuards() runs when the application starts and refuses to serve otherwise, so a
 * forgotten guard fails the tests and the deployment instead of exposing an endpoint.
 */

private object PublicRouteSelector : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ) = RouteSelectorEvaluation.Transparent

    override fun toString() = "(public)"
}

/** Reachable without a token. Use deliberately. */
fun Route.public(build: Route.() -> Unit): Route = createChild(PublicRouteSelector).apply(build)

/** Requires a valid access token; the caller is available as `call.user`. */
fun Route.authenticated(build: Route.() -> Unit): Route = authenticate(JwtAuth.SCHEME, build = build)

/** Requires a valid access token carrying [role]; otherwise 403. */
fun Route.requireRole(
    role: String,
    build: Route.() -> Unit,
): Route =
    authenticate(JwtAuth.SCHEME) {
        install(RoleGuard) { this.role = role }
        build()
    }

internal class RoleGuardConfig {
    var role: String = ""
}

private val RoleGuard =
    createRouteScopedPlugin("RoleGuard", ::RoleGuardConfig) {
        val role = pluginConfig.role
        on(AuthenticationChecked) { call ->
            val user = call.principal<AuthenticatedUser>() ?: return@on
            if (role !in user.roles) throw AppError.Forbidden(message = "Requires role $role")
        }
    }

internal fun verifyRouteGuards(root: RoutingNode) {
    val unguarded =
        root.getAllRoutes().filterNot { route ->
            generateSequence(route) { it.parent }.any { it.selector is PublicRouteSelector || it.selector is AuthenticationRouteSelector }
        }
    check(unguarded.isEmpty()) {
        "Routes must be inside public {}, authenticated {} or requireRole {}: ${unguarded.joinToString { it.toString() }}"
    }
}
