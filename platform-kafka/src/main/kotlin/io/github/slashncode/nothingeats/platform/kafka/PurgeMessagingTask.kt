package io.github.slashncode.nothingeats.platform.kafka

import io.github.slashncode.nothingeats.platform.core.run.CronTask
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

/**
 * `cron purge-messaging`: deletes published outbox rows and old inbox rows. Inbox rows must live
 * longer than any redelivery can take, so keep [inboxRetention] well above the topics' retention.
 */
class PurgeMessagingTask(
    private val db: PostgresDatabase,
    private val outboxRetention: Duration = 7.days,
    private val inboxRetention: Duration = 30.days,
    private val clock: Clock = Clock.systemUTC(),
) : CronTask {
    override val name = "purge-messaging"

    private val log = LoggerFactory.getLogger(PurgeMessagingTask::class.java)

    override suspend fun run() {
        val now = clock.instant()
        val (outbox, inbox) =
            db.transaction {
                val outbox =
                    OutboxEvents.deleteWhere {
                        OutboxEvents.publishedAt.isNotNull() and (OutboxEvents.publishedAt less now.minus(outboxRetention.toJavaDuration()))
                    }
                val inbox = InboxEvents.deleteWhere { InboxEvents.processedAt less now.minus(inboxRetention.toJavaDuration()) }
                outbox to inbox
            }
        log.info("Purged {} outbox and {} inbox rows", outbox, inbox)
    }
}
