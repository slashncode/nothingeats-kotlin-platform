package io.github.slashncode.nothingeats.platform.grpc

import com.google.protobuf.Any
import com.google.protobuf.Duration
import com.google.rpc.BadRequest
import com.google.rpc.Code
import com.google.rpc.ErrorInfo
import com.google.rpc.RetryInfo
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.StatusProto
import org.slf4j.LoggerFactory
import com.google.rpc.Status as RpcStatus

/** The domain of the stable error codes in [ErrorInfo.getReason]. */
const val ERROR_DOMAIN = "nothingeats"

/**
 * Turns what a handler throws into a gRPC status: an [AppError] becomes its code with the
 * rich error model (`ErrorInfo` with the stable `UPPER_SNAKE` code, `BadRequest` violations,
 * `RetryInfo`); anything else becomes INTERNAL without its message, and is logged.
 */
class ErrorInterceptor(
    private val report: (Throwable) -> Unit = {},
) : ServerInterceptor {
    private val log = LoggerFactory.getLogger(ErrorInterceptor::class.java)

    override fun <Req, Resp> interceptCall(
        call: ServerCall<Req, Resp>,
        headers: Metadata,
        next: ServerCallHandler<Req, Resp>,
    ): ServerCall.Listener<Req> =
        next.startCall(
            object : SimpleForwardingServerCall<Req, Resp>(call) {
                override fun close(
                    status: Status,
                    trailers: Metadata,
                ) {
                    // grpc-kotlin closes a call whose handler threw with UNKNOWN and the exception as cause.
                    val cause = status.cause
                    when {
                        status.code != Status.Code.UNKNOWN || cause == null -> super.close(status, trailers)
                        cause is AppError -> {
                            val mapped = StatusProto.toStatusRuntimeException(cause.toRpcStatus())
                            trailers.merge(mapped.trailers ?: Metadata())
                            super.close(mapped.status, trailers)
                        }
                        else -> {
                            log.error("gRPC call {} failed", call.methodDescriptor.fullMethodName, cause)
                            report(cause)
                            super.close(Status.INTERNAL.withDescription("Internal error"), trailers)
                        }
                    }
                }
            },
            headers,
        )
}

internal fun AppError.toRpcStatus(): RpcStatus {
    val code =
        when (this) {
            is AppError.NotFound -> Code.NOT_FOUND
            is AppError.Conflict -> Code.FAILED_PRECONDITION
            is AppError.Invalid -> Code.INVALID_ARGUMENT
            is AppError.Unauthenticated -> Code.UNAUTHENTICATED
            is AppError.Forbidden -> Code.PERMISSION_DENIED
            is AppError.RateLimited -> Code.RESOURCE_EXHAUSTED
            is AppError.Unavailable -> Code.UNAVAILABLE
        }
    val status =
        RpcStatus
            .newBuilder()
            .setCode(code.number)
            .setMessage(message.orEmpty())
            .addDetails(
                Any.pack(
                    ErrorInfo
                        .newBuilder()
                        .setReason(this.code.value)
                        .setDomain(ERROR_DOMAIN)
                        .build(),
                ),
            )
    if (this is AppError.Invalid && violations.isNotEmpty()) {
        val badRequest = BadRequest.newBuilder()
        violations.forEach { badRequest.addFieldViolationsBuilder().setField(it.field).setDescription(it.message) }
        status.addDetails(Any.pack(badRequest.build()))
    }
    val wait = (this as? AppError.RateLimited)?.retryAfter
    if (wait != null) {
        val delay = Duration.newBuilder().setSeconds(wait.inWholeSeconds)
        status.addDetails(Any.pack(RetryInfo.newBuilder().setRetryDelay(delay).build()))
    }
    return status.build()
}

/** The stable error code (`ErrorInfo.reason`) of a failed call to a NothingEats service, if it sent one. */
fun Throwable.errorCode(): String? {
    val status =
        when (this) {
            is StatusException -> StatusProto.fromStatusAndTrailers(status, trailers)
            is StatusRuntimeException -> StatusProto.fromStatusAndTrailers(status, trailers)
            else -> return null
        }
    return status.detailsList
        .firstOrNull { it.`is`(ErrorInfo::class.java) }
        ?.unpack(ErrorInfo::class.java)
        ?.takeIf { it.domain == ERROR_DOMAIN }
        ?.reason
}
