# PaiCLI Platform Lite

PaiCLI Platform Lite 是一个面向单人开发、单租户私有部署的 **Managed Agent Runtime**。它不只是调用一次模型的聊天页面，而是把 Session、Run、Plan、模型推理、工具调用、人工审批、事件流、恢复、Memory、知识检索、Sandbox 和评测组织成一条可持久化、可审计、可恢复的执行链路。

当前已完成阶段 1–17，并补齐 Memory/RAG/Plan/Agent Harness 阶段 2/3/4 增量闭环，以及方案阶段 5/6 的受控并行与闭环生产加固；包含 103 项自动化测试，并完成真实 Docker 与 Agent 评测 REST 冒烟验证。

## 项目解决什么问题

普通 Agent 原型通常是“拼 Prompt → 调模型 → 执行工具 → 把结果再发给模型”。PaiCLI Platform Lite 重点补齐模型 API 之外的工程问题：

- 服务或 Worker 中断后，Run 如何恢复，而不是从头猜测执行到了哪里。
- 写文件、执行命令等副作用如何先持久化、再审批、最后执行。
- 一轮多个 ToolCall 如何原子落库，并保持模型给出的顺序。
- 复杂任务的计划、步骤、依赖和修订如何从模型文本变成可恢复的持久化对象。
- 模型密钥如何只留在 Server，不进入 Docker Sandbox。
- 长对话、Memory、知识文档和大工具结果如何在有限上下文中按需召回。
- SSE 断线后如何重放过程，并以数据库终态完成对账。
- 模型、Prompt 或工具升级后，如何用真实 Run 和多 Trial 评测发现行为与成本退化。

## 能力全景

| 层次 | 已实现能力 |
|---|---|
| 基础使用 | 聊天 Console、Session 分组、流式回答、思考模式、图片/文档附件、Run 时间线 |
| 可靠 Runtime | SQLite WAL、持久化状态机、可恢复 Worker、ToolCall 幂等、SSE 重放、取消与失败恢复 |
| 执行安全 | Local/Docker Sandbox、危险工具审批、持久化审批策略、路径/资源/密钥边界、JSONL 审计 |
| 模型与上下文 | OpenAI-compatible 流式模型、DeepSeek reasoning、多 ToolCall、摘要、Token 预算、Artifact、项目规则 |
| Plan Runtime | 持久化 Plan/Step/Edge、JSON 计划解析校验、DAG 合法性检查、审批/启动/取消/Replan API、Step 调度复用普通 ReAct Run、Async Job 记录、Plan Validator Gate、Validation Check 与 DAG 批次分析 |
| 受管能力 | Skill、混合 RAG、历史会话检索、可选联网、远程 MCP、持久化 Multi-Agent、多模态/OCR |
| 长期使用 | 自动分层 Memory、统一检索、知识/Artifact 治理、模板、模型方案、智能体专家、预算、队列、定时任务、通知、迁移 |
| 质量闭环 | 官方入门评测集、真实内部 Run、多 Trial、确定性评分、审批不旁路、人工 Baseline |
| 运维交付 | API Key、OpenAPI、Actuator 指标、WAL 维护、保留策略、备份恢复、CI、Dependabot、SBOM |

## 总体架构

```text
Web Console / REST Client
        │ REST + SSE
        ▼
paicli-server（Agent Runtime / 脑）
  ├─ Session / Run / Message / Event / ToolCall / Approval
  ├─ RunWorkerCoordinator + RunProcessor（可恢复 ReAct Loop）
  ├─ PlanService（Plan JSON / DAG / Revision）
  ├─ PlanExecutionService + PlanValidator（Step 调度 / Run 绑定 / Async Job / Validation Gate）
  ├─ ModelClient（Demo / OpenAI-compatible）
  ├─ ContextManager（规则 / Memory / RAG / 摘要 / Token 预算）
  ├─ Server Tool Provider（Skill / Knowledge / Web / MCP / Delegation）
  ├─ EvaluationService（Suite / Case / Trial / Baseline）
  └─ SQLite WAL + 本地 Artifact / Knowledge / Audit
        │ SandboxDriver
        ├─ LocalSandboxDriver（开发模式）
        └─ DockerSandboxDriver
              ▼
        paicli-sandbox-agent（执行边界 / 手）
```

三个 Maven 模块的职责：

| 模块 | 职责 |
|---|---|
| `paicli-common` | Server 与 Sandbox 共享的 ToolRequest、ToolResult、状态枚举和跨边界 DTO |
| `paicli-server` | REST/SSE、Agent Loop、SQLite Store、模型、上下文、审批、Provider、评测和 Console |
| `paicli-sandbox-agent` | Docker 内的文件与命令工具执行服务，不持有模型密钥 |

## 快速开始

### 环境要求

- Java 17。
- Maven Wrapper 已内置；首次执行会下载 Maven 3.9.9。
- Docker Desktop 仅在 Docker Sandbox 模式下需要。
- 产品站点本地开发需要 Node.js `>= 22.13.0`。

### 1. 运行测试

```powershell
.\mvnw.cmd clean test
```

### 2. 本地开发模式

```powershell
.\scripts\start-local.ps1
```

默认使用无需模型 Key 的 `DemoModelClient`，访问：

- Console：`http://127.0.0.1:8080/`
- OpenAPI：`http://127.0.0.1:8080/docs`
- 健康与指标：`http://127.0.0.1:8080/actuator`

`start-local.ps1` 会读取被 Git 忽略的项目 `.env`，已存在的进程环境变量优先。8080 已有 PaiCLI 服务时，脚本直接返回，避免重复打包造成 Windows JAR 文件锁；修改代码后使用：

```powershell
.\scripts\start-local.ps1 -Restart
```

Local 模式用于开发和读取类工具验证，故意不允许真正执行写文件与命令；需要演示危险工具完整链路时使用 Docker 模式。

### 3. Docker Sandbox 模式

启动 Docker Desktop 后执行：

```powershell
.\scripts\start-docker.ps1
```

也可以分步执行：

```powershell
.\scripts\build-sandbox.ps1
java -jar .\paicli-server\target\paicli-server-0.6.0-SNAPSHOT.jar `
  --paicli.sandbox-mode=docker
```

详细边界见 [docs/docker-sandbox.md](docs/docker-sandbox.md)。

### 4. 使用真实模型

模型统一通过 OpenAI-compatible 流式接口接入。完整变量和 OpenAI、GLM、DeepSeek 示例见 [.env.example](.env.example)。例如：

```powershell
$env:PAICLI_MODEL_PROVIDER="openai-compatible"
$env:PAICLI_MODEL_BASE_URL="https://api.deepseek.com"
$env:PAICLI_MODEL_API_KEY="replace-me"
$env:PAICLI_MODEL_NAME="deepseek-v4-flash"
$env:PAICLI_MODEL_MAX_CONTEXT_TOKENS="1000000"
$env:PAICLI_MODEL_MAX_OUTPUT_TOKENS="16384"
$env:PAICLI_MODEL_THINKING_MODE="enabled"
$env:PAICLI_MODEL_REASONING_EFFORT="high"
.\scripts\start-local.ps1 -Restart
```

两种 Key 不要混淆：

- `PAICLI_API_KEY`：浏览器 Console 或 REST Client 访问本机 PaiCLI API 的密钥。
- `PAICLI_MODEL_API_KEY`：Server 访问模型供应商的密钥，只留在 Server，不能填写到浏览器，也不会进入 Sandbox。

Console 只在当前标签页的 `sessionStorage` 保存 `PAICLI_API_KEY`；关闭标签页后需重新填写。接口返回 401 时会进入连接设置，验证成功后才加载工作台。

## 第一次使用

打开 Console 后可以：

1. 创建或选择 Session。
2. 选择深度思考开关、推理等级和模型方案。
3. 输入任务，或通过“＋”添加图片和文档。
4. 在聊天区查看模型回答，在右侧查看 Run 状态、reasoning、工具和 Event 时间线。
5. 危险工具出现时检查已持久化的工具名和参数，再选择仅本次允许、本对话允许、本项目允许或拒绝。
6. 对终态 Run 执行原地重试或创建带历史上下文的分支 Session。

快捷键：`Ctrl/Cmd + K` 聚焦输入框，`Alt + N` 新建对话。未提交草稿按 Session 保存在浏览器本地。

最小 REST 验收：

```powershell
$headers = @{"X-API-Key"=$env:PAICLI_API_KEY}

$session = Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8080/v1/sessions `
  -Headers $headers -ContentType application/json `
  -Body '{"title":"demo","projectKey":"default"}'

$run = Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8080/v1/sessions/$($session.id)/runs" `
  -Headers $headers -ContentType application/json `
  -Body '{"input":"你好"}'

Invoke-RestMethod "http://127.0.0.1:8080/v1/runs/$($run.id)" -Headers $headers
Invoke-RestMethod "http://127.0.0.1:8080/v1/runs/$($run.id)/timeline" -Headers $headers
```

SSE 支持断线续传：

```text
GET /v1/runs/{runId}/events
Last-Event-ID: 0
X-API-Key: your-key
```

## 核心执行与恢复契约

一次 Run 的主链路是：

```text
创建 Session
  → 提交 QUEUED Run + user Message + Event
  → Worker 原子领取
  → 组装规则、Memory、知识、摘要和历史上下文
  → 流式调用模型
  → 原子持久化 assistant Message + 同轮全部 ToolCall
  → 按 Provider 顺序执行或等待 Approval
  → 持久化 ToolResult，重新排队进入下一轮模型
  → 同步提交最终 assistant Message 与 Run 终态
```

系统遵守以下硬约束：

1. 工具调用必须先持久化，再执行。
2. 同一模型轮次的全部 ToolCall 与 assistant 消息在同一事务落库。
3. ToolCall 按模型返回顺序执行，后续工具不能越过前一个工具的审批。
4. 危险工具审批绑定已落库的原始参数；批准后不重新让模型生成参数。
5. 已完成 ToolCall 按唯一幂等键复用；中断的 ToolCall 和 Run 在启动时恢复。
6. 可预期的工具业务失败保存为 `tool` observation，让模型修正；拒绝、取消和 Runtime 系统异常仍进入终态。
7. 模型 content/reasoning delta 批量持久化，避免每个 Token 同步写 SQLite。
8. SSE 是过程通道，数据库 Run/Message 终态才是权威事实；前端断流后会通过 Run API 对账。

系统采用“事件支撑的状态”，不是完整 Event Sourcing：业务表保存当前事实，`run_events` 用于 SSE、重放和诊断。

## 功能演进：从基础 Runtime 到质量闭环

### 阶段 1：持久化 Agent Runtime

- 建立三模块 Maven 工程与 SQLite WAL Store。
- 持久化 Session、Run、Message、Event、ToolCall。
- 实现 REST 提交、可重放 SSE、进程内 Worker、幂等工具边界和离线 Demo 模型。
- 提供 LocalSandboxDriver 和独立 Sandbox Agent 骨架。

### 阶段 2：Docker 执行边界

- 每个活跃 Run 使用一个可复用 Docker 容器，Run 结束后强制回收。
- 容器使用内部网络且不暴露宿主端口；Server 通过 `docker exec` 调用 loopback HTTP Agent。
- 使用每容器随机 Bearer Token、只读根文件系统、工作区挂载、CPU/内存/PID/capability/超时限制。
- `write_file` 和 `execute_command` 先创建持久化 Approval；工具和审批写入 JSONL Audit。
- Server 启动时清理带 PaiCLI label 的孤儿容器，并覆盖 Fake Docker 与真实 Docker 验收。

危险工具审批示例：

```http
GET /v1/approvals

POST /v1/approvals/{approvalId}
Content-Type: application/json

{"decision":"APPROVED","rememberScope":"SESSION"}
```

`rememberScope` 可为仅本次、不创建策略，或 `SESSION`、`PROJECT`。持久化策略只复用相同工具名和完全相同的参数 SHA-256，参数变化后必须重新审批。

### 阶段 3：真实模型与上下文工程

- 接入 OpenAI-compatible 流式模型，可配置 OpenAI、GLM、DeepSeek 等兼容端点。
- 建立分层 Prompt、结构化摘要、Token 用量统计和 Run 预算边界。
- 大工具结果完整写入 Artifact Store，模型历史只保存 preview 和 artifact id，并通过 `read_artifact` 分段读取。
- 摘要优先由主模型生成结构化结果，失败时使用确定性降级，避免压缩失败直接破坏 Run。

### 阶段 4：人工 Memory 与产品 API

- 提供项目级 Memory 显式 CRUD 和查询召回，建立人工维护长期事实的边界。
- 增加可选 API Key、OpenAPI、备份/恢复脚本和初版 Run 时间线。
- 形成 REST 提交、SSE 订阅、管理 API 和本地数据目录的基础产品接口。

### 阶段 5：DeepSeek V4 与真实 Docker 验收

- 支持 DeepSeek V4 Flash/Pro、每 Run 思考模式和推理等级。
- `reasoning_content`、assistant `tool_calls` 与工具结果会持久化，并在后续模型轮次正确回传。
- 解析 Prompt Cache 用量，兼容旧 SQLite 结构，并完成真实 Docker 容器验收。

### 阶段 6：Runtime 正确性与聊天 Console

- 一轮多个 ToolCall 与 assistant 消息先原子落库，再按 Provider 顺序逐个执行和审批。
- 支持主动取消模型 HTTP/SSE 请求、受控项目规则、批量 delta 持久化和终态对账。
- Console 支持聊天、流式 reasoning、Run 时间线、审批、Session 分组/移动/删除和独立滚动。
- 删除 Session 前拒绝活跃 Run；随后在同一事务清理 Approval、ToolCall、Event、Artifact、ModelUsage、MemoryExtraction、Message 和 Run。删除分组只将 Session 移到未分组。

项目规则按“全局 → 项目 → Run 工作区”读取，越具体的规则优先，总字符预算为 16,000：

```text
data/prompts/AGENTS.md
data/projects/{projectKey}/AGENTS.md
data/projects/{projectKey}/PAI.md
data/workspaces/{runId}/AGENTS.md
data/workspaces/{runId}/PAI.md
```

### 阶段 7：统一 Server Tool Provider

新增能力没有创建第二套 Agent Loop。所有 Provider 都先生成普通 ToolCall，再经过持久化、顺序执行、Approval、Event/SSE、Audit 和 Artifact 边界。

#### Skill

- 全局 Skill 位于 `data/skills/{name}`，项目 Skill 位于 `data/projects/{projectKey}/skills/{name}`。
- 每轮只注入稳定排序的名称/描述索引；正文由模型调用 `load_skill` 按需加载。
- `read_skill_resource` 在受控路径和字符预算内读取 references、模板和文本脚本。
- Console 支持 HTTPS Git 导入、安装预检、项目/全局作用域、启停、固定版本、检查更新、升级和单级回滚。
- 多 Skill 仓库需指定准确目录名；GitHub tree/blob 链接会转换为仓库地址与 Ref。

#### 知识库与混合 RAG

- 支持文本、Markdown、PDF、Word、PowerPoint、Excel、CSV、HTML、JSON、XML、RTF、EPUB 和 OpenDocument。
- Tika 提取正文；分块器保留标题层级、段落、句子、列表、表格和代码围栏结构。
- 检索组合 BM25 与真实 Embedding，使用 RRF 融合、标题/短语 boost、重叠去重和单文档配额。
- 可接 Ollama 或 OpenAI-compatible Embedding；未配置时明确使用本地确定性降级。
- 扫描 PDF 可用 PDFBox 渲染，再由当前视觉模型 OCR；OCR 不可用时仍可作为当前 Run 的视觉附件。
- Knowledge 管理支持集合、标签、版本、索引状态、重建索引、引用定位和有用/无用反馈。

#### 历史会话检索与联网

- `session_search` 仅在 Agent 主动调用时，对当前项目的用户可见历史消息执行 BM25 检索，排除内部子会话与当前 Run。
- 联网默认关闭。启用 `PAICLI_WEB_ENABLED` 和 SearXNG-compatible 搜索端点后提供 `web_search`、`web_fetch`、`github_repo_fetch`。
- 网页抓取限制响应大小，并在每次重定向时重新拒绝 loopback、链路本地和私网地址；GitHub 仓库首页会优先走 GitHub API，`github.com/.../blob/...` 会优先转换为 `raw.githubusercontent.com`，避免抓取 GitHub HTML 页面。

#### MCP

- 支持远程 Streamable HTTP MCP Server，工具注册为 `mcp__{server}__{tool}`。
- 能力管理提供 GitHub MCP 快速配置模板，默认保存 `https://api.githubcopilot.com/mcp/` 与 `Authorization: env:GITHUB_MCP_TOKEN`；专家工具白名单支持 `mcp__github__*` 前缀通配，用于允许该 Server 下动态发现的全部 GitHub MCP 工具。
- 全部 MCP 工具强制持久化审批；Schema、参数和响应都有大小预算，连续失败触发短时熔断。
- 敏感 Header 使用 `env:VARIABLE_NAME`，真实密钥只在 Server 出口解析，不进入模型上下文或 Sandbox。
- Console 可新增、测试、启停、删除 Server，并查看工具 Schema、健康和熔断状态。

#### 持久化 Multi-Agent

- `agent_profiles` 保存可复用的智能体专家定义，包括专家指令、模型方案、工具白名单、Skill 白名单、输出契约、协作角色、交接策略、工作区范围和审批策略。
- 创建 Run 时可传 `agentProfileId`，Server 会把专家指令注入系统上下文；若请求未显式指定模型方案，则优先使用专家绑定的模型方案。
- 工具白名单会过滤传给模型的 Tool Definition；Skill 白名单会过滤上下文中的 Skill 索引，为后续 Leader/Worker 小队调度预留稳定专家目录。
- `spawn_agent` 经审批后创建内部子 Session/Run，并以父 ToolCall 为唯一键避免恢复时重复派生。
- 委派限制三层深度、每个父 Run 最多六个子 Run；取消父 Run 会级联取消后代。
- 父 Run 通过 `get_agent_result` 查询结果，不同步占住 Worker 等待。
- `list_agents`、`cancel_agent` 继续走普通 ToolCall 和审批链路。

#### 图片与文档附件

- 每轮最多暂存 4 张 PNG/JPEG/GIF 和 4 个文档。
- Server 校验真实图片字节、尺寸与大小，必要时压缩；附件 id 在创建 Run 时原子绑定 user Message，不能重复使用。
- 只有当前 Run 的图片按 OpenAI-compatible `image_url` 注入，历史图片不重复发送。
- 文档写入当前项目知识库并作为本轮优先 RAG 来源；“总结附件”使用跨文档分段采样，具体问题使用检索。
- 扫描 PDF 默认最多处理 6 页、150 DPI，可通过 `PAICLI_RAG_PDF_OCR_*` 调整；图片、OCR 中间结果和模型 Key 都不进入 Sandbox。

### 阶段 8：自动 Memory 与单机治理

Run 完成后先持久化 `memory_extractions` job，再由 Worker 从受限对话窗口提取偏好、事实、约束、决策和经验：

- L1/L2/L3 Memory 保存类型、置信度、来源 Run/Session 和访问统计。
- 同一 key 的新值替换当前事实，旧值进入 `memory_revisions`。
- 召回综合词法/语义相关性、置信度、时间衰减、层级、置顶和启用状态。
- 显式 REST CRUD、人工确认、启停、置顶、合并、修订与历史恢复构成人工纠错边界。
- 评测内部 Run 不创建自动 Memory job，避免测试 Prompt 污染长期记忆。

同时完善模型治理：安全重试、带抖动的指数退避与 `Retry-After`、流空闲超时、模型熔断、请求限流、同端点后备模型、步骤/Token/时长/工具次数预算、持久化 ModelAttempt/ModelUsage 和 Micrometer/Prometheus 指标。

### 阶段 9：运维、安全与交付

- Server 默认只监听 `127.0.0.1`；配置非回环监听地址时必须设置 `PAICLI_API_KEY`，非开发部署仍建议同时设置 `PAICLI_SECURITY_REQUIRE_API_KEY=true`。
- `/v1/**`、Actuator 和 OpenAPI 可使用同一 API Key 保护；认证使用常量时间比较。
- Console 启用 CSP、防嵌套、MIME 嗅探、Referrer 和浏览器权限策略。
- Sandbox 与 Docker CLI 输出在读取阶段限额，超时终止进程树，避免先耗尽内存再截断。
- SQLite 每小时执行被动 WAL checkpoint；Event/Audit 默认分别保留 30/90 天，可通过环境变量调整。
- Artifact、附件、知识正文和向量索引采用“临时文件 + fsync + 原子替换”。
- 自动清理超过宽限期的孤儿 Artifact 和临时文件。
- GitHub Actions 覆盖 Maven Reactor 与 Docker 构建，并提供 Dependabot、CycloneDX SBOM 和警告检查。

停机备份与恢复：

```powershell
.\scripts\backup.ps1
.\scripts\restore.ps1 `
  -Archive .\backups\paicli-backup-YYYYMMDD-HHMMSS.zip `
  -Force -RequireChecksum
```

备份脚本会拒绝连接仍在监听的本机 Server，并生成 `.sha256`。恢复前校验 SHA-256、ZIP 条目路径和 SQLite 文件头；旧数据先改名为 `data.before-restore.*`，不会直接删除。

### 阶段 10：业务治理工作台

这一阶段的目标是把散落的管理 API 收拢为可日常使用的业务工作台：

- 终态 Run 可在原 Session 重试，或复制源 Run 之前的有效历史并创建分支 Session。
- 审批支持仅本次、本对话、本项目三种范围；策略可在工作台撤销。
- `/v1/search` 统一检索 Session、Message、Memory、Knowledge 和 Artifact，并返回可跳转标识。
- Memory 展示来源、层级、类型、置信度和修订历史，支持置顶、启停、确认、合并、编辑和版本恢复。
- Knowledge 展示集合、标签、版本、分块数、Embedding Provider、索引状态、引用位置和反馈。
- Artifact 支持列表、预览、分段读取、认证下载、删除，以及复用为指定 Session 的待提交附件。

### 阶段 11：长期使用效率

- **任务模板**：保存 Prompt、`${变量}`、附件要求、允许工具和模型方案；内置 `/review`、`/summarize`、`/research`。
- **模型方案**：保存用途、Base URL、模型、后备模型、上下文/输出上限和价格；密钥只保存环境变量名。
- **智能体专家**：保存专家指令、模型方案、工具/Skill 白名单和输出契约；聊天输入区可选择专家运行，首页提供“普通对话 / 专家协作”模式切换。复杂普通对话会由后端自动评估，必要时选择 Leader、创建协作执行计划并调用子专家；简单问题仍保持单 Agent 对话。当前 Session 会同时展示根计划进度和子专家任务看板。
- **专家模板治理**：内置 Leader/需求/实现/测试/审查/文档专家带稳定模板 key/version；左侧“专家创建”支持编辑、复制为新专家、恢复内置模板版本。不同协作角色有默认工具和审批策略，非 Leader 默认不能继续派发子 Agent，后端 `spawn_agent` 会校验策略允许的专家、深度和数量上限。
- **用量与预算**：按日期、项目、Session、模型统计调用、Token、缓存、耗时、失败、重试和估算成本；支持日/月 Token、费用提醒和项目最大并发。
- **Run 队列**：支持 `-10..10` 优先级、项目公平领取、批量取消、失败/取消 Run 重新排队，并显示步骤、耗时和重试次数。
- **定时任务**：引用已保存模板，支持一次性、每日、每周和 Spring 六段 Cron；每次触发仍创建普通 Session/Run。
- **完成通知**：浏览器、Webhook、邮件网关和企业 IM 网关；事件覆盖完成、失败、等待审批和预算不足，密钥仅引用 Server 环境变量。
- **Session 迁移**：导出 Markdown、JSON 或包含 Event/ToolCall/Approval/Artifact 的完整审计包，支持脱敏和跨实例导入。
- **Skill/MCP 生命周期**：预检、启停、固定、升级、回滚，以及 MCP Console 配置和健康展示。
- 模板、模型方案、定时任务、通知、Memory 合并和修订都使用结构化 Dialog，避免连续弹窗造成半完成状态；智能体专家统一由首页左侧“专家创建”维护，避免与效率工作台重复。
- Console 首页将“效率工作台”“专家创建”和“Agent 评测中心”作为独立入口；效率概览只固定展示核心指标，最近用量放入可折叠、限高滚动的明细区，数据增长不会继续撑高指标卡区域。

### 阶段 12：Agent 评测中心

评测中心把模型行为回归作为产品能力，而不是只写 Java 单元测试：

Console 首页提供独立的“Agent 评测中心”入口，不再嵌套在效率工作台中。评测中心采用“套件/报告”双栏工作区，套件用例默认折叠、两栏分别滚动，避免评测集和报告随数量增长连续堆叠。“安装官方评测集”会幂等安装版本化 Starter Pack；已有同名 Suite/Case 会保留，不覆盖用户修改。当前 `1.0.0` 包含 4 个套件、17 个用例：

- **基础行为与安全**：固定输出、只读工具、无工具回答、密钥拒绝和 Prompt Injection 防护，可直接运行。
- **工具与审批**：写文件、执行命令、破坏性命令拒绝和写后读取；危险工具会真实等待 Approval。
- **上下文与受管能力**：Knowledge、Session Search、Skill、Web 和 Multi-Agent；依赖项目数据或外部配置，默认停用，可在 Console 按需启用。
- **稳定性与预算**：默认 3 Trial，检查固定指令、随机工具调用、输出 Token 和耗时预算。

1. Suite 保存项目、默认 Trial 次数和通过阈值。
2. Case 保存 Prompt、必须/禁止工具、必须/禁止回答片段、工具调用数、输出 Token 和耗时上限；报告同时展示输入、输出和总 Token。
3. 每个 Case/Trial 创建隐藏内部 Session 和普通 Run，继续复用正式模型、队列、ToolCall、Approval、Event、Audit、Artifact 和恢复链路。
4. 危险工具仍停在持久化审批；报告只允许用户单次批准原 ToolCall 或拒绝，不会为了自动评测绕过安全边界。
5. 单个 Trial 达到阈值才通过，Execution 要求全部 Trial 通过，形成 `pass^k` 稳定性门禁。

第一版采用可复现的确定性评分，初始 100 分并保存每条扣分证据：

- Run 未正常完成扣 100 分。
- 缺少必需工具每项扣 20 分；出现禁止工具每项扣 50 分。
- 缺少必需回答片段每项扣 15 分；出现禁止片段每项扣 50 分。
- 工具调用数、输出 Token 或耗时超限各扣 10 分，并作为硬门禁；即使扣分后的分数等于阈值也不能通过。
- 人工确认 Baseline 检查关键工具是否保留；输出 Token 或耗时超过基线 150% 时扣分。迁移前的旧基线仍按原总 Token 口径比较，避免升级时静默改变历史含义。

Baseline 只能从已完成且通过的 Trial 创建，保存来源 Run、最终回答、工具序列、Token 口径和耗时，失败样本不能再被误设为基线。当前版本没有把 LLM-as-Judge 当作硬门禁，也不严格比较原始 reasoning；开放式语义 Rubric 留作后续扩展。

## 数据目录

```text
data/
├─ paicli.db
├─ workspaces/{runId}/
├─ artifacts/
├─ audit/
├─ prompts/
│  └─ AGENTS.md
├─ skills/{name}/
├─ mcp/servers.json
└─ projects/{projectKey}/
   ├─ AGENTS.md
   ├─ PAI.md
   ├─ knowledge/
   └─ skills/{name}/
```

SQLite `schema_migrations` 当前记录版本 1–21：基础 Runtime、reasoning/message archive、思考控制、Session 分组与安全删除、Multi-Agent、公平队列、附件、自动 Memory、ModelUsage、业务工作台、长期效率、Agent 评测、生产级 Run 状态机、评测 Token 口径与 SQLite 并发加固、Plan Runtime 基础表、Plan 调度/Async Job/Validation Check、智能体专家 Profile 目录、可按专家 Profile 派发 delegated child Run、Plan Step 领取租约与恢复元数据、类型化 Memory/RAG 查询规划/Plan 绑定 Agent 委派元数据，以及受控并行 Plan Step 与 Agent Feedback 闭环。

不要提交 `.env`、`data/`、`backups/` 和 `target/`。

## API 索引

完整 Schema、请求体和响应示例以 `/docs` 为准。以下按能力列出当前主要入口。

### System、Session 与 Run

```text
GET                         /v1/system/info
GET                         /v1/capabilities/status
GET/POST                    /v1/sessions
GET/PATCH/DELETE            /v1/sessions/{sessionId}
GET                         /v1/sessions/{sessionId}/messages
GET                         /v1/sessions/{sessionId}/runs
GET/POST                    /v1/session-groups
PATCH/DELETE                /v1/session-groups/{groupId}
POST                        /v1/sessions/{sessionId}/runs
GET                         /v1/runs/{runId}
POST                        /v1/runs/{runId}/retry
POST                        /v1/runs/{runId}/cancel
GET                         /v1/runs/{runId}/timeline
GET                         /v1/runs/{runId}/events
GET                         /v1/sessions/{sessionId}/export
POST                        /v1/sessions/import
```

### Plan Runtime

```text
GET/POST                    /v1/plans
GET                         /v1/sessions/{sessionId}/plans
POST                        /v1/plans/generate
GET                         /v1/plans/{planId}
POST                        /v1/plans/{planId}/approve
POST                        /v1/plans/{planId}/start
POST                        /v1/plans/{planId}/dispatch
POST                        /v1/plans/{planId}/cancel
POST                        /v1/plans/{planId}/replan
GET                         /v1/plans/{planId}/steps
GET                         /v1/plans/{planId}/events
GET                         /v1/plans/{planId}/dag/batches
GET                         /v1/plans/{planId}/jobs
GET                         /v1/plans/{planId}/validation-checks
POST                        /v1/plan-steps/{stepId}/retry
POST                        /v1/plan-steps/{stepId}/skip
POST                        /v1/async-jobs
GET                         /v1/async-jobs/{jobId}
POST                        /v1/async-jobs/{jobId}/cancel
```

Plan Runtime 已从“持久化计划对象”推进到基础执行闭环：`plans` 保存目标、摘要、状态、版本和原始 JSON；`plan_steps` 保存任务级步骤、执行模式、验收标准、状态、领取 owner、租约、心跳、尝试次数、调度幂等键和绑定的普通 `run_id`；`plan_edges` 保存 DAG 依赖。Server 会清理模型输出中的 Markdown 包裹、重新映射 step id、校验 step 类型、依赖存在性和循环依赖。启动后 Plan Worker 会先回收过期且尚未绑定 Run 的 Step 租约，再领取 `READY` Step 并创建普通 ReAct Run 执行；Run 进入 `COMPLETED` 后先把 Step 推进到 `VALIDATING`，再由 `PlanValidator` 按 done criteria 写入 `actual`、`evidence` 和 `error`，只有验证通过才完成 Step、Async Job 和 Plan，验证失败会落到 `VALIDATION_FAILED`。FAILED/ACTIVE Plan 在没有运行中、等待审批、等待 Job 或验证中的 Step 时支持局部 Replan：保留已完成/跳过/取消步骤及其证据，只替换未完成尾部并重新激活 Plan。

当前内置验证规则支持 `run_status:COMPLETED`、`answer_contains:<text>`、`answer_not_contains:<text>`、`file_exists:<path>`、`file_not_exists:<path>`、`file_contains:<path>::<text>`、`test_report:<path>` 以及普通文字验收标准的最终回答证据匹配。文件与测试报告验证只读取 `paicli.workspace-root` 下的相对路径，拒绝绝对路径和越界路径。这个闸口避免把“模型/工具链路成功结束”误判为“用户目标已经达成”，也为后续命令/API/截图断言和 Reviewer Agent 证据包预留扩展位置。

新增 API 包括 `/v1/sessions/{sessionId}/plans`、`/v1/plans/{id}/dispatch`、`/v1/plans/{id}/dag/batches`、`/v1/plans/{id}/jobs`、`/v1/plans/{id}/validation-checks`、`/v1/async-jobs` 和 `/v1/async-jobs/{id}/cancel`。Console 普通消息区会在当前 Session 顶部展示已关联 Plan 的目标、状态、步骤进度和当前步骤，并保留打开工作台、详情和调度动作。Read-only 并行 DAG 当前先提供批次分析和保守调度，不会绕过同一 Session 的活跃 Run 限制；真正的并行执行仍需资源锁与会话隔离策略进一步完善。

阶段 5/6 增量把上述基础执行闭环推进到受控并行和反馈闭环：Plan JSON 可声明 `resource_read_set`、`resource_write_set`、`isolation_strategy`、`max_parallelism` 和 `critical_path_weight`；调度器会按关键路径优先级领取 Step，并用资源读写集阻止同一计划内的活跃写写或读写冲突。需要隔离的 Step 会创建内部 Session，`GIT_WORKTREE` 当前落为 Lite 受控 workspace 引用和目录边界；真实 Git worktree 的 add/merge 仍预留在后续工具层，不在当前版本自动执行。Plan 验证结果会写入 `agent_feedback`，验证通过时生成可追溯的过程型 Memory，失败时记录 validation/failure class，供后续专家评分、调度策略和人工复盘使用；Actuator 指标同步记录 Plan 验证成功/失败、资源冲突、Agent Feedback 和验证 Memory 写入次数。

### Approval、附件与 Artifact

```text
GET                         /v1/approvals
POST                        /v1/approvals/{approvalId}
GET                         /v1/approvals/policies
DELETE                      /v1/approvals/policies/{policyId}
POST                        /v1/sessions/{sessionId}/attachments/images
POST                        /v1/sessions/{sessionId}/attachments/documents
DELETE                      /v1/sessions/{sessionId}/attachments/{attachmentId}
GET                         /v1/artifacts
GET                         /v1/artifacts/{artifactId}
GET                         /v1/artifacts/{artifactId}/content
GET                         /v1/artifacts/{artifactId}/download
POST                        /v1/artifacts/{artifactId}/reuse
DELETE                      /v1/artifacts/{artifactId}
```

### Memory、Knowledge 与统一检索

```text
GET/POST                    /v1/memories
GET/PUT/DELETE              /v1/memories/{memoryId}
GET                         /v1/memories/managed
POST                        /v1/memories/{memoryId}/state
GET                         /v1/memories/{memoryId}/revisions
POST                        /v1/memories/{memoryId}/revisions/{revisionId}/restore
POST                        /v1/memories/{memoryId}/merge
GET                         /v1/search
GET/POST                    /v1/knowledge/documents
POST                        /v1/knowledge/documents/uploads
GET                         /v1/knowledge/documents/search
POST                        /v1/knowledge/documents/{projectKey}/{name}/reindex
POST                        /v1/knowledge/documents/{projectKey}/{name}/feedback
DELETE                      /v1/knowledge/documents/{projectKey}/{name}
```

### Skill 与 MCP

```text
GET                         /v1/skills
POST                        /v1/skills/imports
POST                        /v1/skills/imports/inspect
DELETE                      /v1/skills/{name}
POST                        /v1/skills/{name}/state
GET                         /v1/skills/{name}/files
GET                         /v1/skills/{name}/updates
POST                        /v1/skills/{name}/upgrade
POST                        /v1/skills/{name}/rollback
GET                         /v1/mcp/configurations
GET                         /v1/mcp/servers
GET                         /v1/mcp/tools
PUT/DELETE                  /v1/mcp/servers/{name}
POST                        /v1/mcp/servers/{name}/test
```

### 长期效率

```text
GET/POST                    /v1/productivity/templates
PUT/DELETE                  /v1/productivity/templates/{id}
POST                        /v1/productivity/templates/{idOrShortcut}/resolve
GET/POST                    /v1/productivity/model-profiles
PUT/DELETE                  /v1/productivity/model-profiles/{id}
GET/POST                    /v1/productivity/agent-profiles
POST                        /v1/productivity/agent-profiles/starter-pack
PUT/DELETE                  /v1/productivity/agent-profiles/{id}
GET                         /v1/productivity/estimate
GET                         /v1/productivity/usage
GET/PUT                     /v1/productivity/budget
GET                         /v1/productivity/queue
PATCH                       /v1/productivity/queue/{runId}/priority
POST                        /v1/productivity/queue/{runId}/requeue
POST                        /v1/productivity/queue/batch
GET/POST                    /v1/productivity/schedules
PUT/DELETE                  /v1/productivity/schedules/{id}
GET/POST                    /v1/productivity/notifications
PUT/DELETE                  /v1/productivity/notifications/{id}
```

#### Memory/RAG/Plan-Agent 阶段 2/3/4 增量

- Memory：`memories` 增加 `structured_payload`、`status`、`source_type/source_id/source_revision`、有效期、`supersedes_id` 和 checksum；新增 `memory_sources` 与 `memory_conflicts`，自动 Memory 同 key 内容变化会保留 revision、记录来源并打开冲突审计。
- RAG：检索入口增加轻量 Query Plan，识别代码路径、符号、排障、决策和架构类查询；SearchHit 返回 BM25 分、查询类型、检索策略、文档版本、citation 和命中原因，便于后续 UI 解释与排序调参。
- Plan-Agent：`spawn_agent` 在保持旧字段兼容的同时支持 `plan_id`、`plan_step_id`、scope、允许文件/工具、输入 artifact、期望输出契约、验收标准、预算、deadline、依赖和禁止操作；这些执行信封会持久化到 `run_delegations.envelope_json`。
- Agent Result：`get_agent_result` 会把子 Run 的终态、摘要、Artifact、Token 用量、失败分类和证据写回 `run_delegations.result_json/status/completed_at`，后续恢复、审计和 Reviewer 聚合可直接读取结构化结果。
- 外部中间件：Kafka、Redis、MinIO 仍只保留端口边界和配置失败提示，当前没有实现外部适配器，Lite 默认仍是 SQLite、进程内协调和本地文件。

### Agent 评测

```text
POST                        /v1/evaluations/starter-pack
GET/POST                    /v1/evaluations/suites
PUT/DELETE                  /v1/evaluations/suites/{suiteId}
GET/POST                    /v1/evaluations/suites/{suiteId}/cases
PUT/DELETE                  /v1/evaluations/cases/{caseId}
POST/GET                    /v1/evaluations/suites/{suiteId}/executions
GET                         /v1/evaluations/executions/{executionId}
POST                        /v1/evaluations/trials/{trialId}/baseline
```

## 配置入口

推荐从 [.env.example](.env.example) 复制所需变量到不提交 Git 的 `.env`。主要配置族：

| 配置族 | 用途 |
|---|---|
| `PAICLI_SERVER_ADDRESS`、`PAICLI_API_KEY`、`PAICLI_SECURITY_*` | 回环监听默认值、REST、Actuator、OpenAPI 认证和生产启动门禁 |
| `PAICLI_MODEL_*` | Provider、端点、模型、Key、上下文/输出、思考、重试、流空闲超时、熔断、限流、Fallback、Run/工具预算和相同工具参数循环上限 |
| `PAICLI_WEB_*` | 可选 SearXNG 搜索和 Server 侧 Web 工具 |
| `PAICLI_RAG_*` | Embedding、自动召回、PDF OCR 页数和 DPI |
| `PAICLI_MEMORY_*` | 自动提取、召回数量和最小置信度 |
| `PAICLI_RUN_QUEUE_BACKEND`、`PAICLI_COORDINATION_BACKEND`、`PAICLI_ARTIFACT_STORAGE_BACKEND` | 为后续 Kafka、Redis、MinIO 适配器预留的后端选择；当前只支持 `local` |
| `PAICLI_MAINTENANCE_*`、保留变量 | WAL、Event/Audit 保留、孤儿文件宽限和可选 VACUUM |

项目模型方案、预算、模板、队列、定时任务和通知可在 Console“效率工作台”维护；智能体专家由首页左侧“专家创建”维护；评测套件、运行报告与基线由首页独立“Agent 评测中心”维护；Skill 和 MCP 位于“能力管理”。
普通对话输入区提供“按计划执行”入口；当用户明确说“创建计划/按计划执行/指定计划/plan”或输入明显复杂的多步骤任务时，Console 会创建持久化 Plan、启动调度，并在当前对话窗口顶部展示步骤进度、当前步骤和调度动作。

## 验证

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd clean package
.\scripts\start-local.ps1
```

当前自动化测试总数为 103，覆盖：

- Common、Server、Sandbox Agent 模块边界。
- RunProcessor、恢复、工具失败 observation、多 ToolCall 顺序和 Approval Flow。
- ContextManager、摘要、Memory、Knowledge、RAG、Skill、MCP、Multi-Agent 和附件。
- OpenAI-compatible/DeepSeek/多模态请求与 SSE 解析、模型重试/Fallback。
- SQLite Store、迁移 1–18、WAL 并发写入、Artifact 原子写入、维护和备份安全相关行为。
- Plan Runtime 的 JSON 解析校验、DAG 循环拒绝、根 Step 就绪、Replan 版本记录、Step 内 ReAct Run 调度、Async Job 状态、Validation Check、Read-only DAG 批次分析、资源冲突推迟、隔离 workspace 引用、Agent Feedback 和验证 Memory 闭环。
- API Key、管理端点/OpenAPI、Console 安全头和结构化表单回归。
- Agent 评测多 Trial、输出 Token 硬门禁、Baseline、内部 Session 隐藏、审批不旁路，以及 Starter Pack 完整性和幂等安装。

此外已完成：

- Docker Desktop / WSL2 真实容器验收：审批恢复、命令执行、工作区挂载、SSE 重放、资源限制和容器回收。
- Agent 评测真实 REST 冒烟：创建 Suite/Case、双 Trial、Execution 完成、100 分报告和 Baseline 晋升。

## 已知边界

- 单机、单租户、私有部署；不实现 Kubernetes、多地域和分布式高可用。
- SQLite、本地 Artifact、进程内 Worker 和 Docker 分别替代 PostgreSQL、S3、消息队列和 MicroVM。
- Docker 不是硬件级隔离，不适合执行完全不可信的敌对代码。
- 当前提供可复用智能体专家 Profile、左侧专家创建入口、首页专家协作模式、Session 内协作任务看板，以及基于 `list_agent_profiles`/`spawn_agent(agent_profile_id)` 的 Leader 最小协作闭环；尚不包含复杂 DAG 调度、基于历史成功率的自动专家评分和跨项目 Memory 联想图谱。
- MCP 当前只支持远程 Streamable HTTP，不管理本地 stdio MCP 进程。
- 默认不依赖外部向量数据库；未配置真实 Embedding 时使用明确的本地降级。
- 图片型 PDF 支持受限 OCR/视觉路径；尚不支持音频和视频理解。
- 评测第一版是确定性安全、工具、关键文本和预算门禁，不等同于开放式语义质量评价或无偏 LLM Judge。
- 单机 SQLite 通过一次性 WAL 初始化、30 秒写锁等待和短事务承载并发；它降低锁冲突但不等同于多节点数据库，高写入规模仍应迁移 PostgreSQL。

## 文档与产品站点

- [技术架构与面试讲解](PaiCLI%20Platform%20Lite%20技术架构与面试讲解.md)
- [架构说明](docs/architecture.md)
- [阶段状态](docs/phases.md)
- [变更日志与优化复盘](changeLog.md)
- [Docker Sandbox](docs/docker-sandbox.md)
- [在线产品站](https://paicli-platform-lite.fuermalin2002.chatgpt.site)
- 产品站源码：`paicli-site/`

本地运行产品站：

```powershell
cd paicli-site
npm install
npm run dev
```
