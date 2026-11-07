package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }

/** Writes the public declaration surface of the published modules to a baseline file. */
object ApiBaseline {
  def main(args: Array[String]): Unit = {
    val root = Paths.get(if (args.nonEmpty) args(0) else ".").toAbsolutePath.normalize()
    val output = Paths.get(
      if (args.length > 1) args(1) else root.resolve("schema-check/public-api-baseline.txt").toString
    )
    val declarations = PublicApiSurface.of(root)
    Files.write(output, PublicApiSurface.render(declarations).getBytes(StandardCharsets.UTF_8))
    println(s"wrote ${declarations.size} declarations to $output")
  }
}
