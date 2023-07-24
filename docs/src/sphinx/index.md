# j5ik2o/pekko-persistence-dynamodb

Status of This Document: [WIP]

[![CI](https://github.com/j5ik2o/pekko-persistence-dynamodb/workflows/CI/badge.svg)](https://github.com/j5ik2o/pekko-persistence-dynamodb/actions?query=workflow%3ACI)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/pekko-persistence-dynamodb-journal-v2_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/pekko-persistence-dynamodb-journal-v2_2.13)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

`j5ik2o/pekko-persistence-dynamodb` is a plugin for akka-persistence.
You can persist your Actor's state to `AWS DynamoDB` without having to worry about detailed persistence techniques.

The plugin features the following.

- [Event sourcing](https://pekko.apache.org/docs/pekko/current/typed/persistence.html)
  - [Persistence Plugin](https://pekko.apache.org/docs/pekko/current/persistence-plugins.html)
- [Durable state](https://pekko.apache.org/docs/pekko/current/typed/index-persistence-durable-state.html)

## Sitemap

```{toctree}
---
maxdepth: 2
---
overview.md
getting-started.md
table-format.md
customize.md
```
