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
Typed puts between the entity type hint and the entity id. The default resolvers match the separator as plain text, so
any separator works, `|` included. Do not change the separator on a table that already has rows: the keys of those rows
would change.

```
j5ik2o.dynamo-db-journal.persistence-id-separator = "-"
j5ik2o.dynamo-db-snapshot.persistence-id-separator = "-"
```

The default resolvers split the `persistenceId` at the first separator. `prefix` is the text before it and goes into
`pkey`. `body` is the text after it, which can contain more separators, and goes into `skey`. A `persistenceId` without
the separator has no `prefix`, and its whole text is the `body`. Because `prefix`, the separator, and `body` together
are the whole `persistenceId`, and the shard and the sequence number have a fixed length, two `persistenceId`s never
get the same key.

When `${body}-${sequenceNumber}` would exceed 1,024 bytes in UTF-8, the largest sort key that DynamoDB accepts, `skey`
is `#h1#${sha256(body)}-${sequenceNumber}` instead: the marker `#h1#`, the SHA-256 of `body` in 64 hexadecimal digits,
and the sequence number. Whether a `persistenceId` gets this form depends on its `body` alone, so every event of the
`persistenceId` gets the same form. A `persistenceId` cannot be read back from such a key.

(key-format-change)=

#### Key format changes after 1.3.0 (issue #1009)

Releases up to 1.3.0 read the separator as a regular expression and used only the first two parts of the split, so two
`persistenceId`s could get the same key, and some `persistenceId`s could not be written at all. The default resolvers
now give the keys below. The keys of every other `persistenceId` stay the same, byte for byte.

| `persistence-id-separator` | `persistenceId`s whose key changes | What changes |
| :-- | :-- | :-- |
| Not a regular expression metacharacter (the default `-`, `::`, `_`) | The separator two or more times, such as `shopping-cart-1` | `skey` only |
| The same | Ends with its only separator, such as `order-` | 1.3.0 failed with `ArrayIndexOutOfBoundsException`, so no rows exist |
| `\|` | Contains the separator, except `<one character>\|\|` | `pkey` and `skey` |
| `.`, `$`, `^`, `?`, `*`, `+`, `(`, `[`, `{`, `\` | Contains the separator | 1.3.0 failed with an exception, so no rows exist |
| Any | `body` longer than 1,004 bytes | `skey` takes the hashed form. Ids with one separator or none could not be written by 1.3.0 |

The rows that 1.3.0 and earlier releases wrote keep their old keys until they are migrated. The default resolvers
still handle them: a replay, the highest sequence number, and a snapshot load read every row of the `persistenceId`
through the `persistence-id` index, and a hard delete, a soft delete, an in-place update, a snapshot delete, and a
snapshot save also reach the row under the old key. An operation on the old key changes the row only while it is the
event or the snapshot of the same `persistenceId` and sequence number, so the row of another `persistenceId` that shares
the old key is never changed. When a snapshot of the same sequence number exists under both keys, a load returns the one
under the current key.

A migration tool that moves the old rows to the current keys comes in a later change. Until then, the new key of a
`persistenceId` can be the old key of another `persistenceId`, for example `Order-` and `Order--23`, or with the
separator `|`, `A|B` and `AB|98`. Such a write fails as described in the next section, and the row of the other
`persistenceId` stays as it is.

The extra requests for the old keys, measured on DynamoDB Local with the AWS SDK v2 client:

| Operation | `persistenceId` whose key changes | `persistenceId` whose key does not change |
| :-- | :-- | :-- |
| Persist | none | none |
| Hard delete, soft delete | one conditional `DeleteItem` per event | none |
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

To keep the keys of 1.3.0 exactly, select the legacy resolvers. With them, `persistenceId`s that share a key keep
failing as described in the next section.

```
j5ik2o.dynamo-db-journal {
  partition-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.journal.PartitionKeyResolver$LegacyPersistenceIdBased"
  sort-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.journal.SortKeyResolver$LegacyPersistenceIdWithSeqNr"
}
j5ik2o.dynamo-db-snapshot {
  partition-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.PartitionKeyResolver$LegacyPersistenceIdBased"
  sort-key-resolver-class-name = "com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKeyResolver$LegacyPersistenceIdWithSeqNr"
}
```

A rolled back release up to 1.3.0 replays the rows written under the new keys, but its deletes and in-place updates do
not reach them.

If you read `pkey` or `skey` outside the plugin, for example from DynamoDB Streams or Kinesis Data Streams, note that the
keys change for the `persistenceId`s in the table above. With the separator `|`, the events of one `persistenceId` are in
two partitions until the old rows are migrated.

```{admonition} DAX (not measured)
:class: warning

The DAX item cache was not measured with this change. A delete or an update under an old key goes through DAX like any
other write, but a read through the cache can return a row that was changed by another client until the cache entry
expires. When you migrate the old rows, wait for the DAX item cache TTL before you rely on the result.
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

With the default resolvers, this happens only when a new key holds an old row of another `persistenceId`, and the
migration of the old rows resolves it. With the legacy resolvers, a `persistenceId` that fails this way cannot write the
sequence numbers whose keys the other `persistenceId` holds, and retrying does not help.

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
- `com.github.j5ik2o.pekko.persistence.dynamodb.journal.PartitionKeyResolver$PersistenceIdBased` (Default)
  - `pkey = ${persistenceId.prefix}-${md5(persistenceId.reverse) % shardCount}`
  - e.g. `counter-875e6ce0425e4d2b8203f3b44b9b531a`, `persistenceId.prefix` is `counter`, the text before the first separator.
  - If you choose this option, the same shard will be assigned if the `PersistenceId` is the same, so be sure to select this option if you are using DynamoDB Streams or KDS for DynamoDB.
- `com.github.j5ik2o.pekko.persistence.dynamodb.journal.PartitionKeyResolver$LegacyPersistenceIdBased`
  - The default of releases up to 1.3.0, kept as it was. `persistenceId.prefix` is the first element of a split that reads the separator as a regular expression. See [Key format changes after 1.3.0](#key-format-change).

#### Sort Key Resolver Class Name
 
`sort-key-resolver-class-name` specifies the implementation class that generates `skey` from `PersistenceId` and `Sequence Number`.
The following two implementations are available for built-in use. 

- `com.github.j5ik2o.pekko.persistence.dynamodb.journal.SortKeyResolver$SeqNr`
  - `skey = $sequenceNumber`
  - An implementation in which `skey` is the `Sequence Number`.
- `com.github.j5ik2o.pekko.persistence.dynamodb.journal.SortKeyResolver$PersistenceIdWithSeqNr` (Default)
  - `skey = ${persistenceId.body}-${sequenceNumber}`
  - e.g. `875e6ce0425e4d2b8203f3b44b9b531a`, `persistenceId.body` is `875e6ce0425e4d2b8203f3b44b9b531a`.
  - Use `persistenceId.body` as the prefix since `shard-count` may cause multiple `persistenceId`s events to be stored in the same shard.
  - If the `persistenceId` does not contain the separator, the full `persistenceId` is used as the prefix: `${persistenceId}-${sequenceNumber}`.
  - `persistenceId.body` is all the text after the first separator. When the key would exceed 1,024 bytes, `body` is replaced with its hash. See [Persistence id separator](#persistence-id-separator).
- `com.github.j5ik2o.pekko.persistence.dynamodb.journal.SortKeyResolver$LegacyPersistenceIdWithSeqNr`
  - The default of releases up to 1.3.0, kept as it was. `persistenceId.body` is only the second element of a split that reads the separator as a regular expression, so two `persistenceId`s can get the same key. See [Key format changes after 1.3.0](#key-format-change).

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
- `com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.PartitionKeyResolver$PersistenceIdBased` (Default)
  - `pkey = ${persistenceId.prefix}-${md5(persistenceId.reverse) % shardCount}`
  - e.g. `counter-875e6ce0425e4d2b8203f3b44b9b531a`, `persistenceId.prefix` is `counter`, the text before the first separator.
  - If you choose this option, the same shard will be assigned if the `PersistenceId` is the same, so be sure to select this option if you are using DynamoDB Streams or KDS for DynamoDB.
- `com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.PartitionKeyResolver$LegacyPersistenceIdBased`
  - The default of releases up to 1.3.0, kept as it was. `persistenceId.prefix` is the first element of a split that reads the separator as a regular expression. See [Key format changes after 1.3.0](#key-format-change).

#### Sort Key Resolver Class Name

`sort-key-resolver-class-name` specifies the implementation class that generates `skey` from `PersistenceId` and `Sequence Number`.
The following two implementations are available for built-in use.

- `com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKeyResolver$SeqNr`
  - `skey = $sequenceNumber`
  - An implementation in which `skey` is the `Sequence Number`.
- `com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKeyResolver$PersistenceIdWithSeqNr` (Default)
  - `skey = ${persistenceId.body}-${sequenceNumber}`
  - e.g. `875e6ce0425e4d2b8203f3b44b9b531a`, `persistenceId.body` is `875e6ce0425e4d2b8203f3b44b9b531a`.
  - Use `persistenceId.body` as the prefix since `shard-count` may cause multiple `persistenceId`s events to be stored in the same shard.
  - If the `persistenceId` does not contain the separator, the full `persistenceId` is used as the prefix: `${persistenceId}-${sequenceNumber}`.
  - `persistenceId.body` is all the text after the first separator. When the key would exceed 1,024 bytes, `body` is replaced with its hash. See [Persistence id separator](#persistence-id-separator).
- `com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKeyResolver$LegacyPersistenceIdWithSeqNr`
  - The default of releases up to 1.3.0, kept as it was. `persistenceId.body` is only the second element of a split that reads the separator as a regular expression, so two `persistenceId`s can get the same key. See [Key format changes after 1.3.0](#key-format-change).

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
