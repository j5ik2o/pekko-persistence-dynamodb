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

  "multiline implementations keep local declarations opaque and preserve following members" in {
    val declarations = scan("""class Store {
      |  def load: Int =
      |    List(1).map { value =>
      |      val localDef = value
      |      localDef
      |    }.head
      |  val cached: Int =
      |    List(1).map { value =>
      |      val localVal = value
      |      localVal
      |    }.head
      |  var updated: Int =
      |    {
      |      val localVar = 1
      |      localVar
      |    }
      |  def following: Int = 1
      |}
      |""".stripMargin)
    declarations.map(d => (d.owner, d.header)) should contain theSameElementsAs Vector(
      "" -> "class Store",
      "Store" -> "def load: Int",
      "Store" -> "val cached: Int",
      "Store" -> "var updated: Int",
      "Store" -> "def following: Int"
    )
  }

  "multiline type headers retain inheritance and distinct member owners" in {
    val source = """object Rejections {
      |  case class First(value: Int)
      |      extends Rejection {
      |    override def convert: Int = value
      |  }
      |  case class Second(
      |      value: Int
      |  )
      |      extends Rejection
      |      with Product {
      |    override def convert: Int = value
      |  }
      |  class Store[A] extends Parent[
      |    A
      |  ] {
      |    def get: A
      |  }
      |  class Separate
      |  class Next
      |  {
      |    val member: Int = 1
      |  }
      |}
      |""".stripMargin
    val before = scan(source)
    before.map(d => (d.owner, d.header)) should contain allOf (
      "Rejections" -> "case class First(value: Int) extends Rejection",
      "Rejections" -> "case class Second( value: Int ) extends Rejection with Product",
      "Rejections.First" -> "override def convert: Int",
      "Rejections.Second" -> "override def convert: Int",
      "Rejections.Store" -> "def get: A",
      "Rejections" -> "class Separate",
      "Rejections.Next" -> "val member: Int"
    )
    val after = scan(source.replace("override def convert: Int = value", ""))
    PublicApiSurface.missing(before, after).map(_.owner) should contain allOf (
      "Rejections.First", "Rejections.Second"
    )
  }

  "abstract signatures and type aliases do not swallow following declarations" in {
    val declarations = scan("""trait Api {
      |  def abstractMethod[A](value: A): A
      |  val abstractValue: Int
      |  type Alias = List[
      |    String
      |  ]
      |  def concrete: String = "value"
      |  def following: Int = 1
      |}
      |""".stripMargin)
    declarations.map(_.header) should contain allOf (
      "def abstractMethod[A](value: A): A", "val abstractValue: Int", "type Alias",
      "def concrete: String", "def following: Int"
    )
  }

  private def scan(source: String): Vector[ApiDeclaration] = {
    val root = Files.createTempDirectory("api-surface-")
    val file = root.resolve("base/base/src/main/scala/example/Api.scala")
    Files.createDirectories(file.getParent)
    write(file, source)
    PublicApiSurface.of(root)
  }

  private def write(path: Path, text: String): Unit =
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))
}
