#!/usr/bin/env python3
"""Check that excluding a transitive dependency from a published module is safe."""

import argparse
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile


POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
CLASS_LOAD = re.compile(r"\[class,load\] (\S+) |\[Loaded (\S+) from ")
MISSING_CLASS = re.compile(r"(?:NoClassDefFoundError|ClassNotFoundException)\S*:? (\S+)")
CENTRAL = "https://repo1.maven.org/maven2/"


def run_command(argv, cwd=None):
    result = subprocess.run(argv, cwd=cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)
    return result.returncode, result.stdout


class Failure(Exception):
    pass


def sbt(run, sbt_path, commands, cwd=None):
    code, out = run([sbt_path, "-batch", "-no-colors", *commands], cwd)
    if code != 0:
        errors = [l for l in out.splitlines() if l.startswith("[error]") and not l[7:].lstrip().startswith("at ")]
        tail = "\n".join((errors or out.splitlines())[-12:])
        raise Failure("sbt " + " ".join(shlex.quote(c) for c in commands) + " exited " + str(code) + "\n" + tail)
    return out


def default_coursier_cache():
    if os.environ.get("COURSIER_CACHE"):
        return Path(os.environ["COURSIER_CACHE"])
    if sys.platform == "darwin":
        return Path.home() / "Library/Caches/Coursier/v1"
    return Path.home() / ".cache/coursier/v1"


def find_excluded_jar(org, name, cache):
    pattern = "**/" + org.replace(".", "/") + "/" + name + "/*/" + name + "-*.jar"
    jars = [p for p in sorted(cache.glob(pattern))
            if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
    if not jars:
        raise Failure("no " + org + ":" + name + " jar under " + str(cache) + "; pass --excluded-jar")
    return jars[-1]


def class_names(jar):
    with zipfile.ZipFile(jar) as archive:
        return {n[:-len(".class")] for n in archive.namelist()
                if n.endswith(".class") and not n.startswith("META-INF/")}


def class_entries(entry):
    path = Path(entry)
    if path.is_dir():
        for file in path.rglob("*.class"):
            yield file.relative_to(path).as_posix()[:-len(".class")], file.read_bytes
    elif path.suffix == ".jar" and path.is_file():
        with zipfile.ZipFile(path) as archive:
            for n in archive.namelist():
                if n.endswith(".class") and not n.startswith("META-INF/"):
                    yield n[:-len(".class")], (lambda n=n: archive.read(n))


def scan_classpath(classpath, excluded):
    packages = sorted({c.rsplit("/", 1)[0] for c in excluded if "/" in c})
    prefilter = sorted({"/".join(p.split("/")[:2]).encode() for p in packages})
    reference = re.compile(rb"(?:" + b"|".join(re.escape(p.encode()) for p in packages)
                           + rb")/[A-Za-z0-9_$]+")
    present, callers = [], {}
    for entry in classpath:
        for name, read in class_entries(entry):
            if name in excluded:
                present.append(entry + "!" + name)
                continue
            data = read()
            if not any(p in data for p in prefilter):
                continue
            refs = {m.decode() for m in reference.findall(data)} & excluded
            if refs:
                callers[name] = (entry, sorted(refs))
    return present, callers


def javap_references(run, javap, entry, name, refs):
    code, out = run([javap, "-c", "-p", "-cp", entry, name.replace("/", ".")], None)
    if code != 0:
        return ["javap failed: " + out.strip().splitlines()[-1] if out.strip() else "javap failed"]
    lines, method = [], None
    for line in out.splitlines():
        if line.startswith("  ") and not line.startswith("   ") and line.rstrip().endswith(";"):
            method = line.strip()
        elif any(r in line for r in refs):
            lines.append((method or "?") + "  " + line.strip())
    return lines


def last_plain_line(output):
    lines = [l for l in output.splitlines() if l.strip() and not l.startswith("[")]
    if not lines:
        raise Failure("sbt printed no classpath")
    return lines[-1].strip()


def pom_path(output):
    match = re.findall(r"Wrote (\S+\.pom)", output)
    if not match:
        raise Failure("makePom printed no POM path")
    return Path(match[-1])


def pom_dependencies(path):
    root = ET.parse(path).getroot()
    version = root.findtext("m:version", namespaces=POM_NS)
    deps = []
    for dep in root.findall("m:dependencies/m:dependency", POM_NS):
        exclusions = {(e.findtext("m:groupId", namespaces=POM_NS), e.findtext("m:artifactId", namespaces=POM_NS))
                      for e in dep.findall("m:exclusions/m:exclusion", POM_NS)}
        deps.append({"group": dep.findtext("m:groupId", namespaces=POM_NS),
                     "artifact": dep.findtext("m:artifactId", namespaces=POM_NS),
                     "version": dep.findtext("m:version", namespaces=POM_NS),
                     "scope": dep.findtext("m:scope", default="compile", namespaces=POM_NS),
                     "exclusions": exclusions})
    return version, deps


def consumer_build(deps):
    lines = ['autoScalaLibrary := false', 'crossPaths := false',
             'externalResolvers := Seq("central" at "' + CENTRAL + '")',
             'libraryDependencies ++= Seq(']
    rendered = []
    for d in deps:
        module = '("%s" %% "%s" %% "%s")' % (d["group"], d["artifact"], d["version"])
        module += "".join('.exclude("%s", "%s")' % e for e in sorted(d["exclusions"]))
        rendered.append("  " + module)
    return "\n".join(lines) + "\n" + ",\n".join(rendered) + "\n)\n"


def step_tree(args, run, report):
    # dependencyTree drops an unresolvable module and still exits 0; update fails on it.
    out = sbt(run, args.sbt, [args.project + "/update", args.project + "/dependencyTree"])
    hits = [l for l in out.splitlines() if args.module + ":" in l]
    if hits:
        raise Failure("still in " + args.project + "/dependencyTree:\n" + "\n".join(hits))
    report("PASS 1 dependency path: " + args.module + " is absent from " + args.project + "/update and dependencyTree")


def step_callers(args, run, report, excluded):
    suite_project = args.suite.split("/", 1)[0]
    classpath = last_plain_line(sbt(run, args.sbt, ["export " + suite_project + "/Test/fullClasspath"]))
    present, callers = scan_classpath(classpath.split(os.pathsep), excluded)
    if present:
        raise Failure("excluded classes are on the " + suite_project + " test classpath:\n" + "\n".join(present[:20]))
    report("PASS 2 callers: " + str(len(callers)) + " classes on the " + suite_project
           + " test classpath reference " + args.module)
    for name, (entry, refs) in sorted(callers.items()):
        report("  caller " + name.replace("/", ".") + " in " + Path(entry).name)
        for line in javap_references(run, args.javap, entry, name, refs)[:10]:
            report("    " + line)
    return {n.replace("/", ".") for n in callers}


def step_pom(args, run, report):
    path = pom_path(sbt(run, args.sbt, [args.project + "/makePom"]))
    version, deps = pom_dependencies(path)
    key = tuple(args.module.split(":"))
    carrying = {d["group"] + ":" + d["artifact"] for d in deps if key in d["exclusions"]}
    listed = [d for d in deps if (d["group"], d["artifact"]) == key]
    if listed:
        raise Failure(path.name + " declares " + args.module + " as a dependency")
    if not carrying:
        raise Failure(path.name + " has no exclusion of " + args.module)
    missing = [v for v in args.via if not any(c == v or c.startswith(v + "_") for c in carrying)]
    if missing:
        raise Failure(path.name + " lacks the exclusion on " + ", ".join(missing))
    report("PASS 3 POM: " + path.name + " excludes " + args.module + " from " + ", ".join(sorted(carrying)))
    return version, deps


def step_central(args, run, report, version, deps):
    if not args.resolve_central:
        report("SKIPPED 4 Maven Central resolution: offline run; pass --resolve-central to resolve from " + CENTRAL)
        return
    external = [d for d in deps if d["scope"] in ("compile", "runtime") and d["version"] != version]
    with tempfile.TemporaryDirectory(prefix="dep-exclusion-") as tmp:
        root = Path(tmp)
        (root / "project").mkdir()
        (root / "project/build.properties").write_text((Path(__file__).resolve().parents[1] / "project/build.properties").read_text())
        (root / "build.sbt").write_text(consumer_build(external))
        sbt(run, args.sbt, ["update"], cwd=tmp)
    report("PASS 4 Maven Central resolution: " + str(len(external)) + " published dependencies resolve from "
           + CENTRAL + " only")


def step_runtime(args, run, report, excluded, callers):
    suite_project = args.suite.split("/", 1)[0]
    option = 'set LocalProject("' + suite_project + '") / Test / javaOptions += "-verbose:class"'
    out = sbt(run, args.sbt, [option, args.suite])
    loaded = {a or b for a, b in CLASS_LOAD.findall(out)}
    if not loaded:
        raise Failure("no -verbose:class output; the suite did not fork or ran no tests")
    excluded_dotted = {c.replace("/", ".") for c in excluded}
    problems = sorted(loaded & (excluded_dotted | callers))
    problems += sorted({m.replace("/", ".") for m in MISSING_CLASS.findall(out)} & excluded_dotted)
    if problems:
        raise Failure("the suite loaded or needed excluded code:\n" + "\n".join(problems))
    report("PASS 5 runtime: " + args.suite + " loaded " + str(len(loaded))
           + " classes and no excluded class or caller")


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="sbt project that declares the exclusion")
    parser.add_argument("--exclude", dest="module", required=True, help="excluded module as org:name")
    parser.add_argument("--suite", required=True, help="project-scoped test command run with -verbose:class")
    parser.add_argument("--via", action="append", default=[],
                        help="org:name of a dependency whose POM entry must carry the exclusion")
    parser.add_argument("--excluded-jar", type=Path, help="jar of the excluded module")
    parser.add_argument("--coursier-cache", type=Path, default=default_coursier_cache())
    parser.add_argument("--resolve-central", action="store_true", help="resolve the POM from Maven Central (network)")
    parser.add_argument("--sbt", default="sbt")
    parser.add_argument("--javap", default="javap")
    args = parser.parse_args(argv)
    if args.module.count(":") != 1:
        parser.error("--exclude must be org:name")
    if "/" not in args.suite:
        parser.error("--suite must be project-scoped, as in journal-v1/testOnly *Spec")
    return args


def main(argv=None, run=run_command, report=print):
    args = parse_args(argv)
    try:
        org, name = args.module.split(":")
        jar = args.excluded_jar or find_excluded_jar(org, name, args.coursier_cache)
        excluded = class_names(jar)
        if not excluded:
            raise Failure(str(jar) + " contains no classes")
        report("excluded module " + args.module + ": " + str(len(excluded)) + " classes in " + str(jar))
        step_tree(args, run, report)
        callers = step_callers(args, run, report, excluded)
        version, deps = step_pom(args, run, report)
        step_central(args, run, report, version, deps)
        step_runtime(args, run, report, excluded, callers)
    except Failure as failure:
        report("FAIL " + str(failure))
        return 1
    report("OK exclusion of " + args.module + " is safe for " + args.suite
           + ("" if args.resolve_central else " (Maven Central resolution skipped)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
