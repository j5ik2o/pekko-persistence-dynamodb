# Table Formats

Assuming the default values are used (adjust as necessary if not):

| type | name | partition key             | sort key | comments |
|:----:|:----:|:--------------------------|:---------|:---------|
|table | Journal | `pkey` (String)           | `skey` (String) | Used when implementing [EventSourcedBehavior](https://pekko.apache.org/docs/pekko/current/typed/persistence.html).|
|index | GetJournalRowsIndex (GSI) | `persistence-id` (String) | `sequence-nr` (Number) | Required the index for replaying [EventSourcedBehavior](https://pekko.apache.org/docs/pekko/current/typed/persistence.html). |
|index | TagsIndex (GSI) | `tags` (String)           | - | Index for queries using tags. Not Required if you do not use queries. |
|table | Snapshot | `pkey` (String)           | `skey` (String) | Required when using the snapshot feature with [EventSourcedBehavior](https://pekko.apache.org/docs/pekko/current/typed/persistence.html). |
|index | GetSnaphotRowsIndex (GSI) | `persistenceId` (String)  | `sequence-nr` (Number) | Required the index for replaying [EventSourcedBehavior](https://pekko.apache.org/docs/pekko/current/typed/persistence.html). |
|table | State | `pkey` (String)           | - | Used when implementing [DurableStateBehavior](https://pekko.apache.org/docs/pekko/current/typed/index-persistence-durable-state.html) |

As the access to the DynamoDB instance is via the AWS Java SDK, use the methods for the SDK, which are documented at [docs.aws.amazon.com](https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/credentials.html)
