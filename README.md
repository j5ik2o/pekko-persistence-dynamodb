# pekko-persistence-dynamodb

[![CI](https://github.com/j5ik2o/pekko-persistence-dynamodb/workflows/CI/badge.svg)](https://github.com/j5ik2o/pekko-persistence-dynamodb/actions?query=workflow%3ACI)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/pekko-persistence-dynamodb-journal-v2_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/pekko-persistence-dynamodb-journal-v2_2.13)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

`j5ik2o/pekko-persistence-dynamodb` is an `pekko-persistence` plugin for persist `pekko-actor` to AWS DynamoDB. 

Forked from [j5ik2o/akka-persistence-dynamodb](https://github.com/j5ik2o/akka-persistence-dynamodb).

The plugin features the following.

- Event sourcing
  - Journal Plugin
  - Snapshot Store Plugin
- Durable state
  - Durable State Store Plugin

NOTE: This plugin is derived from [dnvriend/akka-persistence-jdbc](https://github.com/dnvriend/akka-persistence-jdbc), not [akka/akka-persistence-dynamodb](https://github.com/akka/akka-persistence-dynamodb).

## Programming Language Support

Java 1.8+ or Scala 2.12.x or 2.13.x or 3.x.x

## User's Guide

To use the plugins, please see the [User's Guide](https://pekko-persistence-dynamodb.readthedocs.io/en/latest/getting-started.html)

## License

Apache License Version 2.0

This product was made by duplicating or referring to the code of the following products, so Dennis Vriend's license is included in the product code and test code.

- [dnvriend/akka-persistence-jdbc](https://github.com/dnvriend/akka-persistence-jdbc)
