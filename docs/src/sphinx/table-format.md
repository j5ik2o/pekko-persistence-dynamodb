# Table Formats

Assuming the default values are used (adjust as necessary if not):

| type | name | partition key             | sort key | comments |
|:----:|:----:|:--------------------------|:---------|:---------|
|table | Journal | `pkey` (String)           | `skey` (String) | Used when implementing [EventSourcedBehavior](https://pekko.apache.org/docs/pekko/current/typed/persistence.html).|
|index | GetJournalRowsIndex (GSI) | `persistence-id` (String) | `sequence-nr` (Number) | Required the index for replaying [EventSourcedBehavior](https://pekko.apache.org/docs/pekko/current/typed/persistence.html). |
|table | Snapshot | `pkey` (String)           | `skey` (String) | Required when using the snapshot feature with [EventSourcedBehavior](https://pekko.apache.org/docs/pekko/current/typed/persistence.html). |
|index | GetSnapshotRowsIndex (GSI) | `persistence-id` (String)  | `sequence-nr` (Number) | Required the index for replaying [EventSourcedBehavior](https://pekko.apache.org/docs/pekko/current/typed/persistence.html). |
|table | State | `pkey` (String)           | `skey` (String) | Used when implementing [DurableStateBehavior](https://pekko.apache.org/docs/pekko/current/typed/index-persistence-durable-state.html) |

The table JSON files under `tools/dynamodb-setup/` are generated from the table specs. Change the spec or the
`columns-def` value and regenerate them; do not edit the generated JSON by hand. The legacy table JSON files keep
their own shape.

As the access to the DynamoDB instance is via the AWS Java SDK, use the methods for the SDK, which are documented at [docs.aws.amazon.com](https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/credentials.html)
