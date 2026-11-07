#!/usr/bin/env python3
"""Plant one bug at a time in the atom and prove the checks catch it.

A green suite means nothing if it stays green for a broken atom. Each mutation is one textual change to the real
source; the mutated tree is rebuilt and the named check must fail. A survivor is a hole in the suite.

    python3 tools/mutations.py
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent.parent
CODE = "gitee"
SOURCE = HERE / "src" / "main" / "java" / "com" / "jenkinschina" / "atom" / "giteepr" / "GiteePrAtom.java"

MUTATIONS = [
    ("the token goes out in a different header than PRIVATE-TOKEN",
     '.header("PRIVATE-TOKEN", token)', '.header("X-Gitee-Token", token)', "get.lists.pulls"),
    ("the Accept header is dropped", '.header("Accept", "application/json")', '', "get.lists.pulls"),
    ("the form media type is replaced by JSON",
     'static final String FORM = "application/x-www-form-urlencoded";',
     'static final String FORM = "application/json";', "create.orders.get.then.post"),
    ("the query sends head before base (JAX-RS order is base, head)",
     'return "?base=" + pct(base) + "&head=" + pct(head);',
     'return "?head=" + pct(head) + "&base=" + pct(base);', "get.lists.pulls"),
    ("the query encoder leaves '/' bare instead of %2F",
     "|| c == '-' || c == '.' || c == '_' || c == '~') {",
     "|| c == '/' || c == '-' || c == '.' || c == '_' || c == '~') {", "query.encoding"),
    ("create no longer asks whether the pull request already exists",
     'if (!found.isEmpty()) {', 'if (false) {', "create.existing.is.not.a.failure"),
    ("an existing pull request is reported as newly created",
     'result.data("created", "false");', 'result.data("created", "true");',
     "create.existing.is.not.a.failure"),
    ("create sends draft instead of prune_source_branch",
     'fields.put("prune_source_branch", String.valueOf(prune));',
     'fields.put("prune_source_branch", String.valueOf(draft));', "create.orders.get.then.post"),
    ("create forgets the draft flag",
     'fields.put("draft", String.valueOf(draft));', 'fields.put("draft", "false");',
     "create.orders.get.then.post"),
    ("the create read-back is skipped", 'if (match == null) {', 'if (false) {', "create.readback.missing"),
    ("the created title is invented instead of read back",
     'result.data("title", orEmpty(str(match.get("title"))));',
     'result.data("title", "ok");', "create.orders.get.then.post"),
    ("comments are posted to the pull request itself",
     'api + repoPath + "/pulls/" + number + "/comments";',
     'api + repoPath + "/pulls/" + number;', "comment.posts.and.reads.back"),
    ("the comment read-back is skipped", 'if (mine == null) {', 'if (false) {', "comment.readback.missing"),
    ("${VAR} is no longer expanded in the body",
     'String value = context.getParam(name);', 'String value = null;', "comment.posts.and.reads.back"),
    ("the merge uses POST instead of PUT",
     'api0.send("PUT", url, "")', 'api0.send("POST", url, "")', "merge.puts.empty.form.and.reads.state"),
    ("the merge sends a form body the plugin never sends",
     'api0.send("PUT", url, "")', 'api0.send("PUT", url, "force=true")',
     "merge.puts.empty.form.and.reads.state"),
    ("a merge that did not happen is reported as merged",
     'if (!"merged".equals(mergedState) && str(pull.get("merged_at")) == null) {', 'if (false) {',
     "merge.readback.still.open"),
    ("401 is no longer classified as an auth failure",
     'if (status == 401 || status == 403) {', 'if (false) {', "auth.rejected"),
    ("failOnError=false is ignored", 'if (failOnError) {', 'if (true) {', "failonerror.false"),
    ("a create dry run sends anyway",
     'if (dryRun) {\n                    return dry(context, "GET " + listUrl, "POST "',
     'if (false) {\n                    return dry(context, "GET " + listUrl, "POST "',
     "dryrun.sends.nothing"),
    ("a pull request number of zero is accepted for merge",
     'if (("comment".equals(action) || "merge".equals(action)) && number <= 0) {',
     'if (("comment".equals(action) || "merge".equals(action)) && number < 0) {', "input.validation"),
    ("owner may contain a slash, so the path escapes the repository",
     'if (pair[1].isEmpty() || pair[1].contains("/") || pair[1].contains(" ")',
     'if (pair[1].isEmpty()', "input.validation"),
]


def sdk_jar():
    env = os.environ.get("SDK_JAR")
    if env:
        return env
    for root in (HERE.parent / "jenkins-atom-sdk", HERE / "jenkins-atom-sdk"):
        candidates = sorted((root / "target").glob("jenkins-atom-sdk-*.jar"))
        if candidates:
            return str(candidates[-1])
    raise SystemExit("build the SDK first: cd ../jenkins-atom-sdk && mvn -DskipTests package")


def build(tree):
    env = dict(os.environ)
    env["SDK_JAR"] = sdk_jar()
    proc = subprocess.run(["mvn", "-q", "-B", "-ntp", "-DskipTests", "package"], cwd=tree, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        return None, proc.stderr[-1500:]
    return tree / "target" / "dist" / CODE / (CODE + ".jar"), proc.stdout


def main():
    failures = 0
    for index, (name, old, new, case) in enumerate(MUTATIONS, 1):
        tree = pathlib.Path(tempfile.mkdtemp(prefix=CODE + "-mut-")) / "atom"
        shutil.copytree(HERE, tree, ignore=shutil.ignore_patterns("dist", "target", ".git", "__pycache__"))
        source = tree / SOURCE.relative_to(HERE)
        text = source.read_text()
        if old not in text:
            print("SKIP  %s: the mutation no longer matches the source" % name)
            failures += 1
            continue
        source.write_text(text.replace(old, new, 1))
        jar, output = build(tree)
        if jar is None:
            print("SKIP  %s: the mutated atom does not build: %s" % (name, output))
            failures += 1
            continue
        proc = subprocess.run([sys.executable, "tools/check.py", case, "--jar", str(jar)],
                              cwd=tree, capture_output=True, text=True)
        if proc.returncode == 0:
            print("SURVIVED %-2d %s (case %s stayed green)" % (index, name, case))
            failures += 1
        else:
            print("caught   %-2d %s" % (index, name))
    print("\n%d/%d mutations caught" % (len(MUTATIONS) - failures, len(MUTATIONS)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
