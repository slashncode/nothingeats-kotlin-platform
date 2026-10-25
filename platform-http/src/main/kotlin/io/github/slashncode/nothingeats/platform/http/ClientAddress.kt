package io.github.slashncode.nothingeats.platform.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * The client's address as the gateway saw it, for rate limits and audit logs.
 *
 * Traefik is the only way in. It replaces an untrusted `X-Forwarded-For` with the peer it saw, and
 * a trusted setup would append that peer, so the last entry is the one the gateway vouches for;
 * earlier entries are whatever the client claims. Without the header (tests, direct calls) it is
 * the TCP peer.
 */
val ApplicationCall.clientAddress: String
    get() =
        request.headers[HttpHeaders.XForwardedFor]
            ?.split(',')
            ?.map { it.trim() }
            ?.lastOrNull { it.isNotEmpty() }
            ?: request.origin.remoteAddress
