-- Transactional outbox: events written in the same transaction as the state change,
-- published to Kafka afterwards by the relay. `position` keeps the publish order.
create table outbox_events
(
    id            uuid primary key,
    position      bigint generated always as identity,
    topic         text        not null,
    partition_key text        not null,
    type          text        not null,
    payload       text        not null,
    created_at    timestamptz not null,
    published_at  timestamptz
);

create index outbox_events_unpublished on outbox_events (position) where published_at is null;

-- Inbox: one row per consumed event and consumer group, written in the same transaction as the
-- handler's own changes, so redelivered events have no second effect.
create table inbox_events
(
    consumer     text        not null,
    event_id     text        not null,
    processed_at timestamptz not null,
    primary key (consumer, event_id)
);
