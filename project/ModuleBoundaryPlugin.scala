import sbt._
import sbt.Keys._

import moduleboundary.ModuleBoundaryCheck

object ModuleBoundaryPlugin extends AutoPlugin {

  object autoImport {
    val checkModuleBoundaries: TaskKey[Unit] =
      taskKey[Unit]("Fail when a file imports an AWS SDK outside the SDK modules and the reasoned allowlist.")
    val moduleBoundariesAllowlist: SettingKey[File] =
      settingKey[File]("The file that lists, with a reason, the files outside base-v1 and base-v2 that import an SDK.")
  }

  import autoImport._

  override def trigger: PluginTrigger = allRequirements

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    moduleBoundariesAllowlist := repoRoot(baseDirectory.value) / "project" / "module-boundaries.allowlist",
    checkModuleBoundaries / aggregate := false,
    checkModuleBoundaries := {
      val result = ModuleBoundaryCheck.run(repoRoot(baseDirectory.value).toPath, moduleBoundariesAllowlist.value.toPath)
      if (result.errors.nonEmpty) sys.error(result.errors.mkString("\n"))
      streams.value.log.info(
        s"checkModuleBoundaries: ${result.sdkFiles} files import an AWS SDK, ${result.allowlisted} of them allowlisted " +
        "outside base-v1 and base-v2."
      )
    }
  )

  private def repoRoot(start: File): File = {
    @annotation.tailrec
    def loop(current: File): File = {
      if (current == null) {
        sys.error(s"cannot find build.sbt above $start")
      } else if ((current / "build.sbt").isFile) {
        current
      } else {
        loop(current.getParentFile)
      }
    }
    loop(start.getAbsoluteFile)
  }
}
