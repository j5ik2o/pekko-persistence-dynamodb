# Customize

## Plugin dispatcher

Specify the dispatcher to be used inside the plugin. The default is "pekko.actor.default-dispatcher".

### Journal

```
j5ik2o.dynamo-db-journal {
  plugin-dispatcher = "pekko.actor.default-dispatcher"
}
```

### Snapshot

```
j5ik2o.dynamo-db-snapshot {
  plugin-dispatcher = "pekko.actor.default-dispatcher"
}
```

### State

```
j5ik2o.dynamo-db-state {
  plugin-dispatcher = "pekko.actor.default-dispatcher"
}
```

## Table names

Specify the table name. The default is the followings.

### Journal

```
j5ik2o.dynamo-db-journal {
  table-name = "Journal"
}
```

### Snapshot

```
j5ik2o.dynamo-db-snapshot {
  table-name = "Snapshot"
}
```

#### State

```
j5ik2o.dynamo-db-state {
  table-name = "State"
}
```

## Table column names

### Journal

```
j5ik2o.dynamo-db-journal {
  columns-def {
    partition-key-column-name = "pkey"
    sort-key-column-name = "skey"
    
    persistence-id-column-name = "persistence-id"
    sequence-nr-column-name = "sequence-nr"
    
    deleted-column-name = "deleted"
    message-column-name = "message"
    ordering-column-name = "ordering"
    tags-column-name = "tags"
  }
}
```

### Snapshot

```
j5ik2o.dynamo-db-snapshot {
  columns-def {
    partition-key-column-name = "pkey"
    sort-key-column-name = "skey"
    
    persistence-id-column-name = "persistence-id"
    sequence-nr-column-name = "sequence-nr"
    
    snapshot-column-name = "snapshot"
    created-column-name = "created"
    tags-column-name = "tags"
  }
}
```

### State

```
j5ik2o.dynamo-db-state {
  columns-def {
    partition-key-column-name = "pkey"
    sort-key-column-name = "skey"
    
    persistence-id-column-name = "persistence-id"
    revision-nr-column-name = "revision-nr"
    
    deleted-column-name = "deleted"
    payload-column-name = "payload"
    serializer-id-column-name = "serializer-id"
    serializer-manifest-column-name = "serializer-manifest"
    ordering-column-name = "ordering" 
    tags-column-name = "tags"
  }
}
```

## JournalHead

The journal plugin can write the journal rows and the `JournalHead` item of an aggregate in one transaction, so
that a read model update can consume the changes per aggregate in write order. See [JournalHead](journal-head.md)
for the table, the migration procedure and the limits.

```
j5ik2o.dynamo-db-journal {
  head {
    enabled = true
    table-name = "JournalHead"
  }
}
```

## Index names

### Journal

```
j5ik2o.dynamo-db-journal {
  get-journal-rows-index-name = "GetJournalRowsIndex"
}
```

### Snapshot

```
j5ik2o.dynamo-db-snapshot {
  get-snapshot-rows-index-name = "GetSnapshotRowsIndex"
}
```

### State

The state plugin has not the secondary index.

## Write-sharding

(persistence-id-separator)=

### Persistence id separator

The default resolvers of the journal and the snapshot store split the `persistenceId` with a separator.
`persistence-id-separator` sets it for each plugin. The default value is `-`, which differs from the `|` that Pekko
Typed puts between the entity type hint and the entity id. Do not change the separator on a table that already has
rows: the keys of those rows would change.

```
j5ik2o.dynamo-db-journal.persistence-id-separator = "-"
j5ik2o.dynamo-db-snapshot.persistence-id-separator = "-"
```

The default resolvers, `PartitionKeyResolver$PersistenceIdBased` and `SortKeyResolver$PersistenceIdWithSeqNr`,
give the keys of 1.3.0: they read the separator as a regular expression and keep only the first two parts of the split,
so two `persistenceId`s can share a key (see the note on writes below), and an id such as `order-`, or any id with the
separator `.`, cannot be written. These are the class names that the configuration examples of 1.3.0 named, so a
configuration that names them explicitly keeps the keys of 1.3.0 too. A later release is planned to switch the default
to the new key format; the {ref}`key migration tools <key-migration>` move the existing rows.

(key-format-change)=

#### Experimental: the new key format of `PlainTextPersistenceIdBased` and `PlainTextPersistenceIdWithSeqNr` (issue #1009)

`PartitionKeyResolver$PlainTextPersistenceIdBased` and `SortKeyResolver$PlainTextPersistenceIdWithSeqNr` give every `persistenceId` a key
of its own. They are experimental. Select them only after you read these cautions:

- The rows of 1.3.0 keep their old keys until you move them with the {ref}`key migration tools <key-migration>`.
- Selecting them changes the keys of the `persistenceId`s in the table below. The other keys stay the same, byte for byte.
- Until the old rows are migrated, the new key of a `persistenceId` can be the old key of another `persistenceId`, and a
  write to it fails with `KeyConflict` (see the note on writes below).
- They were measured only on DynamoDB Local, not on DynamoDB or with DAX.
- A later release is planned to make them the default.

```
j5ik2o.dynamo-db-journal {
  partition-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.journal.PartitionKeyResolver$PlainTextPersistenceIdBased"
  sort-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.journal.SortKeyResolver$PlainTextPersistenceIdWithSeqNr"
}
j5ik2o.dynamo-db-snapshot {
  partition-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.PartitionKeyResolver$PlainTextPersistenceIdBased"
  sort-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKeyResolver$PlainTextPersistenceIdWithSeqNr"
}
```

These resolvers match the separator as plain text, so any separator works, `|` included. They split the
`persistenceId` at the first separator. `prefix` is the text before it and goes into
`pkey`. `body` is the text after it, which can contain more separators, and goes into `skey`. A `persistenceId` without
the separator has no `prefix`, and its whole text is the `body`. Because `prefix`, the separator, and `body` together
are the whole `persistenceId`, and the shard and the sequence number have a fixed length, two `persistenceId`s never
get the same key.

When `${body}-${sequenceNumber}` would exceed 1,024 bytes in UTF-8, the largest sort key that DynamoDB accepts, `skey`
is `#h1#${sha256(body)}-${sequenceNumber}#` instead: the marker `#h1#`, the SHA-256 of `body` in 64 hexadecimal digits,
the sequence number, and a closing `#`. In the same way, when `${prefix}-${shard}` would exceed 2,048 bytes, the
largest partition key that DynamoDB accepts, `pkey` is `#h1#${sha256(prefix)}-${shard}#`. Whether a `persistenceId` gets
these forms depends on its `prefix` and `body` alone, so every event of the `persistenceId` gets the same form. A key
without a hash always ends with a digit, so a `persistenceId` whose text looks like a hashed key never gets the key of
another `persistenceId`. A `persistenceId` cannot be read back from a hashed key. The sequence number and the shard are
written with the digits of the default format locale, as in 1.3.0, and the limits are checked with the UTF-8 bytes that
they take.

Compared with the default resolvers, which keep the keys of 1.3.0, these resolvers give other keys to the
`persistenceId`s below. The keys of every other `persistenceId` stay the same, byte for byte.

| `persistence-id-separator` | `persistenceId`s whose key changes | What changes |
| :-- | :-- | :-- |
| Matches only itself as a regular expression (the default `-`, `::`, `_`, and `]` or `}` alone) | The separator two or more times, such as `shopping-cart-1` | `skey` only |
| The same | Ends with its only separator, such as `order-` | 1.3.0 failed with `ArrayIndexOutOfBoundsException`, so no rows exist |
| `\|` | Contains the separator, except `<one character>\|\|` | `pkey` and `skey` |
| `.`, `$`, `^`, `?`, `*`, `+`, `(`, `)`, `[`, `{`, `\` | Contains the separator | 1.3.0 failed with an exception, so no rows exist |
| Several characters that include a regular expression metacharacter, such as `-+` or `a.` | Contains the separator, and the split as a regular expression differs from the split at the first separator. With `-+` (one or more `-`), every such id: 1.3.0 kept the `+` in the body of `Order-+23`. With `a.`, which also matches `ab`, even an id with one separator, such as `Xa.1ab2` | `pkey`, `skey`, or both, or 1.3.0 failed with an exception |
| Any | `body` longer than 1,004 bytes | `skey` takes the hashed form. Ids with one separator or none could not be written by 1.3.0 |
| Any | `prefix` longer than 2,007 bytes | `pkey` takes the hashed form. With a separator that is not a regular expression metacharacter, 1.3.0 could not write such ids. With `\|`, 1.3.0 used only the first character as `prefix`, so these ids are among the ids above whose key changes |

The rows that 1.3.0 and earlier releases wrote keep their old keys until they are migrated. These resolvers still handle
them: a replay, the highest sequence number, and a snapshot load read every row of the `persistenceId`
through the `persistence-id` index, and a hard delete, a soft delete, an in-place update, a snapshot delete, and a
snapshot save also reach the row under the old key. A soft delete marks the event deleted in place under both keys, so it
works even when the new key holds a row of another `persistenceId`. Every operation on the old key is conditional only
on the `persistenceId` and the sequence number of the row, so a request that `write-backoff` sends again after a lost
response gives the same result. A snapshot save succeeds once the snapshot is written under the current key; when the
copy under the old key cannot be deleted, the failure is logged as a warning, and a load still returns the new snapshot. An operation on the old key changes the row only while it
is the event or the snapshot of the same `persistenceId` and sequence number, so the row of another `persistenceId` that
shares the old key is never changed. When a snapshot of the same sequence number exists under both keys, a load returns
the one under the current key, whichever was saved later. With the separator matched as plain text, a `persistenceId`
whose key changes always contains it, so it has no rows in the format of issue #831 (see the compatibility note below).

Until the old rows are moved with the {ref}`key migration tools <key-migration>`, the new key of a
`persistenceId` can be the old key of another `persistenceId`, for example `Order-` and `Order--23`, or with the
separator `|`, `A|B` and `AB|98`. Such a write fails as described in the next section, and the row of the other
`persistenceId` stays as it is.

The extra requests for the old keys, measured on DynamoDB Local with the AWS SDK v2 client:

| Operation | `persistenceId` whose key changes | `persistenceId` whose key does not change |
| :-- | :-- | :-- |
| Persist | none | none |
| Hard delete | one conditional `DeleteItem` per event | none |
| Soft delete | two conditional `UpdateItem` per event, one under each key, instead of the `PutItem` of 1.3.0 | none |
| In-place update, the row is under the current key | none | none |
| In-place update, the row is under the old key | one refused `UpdateItem` and two `GetItem`; with the JournalHead, one refused `TransactWriteItems` and three `GetItem` | none |
| Snapshot save | one conditional `DeleteItem` | none |
| Snapshot load | none; the query for the latest snapshot reads up to two items instead of one | none |
| Snapshot delete | none | none |

The extra requests continue after the migration, because a key alone does not tell whether an old row exists. After
every old row is migrated, set `legacy-keys-enabled = false` for both plugins to stop them. With `false`, rows under an
old key are no longer deleted or updated.

```
j5ik2o.dynamo-db-journal.legacy-keys-enabled = false
j5ik2o.dynamo-db-snapshot.legacy-keys-enabled = false
```

With the default resolvers, none of this runs: the legacy resolvers give the keys of 1.3.0, and no extra request is
sent. To go back to the keys of 1.3.0 after you selected the new resolvers, remove the two settings, but the rows
written under the new keys are then replayed and not deleted or updated, as with a rolled back release.

A rolled back release up to 1.3.0 replays the rows written under the new keys, but its deletes and in-place updates do
not reach them. A snapshot that it saves at a sequence number that already has a snapshot under the new key is stored
under the old key, and after the upgrade again, a load returns the snapshot under the new key, which is the older one.
While you run 1.3.0 after a rollback, do not save a snapshot again at a sequence number that already has one, or delete
that snapshot after the upgrade again.

If you read `pkey` or `skey` outside the plugin, for example from DynamoDB Streams or Kinesis Data Streams, note that the
keys change for the `persistenceId`s in the table above. With the separator `|`, the events of one `persistenceId` are in
two partitions until the old rows are migrated.

(key-migration)=

#### Moving the old rows: `JournalKeyMigration` and `SnapshotKeyMigration` (issue #1014)

```{admonition} Experimental
:class: warning

The key migration tools are experimental. They were measured on DynamoDB Local only, not on DynamoDB or with DAX, and
what `--verify` reports is what it observed when it ran (see {ref}`before you start the application
<key-migration-restart>`). Take a backup of the tables, for example with point-in-time recovery, before you run
`--execute`.
```

`JournalKeyMigration` (in `journal-v2`) and `SnapshotKeyMigration` (in `snapshot-v2`) move the rows that 1.3.0 wrote
to the keys of the resolvers that the configuration selects. You need them only after you select
`PlainTextPersistenceIdBased` and `PlainTextPersistenceIdWithSeqNr`, and only for the `persistenceId`s in the table
above. With the default resolvers, the keys do not change and there is nothing to move, except the rows of issue #831
(see below).

```{admonition} Stop the application before you move the rows
:class: warning

Run `--execute`, forward or with `--reverse`, only while every application that writes to or recovers from the journal
and snapshot tables is stopped. `--execute` refuses to run without `--writers-stopped`, which states that they are.

Each move changes the table in one transaction, but the `persistence-id` index, which a replay and the highest sequence
number read, is updated asynchronously. While a row moves, the index can show it twice or not at all. If an
application runs meanwhile:

- an actor that recovers can miss its latest event, take a lower highest sequence number, and then write that sequence
  number again, which replaces the moved event (with the JournalHead disabled);
- a replay can return an event twice or miss it;
- a snapshot load can miss the latest snapshot and return an older one, or none; an actor then recovers from the older
  state, and when the events after it were deleted on the snapshot, it does not notice;
- a hard delete or a snapshot delete can miss the row that moves between its requests, and the deleted event or
  snapshot stays;
- a node of 1.3.0 keeps writing under the old keys.

- a soft delete can miss the event: it deletes the events that a query of the index returns with `deleted = false`
  (`WriteJournalDaoImpl.deleteMessages`), so an event that the index does not show at that moment stays undeleted.

A move also reaches the consumers of DynamoDB Streams and of Kinesis Data Streams for DynamoDB, as an insert under the
new key and a remove under the old key: two changes to two items, with the same `persistenceId` and sequence number. A
merge sends a remove of the old copy and, when it changes `deleted`, an update of the kept row. DynamoDB Streams keeps
the order of the changes of one item only, and Kinesis Data Streams can deliver the changes out of order and more than
once, so a consumer can receive the remove before the insert. Stopping a consumer while the tools run does not help:
when it resumes, it reads the changes of the migration from where it stopped. A consumer that treats a remove as the
deletion of an event or a snapshot, or an insert as a new one, needs one of these:

- Identify the events by `persistenceId` and sequence number. Skip a remove whose `persistenceId` and sequence number
  the table still holds, and treat an insert of a `persistenceId` and sequence number that the consumer already
  processed as a duplicate.
- Rebuild the data of the consumer from the table after the migration, and apply to it only the changes that the
  application makes after it starts. The changes of the migration are older than the start of the application, which
  stayed stopped while the tools ran: skip the records whose `ApproximateCreationDateTime` is before the start.
  DynamoDB Streams rounds that time down to the second, so start the application at least a second after the last
  run of the tools (the steps below run `--verify` in between). This boundary also holds when Kinesis Data Streams
  delivers records out of order or twice; the position where the rebuild ended does not.

DynamoDB Streams keeps a record for 24 hours. A consumer of DynamoDB Streams that stays stopped longer cannot read the
changes it missed, those of the migration and any others, and has to rebuild its data from the table.

The tool cannot prevent these, so the migration is offline. `--execute` looks for some signs of another writer: a row
that left its key without reaching the target key, a row that changed after the tool read it, or a target key that
holds a row the scan did not see (a sequence number above the highest one of its `persistenceId`). It prints a warning
for each and exits with 1. A run without a warning does not show that the writers were stopped: most of the paths above
leave no sign, and an overwrite after a recovery leaves a table that `--verify` matches. These paths were reasoned from how DynamoDB updates an
index; DynamoDB Local updates its index at once, so they were not reproduced.

Each tool prints a warning at the start, after the line of the locale, for what it cannot check:

- `the tool cannot check that the writers are stopped`, with `--execute`;
- `the tool reads and writes without DAX`, when the `client-version` of the plugin is `v1-dax` or `v2-dax`, also when
  it comes from `j5ik2o.dynamo-db-defaults`;
- `the JournalHead is disabled` (the journal tool, when `head.enabled` is false, the default): an actor that recovers
  while the index lags can replace a moved event, so keep the application stopped until `--verify` matches twice, some
  minutes apart (see {ref}`before you start the application <key-migration-restart>`).
```

Each tool reads its plugin configuration (`j5ik2o.dynamo-db-journal` or `j5ik2o.dynamo-db-snapshot`): the table, the
column names, the `persistence-id` index, `shard-count`, `persistence-id-separator`, and the key resolvers. It refuses
key resolvers other than those of 1.3.0 and the PlainText ones, and a table whose keys are the `persistenceId` and the
sequence number. It connects with a plain AWS SDK v2 client built from `dynamo-db-client` (the endpoint, the region,
and the credentials), whatever `client-version` the plugin uses: with `v1`, `v1-dax`, or `v2-dax`, it builds the v2
client from the same settings and does not go through DAX. Nothing is written unless you pass `--execute`.

```
$ sbt -batch "journal-v2/runMain com.github.j5ik2o.pekko.persistence.dynamodb.journal.migration.JournalKeyMigration"
$ sbt -batch "snapshot-v2/runMain com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.migration.SnapshotKeyMigration"
```

| Option | Meaning |
| :-- | :-- |
| `--execute` | Move the rows. Without it, the tool reports what it would do (dry-run). |
| `--writers-stopped` | Required with `--execute`: every application that writes to or recovers from the tables is stopped. |
| `--verify` | Check the whole table and its `persistence-id` index, and move nothing (see below). |
| `--verify-timeout <seconds>` | With `--verify`: the most time the whole check takes, waits included (default 300, at least 1). |
| `--reverse` | Move the rows under the new keys back to the keys of 1.3.0, or, with `--verify`, check for the keys of 1.3.0. |
| `--persistence-id <id>` | Move only the rows of these `persistenceId`s. Repeat it for more. `--verify` does not take it. |
| `--max-rate <n>` | Move at most `n` rows a second. In a dry-run, check at most `n` target keys a second. Each page of the scan, and each read that compares two copies, also takes one of the `n` a second; set `--scan-page-size` to bound the items of a page. With `--verify`, send at most `n` requests a second. |
| `--parallelism <n>` | Move up to `n` rows at the same time (default 4). `--segments` does not change it. |
| `--segments <n>` | Scan the table in `n` segments (default 1). The dry-run and `--execute` read them in parallel; `--verify` reads them one after another. |
| `--scan-page-size <n>` | Read at most `n` items per scan request. |
| `--progress` | Print the progress to the standard error. |

`--execute` exits with 1 when it leaves a selected row unmoved, finds a selected row that cannot move (for example,
with `--reverse`, a row of `order-`, which 1.3.0 cannot hold), or sees a sign of another writer, and with 0 otherwise. A dry-run exits with 0. Neither
says that the table or the index is ready: they cover the selected rows only and do not read the index.

`--verify` is the check that the migration ends with. It takes no `--persistence-id`: it scans the whole table, places
every row, and scans the whole `persistence-id` index. It first checks the index itself: its partition key is the
`persistenceId` column (S) and its sort key the sequence number column (N), it is ACTIVE, and it projects what a replay
and a snapshot load read (`deleted`, `message`, `ordering`, and `tags` for the journal; `snapshot` and `created` for the
snapshot store). It matches, and exits with 0, only when the table holds no item that is not a row (see below), when
every row is under the key it belongs under (none to move, none blocked, none under an unknown key, none that cannot
move), and when, for every `persistenceId`, the index shows
the same rows as the table: the same keys and the same values of the attributes that the index projects, such as
`deleted`, and no row that the table no longer holds. The rows of each `persistenceId` are compared as their number and two sums of
64-bit parts of the SHA-256 of each row (its first 128 bits), over an encoding that gives each value its type and
length. Two different sets of rows match only when those sums coincide, which is unlikely but not ruled out. With
`--reverse`, it also does not match while two `persistenceId`s that the table holds share their keys of 1.3.0, at any
sequence numbers: after the rollback, a write of one of them collides with a row of the other. The index is updated
asynchronously, so the `persistenceId`s that differ are queried again until they match or `--verify-timeout` passes;
the timeout bounds the whole check, each request, the scans of the table and the index, and the waits for `--max-rate`
included; a check that the timeout stops, or that ends after it, does not match. A query of the index that fails (no permission, throttling) is reported
with its error, apart from an index that does not match yet. With the PlainText resolvers, a forward `--verify` that
matches also says that no row of its own table was left under an old key: the journal tool for
`j5ik2o.dynamo-db-journal.legacy-keys-enabled`, the snapshot tool for `j5ik2o.dynamo-db-snapshot.legacy-keys-enabled`.
Set each only after its own tool matched. Otherwise it lists what does not match and exits with 1.

The tools print the default format locale of their JVM when they start. The keys hold numbers in the digits of that
locale, as the plugin writes them, so run the tools in a JVM with the locale of the application that wrote the rows;
in a JVM whose digits differ (for example `ar-EG` and `en-US`), the tools list the rows as not movable.

The tests run the tools on rows that the published JARs of 1.0.59 and 1.3.0 wrote to DynamoDB Local: events, a soft
delete, and a snapshot of `persistenceId`s with no separator, one, and two, and for 1.3.0 also in a JVM with the locale
`ar-EG` (`ReleasedRowsMigrationSpec`, [#1027](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1027)). After
the forward move, this release with the PlainText resolvers, and after `--reverse`, the published 1.3.0, replay the
events, read the highest sequence number and the snapshot, and delete and write again. The tests do not cover
`persistenceId`s that share their keys of 1.3.0, the JournalHead, the tags, the hard delete, or the releases between
1.0.59 and 1.3.0.

The IAM policy of the tools needs, on the journal or the snapshot table: `dynamodb:Scan` and `dynamodb:GetItem` for a
dry-run; for `--execute` also `dynamodb:PutItem`, `dynamodb:DeleteItem`, `dynamodb:UpdateItem`, and
`dynamodb:ConditionCheckItem`, which the actions of `TransactWriteItems` need; and for `--verify`
`dynamodb:DescribeTable`, `dynamodb:Scan`, and `dynamodb:Query` on the table and its `persistence-id` index.

How a row moves: the tool scans the table with consistent reads and compares the stored key of each row with the key
that the resolvers give its `persistenceId` and sequence number. A row under the key of 1.3.0 moves to the new key.
For each row, the tool reads it again and sends one `TransactWriteItems`: a `Put` under the new key on the condition
that the key holds no row, and a `Delete` under the old key on the condition that the row still has every attribute
that was read. Both happen or neither does, so a run that stops leaves each row once, under its old key or its new
key, and the next run continues. When a response is lost, the tool reads the keys again and counts a row that has left
the old key as moved. Running the tool again after it finished moves nothing.

- When the new key already holds the same event (the same `persistenceId` and sequence number), and every other
  attribute of the two copies is equal, the tool keeps the row under the new key and deletes the old copy, in one
  transaction. The kept event is deleted when either copy was. For a snapshot, whose copies differ only in `created`,
  the copy with the larger `created` is kept, and `--execute` lists each removed copy. When any other attribute differs
  (for example the message after an in-place update of one copy), the tool merges nothing: the dry-run and
  `--execute` list both values, and `--execute` exits with 1. Choose the copy by hand. `--verify` then checks the kept
  row in the index.
- When the new key holds the old row of another `persistenceId` that moves too, the tool moves that row first.
- When the new key holds a row of another `persistenceId` that does not move, the row stays and is listed. With
  `--persistence-id`, a row of a `persistenceId` that is not selected is listed as outside the selection; it may move
  when you select its `persistenceId`. When two rows need the same new key, the one with the smaller stored key gets it,
  in the dry-run and in `--execute` alike, and the other is listed. The dry-run lists such rows as blocked. When that
  row does not reach the key in a run (DynamoDB rejects it, or the tool gives up after its attempts), the key stays
  reserved for it: the other row is not moved in that run and is listed, and a later run gives the key to the same
  row. A row that DynamoDB rejects is rejected again in the next run, so shrink or remove that row by hand first.
- Rows that hold each other's new keys (a cycle) are not moved: the tool lists them, and the rows that wait for them,
  as remaining. Move them by hand: move one row of the cycle to a free key, run `--execute` for the others, and then
  move that row from the free key to the key it belongs under, by hand too, because the tool lists a row under any
  other key as not movable. `--verify` passes only after that.
- A row under a key that is neither the key of 1.3.0, of issue #831, nor of the new format (for example a row written
  with another `shard-count`) is listed as not movable and not moved.
- An item that no plugin wrote, whose `persistence-id` is not a string or whose `sequence-nr` is not a whole number
  from 0 up (missing, of another type, a fraction, negative, or beyond a `Long`), is listed as an item that is not a row
  and is not moved. With `--persistence-id`, such items are counted but not listed. A row whose new key holds such an
  item is listed as blocked, in the dry-run and in `--execute` alike; move or remove the item by hand. `--verify` counts
  such items and checks the rows around them, but it does not match, and exits with 1, while the table holds one: it
  cannot check an item that is not a row. The key of such an item is printed in two cases only: by the dry-run and
  `--execute` without `--persistence-id`, which list every such item, and by `--verify`, which lists the first 20 in
  the order of its scan, apart from the 20 rows to move or not movable that it lists. Beyond those 20, `--verify` prints
  only their number; run the dry-run without `--persistence-id` for the full list. The index has `persistence-id` and
  `sequence-nr` as its keys, so it also holds such an item when both attributes are there (for example a fractional
  `sequence-nr`). `--verify` leaves an item that the table holds under the same key out of the rows of that
  `persistenceId` on both sides; an item that only the index still shows, after it was removed from the table, makes its
  `persistenceId` not match until the index drops it. Move or remove these items, and run `--verify` again.
- The dry-run does not read the items, so it cannot tell whether an item exceeds the 400 KB of DynamoDB under the key
  it moves to (the new key, or with `--reverse` the longer key of 1.3.0). `--execute` lists such a row as remaining with the error of DynamoDB, and leaves it under the old key.
- The JournalHead table is keyed by the `persistenceId` alone, so its items do not move and do not change.

The dry-run prints the rows to move per `persistenceId`, the blocked rows, the cycles, the copies to merge, the rows
that are not moved, and the requests and the capacity units that DynamoDB reported. With `--persistence-id`, it places
only the rows of those `persistenceId`s. It also lists the `persistenceId`s that shared their keys in 1.3.0 (the same
`prefix`, second part, and shard). For the journal, each comes with its highest sequence number and the sequence
numbers below it that have no row. A missing number in such a group can be an event that the other `persistenceId`
replaced up to 1.2.0, or one that a hard delete removed; the table cannot tell them apart, so check the missing numbers
against your own records. For the snapshot store, which does not save every sequence number, the tool lists the group
with the number of snapshots of each `persistenceId`. Events and snapshots that were replaced are not in the table, and
the tool cannot bring them back.

With a global table, run both tools against one region, and run `--verify` of both tools against the endpoint of every
region before you start the application in any region. A transaction is atomic in the region that runs it; DynamoDB
replicates its items one by one, so another region can show a move half applied while it replicates. `--verify` checks
the keys of the rows that a region holds, not that every row reached it: a region where the remove under the old key
arrived before the insert under the new key matches without that row. In each region, check also that `--verify`
reports the same number of rows and of `persistenceId`s as in the region that ran the tools. These checks do not see
every change that has not arrived yet: a merge removes the old copy and can set `deleted` on the kept row, and a region
where the remove arrived first holds the kept row with its old `deleted` while the numbers and `--verify` match.
Before you start the application, wait until the global table has replicated the changes of the migration to every
region (for example, watch the `ReplicationLatency` metric of each replica), and run the checks of each region again.
The tools cannot confirm the replication. A global table with multi-Region strong consistency does not take
`TransactWriteItems`, so the tools cannot move its rows.

The steps:

1. Upgrade every node to this release and select the PlainText resolvers on every node. While a node of 1.3.0 still
   runs, it writes under the old keys, misses the rows under the new keys when it deletes or updates (a hard delete
   leaves those events in the replay), and a `persistenceId` whose new key holds an old row of another `persistenceId`
   fails with `KeyConflict`. Replays read every row on both kinds of node.
2. Run both tools without `--execute`, and read the blocked rows, the cycles, and the shared-key groups.
3. Stop every application that writes to or recovers from the tables.
4. Run both tools with `--execute --writers-stopped`, with or without `--persistence-id`. Set `--max-rate` to what the
   table can take. If a run stops, run it again.
5. Run both tools with `--verify`. If one does not match, keep the application stopped: run `--execute` again for the
   rows to move, resolve the blocked rows, the cycles, and the rows that are not movable, or run `--verify` again later
   when only the index does not match yet.
6. When both match, follow {ref}`before you start the application <key-migration-restart>`, and start it. If you run
   a tool after that `--verify`, run `--verify` again: a table that a run changed and changed back can look the same
   while its index still lags.
7. Set `legacy-keys-enabled = false` only after a forward `--verify` of both tools matched. A blocked row or a row that is
   not movable stays under an old key, and with `false` deletes and updates do not reach it.

To go back to 1.3.0: stop the application, run both tools with `--reverse --execute --writers-stopped`, and then with
`--reverse --verify`. Only when both match, follow {ref}`before you start the application <key-migration-restart>`,
switch the nodes to the resolvers of 1.3.0 (or to 1.3.0 itself), and start them. A row cannot go back when 1.3.0
cannot build a key for its `persistenceId` (such as `order-`, or `.` as the separator), and two `persistenceId`s that
share their keys of 1.3.0 collide after the rollback even at different sequence numbers; then `--reverse --verify` does
not match. In that case stay with the new key format, and keep the application stopped: run both tools with the
forward `--execute --writers-stopped` and then the forward `--verify` until both match, and only then start the nodes
with the PlainText resolvers and `legacy-keys-enabled = true` (the default).

(key-migration-restart)=

##### Before you start the application

`--verify` reports what it observed while it ran. The `persistence-id` index is eventually consistent, so a read that
the application sends after the check is not proven to see the same rows, and the tools cannot prove that it will.
What they leave open, and what to do:

- An actor that recovers reads its highest sequence number and its events through the index. With the JournalHead
  disabled, a read that misses a moved event takes a lower highest sequence number, and the next write replaces the
  moved event under the same key. With the JournalHead enabled, the highest sequence number is the larger of the one
  that the index gives and the one of the JournalHead item, which is read with strong consistency
  (`JournalAggregateStore`), and the write of the head is refused for a sequence
  number that it already holds (`sequence-nr < :first`), so this replacement does not happen; replays and snapshot loads
  still read the index. Enabling the JournalHead needs its own table and migration ({doc}`journal-head`), so it is not a
  step of this migration. A guard on the write side for the disabled JournalHead is tracked in [#1026](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1026).
- Leave time between the end of the moves and the start, and run `--verify` of both tools a second time, some minutes
  after the first, before you start. Two checks that match, apart in time, make an index that still lags less likely;
  they do not prove the absence of a lag. How long DynamoDB takes to update the index was not measured.
- With DAX, a query or an item read through DAX can return what was cached before the migration. Drop both caches
  before you start: restart the DAX cluster, or wait for both the item cache TTL and the query cache TTL to pass.


The rows of issue #831 (`skey` is the sequence number alone in 19 digits, `"%019d"`, written by releases up to 1.0.59
for a `persistenceId` without the separator) move to `${persistenceId}-${sequenceNumber}` in both directions, the key that both formats give
such a `persistenceId`. With the resolvers of 1.3.0 selected, the tools move only these rows. After the move, deletes
and in-place updates reach them.

Cost and time, measured on DynamoDB Local (Docker on one machine, `--parallelism 4`, events of about 300 bytes; the
numbers vary with the load of the machine):

| Rows in the table (rows to move) | Dry-run | `--execute` | Dry-run after | Requests of `--execute` | Capacity units of `--execute` |
| :-- | :-- | :-- | :-- | :-- | :-- |
| 10,000 (5,000) | 2.0 s | 45.6 s | 0.2 s | 4 `Scan`, 5,000 `GetItem`, 5,000 `TransactWriteItems` | 833 read by the scan, 5,000 read, 20,000 written |
| 100,000 (50,000) | 18.1 s | 421 s | 8.0 s | 33 `Scan`, 50,000 `GetItem`, 50,000 `TransactWriteItems` | 8,371 read by the scan, 50,000 read, 200,000 written |
| 100,000 (50,000), `--parallelism 16` | 18.4 s | 950 s | 7.9 s | the same | the same |

The dry-run reads the table once and checks each target key with one `GetItem`. `--verify` reads the whole table again (with
consistent reads, so one read capacity unit per 4 KB) and the whole index once, and queries the index again only for
the `persistenceId`s that differ; on DynamoDB Local it took 1.0 s for 10,000 rows of 1,000 `persistenceId`s (835 read capacity units for the table and 418 for the index). DynamoDB Local was the
limit: 16 moves at a time were slower than 4. On DynamoDB, the time depends on the capacity of the table and on `--max-rate`.

On DynamoDB, a scan with consistent reads consumes one read capacity unit per 4 KB read, so it reads the whole table
once. Each move consumes one consistent read of the row and a transactional write of two items, which DynamoDB counts
twice: 4 write capacity units for a row up to 1 KB, which is also what DynamoDB Local reported. The time on
DynamoDB, with DAX, and under load was not measured. A move goes through DynamoDB Streams and Kinesis Data Streams as an
insert and a remove.

```{admonition} DAX (not measured)
:class: warning

DAX was not measured with this change. A delete or an update under an old key goes through DAX like any other write,
but a read through the item cache or the query cache can return a row or a result that was cached before another client
changed the table, until the cache entry expires. The migration tools do not go through DAX; before you start the
application after a migration, drop both caches (see {ref}`before you start the application <key-migration-restart>`).
```

```{admonition} Writes never replace a row of another persistence id (issue #1009)
:class: warning

Up to 1.2.0, a later write replaced the row of another `persistenceId` that had the same key when the JournalHead was
disabled, and the replaced events were lost. Snapshots were replaced whether the JournalHead was enabled or not. From
1.3.0, a write never replaces a row of another `persistenceId`:

- A journal write fails with `JournalRowStoreException.KeyConflict`, which names both `persistenceId`s. Pekko calls
  `onPersistFailure`, and the actor stops. The events of the other `persistenceId` stay as they are. The other new
  events of the same `persistAll` are deleted again, so a restarted actor does not replay part of the write.
- A snapshot save fails with `SnapshotStoreException.KeyConflict`, and the actor receives `SaveSnapshotFailure`.
- An in-place update is refused with `JournalHeadRowUpdateRejectedException`, and a delete leaves the row of the other
  `persistenceId`.
- With the JournalHead enabled, the journal write keeps failing with `DuplicateJournalEventsException`, as before.

With the default resolvers, which keep the keys of 1.3.0, a `persistenceId` that fails this way cannot write the
sequence numbers whose keys the other `persistenceId` holds, and retrying does not help. With the experimental
`PlainTextPersistenceIdBased` and `PlainTextPersistenceIdWithSeqNr`, this happens only when a new key holds an old row of another
`persistenceId`, and the migration of the old rows will resolve it.

Events and snapshots that releases up to 1.2.0 already replaced cannot be restored. A `persistenceId` lost events when
its replay misses sequence numbers that it did not delete. To find the `persistenceId`s that shared a key in 1.3.0,
group them by the old `prefix` (the first element of the split), the old `body` (the second element), and the shard
number; a group with two or more `persistenceId`s shares keys.

To make the writes conditional, the journal and the snapshot store write and delete each row with its own conditional
`PutItem` or `DeleteItem`, up to `dynamo-db-client.batch-write-item-limit` (25 by default) at a time, instead of
`BatchWriteItem`. The consumed write capacity does not change. The number of write requests becomes one per event
instead of one per 25 events.

The IAM policy must allow `dynamodb:PutItem`, `dynamodb:DeleteItem`, and `dynamodb:GetItem` on the journal and
snapshot tables; `dynamodb:BatchWriteItem` is no longer used.
```

### Journal

```
j5ik2o.dynamo-db-journal {
  shard-count = 64
  partition-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.journal.PartitionKeyResolver$PersistenceIdBased"
  sort-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.journal.SortKeyResolver$PersistenceIdWithSeqNr"
}
```

#### Shard Count

`shard-count` is the logical number of the shards. the default value is `64`.

#### Partition Key Resolver Class Name

`partition-key-resolver-class-name` specifies the implementation class that generates `pkey` from `PersistenceId` and `Sequence Number`.
The following two implementations are available for built-in use. 

- `com.github.j5ik2o.pekko.persistence.dynamodb.journal.PartitionKeyResolver$SequenceNumberBased`
  - `pkey = ${persistenceId}-${sequenceNumber % shardCount}`
  - The same `PersistenceId` will be assigned to a different shard if the `Sequence Number` is different. This is a write-specific sharding.
- `com.github.j5ik2o.pekko.persistence.dynamodb.journal.PartitionKeyResolver$PlainTextPersistenceIdBased` (experimental, see [the new key format](#key-format-change))
  - `pkey = ${persistenceId.prefix}-${md5(persistenceId.reverse) % shardCount}`
  - e.g. `counter-875e6ce0425e4d2b8203f3b44b9b531a`, `persistenceId.prefix` is `counter`, the text before the first separator.
  - If you choose this option, the same shard will be assigned if the `PersistenceId` is the same, so be sure to select this option if you are using DynamoDB Streams or KDS for DynamoDB.
- `com.github.j5ik2o.pekko.persistence.dynamodb.journal.PartitionKeyResolver$PersistenceIdBased` (Default)
  - `pkey = ${persistenceId.prefix}-${md5(persistenceId.reverse) % shardCount}`, the key of 1.3.0. `persistenceId.prefix` is the first element of a split that reads the separator as a regular expression, e.g. `counter` for `counter-875e6ce0425e4d2b8203f3b44b9b531a`. See [Persistence id separator](#persistence-id-separator).

#### Sort Key Resolver Class Name
 
`sort-key-resolver-class-name` specifies the implementation class that generates `skey` from `PersistenceId` and `Sequence Number`.
The following two implementations are available for built-in use. 

- `com.github.j5ik2o.pekko.persistence.dynamodb.journal.SortKeyResolver$SeqNr`
  - `skey = $sequenceNumber`
  - An implementation in which `skey` is the `Sequence Number`.
- `com.github.j5ik2o.pekko.persistence.dynamodb.journal.SortKeyResolver$PlainTextPersistenceIdWithSeqNr` (experimental, see [the new key format](#key-format-change))
  - `skey = ${persistenceId.body}-${sequenceNumber}`
  - e.g. `875e6ce0425e4d2b8203f3b44b9b531a`, `persistenceId.body` is `875e6ce0425e4d2b8203f3b44b9b531a`.
  - Use `persistenceId.body` as the prefix since `shard-count` may cause multiple `persistenceId`s events to be stored in the same shard.
  - If the `persistenceId` does not contain the separator, the full `persistenceId` is used as the prefix: `${persistenceId}-${sequenceNumber}`.
  - `persistenceId.body` is all the text after the first separator. When the key would exceed 1,024 bytes, `body` is replaced with its hash. See [Persistence id separator](#persistence-id-separator).
- `com.github.j5ik2o.pekko.persistence.dynamodb.journal.SortKeyResolver$PersistenceIdWithSeqNr` (Default)
  - `skey = ${persistenceId.body}-${sequenceNumber}`, the key of 1.3.0, or `${persistenceId}-${sequenceNumber}` when the `persistenceId` does not contain the separator. `persistenceId.body` is only the second element of a split that reads the separator as a regular expression, so two `persistenceId`s can get the same key. See [Persistence id separator](#persistence-id-separator).

#### Configure your own implementation

If you need, set up your own implementation.

```
j5ik2o.dynamo-db-journal {
  partition-key-resolver-class-name = "your class name(fqcn)"
  sort-key-resolver-class-name = "your class name(fqcn)"
}
```

### Snapshot

```
j5ik2o.dynamo-db-snapshot {
  shard-count = 64
  partition-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.PartitionKeyResolver$PersistenceIdBased"
  sort-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKeyResolver$PersistenceIdWithSeqNr"
}
```

#### Shard Count

`shard-count` is the logical number of shards. default value is `64`.

#### Partition Key Resolver Class Name

`partition-key-resolver-class-name` specifies the implementation class that generates `pkey` from `PersistenceId` and `Sequence Number`.
The following two implementations are available for built-in use. 

- `com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.PartitionKeyResolver$SequenceNumberBased`
  - `pkey = ${persistenceId}-${sequenceNumber % shardCount}`
  - The same `PersistenceId` will be assigned to a different shard if the `Sequence Number` is different. This is a write-specific sharding.
- `com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.PartitionKeyResolver$PlainTextPersistenceIdBased` (experimental, see [the new key format](#key-format-change))
  - `pkey = ${persistenceId.prefix}-${md5(persistenceId.reverse) % shardCount}`
  - e.g. `counter-875e6ce0425e4d2b8203f3b44b9b531a`, `persistenceId.prefix` is `counter`, the text before the first separator.
  - If you choose this option, the same shard will be assigned if the `PersistenceId` is the same, so be sure to select this option if you are using DynamoDB Streams or KDS for DynamoDB.
- `com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.PartitionKeyResolver$PersistenceIdBased` (Default)
  - `pkey = ${persistenceId.prefix}-${md5(persistenceId.reverse) % shardCount}`, the key of 1.3.0. `persistenceId.prefix` is the first element of a split that reads the separator as a regular expression, e.g. `counter` for `counter-875e6ce0425e4d2b8203f3b44b9b531a`. See [Persistence id separator](#persistence-id-separator).

#### Sort Key Resolver Class Name

`sort-key-resolver-class-name` specifies the implementation class that generates `skey` from `PersistenceId` and `Sequence Number`.
The following two implementations are available for built-in use.

- `com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKeyResolver$SeqNr`
  - `skey = $sequenceNumber`
  - An implementation in which `skey` is the `Sequence Number`.
- `com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKeyResolver$PlainTextPersistenceIdWithSeqNr` (experimental, see [the new key format](#key-format-change))
  - `skey = ${persistenceId.body}-${sequenceNumber}`
  - e.g. `875e6ce0425e4d2b8203f3b44b9b531a`, `persistenceId.body` is `875e6ce0425e4d2b8203f3b44b9b531a`.
  - Use `persistenceId.body` as the prefix since `shard-count` may cause multiple `persistenceId`s events to be stored in the same shard.
  - If the `persistenceId` does not contain the separator, the full `persistenceId` is used as the prefix: `${persistenceId}-${sequenceNumber}`.
  - `persistenceId.body` is all the text after the first separator. When the key would exceed 1,024 bytes, `body` is replaced with its hash. See [Persistence id separator](#persistence-id-separator).
- `com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKeyResolver$PersistenceIdWithSeqNr` (Default)
  - `skey = ${persistenceId.body}-${sequenceNumber}`, the key of 1.3.0, or `${persistenceId}-${sequenceNumber}` when the `persistenceId` does not contain the separator. `persistenceId.body` is only the second element of a split that reads the separator as a regular expression, so two `persistenceId`s can get the same key. See [Persistence id separator](#persistence-id-separator).

```{admonition} Compatibility note
Older versions stored `PersistenceIdWithSeqNr` sort keys as only `${sequenceNumber}` when the `persistenceId` did not contain the separator. New writes use `${persistenceId}-${sequenceNumber}` to avoid overwriting records that share the same `(pkey, sequenceNumber)`. Existing rows that were written with the old sort-key format can still be read through the `persistence-id` / `sequence-nr` secondary index, but delete and update operations calculate the table primary key from the current resolver. If you already have rows with the old format, migrate those rows to the new sort-key format before relying on delete or update for separator-less persistence ids.
```

#### Configure your own implementation
    
If you need, set up your own implementation.

```
j5ik2o.dynamo-db-snapshot {
  partition-key-resolver-class-name = "your class name(fqcn)"
  sort-key-resolver-class-name = "your class name(fqcn)"
}
```

```{admonition} Persistent data images

| persistenceId                            | sequence-nr | pkey(SequenceNumberBased)                  | skey(SeqNr)         |
| :--------------------------------------- | ----------: | :----------------------------------------- | :------------------ |
| counter-875e6ce0425e4d2b8203f3b44b9b531a |           1 | counter-875e6ce0425e4d2b8203f3b44b9b531a-1 | 0000000000000000001 |
| counter-875e6ce0425e4d2b8203f3b44b9b531a |           2 | counter-875e6ce0425e4d2b8203f3b44b9b531a-2 | 0000000000000000002 |
| counter-875e6ce0425e4d2b8203f3b44b9b531a |           3 | counter-875e6ce0425e4d2b8203f3b44b9b531a-3 | 0000000000000000003 |
| counter-875e6ce0425e4d2b8203f3b44b9b531a |           4 | counter-875e6ce0425e4d2b8203f3b44b9b531a-4 | 0000000000000000004 |
| counter-875e6ce0425e4d2b8203f3b44b9b531a |           5 | counter-875e6ce0425e4d2b8203f3b44b9b531a-5 | 0000000000000000005 |

#### 

| persistenceId                            | sequence-nr | pkey(PersistenceIdBased)                         | skey(PersistenceIdWithSeqNr)                         |
| :--------------------------------------- | ----------: | :----------------------------------------------- | :--------------------------------------------------- |
| counter-875e6ce0425e4d2b8203f3b44b9b531a |           1 | counter-0000000000000000000000000000000000000803 | 875e6ce0425e4d2b8203f3b44b9b531a-0000000000000000001 |
| counter-875e6ce0425e4d2b8203f3b44b9b531a |           2 | counter-0000000000000000000000000000000000000803 | 875e6ce0425e4d2b8203f3b44b9b531a-0000000000000000002 |
| counter-875e6ce0425e4d2b8203f3b44b9b531a |           3 | counter-0000000000000000000000000000000000000803 | 875e6ce0425e4d2b8203f3b44b9b531a-0000000000000000003 |
| counter-875e6ce0425e4d2b8203f3b44b9b531a |           4 | counter-0000000000000000000000000000000000000803 | 875e6ce0425e4d2b8203f3b44b9b531a-0000000000000000004 |
| counter-875e6ce0425e4d2b8203f3b44b9b531a |           5 | counter-0000000000000000000000000000000000000803 | 875e6ce0425e4d2b8203f3b44b9b531a-0000000000000000005 |
| counter-a8d46579bc2f4caf8c3b8dc2db984227 |           1 | counter-0000000000000000000000000000000000000803 | a8d46579bc2f4caf8c3b8dc2db984227-0000000000000000001 |
| counter-a8d46579bc2f4caf8c3b8dc2db984227 |           2 | counter-0000000000000000000000000000000000000803 | a8d46579bc2f4caf8c3b8dc2db984227-0000000000000000002 |
| counter-a8d46579bc2f4caf8c3b8dc2db984227 |           3 | counter-0000000000000000000000000000000000000803 | a8d46579bc2f4caf8c3b8dc2db984227-0000000000000000003 |
| counter-a8d46579bc2f4caf8c3b8dc2db984227 |           4 | counter-0000000000000000000000000000000000000803 | a8d46579bc2f4caf8c3b8dc2db984227-0000000000000000004 |
| counter-a8d46579bc2f4caf8c3b8dc2db984227 |           5 | counter-0000000000000000000000000000000000000803 | a8d46579bc2f4caf8c3b8dc2db984227-0000000000000000005 |

```

### State

The state plugin has a sort key. The `skey` value comes from the `SortKeyResolver`. The default resolver uses the
persistence id.

```
j5ik2o.dynamo-db-state {
  shard-count = 64
  partition-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.state.PartitionKeyResolver$PersistenceIdBased"
  sort-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.state.SortKeyResolver$PersistenceIdBased"
}
```

#### Shard Count

`shard-count` is the logical number of shards. default value is `64`.

#### Partition Key Resolver Class Name

There are one standard implementations as follows.

- `com.github.j5ik2o.pekko.persistence.dynamodb.journal.PartitionKeyResolver.PersistenceIdBased`
  - This is the same implementation as Journal or Snapshot.

#### Sort Key Resolver Class Name

`sort-key-resolver-class-name` specifies the implementation class that generates `skey` from `PersistenceId`.
The following implementation is available for built-in use.

- `com.github.j5ik2o.pekko.persistence.dynamodb.state.SortKeyResolver$PersistenceIdBased`
  - `skey = ${persistenceId}`

#### Configure your own implementation

If you need, set up your own implementation.

```
j5ik2o.dynamo-db-state {
  partition-key-resolver-class-name = "your class name(fqcn)"
  sort-key-resolver-class-name = "your class name(fqcn)"
}
```

## AWS Client

### AWS Client Factory

The following factories are used to create AWS Clients inside the plugins.

| key                                                           | version | async/sync | default value                                                                     |
|:--------------------------------------------------------------|:-------:|:----------:|:----------------------------------------------------------------------------------|
| j5ik2o.dynamo-db-?????.v2-async-client-factory-class-name     |   v2    |   async    | com.github.j5ik2o.pekko.persistence.dynamodb.utils.V2AsyncClientFactory$Default    |
| j5ik2o.dynamo-db-?????.v2-sync-client-factory-class-name      |   v2    |    sync    | com.github.j5ik2o.pekko.persistence.dynamodb.utils.V2SyncClientFactory$Default     |
| j5ik2o.dynamo-db-?????.v2-dax-async-client-factory-class-name | v2-dax  |   async    | com.github.j5ik2o.pekko.persistence.dynamodb.utils.V2DaxAsyncClientFactory$Default |
| j5ik2o.dynamo-db-?????.v2-dax-sync-client-factory-class-name  | v2-dax  |    sync    | com.github.j5ik2o.pekko.persistence.dynamodb.utils.V2DaxSyncClientFactory$Default  |
| j5ik2o.dynamo-db-?????.v1-async-client-factory-class-name     |   v1    |   async    | com.github.j5ik2o.pekko.persistence.dynamodb.utils.V1AsyncClientFactory$Default    |
| j5ik2o.dynamo-db-?????.v1-sync-client-factory-class-name      |   v1    |    sync    | com.github.j5ik2o.pekko.persistence.dynamodb.utils.V1SyncClientFactory$Default     |
| j5ik2o.dynamo-db-?????.v1-dax-async-client-factory-class-name | v1-dax  |   async    | com.github.j5ik2o.pekko.persistence.dynamodb.utils.V1DaxAsyncClientFactory$Default |
| j5ik2o.dynamo-db-?????.v1-dax-sync-client-factory-class-name  | v1-dax  |    sync    | com.github.j5ik2o.pekko.persistence.dynamodb.utils.V1DaxSyncClientFactory$Default  |

The AWS Clients generation process is automatic if you specify the set values, but if you wish to apply your own generation process, please do the following.

This plugin is useful for specifying the configuration items to the client that are not supported by this plugin, or for dynamically specifying configuration items within the program.

```scala V2 AWS Client Factory
package example

import org.apache.pekko.actor.DynamicAccess
import com.github.j5ik2o.pekko.persistence.dynamodb.config.PluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.V2AsyncClientFactory
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

class MyV2AsyncClientFactory extends V2AsyncClientFactory {
  override def create(
      dynamicAccess: DynamicAccess,
      pluginConfig: PluginConfig
  ): DynamoDbAsyncClient = {
    // Default Implementation is the following.
    // V2ClientUtils.createV2AsyncClient(dynamicAccess, pluginConfig)
    
    // e.g. Custom AWS Client Initialization
    V2ClientBuilderUtils
      .setupAsync(
        dynamicAccess,
        pluginConfig
      )
      .defaultsMode(DefaultsMode.AUTO) // This is not a configuration item in the plugin.
      .build()
  }
}
```

```
j5ik2o.dynamo-db-journal.v2-async-client-factory-class-name = "example.MyV2AsyncClientFactory"
```

### AWS Client Config

The configurations for AWS Clients is specified in the following sections(`j5ik2o.dynamodb-db-?????.dynamo-db-client`).

```
j5ik2o.dynamo-db-journal {
  dynamo-db-client {
    access-key-id = "x"
    secret-access-key = "x"
    endpoint = "http://localhost:8000/"
  }
}
```

- `access-key-id` is Access Key ID
- `secret-access-key` is Secret Access Key
- `endpoint` is an endpoint to DynamoDB

### AWS CredentialsProvider

You can specify your own `AwsCredentialsProvider`.

`aws-credentials-provider-provider-class-name` can specify the provider that generate `AwsCredentialsProvider`.
`aws-credentials-provider-class-name` can specify the implementation class name of `AwsCredentialsProvider`.

```
j5ik2o.dynamo-db-????? {
  dynamo-db-client {
    v2 {
      aws-credentials-provider-provider-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.client.v2.AwsCredentialsProviderProvider$Default"
      aws-credentials-provider-class-name = "software.amazon.awssdk.auth.credentials.AwsCredentialsProvider"
    }
  }
}
```

#### Custom AWSCredentialsProviderProvider by using `aws-credentials-provider-provider-class-name`

```scala
package example

import akka.actor.DynamicAccess
import com.github.j5ik2o.pekko.persistence.dynamodb.client.v2.AwsCredentialsProviderProvider
import com.github.j5ik2o.pekko.persistence.dynamodb.config.PluginConfig
import software.amazon.awssdk.auth.credentials.{ AwsCredentialsProvider, WebIdentityTokenFileCredentialsProvider }

final class MyAwsCredentialsProviderProvider(@unused dynamicAccess: DynamicAccess, @unused pluginConfig: PluginConfig)
  extends AwsCredentialsProviderProvider {

  override def create: Option[AwsCredentialsProvider] = {
    if (sys.env.contains("AWS_ROLE_ARN"))
      Some(WebIdentityTokenFileCredentialsProvider.create())
    else
      None
  }

}
```

```
j5ik2o.dynamo-db-????? {
  v2 {
    aws-credentials-provider-provider-class-name = "exampe.MyAwsCredentialsProviderProvider"
  }
}
```

#### Custom AWSCredentialsProvider by using `aws-credentials-provider-class-name`

```scala
package example

import org.apache.pekko.actor.DynamicAccess
import software.amazon.awssdk.auth.credentials.{ AwsCredentialsProvider, AwsCredentials }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.PluginConfig

class MyAwsCredentialsProvider(dynamicAccess: DynamicAccess, pluginConfig: PluginConfig) extends AwsCredentialsProvider {
  override def resolveCredentials(): AwsCredentials = {
    // Custom AWS Credentials
  }
}
```

Specify your custom implementation class to `aws-credentials-provider-class-name`.

```
j5ik2o.dynamo-db-????? {
  v2 {
    aws-credentials-provider-class-name = "exampe.MyAwsCredentialsProvider"
  }
}
```

### Plugin MetricsReporter

The plugin calls `MetricsReporter` through the configured `metrics-reporter-provider-class-name` and `metrics-reporter-class-name`.
Journal write callbacks describe two different results.

- `afterJournalAsyncWriteMessages` means the outer `asyncWriteMessages` Future completed successfully. It does not mean every event persisted. The returned sequence can contain a serialization `Failure` for an individual `AtomicWrite`.
- `errorJournalSerializeJournal` reports a serialization failure. That `AtomicWrite` is rejected without reaching the row driver. The outer write Future can still succeed and call `afterJournalAsyncWriteMessages`. Pekko handles the rejection through `onPersistRejected`.
- `errorJournalAsyncWriteMessages` reports failure of the outer write Future. The plugin's preflight limits use this path. The next section lists them. The plugin checks all serialized writes before sending the first one. A limit violation sends none of them. Pekko handles the failure through `onPersistFailure` and stops the persistent actor.

Monitor serialization errors as well as outer write errors. Completion callbacks run asynchronously, so completion of the returned Future does not guarantee that the reporter has received its callback yet.
These plugin callbacks also cover failures before an SDK request exists. The SDK `MetricPublisher` below observes SDK requests.

(metrics-write-limits)=
#### Write limit failures

Before the plugin sends anything, it checks a write against the limits in the table below, and only those. A write that exceeds one of them fails without being sent. The plugin calls `errorJournalAsyncWriteMessages` once for the whole `asyncWriteMessages` call, and its `ex` argument is the exception of the first violation it finds. `errorJournalSerializeJournal` is not called for a limit violation, and `afterJournalAsyncWriteMessages` is not called for that write.

| `head.enabled` | Limit                                         | `ex` type                                  |
|:---------------|:----------------------------------------------|:-------------------------------------------|
| `true`         | more than 99 events in one write              | `JournalHeadTooManyEventsException`        |
| `true`         | the `JournalHead` item would exceed 400 KB    | `JournalHeadItemTooLargeException`         |
| `true`         | a journal row would exceed 400 KB             | `JournalHeadJournalRowTooLargeException`   |
| `true`         | the transaction would exceed 4 MB             | `JournalHeadTransactionTooLargeException`  |
| `false`        | a journal row would exceed 400 KB             | `JournalHeadJournalRowTooLargeException`   |

Each type is a subclass of `JournalHeadWriteRejectedException`, so a reporter can count the limits apart by the type of `ex`. Match on `JournalHeadWriteRejectedException` to count all of them, and on a subclass to count one limit. Each exception carries the measured value and the limit, for example `itemSizeBytes` and `limitBytes`. A serialization failure, a DynamoDB error, and a failed JournalHead condition use other exception types. A limit violation is a bug in the design of the events. Pekko calls `onPersistFailure` and stops the persistent actor. After you fix the code and restart the actor, the next write continues from the last written sequence number. See {ref}`journal-head-limits` for the limits themselves.

### MetricPublisher 

Using the MetricPublisher in the V2 sdk, metrics at the SDK level can be sent to Datadog and Newrelic via Kamon.

```scala
package example

import org.apache.pekko.actor.DynamicAccess
import com.github.j5ik2o.pekko.persistence.dynamodb.config.PluginConfig
import kamon.Kamon
import kamon.metric.{ Counter, Histogram, MeasurementUnit }
import software.amazon.awssdk.core.metrics.CoreMetric
import software.amazon.awssdk.metrics.{ MetricCollection, MetricPublisher }

import scala.annotation.unused
import scala.jdk.StreamConverters.StreamHasToScala

class MyMetricPublisher(
                           @unused dynamicAccess: DynamicAccess,
                           pluginConfig: PluginConfig
                         ) extends MetricPublisher {

  override def publish(metricCollection: MetricCollection): Unit = {
    val metricsMap = metricCollection.stream().toScala(Vector).map { mr => (mr.metric().name(), mr) }.toMap
    metricsMap(CoreMetric.OPERATION_NAME.name()).value() match {
      case "PutItem" =>
        if (metricsMap(CoreMetric.API_CALL_SUCCESSFUL.name()).value().asInstanceOf[java.lang.Boolean]) {
          val apiCallDuration = metricsMap(CoreMetric.API_CALL_DURATION.name()).value().asInstanceOf[java.time.Duration]
          val credentialsFetchDuration =
            metricsMap(CoreMetric.CREDENTIALS_FETCH_DURATION.name()).value().asInstanceOf[java.time.Duration]
          val marshallingDuration =
            metricsMap(CoreMetric.MARSHALLING_DURATION.name()).value().asInstanceOf[java.time.Duration]
          val retryCount = metricsMap(CoreMetric.RETRY_COUNT.name()).value().asInstanceOf[java.lang.Integer]
          putItemApiCallDurationHistogram.record(apiCallDuration.toMillis)
          putItemCredentialsFetchDurationHistogram.record(credentialsFetchDuration.toMillis)
          putItemMarshallingDurationHistogram.record(marshallingDuration.toMillis)
          putItemRetryCounter.increment(retryCount.toLong)
        } else {
          putItemErrorCounter.increment()
        }
      case _ =>
    }

  }

  override def close(): Unit = {}

  private val prefix                            = "app"
  private val apiCallDurationHistogram          = histogram(s"$prefix.aws.api-call-duration")
  private val credentialsFetchDurationHistogram = histogram(s"$prefix.aws.credentials-fetch-duration")
  private val marshallingDurationHistogram      = histogram(s"$prefix.aws.marshalling-duration")
  private val retryCounter                      = counter(s"$prefix.aws.retry-count")
  private val errorCounter                      = counter(s"$prefix.error-count")

  private val putItemApiCallDurationHistogram = apiCallDurationHistogram.withTag("Operation", "PutItem")
  private val putItemCredentialsFetchDurationHistogram = credentialsFetchDurationHistogram
    .withTag("Operation", "PutItem")
  private val putItemMarshallingDurationHistogram = marshallingDurationHistogram
    .withTag("Operation", "PutItem")
  private val putItemRetryCounter = retryCounter
    .withTag("Operation", "PutItem")
  private val putItemErrorCounter = errorCounter
    .withTag("Operation", "PutItem")

  private def histogram(metricName: String): Histogram =
    Kamon
      .histogram(metricName, MeasurementUnit.time.milliseconds)
      .withTag("TableName", pluginConfig.tableName)
      .withTag("sdk", s"java-${pluginConfig.clientConfig.clientVersion}")

  private def counter(metricName: String): Counter =
    Kamon
      .counter(metricName)
      .withTag("TableName", pluginConfig.tableName)
      .withTag("sdk", s"java-${pluginConfig.clientConfig.clientVersion}")
}
```

Specify your custom implementation classes to `metric-publisher-class-names`.

```
j5ik2o.dynamo-db-????? {
  v2 {
    metric-publisher-class-names = ["exampe.MyMetricPublisher"]
  }
}
```
