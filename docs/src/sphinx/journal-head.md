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

A gap is accepted on purpose. The plugin never leaves one for a write that exceeds the limits below, because such a
write fails and stops the actor before anything is written. A gap can still appear when Pekko itself refuses a write,
for example when it cannot serialize an event: Pekko does not take the sequence numbers of that write back, so the next
write of the same actor starts above the head by more than one. Recovery does not check the sequence numbers of an
aggregate for gaps, so such a journal keeps working.

This is **not** optimistic locking. It is a safety check that the assumption "there is only one writer per
aggregate" — kept by Cluster Sharding and Split Brain Resolver — still holds. A head that is equal to or higher than
the first sequence number of this write (a rewind, or another writer that already wrote these sequence numbers) fails
the transaction, and nothing is written.

The assumption that there is one writer per persistence id is kept by Cluster Sharding; the plugin detects some cases
where it no longer holds (for example, a delayed writer that tries to write a sequence number another writer has
already written) and does not prevent a second writer. A delayed writer whose sequence numbers are above the head
passes the condition, because the condition accepts a gap, so the plugin cannot detect it.

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
`message`. The item has no attribute for tags. See the Tags section.

`events` holds all the events of one `persistAll(ev1, ev2, ...)` call, one `AtomicWrite`. Design the events so that
their total size stays below 400 KB for the `JournalHead` item. See the Limits section.

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
| `sequence-nr` is equal and `revision` is higher           | an in-place update of an event of that write: replace the stored events with the same sequence numbers by the events of the record, then move the checkpoint to (`sequence-nr`, `revision`) of the record |
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
from a backup taken at the same point in time. The Backup and restore section describes how to restore the two tables
together.

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

## Tags

The `JournalHead` item does not carry tags. A record of the head stream tells the consumer the persistence id, the
sequence numbers, and the events of one write. It does not tell which tags those events had. This is by design: tags
would make every `JournalHead` item larger, and the item size sets the write rate of an aggregate (see Capacity and
cost).

The tags of an event are stored in the `tags` attribute of its journal row only. The plugin removes the `Tagged`
wrapper before it serializes the event. The journal row and the `JournalHead` item then store the same bytes in
`message`, and those bytes hold the event without the wrapper and without the tags. The plugin joins the tags with
`tag-separator` (default `,`) into one string. A row of an event without tags has no `tags` attribute.

A consumer that routes or filters events by tag has two ways to get the tags:

- **Derive the tags from the event.** If your event adapter chooses the tags from the event alone, deserialize the
  `message` of the record as described in "Consuming the stream" and apply the same rule in the consumer.
- **Read the journal row.** Query the `GetJournalRowsIndex` index of the journal table with the `persistence-id` and the
  `sequence-nr` of the event, and read the `tags` attribute. The index projects all attributes. DynamoDB updates a
  [global secondary index asynchronously](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/GSI.html#GSI.Writes),
  so the row of a new record can be missing for a short time. The consumer must retry the query. This makes the
  consumer depend on the journal table and adds a read for each record, or for each range of sequence numbers.

The plugin does not provide a Pekko Persistence Query read journal, so `eventsByTag` is not available.

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
   hard deleted, the journal cannot rebuild them. Restore the read model, the journal, and the `JournalHead` table from
   a backup taken at the same point in time (see the Backup and restore section).

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

## Backup and restore

Restore the journal table and the `JournalHead` table to the same point in time. If you use the snapshot table, restore
it to that time too, because the migration tool reads it as a source of the last sequence number.

Matching timestamps do not prove that every transaction was restored completely. DynamoDB propagates transaction
changes to backups gradually, so a restored backup can contain only part of a transaction. See the
[transaction backup limitations](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html).
For example, a restored multi-event write can include its head and highest journal row but omit an earlier row.
The highest sequence numbers can then agree and the migration dry-run can report no work, even though history is
incomplete. Neither check validates the complete event history.

Choose a documented, verified recovery checkpoint taken while every writer and migration tool was quiescent, with
all in-flight writes complete and the backup contents validated. Otherwise, validate the restored transaction and
event history against authoritative write records, accounting for intentional deletions, before accepting the
recovery point. Stopping nodes during a restore does not make the historical recovery point quiescent. If neither
form of verification is possible, stop and select a verified backup; do not restart from matching timestamps and
sequence numbers alone.

The plugin does not reject two tables that disagree. With `head.enabled = true`, recovery takes the higher of two
numbers after a restart: the highest `sequence-nr` in the journal and the `sequence-nr` of the `JournalHead` item. The write condition accepts every
write whose first sequence number is above the head. The aggregate therefore keeps writing, and a mismatch shows up
later as a gap in the journal or as a read model that missed events. `J` is the highest sequence number of an aggregate
in the journal, including soft-deleted rows. `H` is the `sequence-nr` of its head item, and `S` is the highest
snapshot sequence number, when a snapshot exists. A hard delete can leave `H > J` with no restore mismatch.
Migration can also produce a valid `H = S > J`. The head can be the only remaining sequence watermark. The table below assumes `S` is absent or no higher than
both `H` and `J`. If `S` is higher, recovery also uses the snapshot sequence number, and migration targets the
higher of `J` and `S`.

| State after the restore | The next write of the aggregate | Dry-run of the migration tool |
|:--|:--|:--|
| `H < J`, the head is behind | starts at `J + 1` and succeeds, the head moves to `J + 1`, and the events from `H + 1` to `J` never reach the head stream | lists the aggregate under "raised JournalHead items" |
| `H > J`, possibly after a normal hard delete or snapshot migration | starts at `H + 1` and succeeds, and the journal has no events from `J + 1` to `H` | does not report it; counts it as "skipped existing" only if the persistence id exists in the journal or snapshot |
| no head item, and `J` is at least 1 | fails with `JournalHeadMissingException` | counts it as "created" |
| `H = J` | continues at `J + 1` | counts it as "skipped existing" |

### Choose the backup type

Use [point-in-time recovery](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Point-in-time-recovery.html)
(PITR). It lets you restore each table to the same timestamp, subject to the transaction validation above.

[On-demand backups](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Backup-and-Restore.html) are
requested separately for each table. Quiesce every writer and migration tool and finish in-flight writes before
requesting the backups, then validate their contents as above. The requests alone do not prove a consistent
multi-table recovery checkpoint.

### Restore the tables

Before you start, check that PITR is on for each table and that the restore time lies inside the recovery window of
each table. The window runs from `EarliestRestorableDateTime` to `LatestRestorableDateTime`:

```shell
$ aws dynamodb describe-continuous-backups --table-name JournalHead
```

1. Stop every node and the stream consumer. Choose and verify the historical recovery point as described above.
   This shutdown protects the restore work; it does not validate the history captured in that recovery point.
2. Restore each table to the same time. A restore always creates a new table. Keep the indexes, because the plugin
   needs `GetJournalRowsIndex`.

   ```shell
   $ RESTORE_TIME=1760000000   # epoch seconds, inside the window of every table
   $ aws dynamodb restore-table-to-point-in-time --source-table-name Journal \
       --target-table-name Journal-restored --no-use-latest-restorable-time --restore-date-time $RESTORE_TIME
   $ aws dynamodb restore-table-to-point-in-time --source-table-name JournalHead \
       --target-table-name JournalHead-restored --no-use-latest-restorable-time --restore-date-time $RESTORE_TIME
   $ aws dynamodb restore-table-to-point-in-time --source-table-name Snapshot \
       --target-table-name Snapshot-restored --no-use-latest-restorable-time --restore-date-time $RESTORE_TIME
   ```

   Wait until every restored table is `ACTIVE`. A restore recreates the data, the indexes, the capacity, and the
   encryption settings. It does not carry over auto scaling policies, IAM policies, CloudWatch alarms, tags,
   DynamoDB Streams settings, Time to Live settings, or PITR settings. See
   [restoring a table to a point in time](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/PointInTimeRecovery.Tutorial.html).
3. Point the plugin at the restored tables with `j5ik2o.dynamo-db-journal.table-name`,
   `j5ik2o.dynamo-db-journal.head.table-name`, and `j5ik2o.dynamo-db-snapshot.table-name`. Keep the head table name
   different from the journal table name.
4. Run the migration tool without `--execute`. It is a dry-run and writes nothing. The tool reads the snapshot table
   from `j5ik2o.dynamo-db-snapshot`. Without that config, pass `--snapshot-table` or `--without-snapshots`.

   ```shell
   $ sbt -batch "journal-v2/runMain com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.JournalHeadMigration"
   [dry-run] JournalHead migration finished: scanned 120 persistence ids, created 0 JournalHead items, raised 2 JournalHead items, skipped 118 existing JournalHead items
   [dry-run] raised JournalHead items (rebuild the read model of these aggregates from the journal):
     - order-17
     - order-42
   ```

   `created` counts the aggregates that have no head item. `raised` counts the aggregates whose head is behind. Both
   must be 0 before you continue, or you repair them in step 6. The tool scans only persistence ids found in the
   journal or snapshot. It cannot recreate a head whose persistence id is absent from both sources.
5. Check heads against the journal, snapshots, deletion history, and backup timestamps. The tool does not report
   heads above both sources or heads whose persistence id is absent from both. For one aggregate, read `H` and `J`.
   The first command reads the head. The second reads the highest journal row.

   ```shell
   $ aws dynamodb get-item --table-name JournalHead-restored --consistent-read \
       --key '{"persistence-id":{"S":"order-17"}}' \
       --projection-expression '#s' --expression-attribute-names '{"#s":"sequence-nr"}'
   $ aws dynamodb query --table-name Journal-restored --index-name GetJournalRowsIndex \
       --key-condition-expression '#p = :p' --expression-attribute-values '{":p":{"S":"order-17"}}' \
       --expression-attribute-names '{"#p":"persistence-id","#s":"sequence-nr"}' \
       --projection-expression '#s' --no-scan-index-forward --limit 1
   ```

   The commands use the default column names. To check every aggregate, scan the `JournalHead` table and run the
   second command for each `persistence-id`. Include soft-deleted journal rows in `J`. Check `S` in the restored
   snapshot table too. An empty journal is not proof of a mismatch. A normal hard delete can remove every journal
   row while leaving a valid head. A snapshot can explain `H = S > J`.

   Preserve these valid heads. If no journal row or snapshot remains, deleting the head loses the only sequence
   watermark, and migration cannot recreate it. If backup timestamps, deletion history, and snapshots cannot
   establish consistency, stop and select backups from a consistent point in time. Do not infer corruption from
   `H > J` alone.
6. Repair a missing or lagging head only after you establish that the restored source tables are consistent.
   Run the tool with `--execute`. It creates missing items and raises lagging items to the higher sequence number
   in the journal or snapshot, and it clears their event lists. It never lowers a head.

   If you confirm a restore mismatch that requires lowering a head, stop and restore a consistent set of backups.
   Do not delete a head to make its sequence number equal `J`.

   Run the dry-run of step 4 again. Both `created` and `raised` must be 0. Confirm that the heads retained in step 5
   agree with the backup timestamps, snapshots, and deletion history. `H = J` is not a requirement for restarting.
7. Enable DynamoDB Streams on the restored `JournalHead` table, because the restore does not carry the setting over.
   Do it after the repairs, so that the stream holds no record of them. Then run the preflight (see the Preflight
   section). It checks that the head table has a stream with the view type `NEW_IMAGE` or `NEW_AND_OLD_IMAGES` and
   that the journal table has none.

   ```shell
   $ aws dynamodb update-table --table-name JournalHead-restored \
       --stream-specification StreamEnabled=true,StreamViewType=NEW_IMAGE
   ```

8. Recover the read model and its checkpoints to a state consistent with the restored tables. Restoring the source
   tables does not roll back the read model. If it contains changes after the restore time, those changes must not
   remain in the recovered projection.

   Rebuild from the restored journal as described in "Consuming the stream" only when it retains all the events
   needed for that rebuild. Verify that history before erasing the existing projection. If required events were
   hard deleted, restore a backup of the read model and its checkpoints from the same recovery point instead.
   A snapshot or a head watermark cannot replace the missing event history.

   Apply this check to every aggregate printed in step 4 or 6 too. If a restored checkpoint is behind a created or
   raised head, replay the missing range from the journal only if the entire range is retained. If neither the
   journal nor a consistent read-model backup can recover the required state, stop. Do not erase the read model or
   restart the application or consumer; select a recovery point with sufficient backups and history.

   Point the consumer at the stream of the restored `JournalHead` table only after the projection and checkpoints
   are consistent with the restored sources. A repaired head has an empty event list; do not apply it or use it to
   advance a checkpoint.
9. Set `head.enabled = true`, start every node, and then start the consumer.

## Limits

A single write fails, before anything is sent to DynamoDB, when:

- it carries more than 99 events (the transaction would contain more than 100 items, the DynamoDB limit), with
  `JournalHeadTooManyEventsException`;
- the `JournalHead` item would exceed 400 KB, with `JournalHeadItemTooLargeException`;
- a journal row would exceed 400 KB, with `JournalHeadJournalRowTooLargeException` (a journal row carries the encoded
  event and its tags, so it can exceed the limit while the `JournalHead` item stays below it);
- the whole transaction would exceed 4 MB, with `JournalHeadTransactionTooLargeException`.

The same applies to a write whose rows are empty, span several persistence ids, repeat a key, or are not contiguous,
with `JournalHeadInvalidWriteException`. A write that the plugin cannot render as a transaction, or whose row cannot be
keyed from the configured column definitions, fails with the same exception.

A write over a limit is neither split into several transactions nor written without a transaction.

Exceeding a limit is a design mistake, not an error to live with. The size and the number of events of one
`persistAll` call are decided when you design the aggregate. The plugin therefore does not reject the write and keep
the actor alive. It fails the whole `asyncWriteMessages` call with the typed exception, which carries the measured
value and the limit. Pekko calls `onPersistFailure` and stops the actor. A Typed actor receives a
`JournalFailureException` and stops by default.

The plugin checks every `AtomicWrite` of the call before it sends the first one, so a violation in a later write also
leaves the earlier writes of the same call unwritten. Nothing is written and the sequence numbers are not used. After
you fix the code and restart the actor, recovery finds the last written sequence number and the next write continues
from the one after it. The journal has no gap, and a reader such as a read model updater does not need to handle one.

The head-less path (`head.enabled = false`) applies the same rule to a journal row over 400 KB.

A write that Pekko itself refuses, such as an event it cannot serialize, stays a failure of that one `AtomicWrite`. A
write that reaches DynamoDB but fails there (a failed condition or a service error) is a journal failure too.

## Failures

| Failure                                      | Meaning                                                          |
|:---------------------------------------------|:-----------------------------------------------------------------|
| `JournalHeadMissingException`                | the aggregate has events but no `JournalHead` item — run the migration |
| `JournalHeadSequenceMismatchException`       | the head is equal to or higher than the first sequence number of this write (a rewind, or another writer) |
| `DuplicateJournalEventsException`            | the journal already contains some of the written events          |
| `JournalHeadTooManyEventsException`          | more than 99 events in one write                                 |
| `JournalHeadItemTooLargeException`           | the `JournalHead` item would exceed 400 KB                       |
| `JournalHeadJournalRowTooLargeException`     | a journal row would exceed 400 KB (the encoded event and tags)   |
| `JournalHeadTransactionTooLargeException`    | the transaction would exceed 4 MB                                |
| `JournalHeadInvalidWriteException`           | the rows of one write are empty, span several persistence ids, repeat a key, or are not contiguous; also a write the plugin cannot render or key from the configured column definitions |
| `JournalHeadConcurrentWriteException`        | the write was cancelled on every attempt by a transaction conflict or throttling, or because the `JournalHead` item kept changing after the check; nothing was written — the actor stops through `onPersistFailure`; after the restart it can send the same write again |

Every failure of this table reaches the actor as a failed write: Pekko calls `onPersistFailure` and the actor stops. The
four limit exceptions are raised before anything is sent. Restart the actor after you fix the code, and the next write
continues from the last written sequence number.

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

An update keeps the `deleted` flag of the journal row and does not update a missing or deleted row. This rule and
`JournalHeadRowUpdateRejectedException`, with `reason` of `RowDoesNotExist` or `RowDeleted`, also apply when
`head.enabled = false`.

Both paths condition the update on the journal row existing and not being soft deleted. If that condition fails, the
plugin reads the row with a strongly consistent read to determine the rejection reason. If the row exists and is not
deleted at that read, it retries the update, for at most three attempts in total. If this race persists through all
three attempts, the caller gets `JournalHeadConcurrentUpdateException` and can send the update again. With
`head.enabled = false`, these attempts check only the journal row and use no head revision check or transaction.

When `head.enabled = true`, the transaction is also conditional on the `sequence-nr` and the `revision` of the head
being the ones the plugin read. A write or an update that lands between the read and the transaction makes the plugin
read the head again and try again, up to three attempts. A transaction conflict and throttling are tried again after a backoff that grows from
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
of the aggregate, not the update, can be the one that DynamoDB cancels with a transaction conflict. The plugin
classifies a cancellation reason of `TransactionConflict`, `ThrottlingError`, or `ProvisionedThroughputExceeded`, and
sends the same transaction again, at most `max-restarts` times (`write-backoff`). It waits between attempts only when
`write-backoff.enabled` is true, as on the update path; the default is false. When the attempts are used up, the write
fails with `JournalHeadConcurrentWriteException`, the persist fails, and the persistent actor stops through
`onPersistFailure`. The actor does not continue: restart it, and it can send the same write again. The transaction
wrote nothing, so a retry cannot duplicate an event. When you enable the backoff, keep the sum of the waits below the
journal circuit breaker's `call-timeout` (`pekko.persistence.journal-plugin-fallback.circuit-breaker`, 10 seconds by
default), or the circuit breaker fails the write first. The test that races updates
with persists against DynamoDB Local did not produce such a conflict, and that is not a proof that it cannot happen on
DynamoDB. Send updates for an aggregate through the aggregate, or when it is not persisting.

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
