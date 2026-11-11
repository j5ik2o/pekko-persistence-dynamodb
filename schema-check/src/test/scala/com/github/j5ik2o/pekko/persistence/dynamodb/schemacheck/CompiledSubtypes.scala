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

import java.nio.file.{ Files, Paths }
import java.util.jar.JarFile
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

/** Enumerates subtypes in the root's own compiled directory or ordinary JAR, without initializing classes. */
private[schemacheck] object CompiledSubtypes {
  def of(root: Class[_]): Set[Class[_]] = {
    val location = Option(root.getProtectionDomain.getCodeSource)
      .flatMap(source => Option(source.getLocation))
      .getOrElse(
        throw new IllegalStateException(s"Missing CodeSource for ${root.getName}; run against compiled classes")
      )
    require(
      location.getProtocol == "file",
      s"Unsupported CodeSource $location for ${root.getName}; use a directory or ordinary JAR"
    )
    val artifact = Paths.get(location.toURI)
    val names = if (Files.isDirectory(artifact)) {
      val paths = Files.walk(artifact)
      try
        paths
          .iterator().asScala.filter(Files.isRegularFile(_)).map(
            artifact.relativize(_).toString.replace('\\', '/')
          ).toVector
      finally paths.close()
    } else if (Files.isRegularFile(artifact) && artifact.toString.endsWith(".jar")) {
      val jar = new JarFile(artifact.toFile)
      try jar.entries().asScala.filterNot(_.isDirectory).map(_.getName).toVector
      finally jar.close()
    } else
      throw new IllegalArgumentException(
        s"Unsupported CodeSource $artifact for ${root.getName}; use a directory or ordinary JAR"
      )

    names
      .filter(_.endsWith(".class")).map { name =>
        val binaryName = name.stripSuffix(".class").replace('/', '.')
        val clazz =
          try Class.forName(binaryName, false, root.getClassLoader)
          catch {
            case error: LinkageError =>
              throw new IllegalStateException(
                s"Cannot load $binaryName from $artifact while checking ${root.getName}",
                error
              )
            case NonFatal(error) =>
              throw new IllegalStateException(
                s"Cannot load $binaryName from $artifact while checking ${root.getName}",
                error
              )
          }
        val loadedFrom = Option(clazz.getProtectionDomain.getCodeSource).flatMap(source => Option(source.getLocation))
        if (
          !loadedFrom
            .exists(location => location.getProtocol == "file" && Files.isSameFile(Paths.get(location.toURI), artifact))
        )
          throw new IllegalStateException(
            s"CodeSource mismatch for $binaryName: expected $artifact, loaded from $loadedFrom"
          )
        if (clazz.getClassLoader ne root.getClassLoader)
          throw new IllegalStateException(
            s"ClassLoader mismatch for $binaryName from $artifact while checking ${root.getName}"
          )
        clazz
      }.filter(clazz => clazz != root && root.isAssignableFrom(clazz)).toSet
  }
}
