package io.github.slashncode.nothingeats.platform.persistence

import org.postgresql.util.PSQLException
import java.sql.SQLException

/** Postgres SQLSTATE codes that services react to. Classify errors by these, never by message text. */
object SqlStates {
    const val UNIQUE_VIOLATION = "23505"
    const val FOREIGN_KEY_VIOLATION = "23503"
    const val NOT_NULL_VIOLATION = "23502"
    const val CHECK_VIOLATION = "23514"
    const val SERIALIZATION_FAILURE = "40001"
    const val DEADLOCK_DETECTED = "40P01"
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

/** The SQLSTATE of the first SQL exception in the cause chain (Exposed wraps the driver's). */
val Throwable.sqlState: String?
    get() = causes().filterIsInstance<SQLException>().firstNotNullOfOrNull { it.sqlState }

/** Name of the violated constraint, e.g. `dishes_name_key`, if Postgres reported one. */
val Throwable.violatedConstraint: String?
    get() = causes().filterIsInstance<PSQLException>().firstNotNullOfOrNull { it.serverErrorMessage?.constraint }

fun Throwable.isUniqueViolation(): Boolean = sqlState == SqlStates.UNIQUE_VIOLATION

fun Throwable.isForeignKeyViolation(): Boolean = sqlState == SqlStates.FOREIGN_KEY_VIOLATION

/** Safe to retry the whole transaction. */
fun Throwable.isRetryable(): Boolean = sqlState == SqlStates.SERIALIZATION_FAILURE || sqlState == SqlStates.DEADLOCK_DETECTED
