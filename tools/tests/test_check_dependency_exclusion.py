import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "check-dependency-exclusion.py"
spec = importlib.util.spec_from_file_location("check_dependency_exclusion", SCRIPT)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)

EXCLUDED_CLASS = "org/seasar/util/io/ResourceUtil"
CALLER = "com/github/j5ik2o/dockerController/DockerComposeController"
SUITE = "journal-v1/testOnly *DynamoDBJournalV1SyncSpec"
POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <version>1.2.0-SNAPSHOT</version>
  <dependencies>
    <dependency>
      <groupId>com.github.j5ik2o</groupId>
      <artifactId>pekko-persistence-dynamodb-base_2.13</artifactId>
      <version>1.2.0-SNAPSHOT</version>
    </dependency>
    <dependency>
      <groupId>com.github.j5ik2o</groupId>
      <artifactId>docker-controller-scala-dynamodb-local_2.13</artifactId>
      <version>1.15.34</version>
      {exclusions}
    </dependency>
    <dependency>
      <groupId>org.scalatest</groupId>
      <artifactId>scalatest_2.13</artifactId>
      <version>3.2.16</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
"""
EXCLUSION = """<exclusions><exclusion>
        <groupId>org.seasar.util</groupId><artifactId>s2util</artifactId>
      </exclusion></exclusions>"""
JAVAP = """Compiled from "DockerComposeController.scala"
public class com.github.j5ik2o.dockerController.DockerComposeController {
  public com.github.dockerjava.api.command.CreateContainerCmd newCreateContainerCmd();
    Code:
     132: invokestatic  #186  // Method org/seasar/util/io/ResourceUtil.getResourceAsFile:(Ljava/lang/String;)Ljava/io/File;
}
"""
CLASS_LOAD = "[info] [0.512s][info][class,load] {} source: file:/fake.jar\n"


def jar(path, classes):
    with zipfile.ZipFile(path, "w") as archive:
        for name, body in classes.items():
            archive.writestr(name + ".class", body)
    return str(path)


class FakeSbt:
    def __init__(self, root):
        self.root = Path(root)
        self.calls = []
        self.tree = "[info] +-com.github.j5ik2o:docker-controller-scala-core_2.13:1.15.34 [S]\n"
        self.update_code = 0
        caller_jar = jar(self.root / "core.jar", {
            CALLER: b"\xca\xfe\xba\xbe" + EXCLUDED_CLASS.encode() + b"\x01",
            "com/github/j5ik2o/dockerController/DynamoDBLocalController": b"\xca\xfe\xba\xbe plain"})
        self.classpath = [caller_jar]
        self.pom = self.root / "test.pom"
        self.pom.write_text(POM.format(exclusions=EXCLUSION))
        self.loaded = ["java.lang.Object", "com.github.j5ik2o.dockerController.DynamoDBLocalController"]
        self.suite_extra = ""
        self.consumer = None

    def __call__(self, argv, cwd):
        self.calls.append((argv, cwd))
        if argv[0] == "javap":
            return 0, JAVAP
        commands = argv[3:]
        if commands[-1].endswith("/dependencyTree"):
            if self.update_code and commands[0].endswith("/update"):
                return self.update_code, "[error] (test / update) ResolveException: Error downloading org.seasar.util:s2util:0.0.1\n[error] \tat sbt.Execute.work(Execute.scala:292)\n"
            return 0, self.tree
        if commands[0].startswith("export "):
            return 0, "[info] welcome to sbt\n" + ":".join(self.classpath) + "\n"
        if commands[0].endswith("/makePom"):
            return 0, "[info] Wrote " + str(self.pom) + "\n"
        if commands == ["update"]:
            self.consumer = (Path(cwd) / "build.sbt").read_text()
            return 0, "[success]\n"
        if commands[0].startswith("set ") and commands[1] == SUITE:
            return 0, "".join(CLASS_LOAD.format(c) for c in self.loaded) + self.suite_extra
        raise AssertionError("unexpected command " + repr(argv))


class CheckDependencyExclusionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.excluded_jar = jar(root / "s2util-0.0.1.jar", {EXCLUDED_CLASS: b"\xca\xfe\xba\xbe"})
        self.sbt = FakeSbt(root)
        self.lines = []

    def main(self, *extra):
        argv = ["--project", "test", "--exclude", "org.seasar.util:s2util", "--suite", SUITE,
                "--excluded-jar", self.excluded_jar, *extra]
        return check.main(argv, run=self.sbt, report=self.lines.append)

    def output(self):
        return "\n".join(self.lines)

    def test_safe_exclusion_passes_and_reports_the_offline_skip(self):
        self.assertEqual(self.main("--via", "com.github.j5ik2o:docker-controller-scala-dynamodb-local"), 0, self.output())
        self.assertIn("caller com.github.j5ik2o.dockerController.DockerComposeController in core.jar", self.output())
        self.assertIn("org/seasar/util/io/ResourceUtil.getResourceAsFile", self.output())
        self.assertNotIn("DynamoDBLocalController in", self.output())
        self.assertIn("SKIPPED 4 Maven Central resolution: offline run", self.output())
        self.assertIn("(Maven Central resolution skipped)", self.output())

    def test_runtime_that_loads_a_caller_fails(self):
        self.sbt.loaded.append("com.github.j5ik2o.dockerController.DockerComposeController")
        self.assertEqual(self.main(), 1)
        self.assertIn("FAIL the suite loaded or needed excluded code:\n"
                      "com.github.j5ik2o.dockerController.DockerComposeController", self.output())

    def test_caller_of_a_subpackage_class_is_found_and_fails_when_loaded(self):
        sub = "org/seasar/util/beans/converter/DateConverter"
        self.excluded_jar = jar(Path(self.tmp.name) / "s2util-sub.jar", {
            "org/seasar/util/beans/BeanDesc": b"\xca\xfe\xba\xbe", sub: b"\xca\xfe\xba\xbe"})
        self.sbt.classpath.append(jar(Path(self.tmp.name) / "sub-caller.jar", {
            "com/example/SubCaller": b"\xca\xfe\xba\xbe\x01\x00\x2c" + sub.encode() + b"\x07"}))
        self.sbt.loaded.append("com.example.SubCaller")
        self.assertEqual(self.main(), 1)
        self.assertIn("caller com.example.SubCaller in sub-caller.jar", self.output())
        self.assertIn("FAIL the suite loaded or needed excluded code:\ncom.example.SubCaller", self.lines[-1])

    def test_runtime_that_loads_an_excluded_class_fails(self):
        self.sbt.loaded.append("org.seasar.util.io.ResourceUtil")
        self.assertEqual(self.main(), 1)
        self.assertIn("org.seasar.util.io.ResourceUtil", self.lines[-1])

    def test_runtime_that_misses_an_excluded_class_fails(self):
        self.sbt.suite_extra = "[info] java.lang.NoClassDefFoundError: org/seasar/util/io/ResourceUtil\n"
        self.assertEqual(self.main(), 1)
        self.assertIn("org.seasar.util.io.ResourceUtil", self.lines[-1])

    def test_runtime_without_class_load_output_fails(self):
        self.sbt.loaded = []
        self.assertEqual(self.main(), 1)
        self.assertIn("no -verbose:class output", self.lines[-1])

    def test_unresolvable_dependency_fails_before_the_tree(self):
        self.sbt.update_code = 1
        self.assertEqual(self.main(), 1)
        self.assertIn("Error downloading org.seasar.util:s2util", self.lines[-1])
        self.assertNotIn("Execute.work", self.lines[-1])

    def test_module_still_in_the_tree_fails(self):
        self.sbt.tree += "[info] | +-org.seasar.util:s2util:0.0.1\n"
        self.assertEqual(self.main(), 1)
        self.assertIn("still in test/dependencyTree", self.lines[-1])

    def test_excluded_class_on_the_classpath_fails(self):
        self.sbt.classpath.append(self.excluded_jar)
        self.assertEqual(self.main(), 1)
        self.assertIn("excluded classes are on the journal-v1 test classpath", self.lines[-1])

    def test_pom_without_the_exclusion_fails(self):
        self.sbt.pom.write_text(POM.format(exclusions=""))
        self.assertEqual(self.main(), 1)
        self.assertIn("has no exclusion of org.seasar.util:s2util", self.lines[-1])

    def test_pom_missing_the_exclusion_on_a_named_dependency_fails(self):
        self.assertEqual(self.main("--via", "com.github.j5ik2o:docker-controller-scala-scalatest"), 1)
        self.assertIn("lacks the exclusion on com.github.j5ik2o:docker-controller-scala-scalatest", self.lines[-1])

    def test_central_resolution_uses_only_central_and_keeps_the_exclusion(self):
        self.assertEqual(self.main("--resolve-central"), 0, self.output())
        build = self.sbt.consumer
        self.assertIn('externalResolvers := Seq("central" at "https://repo1.maven.org/maven2/")', build)
        self.assertIn('("com.github.j5ik2o" % "docker-controller-scala-dynamodb-local_2.13" % "1.15.34")'
                      '.exclude("org.seasar.util", "s2util")', build)
        self.assertNotIn("pekko-persistence-dynamodb-base", build)
        self.assertNotIn("scalatest_2.13", build)
        self.assertIn("PASS 4 Maven Central resolution: 1 published dependencies", self.output())
        self.assertNotIn("skipped", self.lines[-1])


if __name__ == "__main__":
    unittest.main()
