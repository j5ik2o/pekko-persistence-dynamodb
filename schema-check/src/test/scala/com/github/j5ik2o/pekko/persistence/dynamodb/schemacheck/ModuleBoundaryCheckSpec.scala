package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import moduleboundary.ModuleBoundaryCheck
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class ModuleBoundaryCheckSpec extends AnyFreeSpec with Matchers {

  private val SdkSource   = "package p\n\nimport software.amazon.awssdk.services.dynamodb.DynamoDbClient\n"
  private val PlainSource = "package p\n\nimport scala.concurrent.Future\n"

  private val Tool        = "journal/journal-v2/src/main/scala/p/NewTool.scala"
  private val ExistingFix = "journal/journal-v2/src/main/scala/p/Existing.scala"

  private def tree(files: Map[String, String], allowlist: String): (Path, Path) = {
    val root = Files.createTempDirectory("module-boundary")
    ModuleBoundaryCheck.Modules.foreach { case (module, _) => Files.createDirectories(root.resolve(module)) }
    files.foreach { case (path, body) =>
      val file = root.resolve(path)
      Files.createDirectories(file.getParent)
      Files.write(file, body.getBytes(StandardCharsets.UTF_8))
    }
    val allowlistFile = root.resolve("allowlist")
    Files.write(allowlistFile, allowlist.getBytes(StandardCharsets.UTF_8))
    (root, allowlistFile)
  }

  private def errors(files: Map[String, String], allowlist: String): Seq[String] = {
    val (root, allowlistFile) = tree(files + (ExistingFix -> SdkSource), allowlist + s"\n$ExistingFix  # baseline\n")
    val result                = ModuleBoundaryCheck.run(root, allowlistFile)
    result.errors.foreach(error => info(error))
    result.errors
  }

  "the baseline tree passes" in {
    errors(Map.empty, "") shouldBe empty
  }

  "a new SDK file in base-v2 passes without an allowlist entry" in {
    errors(Map("base/base-v2/src/main/scala/p/NewAdapter.scala" -> SdkSource), "") shouldBe empty
  }

  "an allowlist entry for a base-v2 file fails as not needed" in {
    val path = "base/base-v2/src/main/scala/p/NewAdapter.scala"
    errors(Map(path -> SdkSource), s"$path  # adapter").mkString("\n") should include(s"  - $path")
  }

  "an SDK import in base/base fails even with a reasoned entry" in {
    val path = "base/base/src/main/scala/p/Leak.scala"
    errors(Map(path -> SdkSource), "").mkString("\n") should include(s"  + $path")
    errors(Map(path -> SdkSource), s"$path  # convenience").mkString("\n") should include(s"  + $path (allowlisted)")
  }

  "an SDK import in journal-base fails even with a reasoned entry" in {
    val path = "journal/journal-base/src/main/scala/p/Leak.scala"
    errors(Map(path -> SdkSource), s"$path  # convenience") should not be empty
  }

  "a new SDK file in journal-v2 without an allowlist entry fails" in {
    errors(Map(Tool -> SdkSource), "").mkString("\n") should include(s"  + $Tool")
  }

  "a new SDK file in journal-v2 with an entry but no reason fails" in {
    errors(Map(Tool -> SdkSource), Tool).mkString("\n") should include(s"$Tool has no reason")
    errors(Map(Tool -> SdkSource), s"$Tool  #  ").mkString("\n") should include(s"$Tool has no reason")
  }

  "a new SDK file in journal-v2 with a reasoned entry passes" in {
    errors(Map(Tool -> SdkSource), s"$Tool  # calls DescribeTable through the v2 client") shouldBe empty
  }

  "an entry whose file no longer imports the SDK fails as stale" in {
    errors(Map(Tool -> PlainSource), s"$Tool  # used to call the v2 client").mkString("\n") should include(
      s"  - $Tool"
    )
  }

  "an entry whose file is gone fails as stale" in {
    errors(Map.empty, s"$Tool  # deleted tool").mkString("\n") should include(s"  - $Tool")
  }

  "a duplicated entry fails" in {
    errors(Map(Tool -> SdkSource), s"$Tool  # a\n$Tool  # b").mkString("\n") should include(
      s"$Tool is listed more than once"
    )
  }

  "the repository passes with project/module-boundaries.allowlist" in {
    val repo   = Repo.locate()
    val result = ModuleBoundaryCheck.run(repo.root, repo.root.resolve("project/module-boundaries.allowlist"))
    info(s"${result.sdkFiles} files import an AWS SDK, ${result.allowlisted} allowlisted")
    result.errors shouldBe empty
    result.allowlisted should be < result.sdkFiles
  }
}
