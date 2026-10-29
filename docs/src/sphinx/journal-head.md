# JournalHead

## What it is

`JournalHead` is an optional write path of the journal plugin. When `head.enabled` is true, the plugin writes the
journal rows of a write and one item of a dedicated `JournalHead` table in a single `TransactWriteItems` call.

The `JournalHead` table holds one item per persistence id. The item carries the last sequence number of that
persistence id and the events of the write that produced it. It holds no aggregate state.

A read model update can consume the DynamoDB Stream of the `JournalHead` table and receive the events of each
aggregate in write order, because DynamoDB Streams delivers the records of one item in the order the item was
modified.

## Why

The journal table shards writes by `pkey`. Events of one persistence id can therefore land in different partitions,
and the journal stream does not guarantee an order per persistence id. The `JournalHead` item is a single item per
persistence id, so its stream is ordered per persistence id.

## The premise it checks

The write condition of the `JournalHead` item is:

```
(attribute_not_exists(sequence-nr) AND :first = :one) OR (sequence-nr = :prev)
```

`:first` is the first sequence number of the write, `:prev` is `:first - 1`, and `:one` is `1`. The item is written
only if it already holds exactly the sequence number before this write, or if it does not exist yet and the write
starts at sequence number `1`.

This is **not** optimistic locking. It is a safety check that the assumption "there is only one writer per
aggregate" — kept by Cluster Sharding and Split Brain Resolver — still holds. A gap (the head is older than this
write) and a rewind (the head is newer than this write) both fail the transaction, and nothing is written.

The journal rows have their own condition: a row is written only if the key `(persistenceId, seqNr)` does not exist
yet. A duplicate write fails.

## Table

Create the table with `tools/dynamodb-setup/journal-head-table.json`.

| Attribute        | Type   | Role                                |
|:-----------------|:-------|:------------------------------------|
| `persistence-id` | `S`    | partition key, one per aggregate    |
| `sequence-nr`    | `N`    | last sequence number of the write   |
| `events`         | `L`    | events of the write                 |

Each element of `events` is a map of `sequence-nr` (`N`) and `message` (`B`), the serialized event as it is stored
in the journal.

```{important}
Enable DynamoDB Streams on the `JournalHead` table, and do not rely on a stream of the journal table. The journal
table is used for rebuilding and replaying an aggregate, not for ordering the read model updates.
```

## Configuration

```
j5ik2o.dynamo-db-journal {
  head {
    enabled = true
    table-name = "JournalHead"
    columns-def {
      persistence-id-column-name = "persistence-id"
      sequence-nr-column-name = "sequence-nr"
      events-column-name = "events"
      event-sequence-nr-column-name = "sequence-nr"
      event-message-column-name = "message"
    }
  }
}
```

`head.enabled` defaults to `false`. When it is `false`, the plugin behaves exactly as before and does not touch the
`JournalHead` table.

```{warning}
`head.enabled` requires `dynamo-db-client.client-version = "v2"` without DAX. DAX does not support
`TransactWriteItems`, and the AWS SDK v1 client is not supported by this write path. The plugin refuses to start
with any other client configuration.
```

## Enabling it on an existing system

1. Create the `JournalHead` table with DynamoDB Streams enabled.
2. Make sure the application is still running with `head.enabled = false`.
3. Run the migration tool. It scans the journal table and creates a `JournalHead` item for every persistence id it
   finds. The item holds the current last sequence number and an empty event list.

   ```shell
   $ sbt -batch "journal-v2/runMain com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.JournalHeadMigration"
   ```

   Add `--dry-run` to see how many items would be created without writing. The writes are conditional, so the tool
   can be run more than once; existing items are left untouched.
4. Set `head.enabled = true` and restart the application.

If an aggregate is written without its `JournalHead` item, the write fails with
`JournalHeadMissingException`. That failure means the migration has not covered the aggregate yet. A new aggregate
whose first sequence number is `1` does not need a migration.

## Limits

A single write is rejected, before anything is sent to DynamoDB, when:

- it carries more than 99 events (the transaction would contain more than 100 items, the DynamoDB limit), with
  `JournalHeadTooManyEventsException`;
- the `JournalHead` item would exceed 400 KB, with `JournalHeadItemTooLargeException`;
- the whole transaction would exceed 4 MB, with `JournalHeadTransactionTooLargeException`.

A rejected write is neither split into several transactions nor written without a transaction.

## Failures

| Failure                                      | Meaning                                                          |
|:---------------------------------------------|:-----------------------------------------------------------------|
| `JournalHeadMissingException`                | the aggregate has events but no `JournalHead` item — run the migration |
| `JournalHeadSequenceMismatchException`       | the head does not hold the sequence number before this write (gap or rewind) |
| `DuplicateJournalEventsException`            | the journal already contains some of the written events          |
| `JournalHeadTooManyEventsException`          | more than 99 events in one write                                 |
| `JournalHeadItemTooLargeException`           | the `JournalHead` item would exceed 400 KB                       |
| `JournalHeadTransactionTooLargeException`    | the transaction would exceed 4 MB                                |

## Deleting events

`deleteMessagesTo` deletes journal rows only. It does not rewind or delete the `JournalHead` item, because a head
that moves backwards would break the ordering guarantee. A new write continues at the last sequence number the
aggregate had.
