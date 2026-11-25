import sbt._
import sbt.Keys._
import sbt.librarymanagement.{ ModuleDescriptorConfiguration, UnresolvedWarningConfiguration, UpdateConfiguration }

/** The published journal-v2 and snapshot-v2 of earlier releases, which `ReleasedRowsMigrationSpec` runs in a separate
  * JVM to write the rows that the key migration moves (issue #1027).
  *
  * They have the package and the class names of this code base, so they cannot share the test classpath, and a
  * configuration of the project would share its conflict resolution, which drops the modules of this build and raises
  * the other dependencies to the versions of this build. Each release is therefore resolved on its own, as a user of
  * that release resolves it, and passed to the forked test JVM as a system property. Nothing of it reaches the POM.
  */
object ReleasedPlugins {

  val versions: Seq[String] = Seq("1.0.59", "1.3.0")

  def property(version: String): String = s"pekko-persistence-dynamodb.released-classpath.$version"

  val releasedPluginClasspaths: TaskKey[Seq[(String, Seq[File])]] =
    taskKey[Seq[(String, Seq[File])]]("The runtime classpath of the published plugins of each release in versions.")

  lazy val settings: Seq[Def.Setting[_]] = Seq(
    releasedPluginClasspaths := {
      val lm    = dependencyResolution.value
      val log   = streams.value.log
      val org   = organization.value
      val smi   = scalaModuleInfo.value
      val types = classpathTypes.value
      versions.map { version =>
        val descriptor = lm.moduleDescriptor(
          ModuleDescriptorConfiguration(ModuleID(org, s"released-plugins-$version", "0"), ModuleInfo("released"))
            .withScalaModuleInfo(smi)
            .withDependencies(
              Vector(
                org %% "pekko-persistence-dynamodb-journal-v2"  % version,
                org %% "pekko-persistence-dynamodb-snapshot-v2" % version
              )
            )
        )
        // A release that does not resolve (offline, without it in the cache) leaves its property unset, so that the
        // other suites of the module still run; ReleasedRowsMigrationSpec then fails and names the property.
        lm.update(descriptor, UpdateConfiguration(), UnresolvedWarningConfiguration(), log) match {
          case Right(report) =>
            Some(
              version -> report
                .select(configurationFilter("compile"), moduleFilter(), artifactFilter(`type` = types)).distinct
            )
          case Left(warning) =>
            log.warn(s"the published plugins of $version did not resolve: ${warning.resolveException.getMessage}")
            None
        }
      }.flatten
    },
    Test / javaOptions ++= releasedPluginClasspaths.value.map { case (version, jars) =>
      s"-D${property(version)}=${jars.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)}"
    }
  )
}
