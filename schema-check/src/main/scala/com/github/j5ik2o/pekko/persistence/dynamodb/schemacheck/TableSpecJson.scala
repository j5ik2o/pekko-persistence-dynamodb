package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{
  AttributeKind,
  Column,
  IndexSpec,
  PrimaryKey,
  Projection,
  StreamView,
  TableSpec
}

/** Renders the checked-in table JSON files and the DynamoDB Local fixture from a [[TableSpec]].
  *
  * Only the attributes used by the primary key or an index appear in `AttributeDefinitions`. The other columns of a
  * `TableSpec` are item attributes, and DynamoDB does not declare them on the table.
  */
object TableSpecJson {

  /** One table of the DynamoDB Local fixture, rendered under the given method name. */
  final case class FixtureTable(spec: TableSpec)

  def render(spec: TableSpec): String = toJson(spec).render(0) + "\n"

  def renderFixture(tables: Seq[FixtureTable]): String = {
    val names = tables.map(_.spec.table.value)
    require(
      names.distinct.size == names.size,
      s"the DynamoDB Local fixture has duplicate table names: ${names.mkString(", ")}"
    )
    JsonObject(Vector("tables" -> JsonArray(tables.map(table => toJson(table.spec)).toVector))).render(0) + "\n"
  }

  private def toJson(spec: TableSpec): Json = {
    val fields = Vector.newBuilder[(String, Json)]
    fields += "TableName" -> JsonString(spec.table.value)
    fields += "AttributeDefinitions" -> JsonArray(
      referencedAttributes(spec).map { column =>
        JsonObject(
          Vector(
            "AttributeName" -> JsonString(column.name.value),
            "AttributeType" -> JsonString(attributeType(column.kind))
          )
        )
      }
    )
    fields += "KeySchema"             -> keySchema(spec.primaryKey)
    fields += "ProvisionedThroughput" -> provisionedThroughput
    if (spec.indexes.nonEmpty) {
      fields += "GlobalSecondaryIndexes" -> JsonArray(spec.indexes.map(indexJson))
    }
    spec.stream.foreach { view =>
      fields += "StreamSpecification" -> JsonObject(
        Vector(
          "StreamEnabled"  -> JsonBool(true),
          "StreamViewType" -> JsonString(streamViewType(view))
        )
      )
    }
    JsonObject(fields.result())
  }

  private def referencedAttributes(spec: TableSpec): Vector[Column] = {
    val referenced = (Vector(spec.primaryKey.hash) ++ spec.primaryKey.range.toVector ++
      spec.indexes.flatMap(index => Vector(index.hash) ++ index.range.toVector)).distinct
    referenced.map { name =>
      spec.column(name).getOrElse {
        sys.error(s"table ${spec.table.value} references column ${name.value} that it does not declare")
      }
    }
  }

  private def keySchema(primaryKey: PrimaryKey): JsonArray = {
    val hash = JsonObject(Vector("AttributeName" -> JsonString(primaryKey.hash.value), "KeyType" -> JsonString("HASH")))
    val range = primaryKey.range.toVector.map { name =>
      JsonObject(Vector("AttributeName" -> JsonString(name.value), "KeyType" -> JsonString("RANGE")))
    }
    JsonArray(Vector(hash) ++ range)
  }

  private def indexJson(index: IndexSpec): Json =
    JsonObject(
      Vector(
        "IndexName"             -> JsonString(index.name.value),
        "KeySchema"             -> keySchema(PrimaryKey(index.hash, index.range)),
        "Projection"            -> projectionJson(index.projection),
        "ProvisionedThroughput" -> provisionedThroughput
      )
    )

  private def projectionJson(projection: Projection): Json = projection match {
    case Projection.All      => JsonObject(Vector("ProjectionType" -> JsonString("ALL")))
    case Projection.KeysOnly => JsonObject(Vector("ProjectionType" -> JsonString("KEYS_ONLY")))
    case Projection.Include(columns) =>
      JsonObject(
        Vector(
          "ProjectionType"   -> JsonString("INCLUDE"),
          "NonKeyAttributes" -> JsonArray(columns.map(column => JsonString(column.value)))
        )
      )
  }

  private def provisionedThroughput: Json =
    JsonObject(Vector("ReadCapacityUnits" -> JsonNumber(10), "WriteCapacityUnits" -> JsonNumber(10)))

  private def attributeType(kind: AttributeKind): String = kind match {
    case AttributeKind.S => "S"
    case AttributeKind.N => "N"
    case AttributeKind.B => "B"
  }

  private def streamViewType(view: StreamView): String = view match {
    case StreamView.NewImage        => "NEW_IMAGE"
    case StreamView.NewAndOldImages => "NEW_AND_OLD_IMAGES"
    case StreamView.KeysOnly        => "KEYS_ONLY"
    case StreamView.OldImage        => "OLD_IMAGE"
  }

  private sealed trait Json {
    def render(indent: Int): String
  }

  private final case class JsonObject(fields: Vector[(String, Json)]) extends Json {
    override def render(indent: Int): String = {
      if (fields.isEmpty) {
        "{}"
      } else {
        val pad   = "  " * indent
        val inner = "  " * (indent + 1)
        val body = fields
          .map { case (key, value) => inner + "\"" + escape(key) + "\": " + value.render(indent + 1) }
          .mkString(",\n")
        s"{\n$body\n$pad}"
      }
    }
  }

  private final case class JsonArray(items: Vector[Json]) extends Json {
    override def render(indent: Int): String = {
      if (items.isEmpty) {
        "[]"
      } else {
        val pad   = "  " * indent
        val inner = "  " * (indent + 1)
        val body  = items.map(item => inner + item.render(indent + 1)).mkString(",\n")
        s"[\n$body\n$pad]"
      }
    }
  }

  private final case class JsonString(value: String) extends Json {
    override def render(indent: Int): String = "\"" + escape(value) + "\""
  }

  private final case class JsonNumber(value: Long) extends Json {
    override def render(indent: Int): String = value.toString
  }

  private final case class JsonBool(value: Boolean) extends Json {
    override def render(indent: Int): String = value.toString
  }

  private def escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
