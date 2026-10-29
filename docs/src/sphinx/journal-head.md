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
(attribute_not_exists(sequence-nr) AND :first = :one) OR (sequence-nr < :first)
```

`:first` is the first sequence number of the write and `:one` is `1`. The item is written only if it is behind the
first sequence number of this write, or if it does not exist yet and the write starts at sequence number `1`.

A gap is accepted on purpose. When a write is rejected by the limits below, Pekko does not take the sequence numbers
of that write back, so the next write of the same actor starts above the head by more than one. Recovery does not
check the sequence numbers of an aggregate for gaps, so such a journal keeps working.

This is **not** optimistic locking. It is a safety check that the assumption "there is only one writer per
aggregate" — kept by Cluster Sharding and Split Brain Resolver — still holds. A head that is equal to or higher than
the first sequence number of this write (a rewind, or another writer that already wrote these sequence numbers) fails
the transaction, and nothing is written.

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
Enable DynamoDB Streams on the `JournalHead` table only. When `head.enabled` is true, disable DynamoDB Streams on
the journal table: the journal table is used for rebuilding and replaying an aggregate, not for ordering the read
model updates. `tools/dynamodb-setup/journal-table-for-head.json` is the journal table definition without a stream.
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

Run the steps in this order. Do not write to the aggregates between the first migration run and the moment every
node runs with `head.enabled = true`.

1. Create the `JournalHead` table with DynamoDB Streams enabled.
2. Stop the writes of the application (stop every node).
3. Run the migration tool without arguments. It scans the journal table, does not write anything, and reports how
   many `JournalHead` items it would create, raise or leave as they are.

   ```shell
   $ sbt -batch "journal-v2/runMain com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.JournalHeadMigration"
   ```

4. Run the tool with `--execute`. It creates a `JournalHead` item for every persistence id it finds; the item holds
   the last sequence number of the journal and an empty event list.

   ```shell
   $ sbt -batch "journal-v2/runMain com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.JournalHeadMigration --execute"
   ```

5. Set `head.enabled = true` on every node and start the application.
6. Run the tool again with `--execute`. If a write happened after step 4 (for example while the nodes were being
   replaced), the `JournalHead` item can be behind the journal. The tool raises it to the last sequence number of the
   journal, but never lowers it, and clears the event list, because the head no longer describes a single write. The
   events up to that point remain in the journal for a rebuild.
7. The tool prints the persistence ids whose `JournalHead` item it raised. The events between the old head and the
   raise were never written to the `JournalHead` table, so their read models did not receive them. Rebuild the read
   model of those aggregates from the journal table before relying on it.

The writes of the tool are conditional, so it can be run as often as needed. A run without `--execute` is a dry-run
and never writes.

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

The rejection is returned as a failed write of that batch, so the actor handles it with `onPersistRejected` and stays
alive. A write that reaches DynamoDB but fails there (a failed condition or a service error) is a journal failure and
stops the actor with `onPersistFailure`.

Pekko does not take the sequence numbers of a rejected write back: the next write of the same actor starts above the
rejected sequence numbers, and the journal keeps a gap. The `JournalHead` condition accepts a gap, so the actor keeps
working. The `JournalHead` item moves to the next written sequence number.

## Failures

| Failure                                      | Meaning                                                          |
|:---------------------------------------------|:-----------------------------------------------------------------|
| `JournalHeadMissingException`                | the aggregate has events but no `JournalHead` item — run the migration |
| `JournalHeadSequenceMismatchException`       | the head is equal to or higher than the first sequence number of this write (a rewind, or another writer) |
| `DuplicateJournalEventsException`            | the journal already contains some of the written events          |
| `JournalHeadTooManyEventsException`          | more than 99 events in one write                                 |
| `JournalHeadItemTooLargeException`           | the `JournalHead` item would exceed 400 KB                       |
| `JournalHeadTransactionTooLargeException`    | the transaction would exceed 4 MB                                |
| `JournalHeadInvalidWriteException`           | the rows of one write are empty, span several persistence ids, or are not contiguous |

## Deleting events

`deleteMessagesTo` deletes journal rows only. It does not write the `JournalHead` item and does not change the
DynamoDB Stream of the `JournalHead` table, whichever of soft delete and hard delete is configured, because a head
that moves on a delete would break the ordering guarantee.

With soft delete (the default), the deleted rows stay in the journal, so a new write continues at the last sequence
number the aggregate had. A hard delete that removes every event of an aggregate empties the journal, and the journal
no longer knows the last sequence number; the plugin then uses the `sequence-nr` of the `JournalHead` item as the
lower bound of the highest sequence number, so the aggregate continues above the head instead of starting at `1`.
