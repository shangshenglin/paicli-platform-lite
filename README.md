# PaiCLI Platform Lite

面向单人开发、私有单机部署的受管 Agent Runtime。项目保留 CatPaw 类架构最关键的思想：

- Agent Runtime（脑）与 Sandbox（手）通过接口分离。
- Session、Run、Message、Event、Tool Call 持久化。
- REST 提交任务，SSE 实时订阅并支持 `Last-Event-ID` 断线续传。
- 工具调用在执行前持久化，使用幂等键避免恢复时重复副作用。
- 企业级 PostgreSQL、S3、消息队列和 MicroVM 分别由 SQLite、本地目录、进程内 Worker 和 Docker 替代。

## 产品站点

- 在线站点：[PaiCLI Platform Lite 产品站](https://paicli-platform-lite.fuermalin2002.chatgpt.site)
- 站点源码：`paicli-site/`

本地开发产品站点：

```powershell
cd paicli-site
npm install
npm run dev
```

站点使用 Node.js `>= 22.13.0`，详细验证和目录说明见 `paicli-site/README.md`。

## 当前阶段

阶段 1 至阶段 10 已完成：

- 三模块 Maven 工程。
- Spring Boot REST/SSE Server。
- SQLite WAL Runtime Store。
- 可恢复 Run Worker 和 ReAct 工具边界。
- `LocalSandboxDriver` 开发适配器。
- 可独立构建为 Docker 镜像的 `paicli-sandbox-agent`。
- 离线 `DemoModelClient`，无需 API Key 即可验证完整链路。
- `DockerSandboxDriver`：一条 Run 复用一个容器，结束后强制回收。
- Docker 内部网络、无宿主端口暴露、每容器 Bearer Token；控制面通过 `docker exec` 调用容器 loopback HTTP Agent。
- 只读根文件系统、CPU/内存/PID、capability 和超时限制。
- `write_file` / `execute_command` 持久化审批。
- JSONL 工具与审批审计。
- 服务启动时清理带 PaiCLI label 的孤儿容器。
- OpenAI-compatible 流式模型适配，可配置 OpenAI、GLM、DeepSeek 等兼容端点。
- 分层 Prompt、主模型结构化摘要（失败时确定性降级）、Token 预算和持久化模型 usage。
- 大工具结果外置到本地 Artifact Store，并提供 `read_artifact` 分段读取。
- L0 对话到 L1/L2/L3 的持久化自动 Memory 提取、时序修订、混合召回，并保留显式 CRUD 纠错。
- 一轮多个 `tool_calls` 全量持久化，并按模型顺序逐个执行和审批。
- 可预期的工具业务失败会持久化为 `tool` observation 并重新排队给模型修正，不再直接把整个 Run 标记失败；审批拒绝、取消和 Runtime 系统异常仍终止 Run。
- 取消 Run 会主动关闭正在进行的模型 HTTP/SSE 请求。
- project-scoped `AGENTS.md` / `PAI.md` 规则注入，带字符预算和路径边界。
- SQLite `schema_migrations` 版本记录。
- 可选 API Key 认证、生产强制密钥模式、管理端点保护、安全响应头和聊天式 Web Console。
- SQLite WAL checkpoint、可选 Event/Audit 保留策略、孤儿文件清理和扩展 Micrometer 指标。
- 带 SHA-256 校验、路径校验和 SQLite 文件头校验的停机备份/恢复脚本。
- GitHub Actions Reactor/Docker 构建、Dependabot、CycloneDX SBOM 和 59 个自动化测试。
- P0 业务工作台：Run 重试/分支、持久化审批策略、统一检索、Memory 管理、知识版本/索引状态和 Artifact 复用。

Docker Desktop / WSL2 真实容器验收已完成：审批恢复、容器内命令执行、工作区挂载、SSE 重放、资源/安全限制和结束回收均已通过。

## 环境

- Java 17
- Maven Wrapper（已内置，首次运行下载 Maven 3.9.9）
- Docker Desktop（仅 Docker Sandbox 模式需要；Local 模式不需要）

## 构建与启动

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd clean package
$env:PAICLI_API_KEY="replace-with-a-long-random-value"
$env:PAICLI_SECURITY_REQUIRE_API_KEY="true"
java -jar paicli-server\target\paicli-server-0.6.0-SNAPSHOT.jar
```

默认监听 `http://127.0.0.1:8080`，数据写入当前目录下的 `data/`。
浏览器访问 `http://127.0.0.1:8080/` 使用聊天式 Console；对话区和右侧执行详情均在视口内独立滚动，流式事件会批量持久化并合并渲染，访问 `/docs` 查看 OpenAPI。历史对话可创建持久化分组、在分组间移动并删除；删除分组不删对话，删除对话会清理其消息和执行记录。Console 可在每次提交前选择“深度思考：关闭/开启”和“推理等级：高/最高”；关闭是默认快速模式。
`start-local.ps1` 和 `start-docker.ps1` 会自动读取被 Git 忽略的项目 `.env`；已经存在的进程环境变量优先。
Console 只在当前标签页的 `sessionStorage` 中保存 API Key；关闭标签页后需重新填写。启用 API Key 后，
`/actuator/**` 与 `/v3/api-docs` 默认使用同一密钥保护。

## P0 业务工作台

Console 左侧“业务工作台”统一提供项目级搜索、Memory 管理、Artifact 管理和审批策略撤销。终态 Run 顶部提供“重试”和“分支”：重试沿用原 Session，分支会复制源 Run 之前的对话到新 Session，再沿用原输入与推理设置执行。

危险工具审批支持三种范围：

- 仅本次允许：不创建策略。
- 本对话允许：仅当前 Session 可复用。
- 本项目允许：当前项目的任意 Session 可复用。

持久化策略只匹配相同工具名和完全相同的已落库参数 SHA-256；参数发生任何变化都会重新审批。可通过工作台或 `DELETE /v1/approvals/policies/{policyId}` 撤销。

主要 API：

```text
POST   /v1/runs/{runId}/retry                 # body: {"branch":false}
GET    /v1/search?projectKey=default&query=关键词
GET    /v1/memories/managed?projectKey=default
POST   /v1/memories/{id}/state
GET    /v1/memories/{id}/revisions
POST   /v1/memories/{id}/revisions/{revisionId}/restore
POST   /v1/memories/{id}/merge
GET    /v1/knowledge/documents/search
POST   /v1/knowledge/documents/{projectKey}/{name}/reindex
POST   /v1/knowledge/documents/{projectKey}/{name}/feedback
GET    /v1/artifacts?projectKey=default
GET    /v1/artifacts/{id}/download
POST   /v1/artifacts/{id}/reuse
DELETE /v1/artifacts/{id}
```

## 最小验收

创建 Session：

```powershell
$session = Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8080/v1/sessions `
  -Headers @{"X-API-Key"=$env:PAICLI_API_KEY} `
  -ContentType application/json `
  -Body '{"title":"demo","projectKey":"default"}'
```

提交普通 Run：

```powershell
$run = Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8080/v1/sessions/$($session.id)/runs" `
  -Headers @{"X-API-Key"=$env:PAICLI_API_KEY} `
  -ContentType application/json `
  -Body '{"input":"你好"}'
```

查看状态和事件：

```powershell
Invoke-RestMethod "http://127.0.0.1:8080/v1/runs/$($run.id)" -Headers @{"X-API-Key"=$env:PAICLI_API_KEY}
Invoke-RestMethod "http://127.0.0.1:8080/v1/runs/$($run.id)/timeline" -Headers @{"X-API-Key"=$env:PAICLI_API_KEY}
```

工具链路演示：

```json
{"input":"/tool list"}
```

订阅 SSE：

```text
GET /v1/runs/{runId}/events
Last-Event-ID: 0
X-API-Key: your-key
```

## 数据目录

```text
data/
├─ paicli.db
├─ workspaces/{runId}/
├─ artifacts/
├─ audit/
├─ prompts/            # 可选 prompt override；AGENTS.md 为全局规则
└─ projects/{projectKey}/
   ├─ AGENTS.md        # 项目规则
   └─ PAI.md           # 项目记忆/约定
```

## Docker 模式

安装并启动 Docker Desktop 后：

```powershell
.\scripts\start-docker.ps1
```

也可以分别执行：

```powershell
.\scripts\build-sandbox.ps1
java -jar .\paicli-server\target\paicli-server-0.6.0-SNAPSHOT.jar `
  --paicli.sandbox-mode=docker
```

详细约束见 [docs/docker-sandbox.md](docs/docker-sandbox.md)。

## 危险工具审批

提交以下 Demo 输入可触发审批：

```json
{"input":"/tool exec echo hello"}
```

查询审批：

```http
GET /v1/approvals
```

批准或拒绝：

```http
POST /v1/approvals/{approvalId}
Content-Type: application/json

{"decision":"APPROVED","rememberScope":"SESSION"}
```

```json
{"decision":"DENIED"}
```

Local 模式故意不允许命令和写文件；批准后的危险工具只有 Docker 模式才能真正执行。

## 真实模型

统一使用 OpenAI-compatible 流式接口，密钥只存在 Server 进程，不进入 Sandbox。完整变量见 `.env.example`。例如 DeepSeek：

```powershell
$env:PAICLI_MODEL_PROVIDER="openai-compatible"
$env:PAICLI_MODEL_BASE_URL="https://api.deepseek.com"
$env:PAICLI_MODEL_API_KEY="replace-me"
$env:PAICLI_MODEL_NAME="deepseek-v4-flash"
$env:PAICLI_MODEL_MAX_CONTEXT_TOKENS="1000000"
$env:PAICLI_MODEL_MAX_OUTPUT_TOKENS="16384"
$env:PAICLI_MODEL_THINKING_MODE="enabled"
$env:PAICLI_MODEL_REASONING_EFFORT="high"
.\scripts\start-local.ps1
```

DeepSeek V4 Thinking 的 `reasoning_content` 会随流解析并持久化。只要该 assistant 消息包含工具调用，后续模型请求会把 reasoning 与 `tool_calls` 一起恢复，避免多轮工具调用返回 HTTP 400。可用 `deepseek-v4-pro` + `max` 提升复杂任务推理，但默认推荐成本更低的 `deepseek-v4-flash` + `high`。

模型同一轮返回多个 `tool_calls` 时，Runtime 会先原子持久化 assistant 消息和全部工具参数，再按模型顺序逐个执行。后续工具不会绕过前一个工具的审批等待。

## 项目规则

上下文按“全局 → 项目 → Run 工作区”读取规则，后加载的规则优先，总预算 16,000 字符：

```text
data/prompts/AGENTS.md
data/projects/{projectKey}/AGENTS.md
data/projects/{projectKey}/PAI.md
data/workspaces/{runId}/AGENTS.md
data/workspaces/{runId}/PAI.md
```

## 分层自动 Memory 与手动纠错

Run 完成后先写入 `memory_extractions` 任务，再由单机 Worker 调主模型从最近对话窗口提取偏好、事实、约束、决策和经验。结果按 L1/L2/L3、类型、置信度、来源 Run/Session 持久化；同一 key 更新时旧值进入 `memory_revisions`。下一轮按 query 的词法/语义相关性、置信度、时间衰减和层级召回。显式 API 继续用于人工新增、修订和删除。

```http
POST /v1/memories
Content-Type: application/json

{"projectKey":"default","memoryKey":"coding-style","content":"Java 使用 17 语法","tags":"java,stable"}
```

同时提供 `GET /v1/memories?projectKey=default&query=java`、`PUT /v1/memories/{id}` 和 `DELETE /v1/memories/{id}`。

## 备份与恢复

先停止 Server，再执行：

```powershell
.\scripts\backup.ps1
.\scripts\restore.ps1 -Archive .\backups\paicli-backup-YYYYMMDD-HHMMSS.zip -Force -RequireChecksum
```

备份脚本会拒绝连接到仍在监听的本机 Server，并生成同名 `.sha256` 文件。恢复前会校验 SHA-256、ZIP
条目路径和 SQLite 文件头；恢复脚本不会直接删除旧数据，而是先改名保存为 `data.before-restore.*`。
旧备份没有校验文件时仍可恢复，但会发出警告；自动化恢复建议始终带 `-RequireChecksum`。

## 运维与安全基线

- 非开发部署设置 `PAICLI_SECURITY_REQUIRE_API_KEY=true`，缺少 `PAICLI_API_KEY` 时启动直接失败。
- Sandbox Agent 必须由 Server 注入随机 `SANDBOX_AGENT_TOKEN`；命令与 Docker CLI 输出在读取阶段即限额，
  超时会清理进程树，避免先耗尽内存再截断。
- Runtime 每小时执行被动 WAL checkpoint，并清理超过宽限期的孤儿 Artifact/临时文件。Event/Audit
  保留默认关闭；可通过 `PAICLI_EVENT_RETENTION_DAYS`、`PAICLI_AUDIT_RETENTION_DAYS` 显式开启。
- Artifact、附件、知识正文和向量索引使用“临时文件 + fsync + 原子替换”写入。
- `/actuator/metrics` 增加队列深度、待审批、Memory extraction、模型重试、工具失败和 SSE 连接指标。

## 已知边界

- Docker 是 MicroVM 的轻量替代，不适合运行完全不可信的敌对代码。
- Docker Desktop 真实容器 E2E 与 Fake Docker 生命周期测试均已通过。
- 不包含 Kubernetes、多租户、自治 Planner/Reviewer Agent Team 和复杂企业 Console。

架构和阶段说明见 [docs/architecture.md](docs/architecture.md) 与 [docs/phases.md](docs/phases.md)。

## 阶段 7：Server Tool Provider 扩展

新增能力没有引入第二套 Agent Loop。`load_skill`、`search_knowledge`、`session_search`、
`web_search` / `web_fetch`、`mcp__{server}__{tool}`、
`spawn_agent` / `get_agent_result` / `list_agents` / `cancel_agent` 都先作为普通 ToolCall
原子落库，再经过原有审批、顺序执行、Event/SSE、Audit 和 Artifact 边界。

- Skill：全局目录 `data/skills/{name}/SKILL.md`，项目覆盖目录
  `data/projects/{projectKey}/skills/{name}/SKILL.md`。Server 启动时会创建 `data/skills` 和 `data/projects`；
  也可在 Console“能力管理”中填写 HTTPS Git 地址安装。对于 `skills/{name}/SKILL.md` 形式的多 Skill
  仓库，必须在“Skill 名称”中填写要安装的准确目录名。Console 可选择安装为当前项目 Skill，或安装到
  `data/skills/{name}` 作为所有项目可见的全局 Skill。GitHub `/tree/{ref}/...` 或 `/blob/{ref}/...`
  网页链接会由 Server 自动转换为可克隆的仓库地址并提取分支。
  每次模型请求只自动注入稳定排序的名称/描述索引；正文由模型匹配任务后调用 `load_skill`，附带的
  references、模板和文本脚本由 `read_skill_resource` 在路径/字符预算内分页读取。
- RAG：Console 支持上传 PDF、Office、HTML、Markdown、CSV、JSON、文本等文档。Tika 提取正文后，
  分块器识别 Markdown 标题层级、编号标题、段落、句子、列表、表格行和代码围栏。检索使用 BM25 与
  真实 embedding 双路召回、RRF 融合、标题/短语 boost、重叠去重和单文档配额，并在每轮模型前自动召回。
  Ollama/OpenAI-compatible 提供真实向量；未配置时明确降级为本地词法投影，不冒充语义 embedding。
  图片型/扫描 PDF 在没有文本层时由 PDFBox 按页渲染，再交给当前视觉模型做 OCR 并进入同一分块与索引链路；
  OCR 不可用时聊天附件仍可成功暂存，并仅在当前 Run 以页面图像发送，Console 会明确提示模型必须支持视觉。
- 历史会话检索：`session_search` 是 Agent 主动调用的内置工具，对当前项目的历史会话消息做 BM25 全文检索，
  按会话聚合命中消息并返回抽取式摘要。它不会对每条消息自动执行，也不会跨项目或检索内部子会话。
- 联网：设置 `PAICLI_WEB_ENABLED=true` 和 `PAICLI_WEB_SEARCH_URL` 后启用。搜索端点采用
  SearXNG JSON 结构；结果规范化、去重并携带引用编号。网页抓取禁止访问本机、内网和链路本地地址，
  每次重定向重新校验并限制响应大小。
- MCP：在 `data/mcp/servers.json` 配置远程 HTTP Server；工具动态注册为
  `mcp__{server}__{tool}`，全部需要持久化审批。Header 可写成 `env:ENV_NAME`，密钥只在
  Server 出口解析，不进入 Sandbox 或模型上下文；远端 schema/参数/响应有大小预算，连续失败会短时熔断。
- Multi-Agent：`spawn_agent` 经审批后创建持久化子 Run；创建动作以父 ToolCall 为唯一键，
  崩溃恢复不会重复派生。委派限制三层深度、每个父 Run 最多六个子 Run；取消父 Run 会级联取消后代，
  `cancel_agent` 也经过持久化审批。父 Run 用 `get_agent_result` 查询，不同步占住 Worker 等待。
- 统一附件：聊天输入框“＋”可同时选择最多 4 张 PNG/JPEG/GIF 和 4 个文档。图片经校验后作为
  OpenAI-compatible `image_url` 发给视觉模型；TXT、Markdown、PDF、Word、PowerPoint、Excel、CSV、
  HTML、JSON、XML、RTF、EPUB 和 OpenDocument 文件由 Tika 提取并写入当前项目知识库，再以附件 id
  绑定当前 Run，优先进入本轮 RAG。普通“请总结附件”会做跨文档分段采样，具体问题走 BM25/向量召回。
  扫描 PDF 默认最多处理 6 页、150 DPI，可由 `PAICLI_RAG_PDF_OCR_*` 调整；OCR/页面图像只留在 Server，
  不挂载进 Sandbox。使用不支持图片的纯文本模型时，应关闭 OCR 或改用支持视觉的模型。

- 实时结束：SSE 长连接使用不排队的弹性线程池，避免多标签页把后续订阅饿死；Console 收到
  `run.completed/run.failed/run.canceled` 后会主动关流，并在断流时通过 Run API 对账和自动重连，
  不再依赖手动刷新才能恢复输入框和终态。

MCP 配置示例：

```json
{
  "servers": [
    {
      "name": "github",
      "url": "https://example.com/mcp",
      "enabled": true,
      "headers": {"Authorization": "env:GITHUB_MCP_AUTH"}
    }
  ]
}
```

知识文档示例：

```http
POST /v1/knowledge/documents
Content-Type: application/json

{"projectKey":"default","name":"architecture.md","content":"项目知识正文"}
```

新增能力 API：

```http
GET    /v1/skills?projectKey=default
POST   /v1/skills/imports
DELETE /v1/skills/{name}?projectKey=default&global=false

GET    /v1/knowledge/documents?projectKey=default
POST   /v1/knowledge/documents/uploads?projectKey=default  # multipart files
DELETE /v1/knowledge/documents/{name}?projectKey=default

POST   /v1/sessions/{sessionId}/attachments/images         # multipart files
POST   /v1/sessions/{sessionId}/attachments/documents      # multipart files; extract + index + stage
DELETE /v1/sessions/{sessionId}/attachments/{attachmentId}
```

`POST /v1/runs` 可传 `attachmentIds`，Server 会在创建 Run 的同一事务内校验并绑定暂存图片或文档。
