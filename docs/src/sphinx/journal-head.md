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

## Capacity and cost

The plugin keeps one `JournalHead` item per aggregate. An aggregate is one persistence id, so every write of that
aggregate reaches the same partition. DynamoDB limits a partition to 1,000 write units per second, and a
transactional write performs two underlying writes for each item. The write rate of one aggregate is therefore about
`1000 / (2 × S)` writes per second, where `S` is the size of the `JournalHead` item in KB. See
[partition throughput](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-partition-key-design.html)
and [transaction capacity](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html).

| `JournalHead` item size | Writes per second for one aggregate |
|:--|:--|
| 1 KB | about 500 |
| 10 KB | about 50 |
| 100 KB | about 5 |
| 400 KB | about 1.25 |

These values are upper bounds from the DynamoDB limits. Other items in the same partition, the capacity mode of the
table, and the capacity of the journal table also affect the rate you reach.

Head mode writes each event twice, once in the journal table and once in the `JournalHead` table, and it writes both
in one transaction. The transaction uses twice the write capacity of its items, so the write cost is up to about four
times the cost without head mode. A transaction that fails a condition still consumes the capacity of the items it
attempted. The stream and its consumer add their own cost.

For a new deployment, start with on-demand capacity mode. It follows the traffic without a provisioning step. Move to
provisioned mode when the traffic is steady and you know the write rate of each aggregate. In provisioned mode, the
`JournalHead` table and the journal table need their own capacity, including the journal table's
`GetJournalRowsIndex`.

Monitor `ThrottledRequests` and `WriteThrottleEvents` on both the `JournalHead` table and the journal table. If an
aggregate writes faster than the bound above allows, head mode is not a fit for that aggregate, or the
`JournalHead` item must carry less data.

If one item cannot carry the events at the rate you need, keep only the sequence number in the `JournalHead` item and
read the events from the journal table in the consumer. The head stream then carries the order, and the consumer
reads the journal rows between the previous sequence number and the new one. This lowers the item size and the write
cost, and it makes the consumer depend on both tables.

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

The assumption that there is one writer per persistence id is kept by Cluster Sharding; the plugin detects some cases
where it no longer holds (for example, a delayed writer that tries to write a sequence number another writer has
already written) and does not prevent a second writer.

The journal rows have their own condition: a row is written only if the key `(persistenceId, seqNr)` does not exist
yet. A duplicate write fails.

## Table

Create the table with `tools/dynamodb-setup/journal-head-table.json`. That file is generated from the `JournalHead`
table spec, and the DynamoDB Local fixture uses the same definition. Change the spec and regenerate the file instead
of editing the JSON by hand.

| Attribute        | Type   | Role                                |
|:-----------------|:-------|:------------------------------------|
| `persistence-id` | `S`    | partition key, one per aggregate    |
| `sequence-nr`    | `N`    | last sequence number of the write   |
| `events`         | `L`    | events of the write                 |
| `revision`       | `N`    | optional, raised by one on every in-place event update |

Each element of `events` is a map of `sequence-nr` (`N`) and `message` (`B`), the bytes stored in the journal row's
`message`.

A write does not write `revision`, so an item without it has revision 0. Only an in-place event update (see below)
writes it. The attribute name is fixed and is not a column setting. A later write replaces the whole item, so the item
of the next write has no `revision` again.

```{important}
Enable DynamoDB Streams on the `JournalHead` table only, with the stream view type `NEW_IMAGE` or
`NEW_AND_OLD_IMAGES`. With `KEYS_ONLY` or `OLD_IMAGE`, a record does not carry the events of the write that produced
it. When `head.enabled` is true, disable DynamoDB Streams on the journal table. The journal table is used for
rebuilding and replaying an aggregate, not for ordering the read model updates.
`tools/dynamodb-setup/journal-table-for-head.json` is the journal table definition without a stream.
```

## Consuming the stream

A read model update consumes the DynamoDB Stream of the `JournalHead` table. Each record carries the new image of
one item, so it holds the last sequence number and the events of one write. DynamoDB Streams writes each record once
and preserves the order of modifications to one item, so the events of one aggregate arrive in write order. See
[DynamoDB Streams](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Streams.html).

The consumer applies each record at least once. It can retry a failed batch, rewind a checkpoint, or reset a shard
iterator, and it then processes records it has already applied. Make the read model update idempotent. Keep a
checkpoint for each persistence id, the pair (`sequence-nr`, `revision`) of the last record applied to the read model,
and read a missing `revision` as 0. Write the checkpoint in the same transaction as the read model when the read model
store supports transactions. Compare a record with the checkpoint in this order:

| Record compared with the checkpoint                       | What the consumer does                                                      |
|:----------------------------------------------------------|:----------------------------------------------------------------------------|
| `events` is empty                                         | written by the migration tool: do not apply it and do not move the checkpoint, rebuild from the journal |
| `sequence-nr` is lower                                    | discard the record, it was applied before                                   |
| `sequence-nr` is equal and `revision` is not higher       | discard the record, it was applied before                                   |
| `sequence-nr` is equal and `revision` is higher           | an in-place update of an event of that write: replace the stored events with the same sequence numbers by the events of the record, or rebuild them from the journal |
| `sequence-nr` is higher                                   | a new write: apply its events and move the checkpoint to (`sequence-nr`, `revision`) |

Each element of `events` is the bytes of a serialized `PersistentRepr`, the same value the journal row stores in
`message`. Deserialize it with the serializer for `PersistentRepr`, then apply the same event adapter that the
journal uses.

For a Lambda consumer, use the
[DynamoDB Streams event source mapping](https://docs.aws.amazon.com/lambda/latest/dg/invocation-eventsourcemapping.html).
For a Kinesis Client Library consumer, use the
[Kinesis adapter for DynamoDB Streams](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Streams.KCLAdapter.html).
Both preserve the order of the records of one item.

DynamoDB Streams keeps records for up to 24 hours. If the consumer is stopped for longer, it can lose records and the
read model becomes incomplete. Rebuild the read model from the journal table. If the journal events of an aggregate
were hard deleted, the journal cannot rebuild them. Restore the read model, the journal, and the `JournalHead` table
from a backup taken at the same point in time.

To rebuild a read model, stop the consumer, clear the affected read model state, replay the journal rows for the
affected persistence ids, and set the checkpoint of each persistence id to the pair (`sequence-nr`, `revision`) of the
`JournalHead` item that you read before you started the replay, reading a missing `revision` as 0. Read the item first:
the journal rows you replay already contain every update up to that `revision`, so a checkpoint with a lower `revision`
would apply a stale record again. Start the consumer after all affected persistence ids reach that point.

The migration tool writes `JournalHead` items with an empty `events` list. Do not apply such a record to the read
model, and do not advance a checkpoint from it. The events between the old head and the new `sequence-nr` never
reached the stream. If the checkpoint of the consumer is behind the new `sequence-nr`, rebuild the read model for
that persistence id from the journal before you continue. When the tool raises an existing item, it also removes
`revision`, so the raised item reads as revision 0.

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

`head.enabled` defaults to `false`. When it is `false`, the plugin does not write the `JournalHead` table and does
not read the last sequence number from it. Read the Lifecycle constraints section below before you disable head mode.

```{warning}
`head.enabled` requires `dynamo-db-client.client-version = "v2"`. The plugin does not support DAX or the AWS SDK v1
client on this write path, and it refuses to start with either configuration.
```

## Enabling it on an existing system

Run the steps in this order. Do not write to the aggregates between the first migration run and the moment every
node runs with `head.enabled = true`.

1. Create the `JournalHead` table with DynamoDB Streams enabled.
2. Stop the writes of the application (stop every node).
3. Run the migration tool without arguments. It scans the journal table and the snapshot table, does not write
   anything, and reports how many `JournalHead` items it would create, raise or leave as they are.

   ```shell
   $ sbt -batch "journal-v2/runMain com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.JournalHeadMigration"
   ```

4. Run the tool with `--execute`. It creates a `JournalHead` item for every persistence id it finds in the journal or
   the snapshot table; the item holds the last sequence number of that aggregate and an empty event list.

   ```shell
   $ sbt -batch "journal-v2/runMain com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.JournalHeadMigration --execute"
   ```

   The tool reads the snapshot table from `j5ik2o.dynamo-db-snapshot` when that config is present. Without that config,
   pass `--snapshot-table` with the table name; the tool exits with an error instead of guessing the default `Snapshot`
   table. Override the columns with `--snapshot-persistence-id-column` and `--snapshot-sequence-nr-column`, or pass
   `--without-snapshots` when the deployment has no snapshot table. Each of these options requires a value, and a
   missing value is an error. A snapshot table that does not exist is skipped and reported.

   Scanning the snapshot table matters: an aggregate whose journal events were all hard deleted has no journal row
   left, and its snapshot is the only record of its last sequence number. Without a migrated `JournalHead` item, the
   next write of that aggregate fails with `JournalHeadMissingException`. Running the tool again repairs it only when
   the tool scans the snapshot table. With `--without-snapshots`, or without a snapshot table, the tool cannot see
   the aggregate.

5. Set `head.enabled = true` on every node and start the application.
6. Run the tool again with `--execute`. If a write happened after step 4 (for example while the nodes were being
   replaced), the `JournalHead` item can be behind the journal. The tool raises it to the last sequence number of the
   journal or the snapshot, but never lowers it, and clears the event list, because the head no longer describes a
   single write. The events up to that point remain in the journal for a rebuild.
7. The tool prints the persistence ids whose `JournalHead` item it raised. The events between the old head and the
   raise were never written to the `JournalHead` table, so their read models did not receive them. Rebuild the read
   model of those aggregates from the journal table before relying on it. If the journal events of an aggregate were
   hard deleted, the journal cannot rebuild them. Restore the read model and the journal from a backup taken at the
   same point in time.

The writes of the tool are conditional, so it can be run as often as needed. A run without `--execute` is a dry-run
and never writes.

If an aggregate is written without its `JournalHead` item, the write fails with
`JournalHeadMissingException`. That failure means the migration has not covered the aggregate yet. A new aggregate
whose first sequence number is `1` does not need a migration.

## Preflight

Run the preflight once before a deployment enables `head.enabled`:

```shell
$ sbt -batch "journal-v2/runMain com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.JournalHeadPreflight"
```

The tool reads `j5ik2o.dynamo-db-journal` and calls `DescribeTable` for the `JournalHead` table and the journal table.
It exits 0 when both stream settings are right, and reports a failure when:

- the `JournalHead` table has no stream, or its stream view type is not `NEW_IMAGE` or `NEW_AND_OLD_IMAGES`;
- the journal table has a stream.

The preflight is a deployment step, not a plugin startup check. A plugin reads only its own configuration, and
`DescribeTable` costs a request that a startup should not pay. The preflight also cannot detect a mixed deployment, so
the mixed-node warning in Lifecycle constraints still applies.

## Lifecycle constraints

Do not disable `head.enabled` after hard-deleting the journal events of an aggregate. The journal then has no record
of the aggregate's last sequence number, and the plugin reads that number from the `JournalHead` item only while head
mode is enabled. With head mode off, if the journal is empty and there is no snapshot, recovery starts the aggregate
at sequence number `1`. The journal then contains events at sequence numbers the read model has already applied. A
rebuild can make a consumer that deduplicates by sequence number discard the new events. If you re-enable head mode
while the aggregate's sequence number is below the existing head, the write fails with
`JournalHeadSequenceMismatchException`. If the aggregate has already caught up to the head, the write succeeds, but
the events written while head mode was off never reached the head stream. Keep head mode enabled, or keep a snapshot
that holds the last sequence number, before you turn it off. Disabling head mode also stops the head stream, so the
read model no longer receives updates from it. Plan how the read model will be updated before you disable head mode.

Do not run a rolling deployment that mixes nodes with `head.enabled = true` and nodes with `head.enabled = false`. A
node with head mode off writes journal rows without updating the `JournalHead` item, so the events of those writes
never reach the head stream. A later write from a node with head mode on can move the head past those sequence
numbers, because the write condition accepts a gap. The migration tool compares the head with the highest sequence
number in the journal or the snapshot, and it reports only the aggregates whose head is still behind. After a
head-on node writes again, the gap is invisible to the tool, and the read model stays incomplete.

If a mixed deployment has happened, rebuild the read model of every aggregate that a head-off node may have written
during that period. The tool cannot identify those aggregates after a head-on node has written. To avoid the
situation, stop every node, run the migration tool with `--execute`, start every node with `head.enabled = true`, and
then rebuild the read model of the aggregates the tool reports.

## Limits

A single write is rejected, before anything is sent to DynamoDB, when:

- it carries more than 99 events (the transaction would contain more than 100 items, the DynamoDB limit), with
  `JournalHeadTooManyEventsException`;
- the `JournalHead` item would exceed 400 KB, with `JournalHeadItemTooLargeException`;
- a journal row would exceed 400 KB, with `JournalHeadJournalRowTooLargeException` (a journal row carries the encoded
  event and its tags, so it can exceed the limit while the `JournalHead` item stays below it);
- the whole transaction would exceed 4 MB, with `JournalHeadTransactionTooLargeException`.

A rejected write is neither split into several transactions nor written without a transaction.

The rejection is returned as a failed write of that batch, so the actor handles it with `onPersistRejected` and stays
alive. A write that reaches DynamoDB but fails there (a failed condition or a service error) is a journal failure and
stops the actor with `onPersistFailure`.

Pekko does not take the sequence numbers of a rejected write back: the next write of the same actor starts above the
rejected sequence numbers, and the journal keeps a gap. The `JournalHead` condition accepts a gap, so the actor keeps
working. The `JournalHead` item moves to the next written sequence number.

A new aggregate that has no `JournalHead` item yet is the exception. If its first write (starting at sequence number
`1`) is rejected by these limits, the actor keeps the advanced sequence numbers, and the next write of that actor
starts above `1`. The condition accepts a missing item only for a write that starts at `1`, so that next write fails
and stops the actor with `onPersistFailure`. Restart the actor: recovery finds no event and no `JournalHead` item, so
the aggregate starts again at sequence number `1` and the write is accepted. Write a smaller batch (at most 99 events,
at most 400 KB for the `JournalHead` item) after the restart.

## Failures

| Failure                                      | Meaning                                                          |
|:---------------------------------------------|:-----------------------------------------------------------------|
| `JournalHeadMissingException`                | the aggregate has events but no `JournalHead` item — run the migration; or this is the write after the rejected first write of a new aggregate — restart the actor |
| `JournalHeadSequenceMismatchException`       | the head is equal to or higher than the first sequence number of this write (a rewind, or another writer) |
| `DuplicateJournalEventsException`            | the journal already contains some of the written events          |
| `JournalHeadTooManyEventsException`          | more than 99 events in one write                                 |
| `JournalHeadItemTooLargeException`           | the `JournalHead` item would exceed 400 KB                       |
| `JournalHeadJournalRowTooLargeException`     | a journal row would exceed 400 KB (the encoded event and tags)   |
| `JournalHeadTransactionTooLargeException`    | the transaction would exceed 4 MB                                |
| `JournalHeadInvalidWriteException`           | the rows of one write are empty, span several persistence ids, or are not contiguous |

## In-place event updates

`DynamoDBJournal.InPlaceUpdateEvent` rewrites one journal row. When `head.enabled` is true, the plugin also rewrites the
matching event of the `JournalHead` item, and it writes both in one `TransactWriteItems` call. Either both change or
neither does, so the head stream delivers the updated event and never disagrees with the journal.

The `JournalHead` item holds only the events of its latest write, and its stream has already delivered every older
event. The plugin therefore refuses an update of an event outside the latest write with a
`JournalHeadAsyncUpdateEventUnsupportedException` and changes nothing. Every other outcome that is not a success is one
of the failures below, and none of them changes the journal or the head.

| Failure                                       | Meaning                                                                                  | Recoverable by the caller |
|:----------------------------------------------|:-----------------------------------------------------------------------------------------|:--------------------------|
| `JournalHeadAsyncUpdateEventUnsupportedException` | the event is not in the latest write of the head                                     | no, refused               |
| `JournalHeadRowUpdateRejectedException`       | the journal row does not exist (for example after a hard delete) or is soft deleted; `reason` is `RowDoesNotExist` or `RowDeleted` | no, refused |
| `JournalHeadMissingException`                 | the aggregate has no `JournalHead` item — run the migration                              | no, refused               |
| `JournalHeadItemTooLargeException`            | the `JournalHead` item would exceed 400 KB with the new event                            | no, refused               |
| `JournalHeadJournalRowTooLargeException`      | the journal row would exceed 400 KB; measured before anything is read or sent            | no, refused               |
| `JournalHeadConcurrentUpdateException`        | the head kept changing, DynamoDB reported a transaction conflict, or it throttled the request, for three attempts | yes, send the update again |
| `IllegalStateException`                       | a state that cannot happen: the head went backwards between two reads, the head item is malformed, DynamoDB cancelled the transaction for a reason the plugin does not handle, or it returned an unexpected result | no, a bug or a damaged table |

An update keeps the `deleted` flag of the journal row and does not update a missing or deleted row. An event removed
with a soft delete therefore stays deleted, and updating it is refused with `RowDeleted`.

The transaction is conditional on the `sequence-nr` and the `revision` of the head being the ones the plugin read, so a
write or an update that lands between the read and the transaction makes the plugin read the head again and try again,
up to three attempts. A transaction conflict and throttling are tried again after a backoff that grows from
the `min-backoff` of `write-backoff` up to its `max-backoff`. When the attempts are used up, the caller gets
`JournalHeadConcurrentUpdateException`. When `write-backoff` is disabled, these attempts follow one another without a
wait.

A request-level failure of DynamoDB, such as a network error or a throttled request that is not reported as a
cancellation of the transaction, is not one of these outcomes. The write backoff restarts it, and when the restarts are
used up the caller gets the exception of the AWS SDK.

An update changes the message of an event, keeps its sequence number and the `sequence-nr` of the head, and raises the
`revision` of the head by one. The stream carries the update as a record with the same `sequence-nr` and a higher
`revision`. The section "Consuming the stream" describes how a consumer applies it.

An update sent from outside the aggregate can still collide with the aggregate's own writes. When it does, the write
of the aggregate, not the update, can be the one that DynamoDB cancels with a transaction conflict. The write path
reports that cancellation as the SDK exception, the persist fails, and the persistent actor stops through
`onPersistFailure`. The plugin does not retry a write. This follows from the code of the write path; the test that
races updates with persists against DynamoDB Local did not produce it, and that is not a proof that it cannot happen
on DynamoDB. Send updates for an aggregate through the aggregate, or when it is not persisting.

A soft delete and an update of the same event can overlap. A soft delete marks journal rows as deleted and does not
write the head, so the head keeps the events of its latest write whatever their journal rows say. An update that
commits before the delete leaves the new message in both tables, and the delete then marks only the journal row. The
journal and the head therefore disagree about whether that event is deleted, as they do after any soft delete.

A failure of the update itself, such as a DynamoDB error, now reaches the caller with its own type. Before, the head-less
path relabelled every failure of `InPlaceUpdateEvent` as an `IllegalArgumentException` about serialization. Only a
serialization failure keeps that type, and the original exception is its cause.

## Deleting events

`deleteMessagesTo` deletes journal rows only. It does not write the `JournalHead` item and does not change the
DynamoDB Stream of the `JournalHead` table, whichever of soft delete and hard delete is configured, because a head
that moves on a delete would break the ordering guarantee.

With soft delete (the default), the deleted rows stay in the journal, so a new write continues at the last sequence
number the aggregate had. A hard delete that removes every event of an aggregate empties the journal, and the journal
no longer knows the last sequence number; the plugin then uses the `sequence-nr` of the `JournalHead` item as the
lower bound of the highest sequence number, so the aggregate continues above the head instead of starting at `1`.
