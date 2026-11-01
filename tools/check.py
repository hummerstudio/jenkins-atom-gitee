#!/usr/bin/env python3
"""Behaviour checks for the gitee atom.

Every check drives the atom the way the runner does (input.json in, output.json out) against a fake Gitee API v5 that
records the raw request line, headers and body. The expectations are recomputed here from the same inputs, following
the plugin's own shapes: GiteeV5ApiProxy's @Path/@PUT/@POST/@FormParam annotations, the PRIVATE-TOKEN header that
ResteasyGiteeClientBuilder's ApiHeaderTokenFilter sets, the "already exists, do not create" branch of
GiteeCreatePullRequestPublisher#perform, and the form-urlencoded media type JaxrsFormProvider writes.

    python3 tools/check.py                 # every check
    python3 tools/check.py create          # only checks whose name contains "create"
    python3 tools/check.py create --jar X  # run a mutated build
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
JAR = os.path.join(REPO, "dist", "gitee", "gitee.jar")
JAVA = os.path.join(os.environ.get("JAVA_HOME", "/Users/tangming/tools/jdk-21.0.12.1+1/Contents/Home"), "bin", "java")
TASK = json.load(open(os.path.join(REPO, "task.json"), encoding="utf-8"))

TOKEN = "gitee-token-7d1f"
API = "/api/v5"
OWNER = "hummerstudio"
REPO_NAME = "demo"


# --------------------------------------------------------------------------- the runner's contract

def base_input(**overrides):
    data = {name: spec.get("default", "") for name, spec in TASK["input"].items()}
    data.update({"JOB_NAME": "demo", "BUILD_NUMBER": "42", "BUILD_STATUS": "SUCCESS",
                 "GIT_COMMIT": "0f1e2d3c4b5a69788796a5b4c3d2e1f009182736"})
    data.update(overrides)
    data.setdefault("token", TOKEN)
    return data


def run(overrides, timeout=60):
    directory = tempfile.mkdtemp(prefix="gitee-check-")
    data = base_input(**overrides)
    path = os.path.join(directory, "input.json")
    json.dump(data, open(path, "w", encoding="utf-8"), ensure_ascii=False)
    # credentials arrive the way the runner materialises them, not as inputs
    if not data.pop("_no_secret", False):
        json.dump({"token": TOKEN}, open(os.path.join(directory, ".secrets.json"), "w", encoding="utf-8"))
    env = dict(os.environ)
    env.update({"atom_data_dir": directory, "atom_data_input": "input.json", "atom_data_output": "output.json",
                "atom_workspace": directory})
    proc = subprocess.run([JAVA, "-cp", JAR, "com.jenkinschina.atom.sdk.AtomRunner"], cwd=directory, env=env,
                          capture_output=True, text=True, timeout=timeout)
    out = os.path.join(directory, "output.json")
    result = json.load(open(out, encoding="utf-8")) if os.path.exists(out) else {}
    values = {key: node.get("value") for key, node in (result.get("data") or {}).items()}
    return result, values, proc


def expect_error(result, code):
    assert result.get("status") == "error", f"expected an error, got {result.get('status')}: {result.get('message')}"
    assert result.get("errorCode") == code, f"expected error {code}, got {result.get('errorCode')}: {result.get('message')}"


# --------------------------------------------------------------------------- fake Gitee

class Gitee:
    """A Gitee API v5 stand-in: keeps its own state and every request it was sent."""

    def __init__(self):
        self.requests = []
        self.pulls = []
        self.comments = {}
        self.next_number = 7
        self.fail = {}
        self.merge_state = "merged"
        self.drop_comments = False
        outer = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def _read(self):
                length = int(self.headers.get("Content-Length") or 0)
                return self.rfile.read(length).decode("utf-8") if length else ""

            def _answer(self, method):
                body = self._read()
                outer.requests.append({"method": method, "target": self.path, "headers": dict(self.headers),
                                       "body": body})
                status, payload = outer.route(method, self.path, body)
                raw = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else b""
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self):
                self._answer("GET")

            def do_POST(self):
                self._answer("POST")

            def do_PUT(self):
                self._answer("PUT")

            def do_DELETE(self):
                self._answer("DELETE")

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.serveraddress[1] if hasattr(self.server, "serveraddress") else self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    # ---- routing
    def route(self, method, target, body):
        parsed = urllib.parse.urlsplit(target)
        path, query = parsed.path, urllib.parse.parse_qs(parsed.query)

        for key, answer in self.fail.items():
            if target.startswith(key):
                return answer

        if path.startswith(API + "/repos/"):
            rest = path[len(API + "/repos/"):]
            parts = rest.split("/")
            owner, repo = parts[0], parts[1]
            tail = parts[2:]
        else:
            return 404, {"message": "no such endpoint"}

        if owner != OWNER or repo != REPO_NAME:
            return 404, {"message": "repository not found"}

        if tail == ["pulls"] and method == "GET":
            base = (query.get("base") or [""])[0]
            head = (query.get("head") or [""])[0]
            return 200, [p for p in self.pulls if p["base"]["ref"] == base and p["head"]["ref"] == head]

        if tail == ["pulls"] and method == "POST":
            fields = urllib.parse.parse_qs(body, keep_blank_values=True)
            number = self.next_number
            self.next_number += 1
            pull = {"number": number, "iid": number, "title": (fields.get("title") or [""])[0],
                    "state": "open", "html_url": "https://gitee.com/%s/%s/pulls/%d" % (owner, repo, number),
                    "base": {"ref": (fields.get("base") or [""])[0]},
                    "head": {"ref": (fields.get("head") or [""])[0]}}
            self.pulls.append(pull)
            return 201, pull

        if len(tail) == 3 and tail[0] == "pulls" and tail[2] == "comments":
            number = int(tail[1])
            if method == "POST":
                fields = urllib.parse.parse_qs(body, keep_blank_values=True)
                comment = {"id": 900 + len(self.comments.get(number, [])), "body": (fields.get("body") or [""])[0]}
                if not self.drop_comments:
                    self.comments.setdefault(number, []).append(comment)
                return 201, comment
            if method == "GET":
                return 200, self.comments.get(number, [])

        if len(tail) == 3 and tail[0] == "pulls" and tail[2] == "merge" and method == "PUT":
            number = int(tail[1])
            for pull in self.pulls:
                if pull["number"] == number:
                    pull["state"] = self.merge_state
            return 200, {}

        if len(tail) == 2 and tail[0] == "pulls" and method == "GET":
            number = int(tail[1])
            for pull in self.pulls:
                if pull["number"] == number:
                    return 200, pull
            return 404, {"message": "no such pull request"}

        return 404, {"message": "no such endpoint"}

    def close(self):
        self.server.shutdown()
        self.server.server_close()


# --------------------------------------------------------------------------- what the plugin would send

def expect_query(base, head):
    """JAX-RS @QueryParam order is base, head; RFC 3986 unreserved characters stay bare."""
    return "?base=%s&head=%s" % (urllib.parse.quote(base, safe="-._~"), urllib.parse.quote(head, safe="-._~"))


def expect_form(fields):
    """form-urlencoded as java.net.URLEncoder writes it: space -> '+', everything else percent-encoded."""
    return "&".join("%s=%s" % (urllib.parse.quote_plus(k), urllib.parse.quote_plus(v)) for k, v in fields)


def request(server, index=-1):
    assert server.requests, "the atom sent nothing"
    return server.requests[index]


def check_common(req, method, target, body):
    assert req["method"] == method, f"method: got {req['method']}, want {method}"
    assert req["target"] == target, f"target: got {req['target']}\n want {target}"
    assert req["body"] == body, f"body: got {req['body']!r}\n want {body!r}"
    assert req["headers"].get("PRIVATE-TOKEN") == TOKEN, \
        f"PRIVATE-TOKEN header: {req['headers'].get('PRIVATE-TOKEN')!r}"
    assert "application/json" in req["headers"].get("Accept", ""), req["headers"].get("Accept")
    if method != "GET":
        assert req["headers"].get("Content-Type", "").startswith("application/x-www-form-urlencoded"), \
            req["headers"].get("Content-Type")


# --------------------------------------------------------------------------- checks

CHECKS = []


def check(name):
    def register(fn):
        CHECKS.append((name, fn))
        return fn
    return register


@check("get.lists.pulls")
def _():
    server = Gitee()
    try:
        server.pulls = [{"number": 3, "title": "one", "state": "open",
                         "base": {"ref": "master"}, "head": {"ref": "feature"}}]
        result, values, _ = run({"action": "get", "apiUrl": "http://127.0.0.1:%d%s/" % (server.port, API),
                                 "owner": OWNER, "repo": REPO_NAME, "base": "master", "head": "feature"})
        assert result["status"] == "success", result.get("message")
        check_common(request(server), "GET",
                     "%s/repos/%s/%s/pulls%s" % (API, OWNER, REPO_NAME, expect_query("master", "feature")), "")
        assert values["count"] == "1", values
        assert values["number"] == "3", values
        assert json.loads(values["pullRequests"])[0]["title"] == "one"
    finally:
        server.close()


@check("query.encoding")
def _():
    server = Gitee()
    try:
        result, _, _ = run({"action": "get", "apiUrl": "http://127.0.0.1:%d%s" % (server.port, API),
                            "owner": OWNER, "repo": REPO_NAME, "base": "release/2.0", "head": "feature a+b"})
        assert result["status"] == "success", result.get("message")
        want = "%s/repos/%s/%s/pulls%s" % (API, OWNER, REPO_NAME, expect_query("release/2.0", "feature a+b"))
        assert request(server)["target"] == want, request(server)["target"]
        assert "release%2F2.0" in want and "feature%20a%2Bb" in want, want
    finally:
        server.close()


@check("create.orders.get.then.post")
def _():
    server = Gitee()
    try:
        fields = [("title", "准备发布 2.0"), ("base", "master"), ("head", "feature"),
                  ("body", "body 42"), ("prune_source_branch", "false"),
                  ("draft", "true"), ("squash", "false")]
        result, values, _ = run({"action": "create", "apiUrl": "http://127.0.0.1:%d%s" % (server.port, API),
                                 "owner": OWNER, "repo": REPO_NAME, "base": "master", "head": "feature",
                                 "title": "准备发布 2.0", "body": "body ${BUILD_NUMBER}", "draft": "true"})
        assert result["status"] == "success", result.get("message")
        assert [r["method"] for r in server.requests] == ["GET", "POST", "GET"], [r["method"] for r in server.requests]
        check_common(server.requests[0], "GET",
                     "%s/repos/%s/%s/pulls%s" % (API, OWNER, REPO_NAME, expect_query("master", "feature")), "")
        check_common(server.requests[1], "POST", "%s/repos/%s/%s/pulls" % (API, OWNER, REPO_NAME), expect_form(fields))
        assert values["created"] == "true", values
        assert values["number"] == "7", values
        assert values["title"] == "准备发布 2.0", values
        assert values["url"] == "https://gitee.com/%s/%s/pulls/7" % (OWNER, REPO_NAME), values
    finally:
        server.close()


@check("create.existing.is.not.a.failure")
def _():
    server = Gitee()
    try:
        server.pulls = [{"number": 5, "title": "already", "state": "open", "merged_at": None,
                         "base": {"ref": "master"}, "head": {"ref": "feature"}}]
        result, values, _ = run({"action": "create", "apiUrl": "http://127.0.0.1:%d%s" % (server.port, API),
                                 "owner": OWNER, "repo": REPO_NAME, "base": "master", "head": "feature",
                                 "title": "already"})
        assert result["status"] == "success", result.get("message")
        assert [r["method"] for r in server.requests] == ["GET"], [r["method"] for r in server.requests]
        assert values["created"] == "false" and values["number"] == "5", values
    finally:
        server.close()


@check("create.readback.missing")
def _():
    server = Gitee()
    try:
        # the POST is accepted but the pull request never shows up in the list afterwards
        server.fail["%s/repos/%s/%s/pulls?base" % (API, OWNER, REPO_NAME)] = (200, [])
        result, _, _ = run({"action": "create", "apiUrl": "http://127.0.0.1:%d%s" % (server.port, API),
                            "owner": OWNER, "repo": REPO_NAME, "base": "master", "head": "feature",
                            "title": "x"})
        expect_error(result, 40010)
        assert "feature -> master" in result["message"], result["message"]
    finally:
        server.close()


@check("comment.posts.and.reads.back")
def _():
    server = Gitee()
    try:
        server.pulls = [{"number": 9, "title": "t", "state": "open", "base": {"ref": "master"},
                         "head": {"ref": "feature"}}]
        text = "构建 ${BUILD_NUMBER} 通过: a b+c"
        result, values, _ = run({"action": "comment", "apiUrl": "http://127.0.0.1:%d%s" % (server.port, API),
                                 "owner": OWNER, "repo": REPO_NAME, "prNumber": "9", "body": text})
        assert result["status"] == "success", result.get("message")
        want_target = "%s/repos/%s/%s/pulls/9/comments" % (API, OWNER, REPO_NAME)
        check_common(server.requests[0], "POST", want_target, expect_form([("body", "构建 42 通过: a b+c")]))
        assert server.requests[1]["method"] == "GET" and server.requests[1]["target"] == want_target
        assert values["commentId"] == "900", values
    finally:
        server.close()


@check("comment.readback.missing")
def _():
    server = Gitee()
    try:
        server.drop_comments = True
        result, _, _ = run({"action": "comment", "apiUrl": "http://127.0.0.1:%d%s" % (server.port, API),
                            "owner": OWNER, "repo": REPO_NAME, "prNumber": "9", "body": "hi"})
        expect_error(result, 40010)
    finally:
        server.close()


@check("merge.puts.empty.form.and.reads.state")
def _():
    server = Gitee()
    try:
        server.pulls = [{"number": 11, "title": "t", "state": "open", "base": {"ref": "master"},
                         "head": {"ref": "feature"}}]
        result, values, _ = run({"action": "merge", "apiUrl": "http://127.0.0.1:%d%s" % (server.port, API),
                                 "owner": OWNER, "repo": REPO_NAME, "prNumber": "11"})
        assert result["status"] == "success", result.get("message")
        check_common(server.requests[0], "PUT", "%s/repos/%s/%s/pulls/11/merge" % (API, OWNER, REPO_NAME), "")
        assert server.requests[1]["method"] == "GET"
        assert server.requests[1]["target"] == "%s/repos/%s/%s/pulls/11" % (API, OWNER, REPO_NAME)
        assert values["state"] == "merged", values
    finally:
        server.close()


@check("merge.readback.still.open")
def _():
    server = Gitee()
    try:
        server.merge_state = "open"
        server.pulls = [{"number": 11, "title": "t", "state": "open", "base": {"ref": "master"},
                         "head": {"ref": "feature"}}]
        result, _, _ = run({"action": "merge", "apiUrl": "http://127.0.0.1:%d%s" % (server.port, API),
                            "owner": OWNER, "repo": REPO_NAME, "prNumber": "11"})
        expect_error(result, 40010)
        assert "not merged" in result["message"], result["message"]
    finally:
        server.close()


@check("auth.rejected")
def _():
    server = Gitee()
    try:
        server.fail["%s/repos" % API] = (401, {"message": "401 Unauthorized: Access token is invalid"})
        result, _, _ = run({"action": "get", "apiUrl": "http://127.0.0.1:%d%s" % (server.port, API),
                            "owner": OWNER, "repo": REPO_NAME, "base": "master", "head": "f"})
        expect_error(result, 40007)
        assert "401" in result["message"]
    finally:
        server.close()


@check("failonerror.false")
def _():
    server = Gitee()
    try:
        server.fail["%s/repos" % API] = (500, {"message": "boom"})
        result, values, _ = run({"action": "get", "apiUrl": "http://127.0.0.1:%d%s" % (server.port, API),
                                 "owner": OWNER, "repo": REPO_NAME, "base": "master", "head": "f",
                                 "failOnError": "false"})
        assert result["status"] == "success", result.get("message")
        assert values["ok"] == "false" and values["status"] == "500", values
        assert "boom" in result["message"]
    finally:
        server.close()


@check("dryrun.sends.nothing")
def _():
    server = Gitee()
    try:
        result, values, _ = run({"action": "create", "apiUrl": "http://127.0.0.1:%d%s" % (server.port, API),
                                 "owner": OWNER, "repo": REPO_NAME, "base": "master", "head": "feature",
                                 "title": "t", "dryRun": "true"})
        assert result["status"] == "success", result.get("message")
        assert server.requests == [], server.requests
        assert values["dryRun"] == "true"
        assert "POST" in values["request"] and "prune_source_branch=false" in values["request"], values["request"]
    finally:
        server.close()


@check("input.validation")
def _():
    cases = [
        ({"action": "purge", "owner": OWNER, "repo": REPO_NAME}, 40001, "action must be"),
        ({"action": "get", "owner": "a/b", "repo": REPO_NAME, "base": "m", "head": "f"}, 40001, "owner must be"),
        ({"action": "get", "owner": OWNER, "repo": REPO_NAME, "base": "", "head": "f"}, 40001, "base"),
        ({"action": "get", "owner": OWNER, "repo": REPO_NAME, "base": "m", "head": ""}, 40001, "head"),
        ({"action": "comment", "owner": OWNER, "repo": REPO_NAME, "prNumber": "", "body": "x"}, 40001, "prNumber"),
        ({"action": "comment", "owner": OWNER, "repo": REPO_NAME, "prNumber": "4", "body": ""}, 40001, "body"),
        ({"action": "merge", "owner": OWNER, "repo": REPO_NAME, "prNumber": "0"}, 40001, "prNumber"),
        ({"action": "get", "owner": OWNER, "repo": REPO_NAME, "base": "m", "head": "f", "apiUrl": "gitee.com"}, 40001,
         "absolute http"),
        ({"action": "get", "owner": OWNER, "repo": REPO_NAME, "base": "m", "head": "f", "token": "",
          "_no_secret": True}, 40002, "token"),
    ]
    for overrides, code, needle in cases:
        overrides = dict(overrides, apiUrl=overrides.get("apiUrl", "http://127.0.0.1:9/api/v5"))
        result, _, _ = run(overrides)
        expect_error(result, code)
        assert needle in result["message"], (overrides, result["message"])


@check("unreachable.host")
def _():
    result, _, _ = run({"action": "get", "apiUrl": "http://127.0.0.1:9/api/v5",
                        "owner": OWNER, "repo": REPO_NAME, "base": "master", "head": "feature"})
    expect_error(result, 40009)
    assert "cannot reach Gitee" in result["message"], result["message"]


def main():
    global JAR
    args = sys.argv[1:]
    if "--jar" in args:
        at = args.index("--jar")
        JAR = args[at + 1]
        del args[at:at + 2]
    needle = args[0] if args else ""
    failures = 0
    for name, fn in CHECKS:
        if needle and needle not in name:
            continue
        try:
            fn()
            print("ok   " + name)
        except AssertionError as error:
            failures += 1
            print("FAIL " + name + ": " + str(error))
        except Exception as error:  # noqa: BLE001 - a broken check must not stop the rest
            failures += 1
            print("ERROR " + name + ": %r" % (error,))
    total = len([1 for name, _ in CHECKS if not needle or needle in name])
    print("\n%d/%d checks passed" % (total - failures, total))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
