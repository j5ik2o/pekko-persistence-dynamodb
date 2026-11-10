#!/usr/bin/env python3
"""Read-only workflow evidence collection and deliberately limited offline analysis."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timezone


def now():
    return datetime.now(timezone.utc).isoformat()


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


class Collector:
    def __init__(self, out):
        self.out = out
        self.sources = []
        self.unknowns = []
        self.failed = False

    def get(self, endpoint, allow_404=False):
        index = len(self.sources)
        stem = f"api-{index:03}"
        args = ["gh", "api", "--method", "GET", endpoint]
        started = now()
        result = subprocess.run(args, capture_output=True, check=False)
        (self.out / (stem + ".stdout")).write_bytes(result.stdout)
        (self.out / (stem + ".stderr")).write_bytes(result.stderr)
        source = dict(api=endpoint, args=args, started_at=started, finished_at=now(),
                      exit_code=result.returncode, stdout=stem + ".stdout",
                      stderr=stem + ".stderr", sha256=digest(result.stdout))
        self.sources.append(source)
        try:
            value = json.loads(result.stdout)
        except (ValueError, UnicodeError):
            value = None
        if result.returncode or not isinstance(value, dict):
            managed = allow_404 and isinstance(value, dict) and value.get("status") == "404"
            self.unknowns.append(dict(kind="managed_workflow_404" if managed else "collection_failure",
                                      source=index, api=endpoint))
            self.failed |= not managed
            return None
        return value

    def inventory(self, repo, revision, states, label):
        tree = self.get(f"repos/{repo}/git/trees/{revision}?recursive=1")
        if not isinstance(tree, dict) or not isinstance(tree.get("tree"), list):
            self.failed = True
            self.unknowns.append(dict(kind="missing_inventory", inventory=label))
            return []
        if tree.get("truncated"):
            self.failed = True
            self.unknowns.append(dict(kind="truncated_inventory", inventory=label))
        workflows = []
        for item in tree["tree"]:
            path = item["path"]
            if (item.get("type") != "blob" or not path.startswith(".github/workflows/")
                    or path.count("/") != 2 or not path.endswith((".yml", ".yaml"))):
                continue
            blob = self.get(f"repos/{repo}/git/blobs/{item['sha']}")
            entry = dict(path=path, git_blob_sha=item["sha"], source_revision=revision,
                         state=states.get(path, "unknown"), raw_file=None,
                         source_api=f"repos/{repo}/git/blobs/{item['sha']}",
                         source_index=len(self.sources) - 1)
            try:
                raw = base64.b64decode("".join(blob["content"].split()), validate=True)
                if blob.get("encoding") != "base64":
                    raise ValueError("unsupported blob encoding")
                filename = f"{label}-{len(workflows):03}.yaml"
                (self.out / filename).write_bytes(raw)
                entry.update(raw_file=filename, raw_sha256=digest(raw))
            except (TypeError, KeyError, ValueError):
                self.failed = True
                self.unknowns.append(dict(kind="missing_definition", path=path))
            workflows.append(entry)
        return workflows


def collect(args):
    out = args.out.resolve()
    root = Path(__file__).resolve().parents[1]
    if out == root or (root in out.parents and root / "target" not in out.parents):
        raise ValueError("Store raw evidence under target/ or outside the repository")
    out.mkdir(parents=True, exist_ok=False)
    c = Collector(out)
    prefix = f"repos/{args.repo}"

    def observe():
        return dict(branch=c.get(f"{prefix}/branches/{args.branch}"),
                    pr=c.get(f"{prefix}/pulls/{args.pr}") if args.pr else None,
                    workflows=c.get(f"{prefix}/actions/workflows?per_page=100"))

    before = observe()
    branch = before["branch"] or {}
    base = branch.get("commit", {}).get("sha")
    pr = before["pr"] or {}
    head = args.head or pr.get("head", {}).get("sha") or base
    if args.pr and pr.get("base", {}).get("ref") != args.branch:
        c.failed = True
        c.unknowns.append(dict(kind="pr_destination_mismatch"))
    listing = before["workflows"] or {}
    if not isinstance(listing.get("workflows"), list) or any(
            not isinstance(w, dict) or not isinstance(w.get("path"), str)
            for w in listing.get("workflows", [])):
        c.failed = True
        c.unknowns.append(dict(kind="invalid_workflow_state_inventory"))
        listing = {"workflows": []}
    states = {w["path"]: w.get("state", "unknown") for w in listing.get("workflows", [])}
    if listing.get("total_count", 0) > 100:
        c.failed = True
        c.unknowns.append(dict(kind="workflow_state_pagination_unsupported"))
    inventories = {}
    for label, revision in (("base", base), ("head", head)):
        if not revision:
            c.failed = True
            c.unknowns.append(dict(kind="missing_revision", inventory=label))
        else:
            inventories[label] = c.inventory(args.repo, revision, states, label)
    paths = {w["path"] for w in inventories.get("base", [])}
    for path in states.keys() - paths:
        c.get(f"{prefix}/contents/{path}?ref={base}", allow_404=True)
        c.unknowns.append(dict(kind="api_workflow_without_repository_definition", path=path,
                              state=states[path]))
    c.get(f"{prefix}/actions/permissions")
    c.get(f"{prefix}/actions/permissions/workflow")
    after = observe()
    # Compare the identities and workflow states, not unrelated volatile PR fields.
    def identities(obs):
        p = obs["pr"] or {}
        b = obs["branch"] or {}
        w = obs["workflows"] or {}
        return (b.get("commit", {}).get("sha"), p.get("base", {}).get("sha"),
                p.get("head", {}).get("sha"), p.get("state"),
                sorted((x["path"], x.get("state")) for x in w.get("workflows", [])
                       if isinstance(x, dict) and isinstance(x.get("path"), str)))
    if identities(before) != identities(after):
        c.unknowns.append(dict(kind="observation_drift"))
    snapshot = dict(format_version=1, repository=args.repo, destination_branch=args.branch,
                    observed_base_sha=base, observed_head_sha=head, pr=args.pr,
                    observed_pr_base_sha=pr.get("base", {}).get("sha"),
                    started_at=c.sources[0]["started_at"], finished_at=now(),
                    prospective_tree_verified=False, inventories=inventories,
                    unknowns=c.unknowns, sources=c.sources,
                    collection_complete=not c.failed)
    save(out / "snapshot.json", snapshot)
    report = analyze(out / "snapshot.json")
    save(out / "analysis.json", report)
    print(json.dumps(dict(snapshot=str(out / "snapshot.json"), unknowns=report["unknowns"],
                          evidence_saved=not c.failed), indent=2))
    return 1 if c.failed or report["parse_failures"] else 0


def load_yaml(raw):
    try:
        import yaml
    except ImportError as exc:
        raise ValueError("PyYAML required: use target/docs-venv/bin/python; no parser fallback") from exc

    class UniqueLoader(yaml.BaseLoader):
        def construct_mapping(self, node, deep=False):
            result = {}
            for key_node, value_node in node.value:
                key = self.construct_object(key_node, deep=deep)
                if not isinstance(key, str) or key in result:
                    raise ValueError("duplicate or non-string YAML key")
                result[key] = self.construct_object(value_node, deep=deep)
            return result

    try:
        value = yaml.load(raw, Loader=UniqueLoader)
    except yaml.YAMLError as exc:
        raise ValueError(f"invalid YAML: {exc}") from exc

    def check(node, ancestors):
        if isinstance(node, (list, dict)):
            if id(node) in ancestors:
                raise ValueError("cyclic YAML")
            for child in (node.values() if isinstance(node, dict) else node):
                check(child, ancestors | {id(node)})
    check(value, set())
    if not isinstance(value, dict) or "on" not in value or not isinstance(value.get("jobs"), dict):
        raise ValueError("workflow requires on and a jobs mapping")
    events = value["on"]
    if not ((isinstance(events, str) and events)
            or (isinstance(events, list) and events and all(isinstance(x, str) for x in events))
            or (isinstance(events, dict) and events and all(
                isinstance(x, dict) or x == "" or (k == "schedule" and isinstance(x, list)
                and all(isinstance(item, dict) and isinstance(item.get("cron"), str) for item in x))
                for k, x in events.items()))):
        raise ValueError("invalid on shape")
    for job in value["jobs"].values():
        if not isinstance(job, dict):
            raise ValueError("job must be a mapping")
        if "steps" in job and (not isinstance(job["steps"], list)
                               or any(not isinstance(s, dict) for s in job["steps"])):
            raise ValueError("steps must be a list of mappings")
        for key in ("uses", "if"):
            if key in job and not isinstance(job[key], str):
                raise ValueError(f"job {key} must be a string")
        if "strategy" in job and not isinstance(job["strategy"], dict):
            raise ValueError("strategy must be a mapping")
        for step in job.get("steps", []):
            for key in ("uses", "run", "if"):
                if key in step and not isinstance(step[key], str):
                    raise ValueError(f"step {key} must be a string")
    return value


def push_trigger(config, branch):
    evidence = dict(pointer="/on", raw=config)
    if isinstance(config, str):
        events = {config: ""}
    elif isinstance(config, list) and all(isinstance(x, str) for x in config):
        events = {x: "" for x in config}
    elif isinstance(config, dict):
        events = config
    else:
        return dict(status="unknown", reason="invalid on shape", **evidence)
    if "push" not in events:
        return dict(status="excluded", reason="no push event", **evidence)
    push = events["push"]
    if push == "":
        return dict(status="potential", reason="unfiltered push", **evidence)
    if not isinstance(push, dict):
        return dict(status="unknown", reason="invalid push shape", **evidence)
    if any(key not in {"branches", "tags"} for key in push):
        return dict(status="unknown", reason="unsupported push filter, including paths", **evidence)
    for values in push.values():
        if not isinstance(values, list) or not values or any(not isinstance(x, str) for x in values):
            return dict(status="unknown", reason="invalid filter shape", **evidence)
    if "branches" not in push:
        return dict(status="excluded" if "tags" in push else "potential",
                    reason="tags-only" if "tags" in push else "unfiltered push", **evidence)
    if any(any(c in x for c in "*?![+${}") for x in push["branches"]):
        return dict(status="unknown", reason="glob, negation or expression unevaluated", **evidence)
    return dict(status="potential" if branch in push["branches"] else "excluded",
                reason="literal branch comparison", **evidence)


def strings(value, pointer=""):
    if isinstance(value, dict):
        for key, child in value.items():
            yield from strings(child, pointer + "/" + key.replace("~", "~0").replace("/", "~1"))
    elif isinstance(value, list):
        for i, child in enumerate(value):
            yield from strings(child, pointer + "/" + str(i))
    elif isinstance(value, str):
        yield pointer, value


def analyze(path):
    snapshot = json.loads(path.read_text())
    if not isinstance(snapshot, dict):
        raise ValueError("snapshot must be a mapping")
    inventories = snapshot.get("inventories", {})
    if not isinstance(inventories, dict) or any(
            not isinstance(items, list) or any(not isinstance(w, dict) or "path" not in w for w in items)
            for items in inventories.values()):
        raise ValueError("inventories must map names to workflow lists")
    report = dict(repository=snapshot.get("repository"), destination_branch=snapshot.get("destination_branch"),
                  prospective_tree_verified=False, workflows=[], indirect_candidates=[],
                  unknowns=list(snapshot.get("unknowns", [])), parse_failures=False, collection_complete=snapshot.get("collection_complete") is True)
    report["unknowns"].append(dict(kind="prospective_tree_unverified",
                                   reason="Candidate head is not a verified prospective squash tree"))
    if snapshot.get("collection_complete") is not True or not snapshot.get("inventories"):
        report["unknowns"].append(dict(kind="missing_collection"))
    inventories = snapshot.get("inventories", {})
    base = {w["path"]: w.get("git_blob_sha") for w in inventories.get("base", [])}
    head = {w["path"]: w.get("git_blob_sha") for w in inventories.get("head", [])}
    if base != head:
        report["unknowns"].append(dict(kind="changed_workflow_inventory"))
    for inventory, workflows in inventories.items():
        for workflow in workflows:
            entry = dict(inventory=inventory, **workflow, effect_candidates=[], unknowns=[])
            report["workflows"].append(entry)
            try:
                raw_path = (path.parent / workflow["raw_file"]).resolve()
                if path.parent.resolve() not in raw_path.parents:
                    raise ValueError("raw reference must stay in snapshot directory")
                raw = raw_path.read_bytes()
                if digest(raw) != workflow["raw_sha256"]:
                    raise ValueError("raw SHA256 mismatch")
                config = load_yaml(raw)
            except (OSError, ValueError, TypeError, KeyError, RecursionError) as exc:
                entry["trigger"] = dict(status="unknown", reason=str(exc))
                entry["unknowns"].append(dict(kind="definition_parse_failure", reason=str(exc)))
                report["parse_failures"] = True
                continue
            entry["trigger"] = push_trigger(config["on"], snapshot.get("destination_branch"))
            if workflow.get("state") != "active":
                entry["unknowns"].append(dict(kind="workflow_state", observed=workflow.get("state")))
            entry["conditions"] = {"on": config["on"], "jobs": {
                name: {k: job[k] for k in ("if", "strategy", "needs", "environment") if k in job}
                for name, job in config["jobs"].items()}}
            if isinstance(config["on"], dict) and "workflow_run" in config["on"]:
                report["indirect_candidates"].append(dict(inventory=inventory, path=workflow["path"],
                    pointer="/on/workflow_run", raw=config["on"]["workflow_run"],
                    reason="Upstream names, branches and types are not a push execution guarantee"))
            for name, job in config["jobs"].items():
                for key in ("if", "strategy", "needs", "environment"):
                    if key in job:
                        entry["unknowns"].append(dict(kind="unevaluated_job_condition",
                            pointer="/jobs/" + name.replace("~", "~0").replace("/", "~1") + "/" + key,
                            raw=job[key]))
            for pointer, value in strings(config):
                for kind, pattern in (("publish", r"publish|ci-release|sonatype"),
                                      ("tag_release", r"git\s+tag|gh\s+release|release"),
                                      ("deploy", r"deploy")):
                    if re.search(pattern, value, re.IGNORECASE):
                        entry["effect_candidates"].append(dict(kind=kind, pointer=pointer, raw=value,
                            conditions="See workflow conditions and raw job/step if; all unevaluated"))
                if pointer.endswith("/uses") or pointer.endswith("/run"):
                    kind = "run_or_local_script" if pointer.endswith("/run") else "external_action"
                    if pointer.endswith("/uses") and value.startswith("./"):
                        kind = "local_action"
                    if pointer.endswith("/uses") and pointer.count("/") == 3:
                        kind = "unexpanded_reusable_workflow"
                    entry["unknowns"].append(dict(kind=kind, pointer=pointer, raw=value))
                if "${{" in value or pointer.endswith(("/if", "/matrix")):
                    entry["unknowns"].append(dict(kind="unevaluated_condition", pointer=pointer, raw=value))
            if entry["trigger"]["status"] == "unknown":
                entry["unknowns"].append(dict(kind="trigger_unknown", **entry["trigger"]))
    report["unknowns"].extend(dict(inventory=w["inventory"], path=w["path"], **u)
                              for w in report["workflows"] for u in w["unknowns"])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    collect_parser = sub.add_parser("collect")
    collect_parser.add_argument("--repo", required=True)
    collect_parser.add_argument("--branch", default="main")
    mode = collect_parser.add_mutually_exclusive_group()
    mode.add_argument("--pr", type=int)
    mode.add_argument("--head", help="Explicit candidate revision; default is observed branch SHA")
    collect_parser.add_argument("--out", required=True, type=Path)
    offline = sub.add_parser("analyze")
    offline.add_argument("snapshot", type=Path)
    args = parser.parse_args()
    if args.command == "collect":
        if not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repo) or not re.fullmatch(r"[\w./-]+", args.branch):
            parser.error("Invalid repository or branch")
        if args.head and not re.fullmatch(r"[0-9a-fA-F]{40}", args.head):
            parser.error("--head requires a full commit SHA")
    try:
        if args.command == "collect":
            return collect(args)
        report = analyze(args.snapshot)
        print(json.dumps(report, indent=2, ensure_ascii=False))
        return 1 if report["parse_failures"] or not report["collection_complete"] else 0
    except (OSError, ValueError, KeyError, TypeError, RecursionError) as exc:
        print(f"landing-effects: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
