# jenkins-atom-gitee

Gitee 拉取请求 — 把 **gitee** 插件（13,000+ 安装量口径见分类表 2,833）里对 PR 的四件事做成一个原子：
按 base/head 查、创建、评论、合并。

插件本身是一个 `Notifier`（`GiteeCreatePullRequestPublisher`、`GiteeReleasePublisher`、`GiteeMessagePublisher`）
加两个 Pipeline 步骤（`addGiteePRComment`、`acceptGiteePR`），但它们最后都落到 `GiteeV5ApiProxy` 的几个
JAX-RS 方法上，报文规则全在那几个注解里，所以能一个原子包住。

## 抄自插件源码的部分

先看 hpi（`WEB-INF/lib/gitee.jar`，395 个类），再取 `c2702f1d` 那次提交的源码读
`GiteeV5ApiProxy` / `ResteasyGiteeClient` / `ResteasyGiteeClientBuilder` /
`GiteeCreatePullRequestPublisher` / `AddGiteePullRequestCommentStep`：

| 插件行为 | 原子行为 |
|---|---|
| 连接 URL 是 `https://gitee.com`，`@Path("/api/v5")` 拼在前面 | `apiUrl` 默认 `https://gitee.com/api/v5`，尾斜杠去掉 |
| `ApiHeaderTokenFilter` 每个请求加 `PRIVATE-TOKEN: <token>` | 同 |
| 查询：`GET /repos/{owner}/{repo}/pulls`，`@QueryParam("base")` 在前、`head` 在后 | 同顺序，路径段与查询值按 RFC 3986 百分号编码（`/` → `%2F`，空格 → `%20`） |
| 创建：`POST /repos/{owner}/{repo}/pulls`，`application/x-www-form-urlencoded`，字段顺序 title、base、head、body、prune_source_branch、draft、squash | 同顺序同编码（空格 `+`），见下面第一条差异 |
| 先查 base+head，非空就打印 already exists 直接返回（不算失败） | 同：`created=false` 并且不再发 POST |
| 评论：`POST /repos/{owner}/{repo}/pulls/{n}/comments`，表单只有 `body` | 同 |
| 合并：`PUT /repos/{owner}/{repo}/pulls/{n}/merge`，`@Consumes` 表单但**没有任何 @FormParam**，请求体为空 | 同：PUT 空体（Gitee v5 不接受合并说明与删源分支，插件注释里也写了） |
| `title` / `body` 里的 `${VAR}` 由 Jenkins 宏展开 | 同：先查原子输入，再查构建环境变量 |

## 与插件不同的地方（有意为之）

1. **每个写操作都回读**。插件只看状态码，评论失败时连异常都吞掉（`catch (WebApplicationException | ProcessingException)` 后只打印一行）。
   原子创建后重新 `GET` 列表，要求里面真有一条 base/head/title 都对得上的；评论后 `GET` 评论列表，要求真有一条 `body` 完全相同的；
   合并后 `GET` 那个 PR，要求 `state=merged`（或 `merged_at` 非空）。回读不到就 `40010` 失败，绝不拿 201/200 当证据。
2. **三个布尔永远显式发送**。插件的 `@FormParam Boolean` 在没勾选时传 null，Resteasy 写不写这个字段取决于实现；原子固定写 `true`/`false`，
   这样服务端看到的值与 Jenkins 里勾选状态一一对应。
3. **401/403 单独归类**：`40007`（第三方鉴权），其余非 2xx `40003`；`failOnError=false` 时降级成 `ok=false` 加一行日志。
4. **不移植**：webhook 触发器（`GiteeWebHookTrigger` 及其 push/PR/note/pipeline handler）、PR 触发的构建参数、
   构建状态 PNG（`CommitStatusPngAction`）、`GiteeReleasePublisher` 与 `GiteeMessagePublisher` 的发布/留言逻辑 ——
   前两类是 job 模型与块语义，状态图是 Jenkins UI 资源，都不是原子形态。

## 输入

| 名字 | 说明 |
|---|---|
| `action` | `get` / `create` / `comment` / `merge`，默认 `get` |
| `apiUrl` | API 根，默认 `https://gitee.com/api/v5` |
| `owner` / `repo` | 仓库坐标，各占一个路径段（带 `/` 直接拒绝，不让路径被改写） |
| `prNumber` | PR 编号，`comment` / `merge` 必填 |
| `base` / `head` | 分支，`get` / `create` 必填 |
| `title` / `body` | 标题与正文/评论，支持 `${VAR}` |
| `pruneSourceBranch` / `draft` / `squash` | 默认都是 false |
| `token` | PRIVATE-TOKEN，按凭据绑定（`x-jenkins.secrets`） |
| `dryRun` | 只打印会发的请求，一个字节都不发 |
| `failOnError` | 默认 true |

## 输出

`created`、`number`、`title`、`url`、`count`、`pullRequests`（原始 JSON 数组）、`commentId`、`state`、
`ok` / `status`（放行时）、`dryRun` / `request`（dry run）。

## 验证

```bash
mvn verify      # src/test/java/atom/GiteeIT.java
```

测试起一个假 Gitee（自己维护 PR 与评论状态、记录原始请求行与 body），验的不是"退出码 0"，而是自己复算出来的报文：

- 请求行逐字节比对：路径、查询串（base 在前、`/` → `%2F`、空格 → `%20`）、表单编码（空格 `+`、中文与 `+` 百分号化）；
- 头：`PRIVATE-TOKEN`、`Accept`、非 GET 的 `Content-Type: application/x-www-form-urlencoded`；
- create 的顺序必须是 GET → POST → GET，且已存在时只有一次 GET；
- 回读抓得住"回 201 但没真建/没真评/没真合"（`create.readback.missing`、`comment.readback.missing`、`merge.readback.still.open`）；
- 401 → `40007`，500 → `40003`，`failOnError=false` 放行，连不上 → `40009`；
- dry run 时假服务端收到的请求数必须是 0；输入校验 9 条。

14/14 通过。`tools/mutations.py` 往源码里种 22 个 bug（换 token 头、换 Content-Type、head/base 倒序、
`/` 不编码、跳过已存在检查、把已存在报成新建、prune 用 draft 的值、draft 恒 false、跳过创建回读、
标题编造、评论打到 PR 本体、跳过评论回读、不做 `${VAR}` 展开、merge 用 POST、merge 带表单体、
合并没成功也报成功、401 归错类、忽略 failOnError、dry run 照发、编号 0 放行、owner 允许斜杠），
22/22 全部被抓。
