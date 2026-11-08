/*
 * Copyright 2026 Junichi Kato
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

final class ApiSurfaceScannerSpec extends AnyFreeSpec with Matchers {

  "the scanner records a package object and its members, and a removal is detected" in {
    val root = Files.createTempDirectory("api-surface-")
    val dir  = root.resolve("base/base/src/main/scala/example")
    Files.createDirectories(dir)
    val file = dir.resolve("package.scala")

    write(
      file,
      "package example\n\npackage object serialization {\n  def encodeTags(value: String): String = value\n  def decodeTags(value: String): String = value\n}\n"
    )
    val before  = PublicApiSurface.of(root)
    val members = before.map(declaration => (declaration.owner, declaration.header))
    members should contain(("serialization", "def encodeTags(value: String): String"))
    members should contain(("serialization", "def decodeTags(value: String): String"))
    members should contain(("", "package object serialization"))

    write(
      file,
      "package example\n\npackage object serialization {\n  def encodeTags(value: String): String = value\n}\n"
    )
    val after = PublicApiSurface.of(root)
    PublicApiSurface.missing(before, after).map(_.header) should contain("def decodeTags(value: String): String")
  }

  private def write(path: Path, text: String): Unit =
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))
}
