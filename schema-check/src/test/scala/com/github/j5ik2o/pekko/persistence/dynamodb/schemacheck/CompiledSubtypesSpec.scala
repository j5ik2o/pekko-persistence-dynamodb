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

import java.net.{ URL, URLClassLoader }
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.security.{ CodeSource, SecureClassLoader }
import java.security.cert.Certificate
import java.util.jar.{ JarEntry, JarOutputStream }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.exceptions.TestFailedException
import scala.collection.JavaConverters._

private[schemacheck] object CompiledSubtypeFixtures {
  sealed trait Root
  final case class Known() extends Root
  case class NonFinal() extends Root
  final case object ObjectCase extends Root {
    throw new IllegalStateException("subtype discovery initialized ObjectCase")
  }
  final class Plain extends Root
  @deprecated("fixture", "test") final case class Annotated() extends Root
  final case class Multiline(
      firstFixtureParameter: String,
      secondFixtureParameter: String,
      thirdFixtureParameter: String,
      fourthFixtureParameter: String
  ) extends Root
  private final class Hidden extends Root
  trait Bridge extends Root
  final class Indirect extends Bridge
  def anonymous(): Root = new Root {}
}

final class CompiledSubtypesSpec extends AnyFreeSpec with Matchers {
  private val rootName = classOf[CompiledSubtypeFixtures.Root].getName
  private val prefix   = rootName.stripSuffix("Root")

  private def withDirectory(test: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("compiled-subtypes-")
    try {
      val source = Paths.get(classOf[CompiledSubtypeFixtures.Root].getProtectionDomain.getCodeSource.getLocation.toURI)
      val paths  = Files.walk(source)
      try
        paths.iterator().asScala.filter(Files.isRegularFile(_)).foreach { path =>
          val relative = source.relativize(path)
          if (
            relative.toString
              .replace('\\', '/').startsWith(prefix.replace('.', '/')) && path.toString.endsWith(".class")
          ) {
            val destination = directory.resolve(relative)
            Files.createDirectories(destination.getParent)
            Files.copy(path, destination)
          }
        }
      finally paths.close()
      test(directory)
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
      finally paths.close()
    }
  }

  private def withRoot(artifact: Path)(test: Class[_] => Unit): Unit = {
    val loader = new URLClassLoader(Array(artifact.toUri.toURL), getClass.getClassLoader) {
      override def loadClass(name: String, resolve: Boolean): Class[_] = synchronized {
        if (name.startsWith(prefix)) {
          val loaded = Option(findLoadedClass(name)).getOrElse(findClass(name))
          if (resolve) resolveClass(loaded)
          loaded
        } else super.loadClass(name, resolve)
      }
    }
    try {
      val root = Class.forName(rootName, false, loader)
      root.getClassLoader shouldBe loader
      Paths.get(root.getProtectionDomain.getCodeSource.getLocation.toURI) shouldBe artifact
      test(root)
    } finally loader.close()
  }

  private def known(root: Class[_]): Set[Class[_]] = {
    val suffixes = Set(
      "Known",
      "NonFinal",
      "ObjectCase$",
      "Plain",
      "Annotated",
      "Multiline",
      "Hidden",
      "Bridge",
      "Indirect",
      "$anon$1"
    )
    suffixes.map(suffix => Class.forName(prefix + suffix, false, root.getClassLoader))
  }

  "discovers every compiled form, including private, indirect and anonymous subtypes, without initialization" in {
    withDirectory { directory =>
      withRoot(directory) { root => CompiledSubtypes.of(root) shouldBe known(root) }
    }
  }

  "an unregistered subtype fails the exact-set guard that the old final-case-class filter missed" in {
    withDirectory { directory =>
      val fixture = directory.resolve("base/base/src/main/scala/Fixture.scala")
      Files.createDirectories(fixture.getParent)
      Files.write(
        fixture,
        "package fixture\nsealed trait Operation\nobject Operation {\n final case class Known() extends Operation\n case class NonFinal() extends Operation\n}\n"
          .getBytes(StandardCharsets.UTF_8)
      )
      val oldNames = PublicApiSurface
        .of(directory).filter(_.owner == "Operation")
        .filter(_.header.startsWith("final case class "))
        .map(_.header.stripPrefix("final case class ").takeWhile(_ != '(')).toSet
      oldNames shouldBe Set("Known")
      withRoot(directory) { root =>
        val expected = known(root).filterNot(_.getName == prefix + "NonFinal")
        val failure  = intercept[TestFailedException] { CompiledSubtypes.of(root) shouldBe expected }
        failure.getMessage should include(prefix + "NonFinal")
      }
    }
  }

  "an ordinary JAR and a directory discover equivalent sets using their own classes" in {
    withDirectory { directory =>
      val jar    = directory.resolve("fixtures.jar")
      val output = new JarOutputStream(Files.newOutputStream(jar))
      val paths  = Files.walk(directory)
      try
        paths.iterator().asScala.filter(_.toString.endsWith(".class")).foreach { path =>
          output.putNextEntry(new JarEntry(directory.relativize(path).toString.replace('\\', '/')))
          Files.copy(path, output)
          output.closeEntry()
        }
      finally {
        paths.close()
        output.close()
      }
      withRoot(directory) { directoryRoot =>
        val directoryTypes = CompiledSubtypes.of(directoryRoot)
        withRoot(jar) { jarRoot =>
          val jarTypes = CompiledSubtypes.of(jarRoot)
          jarTypes shouldBe known(jarRoot)
          jarTypes.map(_.getName) shouldBe directoryTypes.map(_.getName)
          jarTypes.intersect(directoryTypes) shouldBe empty
        }
      }
    }
  }

  "parent-first collisions fail instead of silently hiding an artifact subtype" in {
    withDirectory { directory =>
      val shadowed = prefix + "NonFinal"
      val loader = new URLClassLoader(Array(directory.toUri.toURL), getClass.getClassLoader) {
        override def loadClass(name: String, resolve: Boolean): Class[_] = synchronized {
          if (name.startsWith(prefix) && name != shadowed) {
            val loaded = Option(findLoadedClass(name)).getOrElse(findClass(name))
            if (resolve) resolveClass(loaded)
            loaded
          } else super.loadClass(name, resolve)
        }
      }
      try {
        val root      = Class.forName(rootName, false, loader)
        val collision = Class.forName(shadowed, false, loader)
        root.isAssignableFrom(collision) shouldBe false
        val failure = intercept[IllegalStateException] { CompiledSubtypes.of(root) }
        failure.getMessage should include(shadowed)
        failure.getMessage should include("CodeSource mismatch")
        failure.getMessage should include(directory.toString)
      } finally loader.close()
    }
  }

  "missing and unsupported CodeSources fail with an actionable diagnostic" in {
    val bytes = {
      val stream = getClass.getClassLoader.getResourceAsStream(rootName.replace('.', '/') + ".class")
      try {
        val output = new java.io.ByteArrayOutputStream()
        val buffer = new Array[Byte](4096)
        Iterator.continually(stream.read(buffer)).takeWhile(_ != -1).foreach(size => output.write(buffer, 0, size))
        output.toByteArray
      } finally stream.close()
    }
    class FixtureLoader(location: URL) extends SecureClassLoader(getClass.getClassLoader) {
      def root: Class[_] =
        defineClass(rootName, bytes, 0, bytes.length, new CodeSource(location, null: Array[Certificate]))
    }
    def rootAt(location: URL): Class[_] = new FixtureLoader(location).root
    intercept[IllegalStateException] { CompiledSubtypes.of(rootAt(null)) }.getMessage should include(
      "Missing CodeSource"
    )
    intercept[IllegalArgumentException] {
      CompiledSubtypes.of(rootAt(new URL("https://example.invalid/classes")))
    }.getMessage should include("use a directory or ordinary JAR")
    withDirectory { directory =>
      val file = Files.createFile(directory.resolve("unsupported.bin"))
      intercept[IllegalArgumentException] { CompiledSubtypes.of(rootAt(file.toUri.toURL)) }.getMessage should include(
        file.toString
      )
    }
  }

  "unloadable class files fail instead of silently shrinking the set" in {
    withDirectory { directory =>
      Files.write(directory.resolve("Broken.class"), Array[Byte](0, 1, 2))
      withRoot(directory) { root =>
        val failure = intercept[IllegalStateException] { CompiledSubtypes.of(root) }
        failure.getMessage should include("Cannot load Broken")
        failure.getMessage should include(directory.toString)
        failure.getCause shouldBe a[ClassFormatError]
      }
    }
  }
}
