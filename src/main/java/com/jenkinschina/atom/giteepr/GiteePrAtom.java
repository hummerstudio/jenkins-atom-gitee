package com.jenkinschina.atom.giteepr;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gitee pull-request work, ported from the gitee plugin (com.gitee.jenkins.gitee.api.impl.GiteeV5ApiProxy).
 *
 * <p>The plugin is a Notifier plus two steps; all of them end in one of four Gitee API v5 form-encoded calls:
 * list pull requests by base/head, create one, comment on one, merge one. The paths, the HTTP methods, the media type
 * and the form field names below are copied from the JAX-RS annotations in GiteeV5ApiProxy and the call sites in
 * ResteasyGiteeClient / GiteeCreatePullRequestPublisher / AddGiteePullRequestCommentStep.
 *
 * <p>Two deliberate differences, both documented in the README. First, every write is read back: the plugin trusts
 * the status code (the comment step even swallows the exception), an atom may not. Second, the create call sends
 * prune_source_branch / draft / squash always, while the plugin passes null through Resteasy when the box is unset.
 */
public final class GiteePrAtom implements AtomTask {

    /** Absolute API root, the plugin's connection URL ("https://gitee.com") plus GiteeV5ApiProxy's @Path("/api/v5"). */
    static final String DEFAULT_API = "https://gitee.com/api/v5";

    static final int BAD_INPUT = 40001;
    static final int NO_TOKEN = 40002;
    static final int REJECTED = 40003;
    static final int AUTH = 40007;
    static final int READ_BACK = 40010;
    static final int UNREACHABLE = 40009;

    private static final String FORM = "application/x-www-form-urlencoded";

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        try {
            return execute(context);
        } catch (java.io.IOException e) {
            return AtomResult.error(ErrorType.ENVIRONMENT, UNREACHABLE,
                    "cannot reach Gitee: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private AtomResult execute(AtomContext context) throws Exception {
        String action = orEmpty(context.getParam("action", "get")).trim().toLowerCase(Locale.ROOT);
        if (!List.of("get", "create", "comment", "merge").contains(action)) {
            return AtomResult.error(ErrorType.USER, BAD_INPUT,
                    "action must be get, create, comment or merge, not '" + action + "'");
        }

        String api = orEmpty(context.getParam("apiUrl", DEFAULT_API)).trim();
        while (api.endsWith("/")) {
            api = api.substring(0, api.length() - 1);
        }
        if (!api.startsWith("http://") && !api.startsWith("https://")) {
            return AtomResult.error(ErrorType.USER, BAD_INPUT,
                    "apiUrl must be an absolute http(s) URL, e.g. " + DEFAULT_API + ": " + api);
        }

        String owner = orEmpty(context.getParam("owner")).trim();
        String repo = orEmpty(context.getParam("repo")).trim();
        for (String[] pair : new String[][]{{"owner", owner}, {"repo", repo}}) {
            if (pair[1].isEmpty() || pair[1].contains("/") || pair[1].contains(" ")
                    || pair[1].contains("?") || pair[1].contains("#")) {
                return AtomResult.error(ErrorType.USER, BAD_INPUT,
                        pair[0] + " must be one path segment (no '/', space, '?' or '#'): '" + pair[1] + "'");
            }
        }

        String token = orEmpty(context.getSecret("token")).trim();
        if (token.isEmpty()) {
            token = orEmpty(context.getParam("token")).trim();
        }
        if (token.isEmpty()) {
            return AtomResult.error(ErrorType.USER, NO_TOKEN,
                    "token is required: bind the Gitee API v5 personal access token (PRIVATE-TOKEN) as a secret");
        }

        String repoPath = "/repos/" + enc(owner) + "/" + enc(repo);
        String base = orEmpty(context.getParam("base")).trim();
        String head = orEmpty(context.getParam("head")).trim();
        String title = expand(context, orEmpty(context.getParam("title")));
        String body = expand(context, orEmpty(context.getParam("body")));
        int number = context.getIntParam("prNumber", 0);
        boolean dryRun = context.getBooleanParam("dryRun", false);
        boolean failOnError = context.getBooleanParam("failOnError", true);
        boolean draft = context.getBooleanParam("draft", false);
        boolean squash = context.getBooleanParam("squash", false);
        boolean pruneSourceBranch = context.getBooleanParam("pruneSourceBranch", false);

        if (("create".equals(action) || "get".equals(action)) && (base.isEmpty() || head.isEmpty())) {
            return AtomResult.error(ErrorType.USER, BAD_INPUT,
                    action + " needs both base (target branch) and head (source branch)");
        }
        if (("comment".equals(action) || "merge".equals(action)) && number <= 0) {
            return AtomResult.error(ErrorType.USER, BAD_INPUT,
                    action + " needs prNumber, the pull request number Gitee shows in the URL");
        }
        if ("comment".equals(action) && body.isEmpty()) {
            return AtomResult.error(ErrorType.USER, BAD_INPUT, "comment needs body: the text to post");
        }

        Api api0 = new Api(api, token);

        switch (action) {
            case "get": {
                String listUrl = api + repoPath + "/pulls" + query(base, head);
                if (dryRun) {
                    return dry(context, "GET " + listUrl);
                }
                HttpResponse<String> response = api0.send("GET", listUrl, null);
                AtomResult rejected = check(response, failOnError, "list pull requests");
                if (rejected != null) {
                    return rejected;
                }
                List<Map<String, Object>> pulls = asList(Json.parse(response.body()));
                AtomResult result = AtomResult.success("Gitee lists " + pulls.size() + " pull request(s) "
                        + head + " -> " + base);
                result.data("count", String.valueOf(pulls.size()));
                result.data("pullRequests", response.body());
                if (!pulls.isEmpty()) {
                    result.data("number", String.valueOf(number(pulls.get(0))));
                }
                return result;
            }
            case "create": {
                String listUrl = api + repoPath + "/pulls" + query(base, head);
                if (dryRun) {
                    return dry(context, "GET " + listUrl, "POST " + api + repoPath + "/pulls "
                            + createForm(title, base, head, body, pruneSourceBranch, draft, squash));
                }
                // GiteeCreatePullRequestPublisher#perform asks first and returns early: "already exists" is not a failure.
                HttpResponse<String> existing = api0.send("GET", listUrl, null);
                AtomResult rejected = check(existing, failOnError, "look for an existing pull request");
                if (rejected != null) {
                    return rejected;
                }
                List<Map<String, Object>> found = asList(Json.parse(existing.body()));
                if (!found.isEmpty()) {
                    AtomResult result = AtomResult.success("Gitee already has pull request #" + number(found.get(0))
                            + " for " + head + " -> " + base + "; nothing was created");
                    result.data("created", "false");
                    result.data("number", String.valueOf(number(found.get(0))));
                    result.data("title", orEmpty(str(found.get(0).get("title"))));
                    return result;
                }

                String form = createForm(title, base, head, body, pruneSourceBranch, draft, squash);
                HttpResponse<String> created = api0.send("POST", api + repoPath + "/pulls", form);
                rejected = check(created, failOnError, "create the pull request");
                if (rejected != null) {
                    return rejected;
                }

                // Read back instead of trusting the 201: ask Gitee for the pull request between these two branches.
                HttpResponse<String> verify = api0.send("GET", listUrl, null);
                AtomResult failed = check(verify, failOnError, "read the created pull request back");
                if (failed != null) {
                    return failed;
                }
                Map<String, Object> match = null;
                for (Map<String, Object> pull : asList(Json.parse(verify.body()))) {
                    if (!head.equals(branch(pull, "head")) || !base.equals(branch(pull, "base"))) {
                        continue;
                    }
                    if (!title.isEmpty() && !title.equals(orEmpty(str(pull.get("title"))))) {
                        continue;
                    }
                    match = pull;
                    break;
                }
                if (match == null) {
                    return AtomResult.error(ErrorType.THIRD_PART, READ_BACK,
                            "Gitee answered the create call but no pull request " + head + " -> " + base
                                    + (title.isEmpty() ? "" : " titled '" + title + "'") + " is listed afterwards");
                }
                AtomResult result = AtomResult.success("Gitee created pull request #" + number(match));
                result.data("created", "true");
                result.data("number", String.valueOf(number(match)));
                result.data("title", orEmpty(str(match.get("title"))));
                result.data("url", orEmpty(str(match.get("html_url"))));
                return result;
            }
            case "comment": {
                String url = api + repoPath + "/pulls/" + number + "/comments";
                if (dryRun) {
                    return dry(context, "POST " + url + " " + form(ordered("body", body)));
                }
                HttpResponse<String> posted = api0.send("POST", url, form(ordered("body", body)));
                AtomResult rejected = check(posted, failOnError, "comment on pull request #" + number);
                if (rejected != null) {
                    return rejected;
                }
                HttpResponse<String> list = api0.send("GET", url, null);
                AtomResult failed = check(list, failOnError, "read the comment back");
                if (failed != null) {
                    return failed;
                }
                Map<String, Object> mine = null;
                for (Map<String, Object> comment : asList(Json.parse(list.body()))) {
                    if (body.equals(orEmpty(str(comment.get("body"))))) {
                        mine = comment;
                    }
                }
                if (mine == null) {
                    return AtomResult.error(ErrorType.THIRD_PART, READ_BACK,
                            "Gitee accepted the comment on pull request #" + number
                                    + " but it is not in the comment list afterwards");
                }
                AtomResult result = AtomResult.success("commented on Gitee pull request #" + number);
                result.data("commentId", String.valueOf(orEmpty(str(mine.get("id")))));
                return result;
            }
            case "merge":
            default: {
                // GiteeV5ApiProxy#acceptPullRequest: PUT, form-urlencoded, no form parameters at all.
                String url = api + repoPath + "/pulls/" + number + "/merge";
                if (dryRun) {
                    return dry(context, "PUT " + url);
                }
                HttpResponse<String> merged = api0.send("PUT", url, "");
                AtomResult rejected = check(merged, failOnError, "merge pull request #" + number);
                if (rejected != null) {
                    return rejected;
                }
                HttpResponse<String> state = api0.send("GET", api + repoPath + "/pulls/" + number, null);
                AtomResult failed = check(state, failOnError, "read pull request #" + number + " back");
                if (failed != null) {
                    return failed;
                }
                Map<String, Object> pull = Json.parseObject(state.body());
                String mergedState = orEmpty(str(pull.get("state")));
                if (!"merged".equals(mergedState) && str(pull.get("merged_at")) == null) {
                    return AtomResult.error(ErrorType.THIRD_PART, READ_BACK,
                            "Gitee accepted the merge of pull request #" + number + " but its state is '"
                                    + mergedState + "', not merged");
                }
                AtomResult result = AtomResult.success("merged Gitee pull request #" + number);
                result.data("state", mergedState.isEmpty() ? "merged" : mergedState);
                return result;
            }
        }
    }

    // ------------------------------------------------------------------ request building

    /** GiteeApiProxy#createPullRequest field order; @FormParam on a Boolean always writes true/false in the atom. */
    static String createForm(String title, String base, String head, String body, boolean prune, boolean draft, boolean squash) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("base", base);
        fields.put("head", head);
        fields.put("body", body);
        fields.put("prune_source_branch", String.valueOf(prune));
        fields.put("draft", String.valueOf(draft));
        fields.put("squash", String.valueOf(squash));
        return form(fields);
    }

    static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            // java.net.URLEncoder: what the JAX-RS form provider writes. Space becomes '+'.
            sb.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static Map<String, String> ordered(String key, String value) {
        Map<String, String> one = new LinkedHashMap<>();
        one.put(key, value);
        return one;
    }

    /** JAX-RS @QueryParam order: base first, then head. */
    static String query(String base, String head) {
        return "?base=" + pct(base) + "&head=" + pct(head);
    }

    private static String pct(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            // ASCII only: Character.isLetterOrDigit() would call 0xC3 a letter and leak a raw UTF-8 byte.
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return sb.toString();
    }

    private static String enc(String segment) {
        return pct(segment);
    }

    // ------------------------------------------------------------------ HTTP

    static final class Api {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        private final String token;

        Api(String api, String token) {
            this.token = token;
        }

        HttpResponse<String> send(String method, String url, String form) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("PRIVATE-TOKEN", token)
                    .header("Accept", "application/json");
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.header("Content-Type", FORM)
                        .method(method, HttpRequest.BodyPublishers.ofString(form == null ? "" : form, StandardCharsets.UTF_8));
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private AtomResult check(HttpResponse<String> response, boolean failOnError, String what) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return null;
        }
        String body = response.body() == null ? "" : response.body();
        String excerpt = body.length() > 500 ? body.substring(0, 500) + "..." : body;
        String message = "Gitee refused to " + what + ": HTTP " + status + " " + excerpt;
        if (status == 401 || status == 403) {
            return AtomResult.error(ErrorType.THIRD_PART, AUTH, message);
        }
        if (failOnError) {
            return AtomResult.error(ErrorType.THIRD_PART, REJECTED, message);
        }
        AtomResult result = AtomResult.success(message + " (failOnError=false, the build keeps going)");
        result.data("ok", "false");
        result.data("status", String.valueOf(status));
        return result;
    }

    /** Prints every request the action would send, one per line, and sends nothing. */
    private AtomResult dry(AtomContext context, String... lines) {
        for (String line : lines) {
            context.log("dry run: " + line);
        }
        AtomResult result = AtomResult.success("dry run: nothing was sent");
        result.data("dryRun", "true");
        result.data("request", String.join("\n", lines));
        return result;
    }

    // ------------------------------------------------------------------ small helpers

    private static int number(Map<String, Object> pull) {
        Object number = pull.get("number");
        if (number == null) {
            number = pull.get("iid");
        }
        return number instanceof Number ? ((Number) number).intValue() : 0;
    }

    private static String branch(Map<String, Object> pull, String side) {
        Object node = pull.get(side);
        if (node instanceof Map) {
            Object ref = ((Map<?, ?>) node).get("ref");
            return ref == null ? "" : String.valueOf(ref);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object parsed) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (parsed instanceof List) {
            for (Object item : (List<Object>) parsed) {
                if (item instanceof Map) {
                    out.add((Map<String, Object>) item);
                }
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** ${VAR} from the atom's inputs first, then the build environment, like the plugin's token macro expansion. */
    static String expand(AtomContext context, String text) {
        if (text == null || text.indexOf("${") < 0) {
            return orEmpty(text);
        }
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (true) {
            int start = text.indexOf("${", at);
            if (start < 0) {
                out.append(text, at, text.length());
                return out.toString();
            }
            int end = text.indexOf('}', start + 2);
            if (end < 0) {
                out.append(text, at, text.length());
                return out.toString();
            }
            out.append(text, at, start);
            String name = text.substring(start + 2, end);
            String value = context.getParam(name);
            if (value == null) {
                value = System.getenv(name);
            }
            out.append(value == null ? text.substring(start, end + 1) : value);
            at = end + 1;
        }
    }
}
