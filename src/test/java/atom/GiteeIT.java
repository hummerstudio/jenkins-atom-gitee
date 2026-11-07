package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gitee atom against a fake Gitee API v5 that keeps its own state and records every request.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made through {@code tools/check.py}: the GET-then-POST order,
 * the query and form encodings, the PRIVATE-TOKEN header, "already exists is not a failure", the read-backs, and the
 * refusals that must happen before the network is touched.
 */
class GiteeIT {

    private static final String TOKEN = "gitee-token-7d1f";
    private static final String API = "/api/v5";
    private static final String OWNER = "hummerstudio";
    private static final String REPO = "demo";

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    /** {@code task.json}'s input defaults — the fields the runner fills in before the job's own inputs. */
    private static final Map<String, String> DEFAULTS = defaults();

    private static Map<String, String> defaults() {
        try {
            Path module = Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
            Map<String, Object> spec = Json.parseObject(Files.readString(module.resolve("task.json")));
            Map<String, String> values = new LinkedHashMap<>();
            ((Map<?, ?>) spec.get("input")).forEach((name, node) -> {
                Object value = ((Map<?, ?>) node).get("default");
                values.put(String.valueOf(name), value == null ? "" : String.valueOf(value));
            });
            return Map.copyOf(values);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("get lists the pulls for base and head")
    void listsPullRequests() throws Exception {
        try (GiteeApi api = GiteeApi.start()) {
            api.pulls.add(pull(3, "one", "open", "master", "feature"));

            AtomResult run = run(Map.of("action", "get", "apiUrl", api.url(), "owner", OWNER, "repo", REPO,
                    "base", "master", "head", "feature"), true);

            ok(run);
            api.common(api.request(0), "GET",
                    API + "/repos/" + OWNER + "/" + REPO + "/pulls" + query("master", "feature"), "");
            assertEquals("1", data(run, "count"));
            assertEquals("3", data(run, "number"));
            assertEquals("one", parseList(data(run, "pullRequests")).get(0).get("title"));
        }
    }

    @Test
    @DisplayName("query values are RFC 3986 encoded: slashes and pluses survive as escapes")
    void encodesTheQuery() throws Exception {
        try (GiteeApi api = GiteeApi.start()) {
            AtomResult run = run(Map.of("action", "get", "apiUrl", api.url(), "owner", OWNER, "repo", REPO,
                    "base", "release/2.0", "head", "feature a+b"), true);

            ok(run);
            String want = API + "/repos/" + OWNER + "/" + REPO + "/pulls" + query("release/2.0", "feature a+b");
            assertEquals(want, api.request(0).target);
            assertTrue(want.contains("release%2F2.0"), want);
            assertTrue(want.contains("feature%20a%2Bb"), want);
        }
    }

    @Test
    @DisplayName("create looks first, then posts the form, then reads the new pull request back")
    void createsAPullRequest() throws Exception {
        try (GiteeApi api = GiteeApi.start()) {
            List<String[]> fields = List.of(
                    new String[] {"title", "准备发布 2.0"},
                    new String[] {"base", "master"},
                    new String[] {"head", "feature"},
                    new String[] {"body", "body 42"},
                    new String[] {"prune_source_branch", "false"},
                    new String[] {"draft", "true"},
                    new String[] {"squash", "false"});

            AtomResult run = run(Map.of("action", "create", "apiUrl", api.url(), "owner", OWNER, "repo", REPO,
                    "base", "master", "head", "feature", "title", "准备发布 2.0",
                    "body", "body ${BUILD_NUMBER}", "draft", "true"), true);

            ok(run);
            assertEquals(List.of("GET", "POST", "GET"), api.methods());
            api.common(api.request(0), "GET",
                    API + "/repos/" + OWNER + "/" + REPO + "/pulls" + query("master", "feature"), "");
            api.common(api.request(1), "POST", API + "/repos/" + OWNER + "/" + REPO + "/pulls", form(fields));
            assertEquals("true", data(run, "created"));
            assertEquals("7", data(run, "number"));
            assertEquals("准备发布 2.0", data(run, "title"));
            assertEquals("https://gitee.com/" + OWNER + "/" + REPO + "/pulls/7", data(run, "url"));
        }
    }

    @Test
    @DisplayName("an existing pull request for the same base and head is not a failure")
    void treatsAnExistingPullRequestAsSuccess() throws Exception {
        try (GiteeApi api = GiteeApi.start()) {
            api.pulls.add(pull(5, "already", "open", "master", "feature"));

            AtomResult run = run(Map.of("action", "create", "apiUrl", api.url(), "owner", OWNER, "repo", REPO,
                    "base", "master", "head", "feature", "title", "already"), true);

            ok(run);
            assertEquals(List.of("GET"), api.methods());
            assertEquals("false", data(run, "created"));
            assertEquals("5", data(run, "number"));
        }
    }

    @Test
    @DisplayName("a created pull request that does not show up in the read-back is an error")
    void refusesAMissingCreateReadBack() throws Exception {
        try (GiteeApi api = GiteeApi.start()) {
            api.fails.put(API + "/repos/" + OWNER + "/" + REPO + "/pulls?base", new Response(200, List.of()));

            AtomResult run = run(Map.of("action", "create", "apiUrl", api.url(), "owner", OWNER, "repo", REPO,
                    "base", "master", "head", "feature", "title", "x"), true);

            error(run, 40010);
            assertTrue(run.field("message").contains("feature -> master"), run.field("message"));
        }
    }

    @Test
    @DisplayName("a comment is posted as a form and read back")
    void postsAComment() throws Exception {
        try (GiteeApi api = GiteeApi.start()) {
            api.pulls.add(pull(9, "t", "open", "master", "feature"));

            AtomResult run = run(Map.of("action", "comment", "apiUrl", api.url(), "owner", OWNER, "repo", REPO,
                    "prNumber", "9", "body", "构建 ${BUILD_NUMBER} 通过: a b+c"), true);

            ok(run);
            String target = API + "/repos/" + OWNER + "/" + REPO + "/pulls/9/comments";
            api.common(api.request(0), "POST", target,
                    form(List.<String[]>of(new String[] {"body", "构建 42 通过: a b+c"})));
            assertEquals("GET", api.request(1).method);
            assertEquals(target, api.request(1).target);
            assertEquals("900", data(run, "commentId"));
        }
    }

    @Test
    @DisplayName("a comment that the read-back cannot find is an error")
    void refusesAMissingCommentReadBack() throws Exception {
        try (GiteeApi api = GiteeApi.start()) {
            api.dropComments = true;

            AtomResult run = run(Map.of("action", "comment", "apiUrl", api.url(), "owner", OWNER, "repo", REPO,
                    "prNumber", "9", "body", "hi"), true);

            error(run, 40010);
        }
    }

    @Test
    @DisplayName("merge puts an empty form and reads the state back")
    void merges() throws Exception {
        try (GiteeApi api = GiteeApi.start()) {
            api.pulls.add(pull(11, "t", "open", "master", "feature"));

            AtomResult run = run(Map.of("action", "merge", "apiUrl", api.url(), "owner", OWNER, "repo", REPO,
                    "prNumber", "11"), true);

            ok(run);
            api.common(api.request(0), "PUT", API + "/repos/" + OWNER + "/" + REPO + "/pulls/11/merge", "");
            assertEquals("GET", api.request(1).method);
            assertEquals(API + "/repos/" + OWNER + "/" + REPO + "/pulls/11", api.request(1).target);
            assertEquals("merged", data(run, "state"));
        }
    }

    @Test
    @DisplayName("a merge that leaves the pull request open is an error")
    void refusesAMergeThatDidNotHappen() throws Exception {
        try (GiteeApi api = GiteeApi.start()) {
            api.mergeState = "open";
            api.pulls.add(pull(11, "t", "open", "master", "feature"));

            AtomResult run = run(Map.of("action", "merge", "apiUrl", api.url(), "owner", OWNER, "repo", REPO,
                    "prNumber", "11"), true);

            error(run, 40010);
            assertTrue(run.field("message").contains("not merged"), run.field("message"));
        }
    }

    @Test
    @DisplayName("a 401 from Gitee is reported with its status")
    void reportsAnAuthRejection() throws Exception {
        try (GiteeApi api = GiteeApi.start()) {
            api.fails.put(API + "/repos",
                    new Response(401, Map.of("message", "401 Unauthorized: Access token is invalid")));

            AtomResult run = run(Map.of("action", "get", "apiUrl", api.url(), "owner", OWNER, "repo", REPO,
                    "base", "master", "head", "f"), true);

            error(run, 40007);
            assertTrue(run.field("message").contains("401"), run.field("message"));
        }
    }

    @Test
    @DisplayName("failOnError=false reports the status without failing the step")
    void honoursFailOnErrorFalse() throws Exception {
        try (GiteeApi api = GiteeApi.start()) {
            api.fails.put(API + "/repos", new Response(500, Map.of("message", "boom")));

            AtomResult run = run(Map.of("action", "get", "apiUrl", api.url(), "owner", OWNER, "repo", REPO,
                    "base", "master", "head", "f", "failOnError", "false"), true);

            ok(run);
            assertEquals("false", data(run, "ok"));
            assertEquals("500", data(run, "status"));
            assertTrue(run.field("message").contains("boom"), run.field("message"));
        }
    }

    @Test
    @DisplayName("dry run builds the request and sends nothing")
    void dryRunSendsNothing() throws Exception {
        try (GiteeApi api = GiteeApi.start()) {
            AtomResult run = run(Map.of("action", "create", "apiUrl", api.url(), "owner", OWNER, "repo", REPO,
                    "base", "master", "head", "feature", "title", "t", "dryRun", "true"), true);

            ok(run);
            assertTrue(api.requests.isEmpty(), "dry run must not send anything: " + api.methods());
            assertEquals("true", data(run, "dryRun"));
            assertTrue(data(run, "request").contains("POST"), data(run, "request"));
            assertTrue(data(run, "request").contains("prune_source_branch=false"), data(run, "request"));
        }
    }

    @Test
    @DisplayName("input validation names the field it refused")
    void validatesInput() throws Exception {
        record Case(Map<String, String> overrides, int code, String needle) {}

        String unreachable = "http://127.0.0.1:9/api/v5";
        List<Case> cases = List.of(
                new Case(Map.of("action", "purge", "owner", OWNER, "repo", REPO), 40001, "action must be"),
                new Case(Map.of("action", "get", "owner", "a/b", "repo", REPO, "base", "m", "head", "f"),
                        40001, "owner must be"),
                new Case(Map.of("action", "get", "owner", OWNER, "repo", REPO, "base", "", "head", "f"), 40001, "base"),
                new Case(Map.of("action", "get", "owner", OWNER, "repo", REPO, "base", "m", "head", ""), 40001, "head"),
                new Case(Map.of("action", "comment", "owner", OWNER, "repo", REPO, "prNumber", "", "body", "x"),
                        40001, "prNumber"),
                new Case(Map.of("action", "comment", "owner", OWNER, "repo", REPO, "prNumber", "4", "body", ""),
                        40001, "body"),
                new Case(Map.of("action", "merge", "owner", OWNER, "repo", REPO, "prNumber", "0"), 40001, "prNumber"),
                new Case(Map.of("action", "get", "owner", OWNER, "repo", REPO, "base", "m", "head", "f",
                        "apiUrl", "gitee.com"), 40001, "absolute http"));

        for (Case one : cases) {
            Map<String, String> overrides = new LinkedHashMap<>(one.overrides());
            overrides.putIfAbsent("apiUrl", unreachable);
            AtomResult run = run(overrides, true);
            error(run, one.code());
            assertTrue(run.field("message").contains(one.needle()),
                    one.overrides() + ": " + run.field("message"));
        }

        AtomResult noToken = runOn(Atom.of(), Map.of("action", "get", "owner", OWNER, "repo", REPO,
                "base", "m", "head", "f", "apiUrl", unreachable, "token", ""), false);
        error(noToken, 40002);
        assertTrue(noToken.field("message").contains("token"), noToken.field("message"));
    }

    @Test
    @DisplayName("an unreachable host is reported as such")
    void reportsAnUnreachableHost() throws Exception {
        AtomResult run = run(Map.of("action", "get", "apiUrl", "http://127.0.0.1:9/api/v5", "owner", OWNER,
                "repo", REPO, "base", "master", "head", "feature"), true);

        error(run, 40009);
        assertTrue(run.field("message").contains("cannot reach Gitee"), run.field("message"));
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private AtomResult run(Map<String, String> overrides, boolean withSecret) throws Exception {
        return runOn(atom, overrides, withSecret);
    }

    private static AtomResult runOn(Atom target, Map<String, String> overrides, boolean withSecret) throws Exception {
        Map<String, String> input = new LinkedHashMap<>(DEFAULTS);
        input.put("JOB_NAME", "demo");
        input.put("BUILD_NUMBER", "42");
        input.put("BUILD_STATUS", "SUCCESS");
        input.put("GIT_COMMIT", "0f1e2d3c4b5a69788796a5b4c3d2e1f009182736");
        input.putAll(overrides);
        Atom prepared = withSecret
                ? target.file(".secrets.json", Json.write(Map.of("token", TOKEN)))
                : target;
        return prepared.input(Json.write(input)).run();
    }

    private static void ok(AtomResult run) {
        assertEquals("success", run.status(), run.file("output.json") + "\nconsole:\n" + run.console());
        run.assertOk();
    }

    private static void error(AtomResult run, int code) {
        assertEquals("error", run.status(), "expected an error, got " + run.status() + ": " + run.field("message"));
        run.assertRefused();
        assertEquals(String.valueOf(code), run.field("errorCode"),
                "expected error " + code + ", got " + run.field("errorCode") + ": " + run.field("message"));
    }

    private static String data(AtomResult run, String key) {
        Object data = Json.parseObject(run.file("output.json")).get("data");
        assertInstanceOf(Map.class, data, "output.json:\n" + run.file("output.json"));
        Object node = ((Map<?, ?>) data).get(key);
        assertInstanceOf(Map.class, node, "no output '" + key + "'");
        return String.valueOf(((Map<?, ?>) node).get("value"));
    }

    private static List<Map<String, Object>> parseList(String json) {
        Object parsed = Json.parse(json);
        assertInstanceOf(List.class, parsed, "not a JSON array: " + json);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : (List<?>) parsed) {
            items.add(Json.parseObject(Json.write(item)));
        }
        return items;
    }

    private static Map<String, Object> pull(int number, String title, String state, String base, String head) {
        Map<String, Object> pull = new LinkedHashMap<>();
        pull.put("number", number);
        pull.put("title", title);
        pull.put("state", state);
        pull.put("base", Map.of("ref", base));
        pull.put("head", Map.of("ref", head));
        return pull;
    }

    /** {@code @QueryParam} order is base, head; RFC 3986 unreserved characters stay bare. */
    private static String query(String base, String head) {
        return "?base=" + queryValue(base) + "&head=" + queryValue(head);
    }

    private static String queryValue(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }

    /** form-urlencoded the way {@code java.net.URLEncoder} writes it: space is '+'. */
    private static String form(List<String[]> fields) {
        List<String> parts = new ArrayList<>();
        for (String[] field : fields) {
            parts.add(java.net.URLEncoder.encode(field[0], StandardCharsets.UTF_8) + "="
                    + java.net.URLEncoder.encode(field[1], StandardCharsets.UTF_8));
        }
        return String.join("&", parts);
    }

    /** One recorded request. */
    private record Record(String method, String target, Map<String, String> headers, String body) {}

    private record Response(int status, Object payload) {}

    /** A Gitee API v5 stand-in: keeps its own state and every request it was sent. */
    private static final class GiteeApi implements AutoCloseable {

        private final HttpServer server;
        private final List<Record> requests = new ArrayList<>();
        private final List<Map<String, Object>> pulls = new ArrayList<>();
        private final Map<Integer, List<Map<String, Object>>> comments = new LinkedHashMap<>();
        private final Map<String, Response> fails = new LinkedHashMap<>();
        private int nextNumber = 7;
        private String mergeState = "merged";
        private boolean dropComments;

        private GiteeApi(HttpServer server) {
            this.server = server;
        }

        static GiteeApi start() throws IOException {
            GiteeApi api = new GiteeApi(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
            api.server.createContext("/", api::handle);
            api.server.start();
            return api;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + API;
        }

        List<String> methods() {
            return requests.stream().map(Record::method).toList();
        }

        Record request(int index) {
            assertTrue(requests.size() > index, "the atom sent nothing");
            return requests.get(index);
        }

        private void common(Record req, String method, String target, String body) {
            assertEquals(method, req.method(), "method");
            assertEquals(target, req.target(), "target");
            assertEquals(body, req.body(), "body");
            assertEquals(TOKEN, header(req, "PRIVATE-TOKEN"), "PRIVATE-TOKEN header");
            assertTrue(header(req, "Accept") != null && header(req, "Accept").contains("application/json"),
                    "Accept: " + header(req, "Accept"));
            if (!"GET".equals(method)) {
                String contentType = header(req, "Content-Type");
                assertTrue(contentType != null && contentType.startsWith("application/x-www-form-urlencoded"),
                        "Content-Type: " + contentType);
            }
        }

        private static String header(Record req, String name) {
            for (Map.Entry<String, String> entry : req.headers().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String target = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
            synchronized (this) {
                requests.add(new Record(method, target, headers, body));
            }

            Response response = route(method, target, body);
            byte[] raw = response.payload() == null ? new byte[0]
                    : Json.write(response.payload()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(response.status(), raw.length);
            exchange.getResponseBody().write(raw);
            exchange.close();
        }

        private synchronized Response route(String method, String target, String body) {
            for (Map.Entry<String, Response> entry : fails.entrySet()) {
                if (target.startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }

            URI uri = URI.create("http://127.0.0.1" + target);
            String path = uri.getPath();
            Map<String, String> query = parseQuery(uri.getRawQuery());
            if (!path.startsWith(API + "/repos/")) {
                return new Response(404, Map.of("message", "no such endpoint"));
            }
            String rest = path.substring((API + "/repos/").length());
            String[] parts = rest.split("/", -1);
            if (parts.length < 2) {
                return new Response(404, Map.of("message", "no such endpoint"));
            }
            String owner = parts[0];
            String repo = parts[1];
            List<String> tail = new ArrayList<>(List.of(parts).subList(2, parts.length));
            if (!OWNER.equals(owner) || !REPO.equals(repo)) {
                return new Response(404, Map.of("message", "repository not found"));
            }

            if (tail.equals(List.of("pulls")) && "GET".equals(method)) {
                String base = query.getOrDefault("base", "");
                String head = query.getOrDefault("head", "");
                List<Map<String, Object>> matching = pulls.stream()
                        .filter(p -> base.equals(ref(p, "base")) && head.equals(ref(p, "head")))
                        .toList();
                return new Response(200, matching);
            }

            if (tail.equals(List.of("pulls")) && "POST".equals(method)) {
                Map<String, String> fields = parseForm(body);
                Map<String, Object> pull = new LinkedHashMap<>();
                pull.put("number", nextNumber);
                pull.put("iid", nextNumber);
                pull.put("title", fields.getOrDefault("title", ""));
                pull.put("state", "open");
                pull.put("html_url", "https://gitee.com/" + owner + "/" + repo + "/pulls/" + nextNumber);
                pull.put("base", Map.of("ref", fields.getOrDefault("base", "")));
                pull.put("head", Map.of("ref", fields.getOrDefault("head", "")));
                nextNumber++;
                pulls.add(pull);
                return new Response(201, pull);
            }

            if (tail.size() == 3 && "pulls".equals(tail.get(0)) && "comments".equals(tail.get(2))) {
                int number = Integer.parseInt(tail.get(1));
                if ("POST".equals(method)) {
                    Map<String, String> fields = parseForm(body);
                    List<Map<String, Object>> existing = comments.getOrDefault(number, List.of());
                    Map<String, Object> comment = Map.of("id", 900 + existing.size(),
                            "body", fields.getOrDefault("body", ""));
                    if (!dropComments) {
                        List<Map<String, Object>> updated = new ArrayList<>(existing);
                        updated.add(comment);
                        comments.put(number, updated);
                    }
                    return new Response(201, comment);
                }
                if ("GET".equals(method)) {
                    return new Response(200, comments.getOrDefault(number, List.of()));
                }
            }

            if (tail.size() == 3 && "pulls".equals(tail.get(0)) && "merge".equals(tail.get(2))
                    && "PUT".equals(method)) {
                int number = Integer.parseInt(tail.get(1));
                for (Map<String, Object> pull : pulls) {
                    if (number == ((Number) pull.get("number")).intValue()) {
                        pull.put("state", mergeState);
                    }
                }
                return new Response(200, Map.of());
            }

            if (tail.size() == 2 && "pulls".equals(tail.get(0)) && "GET".equals(method)) {
                int number = Integer.parseInt(tail.get(1));
                for (Map<String, Object> pull : pulls) {
                    if (number == ((Number) pull.get("number")).intValue()) {
                        return new Response(200, pull);
                    }
                }
                return new Response(404, Map.of("message", "no such pull request"));
            }

            return new Response(404, Map.of("message", "no such endpoint"));
        }

        private static String ref(Map<String, Object> pull, String side) {
            Object node = pull.get(side);
            return node instanceof Map<?, ?> map ? String.valueOf(map.get("ref")) : "";
        }

        private static Map<String, String> parseQuery(String raw) {
            Map<String, String> params = new LinkedHashMap<>();
            if (raw == null || raw.isEmpty()) {
                return params;
            }
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                params.put(decode(key), decode(value));
            }
            return params;
        }

        private static Map<String, String> parseForm(String body) {
            return parseQuery(body);
        }

        private static String decode(String value) {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
