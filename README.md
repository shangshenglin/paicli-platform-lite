# PaiCLI Platform Lite

PaiCLI Platform Lite 是一个面向单人开发、单租户私有部署的 **Managed Agent Runtime**。它不只是调用一次模型的聊天页面，而是把 Session、Run、Plan、模型推理、工具调用、人工审批、事件流、恢复、Memory、知识检索、Sandbox 和评测组织成一条可持久化、可审计、可恢复的执行链路。

当前已完成阶段 1–24，并补齐 Memory/RAG/Plan/Agent Harness、持久化 CollaborationTask、结构化团队路由、事件驱动 Leader 唤醒、受控并行、多 Shell 执行和 Context/Memory 认知控制层；自动化测试覆盖 Runtime、Store、Graph 路由、上下文、Memory、Sandbox、Console 与评测链路，并完成真实 Docker 与 Agent 评测 REST 冒烟验证。

外部 Harness 的下一步不再只是压缩单次 Prompt，而是以任务为单位控制模型调用、事件合并、Agent 交接、模型分层与成本质量评测；本机“推箱子”协作任务的真实用量复盘和可落地优化顺序见 [外部 Harness Token 成本优化方案](docs/harness-token-optimization.md)。

## 项目解决什么问题

普通 Agent 原型通常是“拼 Prompt → 调模型 → 执行工具 → 把结果再发给模型”。PaiCLI Platform Lite 重点补齐模型 API 之外的工程问题：

- 服务或 Worker 中断后，Run 如何恢复，而不是从头猜测执行到了哪里。
- 写文件、执行命令等副作用如何先持久化、按风险审批、再执行。
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
| 模型与上下文 | OpenAI-compatible 流式模型、DeepSeek reasoning、多 ToolCall、结构化工作记忆、Context Manifest、统一 Token 预算、按需工具 Schema、Artifact、项目规则 |
| Plan / Graph Runtime | 持久化 Plan/Step/类型化 Edge、确定性条件分支、有限失败回流、PlanState 快照、Human Node 决策、DAG 校验、Step 调度复用普通 ReAct Run、Validation Gate 与受控并行 |
| 受管能力 | Skill、混合 RAG、历史会话检索、可选联网、远程 MCP、持久化 Multi-Agent、多模态/OCR |
| 协作工作层 | 增强 AgentTeam、结构化 Route Decision、持久化 CollaborationTask、评论/提及/时间线、Task-Run 关联、阶段屏障与 Leader 唤醒 |
| 长期使用 | 自动分层 Memory、统一检索、知识/Artifact 治理、模板、模型方案、智能体专家、预算、队列、定时任务、通知、迁移 |
| 质量闭环 | 官方入门评测集、Context/Memory Harness 专项集、真实内部 Run、多 Trial、确定性评分、审批不旁路、人工 Baseline |
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

启动脚本会在加载 `.env` 前按 Windows 规则合并仅大小写不同的进程环境变量，例如 `NO_PROXY/no_proxy`、`HTTP_PROXY/http_proxy` 和 `PATH/Path`。这可避免代理工具、IDE 或 Agent Runtime 重复注入变量后，PowerShell `Start-Process` 抛出 `Item has already been added`。

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

脚本默认跳过测试并构建 Server 与 Sandbox 镜像。8080 已有 PaiCLI 服务时会直接返回；修改代码后需要重建并重启时使用：

```powershell
.\scripts\start-docker.ps1 -Restart
```

这样会先停止旧服务，再重打包 Server，避免 Windows 锁定运行中 JAR 导致 Spring Boot `repackage` 失败。完整回归测试仍使用 `.\mvnw.cmd clean test` 显式执行。

也可以分步执行：

```powershell
.\scripts\build-sandbox.ps1
java -jar .\paicli-server\target\paicli-server-0.6.0-SNAPSHOT.jar `
  --paicli.sandbox-mode=docker
```

`build-sandbox.ps1` 只构建 `paicli-common` 与 `paicli-sandbox-agent`，默认跳过测试；需要同时运行这两个模块的测试时传入 `-RunTests`。

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

如需在同一个 Console 中按对话或专家自由切换 DeepSeek 与 Kimi，在 `.env` 中保留
`PAICLI_MODEL_PROVIDER=openai-compatible`，并分别配置两个只存在于 Server 进程的密钥：

```powershell
PAICLI_DEEPSEEK_API_KEY=replace-with-deepseek-key
PAICLI_KIMI_API_KEY=replace-with-kimi-key
```

模型方案接口会为每个项目幂等补齐 `DeepSeek V4 Flash` 与 `Kimi K3`。首页模型轨道、对话输入区和专家设置使用同一批方案；选择 Kimi K3 时，Runtime 使用
`https://api.moonshot.cn/v1`、`kimi-k3`、1M 上下文，并按 K3 协议省略 K2.x 的
`thinking` 字段，发送 `reasoning_effort=low|high|max`。Kimi K3 始终思考，界面中的关闭开关会禁用。

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

### Prompt Cache 命中率优化

优化前的实际样本为输入 Token `8,714,118`、缓存命中 Token `544,640`，累计命中率约 `6.25%`。主要原因不是模型没有缓存能力，而是早期 Prompt 中包含每轮变化的墙钟时间，RAG、Memory 和 Run 工作区等动态内容又位于长历史之前；任一动态块变化都会截断后续原本可以复用的共同前缀。

当前按以下顺序组装模型上下文：

```text
稳定系统前缀
  = 基础 Prompt + 安全规则 + Agent 循环
  + 专家配置 + 项目规则 + Skill 索引
→ 已归档摘要
→ 当前 Run 之前的会话历史
→ Run 动态上下文
  = 持久化 Run 基准时间 + 工作区 + RAG + Memory
→ 当前 Run 的 user / assistant / tool 消息
```

具体优化包括：

- 不再在每轮调用的早期 Prompt 中写入 `Instant.now()`；同一 Run 始终使用持久化的创建时间作为基准时间。
- 将既有摘要和历史移动到 RAG、Memory、工作区等 Run 动态块之前，使同一会话的新 Run 仍可复用已有长前缀。
- 基础 Prompt、专家指令、项目规则和 Skill 索引使用稳定顺序与系统消息角色；内容或规则真正变化时才合理失效。
- Tool Definition/Schema 纳入估算输入 Token，避免只计算消息文本而低估真实请求。默认上下文只常驻文件、命令、Artifact 与 `tool_search` 等核心工具；Knowledge、Skill、MCP、Multi-Agent 等扩展工具由 `tool_search` 返回能力目录后，在下一模型轮次加载完整 Schema，减少稳定前缀体积和无关 Schema 波动。
- 必需指令、历史、当前 Run 消息和工具 Schema 先占预算；RAG 与 Memory 共享剩余动态预算，超限时写入明确标记并有界裁剪。
- 每轮在 `run_events` 写入 `context.prepared` Context Manifest，记录可复用前缀 Token 和 SHA-256、工具 Token、各消息分区 Token、Plan 状态、RAG citation/命中理由、实际选择的 Memory id/理由、完整工具名/选择理由、动态激活工具和被丢弃来源，便于解释后续命中率与上下文选择。

缓存效果必须按部署新版本后的增量窗口计算：

```text
增量缓存命中率 = 新增 cached input tokens / 新增 input tokens
```

历史累计数据会长期稀释优化后的结果。同一 Run 的多轮 ReAct、包含稳定历史的连续 Run 最容易获得提升；短于供应商最小缓存前缀、模型/工具集频繁切换、项目规则实际变化或供应商不支持 Prompt Cache 时，命中率仍可能较低。

### 轻量 WorkingPlan 与语言一致性

- **WorkingPlan**：普通 Run 内可维护一个轻量工作清单（目标 + TODO/IN_PROGRESS/COMPLETED/BLOCKED 条目）。主 Agent 通过 `update_working_plan` 创建/修订；`run_working_plans` 每 Run 单行、revision 自增，`ContextManager` 每轮只注入最新修订，随 Run 结束自然归档。它不是 Formal Plan：不创建 PlanStep、不经过 PlanWorker、无 DAG、无 PlanValidator。简单问答不产生计划，避免额外 Token 与状态负担；复杂多步任务由模型按需建立。
- **语言一致性**：系统提示不再固定用中文作答，而是要求与用户最近一条消息语言一致，并显式要求“用户中文时全程中文（代码/命令/标识符/专有名词除外）”；`ContextManager` 按当前 Run 用户消息的中/英占比注入显式 `<language>` 指令（中文问中文答、英文问英文答，短中文指令也覆盖），协作任务复唤醒的 Leader Run 同样遵守。前端展示固定为中文。

### 完成验证、失败反思与只读工具批次（Harness Loop v2 PR2–PR4）

- **CompletionVerifier**：最终答案非空不再等于完成。Run 执行过写操作但工作区无变化、或测试命令失败时，平台把验证结果注入下一轮并要求修复，连续 2 次仍不过才 `FAILED`；普通问答仍直接完成。验证结果持久化为 `run.verification` Event。
- **失败反思**：测试/工具失败与重复工具调用会记录结构化 `run_reflections`（失败分类、诊断、决策、证据引用、下一步），每轮注入最新 `<reflection>`，不保存模型隐藏思维链；重复相同工具+参数超限后停止 Run。
- **只读工具批次**：同一模型响应中连续的只读 ToolCall 单次领取并行执行（≤4 并发），按模型原始顺序写 Tool Message；写工具与审批工具保持顺序执行屏障。

### 专家交付协议、写隔离与任务摘要（Harness Loop v2 PR5–PR8）

- **结构化专家交付（PR5）**：服务端统一构建委派信封（`DelegationEnvelopeBuilder`），`AgentResultValidator` 校验子结果——无证据 `COMPLETED`、无证据的测试通过声明、无错误的 `FAILED` 都会被标记；`get_agent_result` 返回 `validation` 结论。普通 Expert 默认不能嵌套委派。
- **工作区写隔离与冲突检测（PR6）**：新增 `WorkspaceMode`（SHARED_READONLY/SHARED_SERIAL/ISOLATED_WORKTREE）并按角色默认映射；`WorkspaceMergeService` 检测并行子交付的变更文件冲突，冲突必须先仲裁再合并。
- **任务摘要与交付清单（PR7）**：Leader 复唤醒注入 `<task_digest>`（目标、状态、阶段、阻塞、最近人工指令、增量活动、交付），无需重读全量历史；阶段交付记录 `collaboration_deliveries` 清单（变更文件/Artifact/测试证据/内容哈希）；人工 `ACCEPT` 生成不可变验收快照。
- **路由评分、评测与前端（PR8）**：路由综合评分引入历史验证通过率与当前负载；官方评测集新增“官方·08 Harness Loop”用例；Run 审计页展示 WorkingPlan/反思/完成验证；效率工作台“长期记忆”“持久化审批策略”“Artifact 工作台”列表默认收缩；长期记忆移除关系地图（图谱）视图，保留 Wiki 页面浏览。

### Context 与 Memory Harness 补齐

本轮把 Context 和 Memory 从“能拼进 Prompt”推进为可持久化、可解释、可反馈的认知控制层：

- **来源冻结与回跳**：创建 `memory_extractions` job 时把该 Run 的 Message id、序列、角色、正文和 ToolCall 引用序列化为不可变快照。Worker 后续只能读取快照；每条自动 Memory 在 `memory_sources` 保存证据 Message id 列表、起止 sequence 和摘录，`GET /v1/memories/{memoryId}/sources` 可直接回跳证据范围。
- **统一 Context Manifest**：Rules、Skill 索引、工具 Schema、摘要、历史、PlanState、Memory、RAG 和当前 Run 进入同一输入预算；Manifest 记录各区块 Token、选择原因、引用、裁剪与丢弃项，不再只在超限时失败。
- **结构化工作记忆**：压缩摘要固定包含“目标与硬约束、计划状态、已验证事实、未验证假设、技术决策、失败尝试、待办与下一步、证据引用”八节。模型摘要缺节、乱序或超预算时拒绝采用，并回退到同 Schema 的确定性摘要。
- **按需工具加载**：常驻核心工具加 `tool_search`；扩展能力只先暴露轻量目录项，模型搜索后才在后续轮次注入匹配工具的完整 Schema。Agent Profile 的显式工具白名单仍是上限，工具发现不能绕过权限。
- **Memory 归并与反馈**：自动提取对高相似候选复用 canonical key，中等相似候选进入 OPEN conflict；L1 长期未访问记录自动标记 STALE。召回执行类型配额，保存本轮选择的 Memory id 与理由，并用 Run 完成/失败、Plan 验证通过/返工结果形成历史效果分，参与后续排序。
- **协作降噪与证据门禁**：自动提取只在委派树根 Run 完成时排队；每个根 Run 最多写入 3 条（L1 至多 1、L2 至多 2、L3 至多 1）。流程事件、空/伪造证据和仅 Assistant 自述都会被丢弃；最终置信度由模型分数、证据质量、重复出现程度和层级稳定性校准，用户陈述上限 0.80，用户陈述加成功工具结果上限 0.95。
- **专项评测**：官方 Starter Pack `1.1.0` 新增“Context 与 Memory Harness”套件，覆盖长会话约束保持、摘要续作、错误记忆抵抗、冲突修正、按需工具发现与统一上下文预算；依赖夹具的用例默认关闭，由用户准备数据后启用。

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
- `execute_command` 支持固定白名单 `sh`、`bash`、`powershell`（PowerShell Core / `pwsh`），并支持受控 `cwd`、请求级 `timeoutSeconds`、`maxOutputBytes` 与显式非敏感 `env`；不接受任意解释器路径。
- Run 和 Agent Profile 都持久化默认 Shell。模型省略 `shell` 时，Server 会在同轮 ToolCall 原子落库前补入 Run 默认值，使 Approval、幂等键与恢复执行复用同一组最终参数。
- stdout/stderr 分开收集并持续排空，结果记录实际 Shell、退出码、耗时、超时、字节数和截断状态；超过模型内联预算的输出由既有 Artifact Store 保存并可通过 `read_artifact` 分段读取。
- `write_file` 与普通读取、构建、测试命令在 ToolCall 持久化后直接执行；`execute_command` 中的删除/清空、提权/权限修改、进程/系统控制、破坏性 Git/数据库操作、下载安装、远程执行、发布和部署命令先创建持久化 Approval。工具调用和实际审批均写入 JSONL Audit。
- Server 启动时清理带 PaiCLI label 的孤儿容器，并覆盖 Fake Docker 与真实 Docker 验收。

危险工具审批示例：

```http
GET /v1/approvals
GET /v1/approvals?runId={runId}
GET /v1/approvals?projectKey={projectKey}

POST /v1/approvals/{approvalId}
Content-Type: application/json

{"decision":"APPROVED","rememberScope":"SESSION"}
```

`runId` 可选；传入后返回当前 Run 所属整棵父子委派树的待审批项。`projectKey` 可选；不传 `runId` 时返回该项目全部待审批项，供 Console 的全局审批入口汇总专家和子专家操作。`rememberScope` 可为仅本次、不创建策略，或 `SESSION`、`PROJECT`。持久化策略只复用相同工具名和完全相同的参数 SHA-256，参数变化后必须重新审批。

服务升级后会重新检查历史未决的 `execute_command` Approval：按当前风险规则属于普通读取、构建或测试的命令自动批准并重新排队，危险命令继续等待人工确认。该恢复只处理已持久化的原参数，不会让模型重新生成命令。

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

- 支持 DeepSeek V4 Flash/Pro、每 Run 思考模式和推理等级；默认不按单 Run 累计 Token 或墙钟时长截断任务，可通过 `PAICLI_MODEL_MAX_RUN_TOKENS`、`PAICLI_MODEL_MAX_RUN_DURATION_SECONDS` 设置正数启用可选保护阀。步骤数、工具调用次数、单次模型请求和流空闲超时保护仍然生效。
- `reasoning_content`、assistant `tool_calls` 与工具结果会持久化，并在后续模型轮次正确回传。
- OpenAI-compatible 流若以 HTTP 200 结束但只有 reasoning、没有最终正文和 ToolCall，客户端会在模型最大尝试次数内记录一次 `RETRY`，追加停止重复分析并立即执行的纠偏消息，并在恢复尝试中关闭 thinking（Kimi 降到 `low`）后重试；恢复产生无 `reasoning_content` 的 ToolCall 后，后续轮次保持无思考模式，避免 DeepSeek 把混合模式历史判为非法。若流后的 ToolCall 参数被截断或不是有效 JSON，下一次请求会要求缩小参数、按文件和轮次拆分写入。恢复失败仍由 Run 门禁终止，不会误报完成或直接推动协作阶段交付。
- 解析 Prompt Cache 用量，兼容旧 SQLite 结构，并完成真实 Docker 容器验收。

### 阶段 6：Runtime 正确性与聊天 Console

- 一轮多个 ToolCall 与 assistant 消息先原子落库，再按 Provider 顺序逐个执行和审批。
- 支持主动取消模型 HTTP/SSE 请求、受控项目规则、批量 delta 持久化和终态对账。
- Console 支持聊天、流式 reasoning、Run 时间线、审批、Session 分组/移动/删除和独立滚动。
- 删除 Session 前拒绝活跃 Run；随后在同一事务清理 Approval、ToolCall、Event、Artifact、ModelUsage、ModelAttempt、MemoryExtraction、CollaborationPolicy、AsyncJob、Message 和 Run。删除分组只将 Session 移到未分组。

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
- 多 Skill 仓库可指定准确目录名；GitHub tree/blob 链接会转换为仓库地址与 Ref，并优先从链接路径推断 Skill 目录名。

#### 知识库与混合 RAG

- 支持文本、Markdown、PDF、Word、PowerPoint、Excel、CSV、HTML、JSON、XML、RTF、EPUB 和 OpenDocument。
- Tika 提取正文；分块器保留标题层级、段落、句子、列表、表格和代码围栏结构。
- 检索组合 BM25 与真实 Embedding，使用 RRF 融合、标题/短语 boost、重叠去重和单文档配额。
- 可接 Ollama 或 OpenAI-compatible Embedding；未配置时明确使用本地确定性降级。
- 扫描 PDF 可用 PDFBox 渲染，再由当前视觉模型 OCR；OCR 不可用时仍可作为当前 Run 的视觉附件。
- Knowledge 管理支持集合、标签、版本、索引状态、重建索引、引用定位和有用/无用反馈。

#### 历史会话检索与联网

- `session_search` 仅在 Agent 主动调用时，对当前项目的用户可见历史消息执行 BM25 检索，排除内部子会话与当前 Run。
- 联网默认关闭。启用 `PAICLI_WEB_ENABLED` 和 SearXNG-compatible 搜索端点后提供 `web_search`、`web_fetch`、`github_repo_fetch`，并在普通会话中**默认直接可见**（无需先 `tool_search`），仍受 Server 侧 SSRF 防护（`web_fetch` 只允许公网 HTTP(S)，私有/内网目标被阻断）。
- 网页抓取限制响应大小，并在每次重定向时重新拒绝 loopback、链路本地和私网地址；GitHub 仓库首页会优先走 GitHub API，`github.com/.../blob/...` 会优先转换为 `raw.githubusercontent.com`，避免抓取 GitHub HTML 页面。

#### MCP

- 支持远程 Streamable HTTP MCP Server，工具注册为 `mcp__{server}__{tool}`。
- 能力管理提供 GitHub MCP 快速配置模板，默认保存 `https://api.githubcopilot.com/mcp/` 与 `Authorization: env:GITHUB_MCP_TOKEN`；专家工具白名单支持 `mcp__github__*` 前缀通配，用于允许该 Server 下动态发现的全部 GitHub MCP 工具。
- 全部 MCP 工具强制持久化审批；Schema、参数和响应都有大小预算，连续失败触发短时熔断。
- 敏感 Header 使用 `env:VARIABLE_NAME`，真实密钥只在 Server 出口解析，不进入模型上下文或 Sandbox。
- Console 可新增、测试、启停、删除 Server，并查看工具 Schema、健康和熔断状态。

#### 持久化 Multi-Agent

- `agent_profiles` 保存可复用的智能体专家定义，包括专家指令、模型方案、专家级思考开关/深度、工具白名单、Skill 白名单、输出契约、协作角色、交接策略、工作区范围和审批策略。
- 创建 Run 时可传 `agentProfileId`，Server 会把专家指令注入系统上下文，并优先使用专家绑定的模型方案与思考设置；未设置的专家字段继续继承对话级设置。Leader 派发的子专家同样使用各自绑定的模型和思考深度。
- 工具白名单会过滤传给模型的 Tool Definition；Skill 白名单会过滤上下文中的 Skill 索引，为后续 Leader/Worker 小队调度预留稳定专家目录。
- `spawn_agent` 经审批后创建内部子 Session/Run，并以父 ToolCall 为唯一键避免恢复时重复派生。
- 委派限制三层深度、每个父 Run 最多六个子 Run；取消父 Run 会级联取消后代。
- 父 Run 通过 `get_agent_result` 查询结果，不同步占住 Worker 等待。
- `list_agents`、`cancel_agent` 继续走普通 ToolCall 和审批链路。

#### 图片与文档附件

- 每轮最多暂存 4 张 PNG/JPEG/GIF 和 4 个文档。
- Server 校验真实图片字节、尺寸与大小，必要时压缩；附件 id 在创建 Run 时原子绑定 user Message，不能重复使用。
- 只有当前 Run 的图片按 OpenAI-compatible `image_url` 注入，历史图片不重复发送。
- 当所选纯文本模型明确拒绝多模态内容时，Server 会自动降级为文本请求并要求模型用中文提示切换视觉模型，避免整个对话 Run 因图片能力不匹配而失败。
- 文档写入当前项目知识库并作为本轮优先 RAG 来源；“总结附件”使用跨文档分段采样，具体问题使用检索。
- 扫描 PDF 默认最多处理 6 页、150 DPI，可通过 `PAICLI_RAG_PDF_OCR_*` 调整；图片、OCR 中间结果和模型 Key 都不进入 Sandbox。

### 阶段 8：自动 Memory 与单机治理

只有委派树根 Run 完成后才持久化一次 `memory_extractions` job，再由 Worker 从受限对话窗口提取偏好、事实、约束、决策和经验，避免 Leader 与多个子 Agent 重复沉淀：

- job 创建时冻结所属 Run 的不可变消息快照，Worker 不读取稍后变化的 Session；自动 Memory 保存来源 Message id、序列范围和摘录。
- L1/L2/L3 Memory 保存类型、置信度、来源 Run/Session、访问统计和生命周期状态；长期未访问的短期 L1 可进入 `STALE`。
- 同一 key 的新值替换当前事实，旧值进入 `memory_revisions`；高相似候选复用 canonical key，中等相似候选进入 `memory_conflicts` 人工审计队列。
- 召回综合词法/语义相关性、置信度、时间衰减、层级、类型配额、置顶、启用状态和历史效果反馈。
- 每个 Run 记录被选入上下文的 Memory；Run 终态和 Plan 验证结果回写完成、失败、验证通过或返工结果，用于后续排序分析。
- 显式 REST CRUD、人工确认、启停、置顶、合并、修订与历史恢复构成人工纠错边界。
- Console 的“新增 L3 长期记忆”入口复用 `POST /v1/memories`，创建默认的人工 L3 Memory，适合主动录入稳定偏好、长期约束与可复用背景。
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
- Memory 展示来源、层级、类型、置信度和修订历史，支持置顶、启停、确认、合并、编辑和版本恢复；同时提供不改写原始数据的 LLM Wiki 页面和分层可点击关系图，标题从事实内容概括而非内部 key。
- Knowledge 展示集合、标签、版本、分块数、Embedding Provider、索引状态、引用位置和反馈。
- Artifact 支持列表、预览、分段读取、认证下载、删除，以及复用为指定 Session 的待提交附件。

### 阶段 11：长期使用效率

- **任务模板**：保存 Prompt、`${变量}`、附件要求、允许工具和模型方案；内置 `/review`、`/summarize`、`/research`。
- **模型方案**：保存用途、Base URL、模型、后备模型、上下文/输出上限和价格；密钥只保存环境变量名。
- **智能体专家**：保存专家指令、模型方案、独立思考模式/深度、工具/Skill 白名单和输出契约；首页左上角固定提供唯一的“普通对话 / 专家协作 / 协作任务”模式切换。复杂普通对话会由后端自动评估，必要时选择 Leader、创建协作执行计划并调用子专家；简单问题仍保持单 Agent 对话。当前 Session 会持续展示最近一次根协作计划和子专家任务看板，后续普通续聊或计划模式不会让工作空间入口消失。
- **专家模板治理**：内置 Leader/需求/实现/测试/审查/文档专家带稳定模板 key/version；读取专家列表时，仍绑定旧内置模板版本的 Profile 会升级提示词和最小工具集，同时保留其模型、思考档位、Shell 与启用状态；左侧“专家创建”支持编辑、复制为新专家、恢复内置模板版本。不同协作角色有默认工具和审批策略，非 Leader 默认不能继续派发子 Agent，后端 `spawn_agent` 会校验策略允许的专家、深度和数量上限。
- **执行小队**：`agent_teams` 持久化 Leader、成员专家、团队指令、成员角色说明、能力标签、路由/完成策略、回退 Agent、并发人数、委派深度和 Reviewer/Runner 要求；“专家创建”支持小队 CRUD、路由预览和团队指标。父界面持续聚合整棵委派树审批，即使父 Run 已终止也继续刷新；子专家页面提供返回父专家入口。
- **用量与预算**：按日期、项目、Session、模型统计调用、Token、缓存、耗时、失败、重试和估算成本；支持日/月 Token、费用提醒和项目最大并发。
- **Run 队列**：支持 `-10..10` 优先级、项目公平领取、批量取消、失败/取消 Run 重新排队，并显示步骤、耗时和重试次数。效率工作台可勾选终态 Run 批量永久删除；后端在同一事务清理 Run 及消息、工具调用、审批、事件、用量、委派关系和 Artifact 元数据，目标或其关联委派树仍活跃、或任一 ID 缺失时会使整批回滚。
- **定时任务**：引用已保存模板，支持一次性、每日、每周和 Spring 六段 Cron；创建时可独立固定模型方案，或选择一个执行专家、一个执行小队（二选一）。小队任务以保存的 Leader、成员、并发和审查策略创建协作 Run；每次触发仍创建普通 Session/Run。
- **完成通知**：浏览器、Webhook、邮件网关和企业 IM 网关；事件覆盖完成、失败、等待审批和预算不足，密钥仅引用 Server 环境变量。
- **Session 迁移**：导出 Markdown、JSON 或包含 Event/ToolCall/Approval/Artifact 的完整审计包，支持脱敏和跨实例导入。
- **Skill/MCP 生命周期**：预检、启停、固定、升级、回滚，以及 MCP Console 配置和健康展示。
- 模板、模型方案、定时任务、通知、Memory 合并和修订都使用结构化 Dialog，避免连续弹窗造成半完成状态；智能体专家统一由首页左侧“专家创建”维护，避免与效率工作台重复。
- Console 首页将“效率工作台”“专家创建”和“Agent 评测中心”作为独立入口；效率概览只固定展示核心指标，最近用量放入可折叠、限高滚动的明细区，数据增长不会继续撑高指标卡区域。

### 阶段 12：Agent 评测中心

评测中心把模型行为回归作为产品能力，而不是只写 Java 单元测试：

Console 首页提供独立的“Agent 评测中心”入口，不再嵌套在效率工作台中。评测中心采用“套件/报告”双栏工作区，套件用例默认折叠、两栏分别滚动，避免评测集和报告随数量增长连续堆叠。运行前可选择单 Agent 基线或一个 AgentTeam；团队 Trial 由保存的 Leader 启动并固化团队协作策略。“安装官方评测集”会幂等安装版本化 Starter Pack；已有同名 Suite/Case 会保留，不覆盖用户修改。当前 `1.2.0` 包含 7 个套件、28 个用例：

- **基础行为与安全**：固定输出、只读工具、无工具回答、密钥拒绝和 Prompt Injection 防护，可直接运行。
- **工具与审批**：写文件、普通读取/构建/测试命令、危险命令等待审批、破坏性命令拒绝和写后读取；只有风险分类命中的命令会真实等待 Approval。
- **上下文与受管能力**：Knowledge、Session Search、Skill、Web 和 Multi-Agent；依赖项目数据或外部配置，默认停用，可在 Console 按需启用。
- **稳定性与预算**：默认 3 Trial，检查固定指令、随机工具调用、输出 Token 和耗时预算。
- **Plan DAG 与验证**：结构化计划和验证证据模板，默认停用。
- **Context 与 Memory Harness**：长会话、结构化摘要、错误/冲突 Memory、工具发现和统一输入预算，默认停用。
- **AgentTeam 协作 Harness**：Leader 拆分、Reviewer/Runner 角色路由和结果汇总，默认停用，需选择执行团队。

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

SQLite `schema_migrations` 当前记录版本 1–38：版本 1–27 覆盖基础 Runtime、Plan/Graph、专家执行小队、Delegation Graph、Memory/RAG 与 Context Harness；28 增强 AgentTeam 并为评测 Execution 增加团队执行者，29 增加 CollaborationTask、评论、活动、Trigger、Mention、Task-Run 与 Route Decision，30 增加幂等事件触发和阶段屏障，31 将小队的有效并发持久化到协作 Run 树并在领取队列时执行，32 会关闭已终态 Run 遗留的待审批记录，33 会把仍有活跃阶段 Run 的历史根任务从错误的 `IN_REVIEW` 恢复为 `IN_PROGRESS`，34 将同一根协作任务的历史 Run/委派树归并到稳定任务工作区，并启用阶段交付证据门禁，35 为每个 Run 增加轻量 WorkingPlan（单行 upsert、revision 自增），36 增加持久化 Run 反思（结构化失败分类与决策，不含隐藏思维链），37 增加协作任务摘要、阶段交付清单与人工验收快照，38 增加 ExpertThread 专家线程与线程-Run 绑定（同一专家在同一协作任务内的逻辑连续性）。

### 协作任务状态与交付语义（阶段 22–24 补充）

- 阶段子 Run 成功结束后，平台必须通过 `persistStatus` 完成对应 `StageBarrier`，再以幂等 `STAGE_BARRIER` Trigger 唤醒 Leader。Leader 派发阶段后同一 Run 原地等待该子 Run，子 Run 终态后原地恢复并继续推进；若 Leader Run 在未发布结论时提前终态，平台会先对已完成但缺失 `STAGE_BARRIER` Trigger 的 Barrier 补发一次唤醒（每个阶段最多一次），只有无法唤醒时才置 `BLOCKED`；任务已进入 `IN_REVIEW`（人工验收）时不再补发唤醒，避免重启对账把已交付任务重新置回执行。Leader 的唤醒输入要求读取阶段交付证据，继续派发后续阶段或发布根任务结论；服务启动会补偿旧版本遗留的 `WAITING` Barrier，以及已完成但尚未持久化 Leader Trigger 的 Barrier。
- Team 根任务不能仅凭所有关联 Run 终态进入 `IN_REVIEW`：至少要有一个已交付的阶段，并且 Team Leader 必须在最后一个阶段交付之后发布结论评论。Leader Run 提前结束且无结论时，平台先补发阶段屏障唤醒；仍无进展才保持 `BLOCKED`，不会把未汇总的阶段成果错误交给人工验收。
- 根协作任务是左侧列表与历史中的唯一任务记录。由 Leader 创建的阶段 1、阶段 2 等任务必须带有 `parentId`，只在根任务的“子任务与阶段”区域显示，不能作为独立任务出现。
- Agent 只能报告 `IN_PROGRESS` 或 `BLOCKED`。阶段子 Run 结束后由平台标为已交付并等待 Leader 汇总；根任务及其全部阶段 Run 都已终态后，平台才可将根任务置为 `IN_REVIEW`，随后由人工 `ACCEPT` 完成最终验收。
- 同一父任务、阶段和负责人只能存在一个活跃或已交付的阶段任务；同一组合连续两次进入 `BLOCKED` 后，Leader 不能再自动复制第三个阶段任务，必须报告明确阻塞原因并等待人工干预。不同负责人仍可在同一阶段并行执行独立工作。
- “执行”页的“最终交付”只在根任务完成并通过最终交付门禁后显示，列出根 Leader 与阶段专家共享 workspace 中的真实文件，可直接预览或下载；任务仍处于协作或验收过程中时不会提前暴露为“最终交付”。HTML 预览会读取并内联工作区内的相对脚本、样式和图片，再运行于无同源权限的 iframe；因此多文件网页可直接运行，但不能借交付页面读取 Console 凭据、调用 PaiCLI API 或访问外部网络。
- 已取消、失败或完成的协作任务可以删除协作层记录：任务树、评论、动态、Trigger、Route Decision 和 Task-Run 关联会移除，而已结束的 Run、会话和交付文件继续保留。仍有活跃 Run 时必须先使用“取消任务”。整体取消会先取消任务树内全部活跃 Run，并把仍处于 `BACKLOG/TODO/IN_PROGRESS` 的后代阶段任务一并置为 `CANCELED`，避免子树残留“执行中”状态。

### 阶段 22–24：持久化专家协作工作层

- `CollaborationTask` 是跨 Run 长期存在的工作项，保存 Agent 或 AgentTeam 负责人、状态、优先级、可选完成条件、父子关系、阶段和可选 Plan 引用；Run 只表示一次执行尝试，二者通过 `collaboration_task_runs` 多对多关联。人工不是任务负责人，而是可以在任意阶段追加评论/指令，并通过显式动作启动、继续、阻塞、返工、验收、取消或重新打开任务。
- Route Preview 使用团队能力、成员角色、任务词、复杂度与风险生成结构化候选、Leader、原因和预计并发；复杂度/风险是当前文本启发式路由推断，不是任务表单的固定配置。预览不创建 Run，真实 Trigger 会同时持久化 Route Decision，并把有效并发写入根协作策略；同一委派树的活动子 Run 不会超过该上限，且仍受项目最大并发约束。
- 评论、结论、回复和显式 Mention 写入任务时间线。用户评论默认唤醒任务负责人，回复 Agent 评论回到原 Agent，成员结果事件唤醒团队 Leader；Trigger 以 idempotency key 去重，恢复不会重复派发。若目标负责人已有活跃 Run，不并发创建第二个 Run，而是把评论直接注入该活跃 Run 的会话，让执行中的专家在下一轮读取并回应；返工/评论理由会进入新 Run 的指令，并要求 Leader 原样写入其派发的阶段子任务。已交付待人工验收（IN_REVIEW）的任务若被评论/提及触发新的返工 Run，会先置回 IN_PROGRESS，待返工 Run 终态且重新具备交付证据后再回到 IN_REVIEW，避免状态停留在“待验收”却又有执行在跑。根任务的「评论与决策」按时间顺序聚合整棵任务树的评论（人工、Leader 与各阶段子 Agent 的最终回复都可见，子 Agent 评论带“阶段 N · 负责人”标识，人工评论带“人工评论”标识），「协作动态」同样聚合各阶段的执行、并行交付与阶段屏障事件；返工/阻塞原因会作为人工评论落库，并进入复唤醒摘要的最新人工指令。
- Agent 可通过受 ToolCall/Approval/幂等边界约束的协作工具读取任务、发评论、报告进度/阻塞和创建阶段子任务。`create_collaboration_subtask` 会原子创建子任务、派发直接专家 Run 并绑定二者；根任务的全部 Leader 唤醒 Run、默认阶段 Run 和委派后代使用同一个稳定任务工作区，即使 Barrier 在新 Session 中唤醒新的 Leader Run，也能读取此前交付。只有显式逻辑 `workspace_ref` 才建立隔离目录，适合需要独立产物和后续显式合并的并行工作；误把当前协作工作区的文件系统路径填入该字段时按继承处理，避免子专家落入空目录。评论和提及始终持久化，但同一任务树中的目标 Agent/Team Leader 已有活跃 Run 时，不再并发创建第二个评论、回复、子专家终态或阶段屏障 Run。阶段 Run 必须留下本 Run 的写文件证据、Artifact 或任务评论，否则阶段和父任务转为 `BLOCKED`，不完成 Barrier。Artifact 仅指真实交付产物，只读工具大结果外置的 `tool_result` 不视为交付证据（只读后直接结束的“空交付”阶段会正确转为 BLOCKED）；Leader Run 失败但任务树已有已交付阶段时，根任务回到 `IN_REVIEW` 等待人工重新验收，而不是直接 `BLOCKED`。单 Agent 任务只能由被分配 Agent 更新任务级状态，Team 任务只能由 Team Leader 更新；根 Team Leader 只有在除当前 Run 外的阶段、委派和并行 Run 全部终态后才能发布最终结论。Run 完成不会自动把顶层任务改为完成，只有人工 `ACCEPT` 才能进入 `DONE`。
- Console 的“协作任务”工作区分为任务、协作、执行三层：创建区默认只展示标题和 Agent/AgentTeam 负责人，任务说明与完成条件收在“更多设置”；任务层按当前状态展示可执行的人工动作和 Route Preview；协作层把任务建立、执行派发、专家协作、人工验收组织为阶段进度，并展示参与角色/关联执行/评论指标、评论与决策、中文语义协作动态。任务详情每 3 秒从同一响应同步评论、活动、Run 和状态，输入评论时暂停重绘以保留草稿。执行层在任务未完成时也展示“当前工作区产物”，明确其仍可能被后续阶段修改；`WAITING_AGENT` 显示为 Leader 等待子专家，不再与最终验收混淆。执行层对根任务 Run 显示中文触发语义（触发执行/人工发起/阶段完成触发等）与真实状态（失败/完成）；任务处于待验收且存在失败 Run 时，提示核验交付后再验收（ACCEPT）或带原因返工（REQUEST_REWORK），避免把失败执行误读为卡住。整页刷新会恢复上次查看的页面：普通/专家对话回到最后打开的 Session，协作任务回到选中的任务及其视图（任务/协作/执行层），仅在没有持久化记录时才落到中性首页；历史仍保留在左栏按需打开。主 Header 还会汇总当前项目的待审批项，专家或子专家请求审批时无需进入会话即可直接处理。三模式切换位于始终可见的主 Header，不再只存在于首页内容；左侧历史按项目和既有自定义分组统一展示普通对话、专家协作与协作任务，并使用类型标签区分。一个协作任务树下的根 Leader 会话、阶段子任务会话和复唤醒会话都会折叠到同一条任务记录，不再混入普通/专家对话历史。原“新建对话”按钮已移除，用户通过全局模式切换返回普通对话首页，首次发送时按原有惰性逻辑创建会话。普通/专家会话保留移动分组、删除和打开能力；无 Run 历史的协作任务可以永久删除，已有 Run 历史的任务只能通过“取消任务”保留审计链路。由任务打开关联会话时，顶部固定提供“返回协作任务”，返回后恢复原任务的执行层。执行层把 `agentProfileId`、`modelProfileId` 映射为专家名称和“模型方案 · 实际模型”，评论作者、提及目标、工具、队列、计划审计与审批等常规界面也优先展示业务名称，不把内部 ID 作为主文案。
- 协作任务触发时不读取首页当前模型选择：单 Agent 使用该负责人，AgentTeam 使用路由选出的 Leader；负责人绑定模型优先，否则使用项目默认模型，项目未配置默认方案时使用服务端默认模型。团队子专家绑定模型时使用自己的模型，未绑定时继承父 Run。取消 Run 会自动拒绝其未决审批，启动恢复也会清理已终态 Run 遗留的 `PENDING` 审批，避免全局待审批计数滞留。`GET /v1/collaboration/tasks/{taskId}` 的 `runs[]` 同时返回 Agent/模型方案引用、模型方案名称和最近一次真实调用记录的 `modelName`，Console 因此可以展示最终执行者和实际模型；例如服务端默认最终路由到 Kimi 时显示 `kimi-k3`，而不是含糊的内部 ID。
- 同一专家在同一协作任务内的多次执行通过轻量 `ExpertThread` 保持逻辑连续性：`root_task_id + agent_profile_id + thread_role` 唯一确定一个线程，终端 Run 永远不复活，后续再次执行创建新 Session + 新 Run 并挂到原线程（不同任务/不同专家/不同 role 各自独立线程）。角色规则：只有真正的小队 Leader（TEAM leaderAgentProfileId）使用 `LEADER` 线程并继续由 TaskDigest 提供任务级连续性；团队阶段专家、直接提及的团队专家以及**单 Agent 任务的被指派 Agent** 一律使用 `EXPERT` 线程——普通再次 @ 专家、Leader 再派同一阶段专家（`createAndDispatchSubtask`）、单 Agent REQUEST_REWORK 都会在 Run 输入注入 `<expert_thread_resume>` 紧凑摘要（最新 Run 状态/摘要、仅本专家负责阶段的已完成/剩余工作、blockers、changed files、artifact refs、test 报告引用、最新人工指令），ToolResult 全文、Artifact 正文、reasoning 与全量旧对话一律不进摘要；Digest 的阶段与变更文件只按本专家归属：阶段按 `assigneeId` 过滤，changed_files 只消费该 Run 自己的 DeliveryManifest 记录，共享工作区里其他专家写下的文件不会串入。需要具体内容时模型按需 `read_file/read_artifact`。`GET /v1/collaboration/tasks/{taskId}` 的响应新增 `expertThreads`（线程 + 绑定 Runs 的 ordinal/实时状态），Console 执行层按“专家线程”分组展示 `#序号 状态` 并可直接打开对应会话。永久删除终态 Run 时，其线程绑定同步清理，`latest_run_id` 从剩余绑定重选（无则置空），受影响线程的摘要置空避免注入已删除 Run/Artifact 引用，下一 Run 终态时重建。
- 模型执行期间收到的新评论不会丢失，且检查与完成在同一事务内：`ContextManager.PreparedContext` 记录本次模型上下文构建时的最大 message sequence，`RunProcessor` 通过 `commitFinalAssistantAndComplete(..., expectedSequence)` 在单个 SQLite 事务里“比对最新 active sequence + 置 COMPLETED”，模型调用期间有新输入则整体回滚不完成，再由 `commitIntermediateAssistantAndRequeue` 单事务持久化 `run.new_input_during_model` 事件（含 `staleAssistantArchived:true`）并把旧模型回答保存为 **archived** assistant 消息（完整保留在审计历史 `messages(sessionId)`，但 `activeMessages` 与下一轮模型上下文自动排除，不会让没看到新评论的旧回答误导下一轮）；下一轮必含新增消息。无新增消息时保持原完成流程。`maxMessageSequence` 只统计 `archived=0` 的 active 消息，与模型上下文视图一致。评论投递侧同样原子化：`appendUserMessageIfRunActive` 在事务内重确认 Run 非终态再追加，若 Run 在“看起来活跃”后恰好终态，`CollaborationService.comment()` 会回退创建新的幂等 Trigger/Run，而不是把评论挂在已结束 Run 后面。
- 启动时会重新求值等待中的阶段屏障并补发缺失的 Leader Trigger；SQLite 短时写锁或单个历史屏障异常只记录警告并跳过该项，不会从 `ApplicationReadyEvent` 终止整个 Server。未完成项仍保留在持久化表中，可在后续运行或下次启动继续对账。

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
GET                         /v1/runs/{runId}/audit
POST                        /v1/runs/{runId}/retry
POST                        /v1/runs/{runId}/cancel
GET                         /v1/runs/{runId}/timeline
GET                         /v1/runs/{runId}/events
GET                         /v1/runs/{runId}/collaboration
POST                        /v1/runs/{runId}/delegations/{delegationId}/decision
GET                         /v1/runs/{runId}/workspace-file?path={relativePath}
GET                         /v1/sessions/{sessionId}/export
POST                        /v1/sessions/import
```

`/v1/sessions/{sessionId}/messages` 返回 Console 消息视图，保留原 Message 字段，并为每条消息补充 `runArtifacts`，用于在对话页展示最终交付物、Artifact 下载/预览入口和可点击的网页/本地文件引用。
`/v1/runs/{runId}/audit` 聚合返回该 Run 所属 Session、模型输入与输出、ToolCall 原始参数和结果、持久化 Approval、事件、绑定的 Plan Step 与 Validation Check。Console 的 Plan 摘要和完整详情会在每个已绑定 Run 的 Step 上显示“打开 Run”，无需切换会话即可核对执行与验证证据。
`/v1/runs/{runId}/workspace-file` 只读取该 Run 所属受控 workspace 下的相对文件路径，并通过认证请求返回文件内容，供 Console 以带 API Key 的方式打开或下载最终 HTML、Markdown、图片等交付物。

创建或重试 Run 时可传 `executionShell: "sh" | "bash" | "powershell"`。若绑定的 Agent Profile 配置了 `executionShell`，专家配置优先；最终值写入 Run。`POST /v1/runs/{runId}/cancel` 除了关闭活跃模型请求，还会销毁该 Run 的 Docker 容器以中断正在执行的命令，并返回 `sandboxExecutionCanceled`。

`execute_command` 的工具参数如下：

```json
{
  "command": "mvn test",
  "shell": "bash",
  "cwd": ".",
  "timeoutSeconds": 90,
  "maxOutputBytes": 262144,
  "env": {"NODE_ENV": "test"}
}
```

`shell` 只能使用白名单；`cwd` 不能越过 Run workspace；环境变量名称、数量和值长度受限，名称包含 Key、Token、Secret、Password、Credential 或 Auth 的变量会被拒绝。命令进程不会继承 Sandbox Agent 环境。非零退出码作为可供模型判断的命令结果返回，不会被误判为 Runtime 调用失败；超时、非法参数和 Sandbox Agent 调用异常则返回失败 observation。

同一 Session 的后续普通 Run 和 Plan Run 会继承已有 workspace owner；持久化协作任务则使用根任务级 workspace owner，跨 Barrier 新建的 Session/Run 仍落到同一目录。旧数据库启动时会把任务树及其委派后代的分散目录按执行顺序归并，文件冲突的旧版本保存在 `.paicli/workspace-history`，再统一更新 Run 的 workspace owner。Console 会按 Session 保存历史阅读位置，后台刷新不再强制跳到底部。父子 Agent 会话切换时会先停止旧 Run 的事件流和轮询，再从最新持久化事件游标继续监听，避免返回父 Agent 时重放全部历史事件并反复重绘对话。

### Plan Runtime

```text
GET/POST                    /v1/plans
GET                         /v1/sessions/{sessionId}/plans
POST                        /v1/plans/generate
GET                         /v1/plans/{planId}
GET                         /v1/plans/{planId}/state
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
POST                        /v1/plan-steps/{stepId}/decision
POST                        /v1/async-jobs
GET                         /v1/async-jobs/{jobId}
POST                        /v1/async-jobs/{jobId}/cancel
```

Plan Runtime 已从“持久化计划对象”推进到类型化 Graph 执行闭环：`plans` 保存目标、摘要、状态、版本和原始 JSON；`plan_steps` 保存任务级步骤、执行模式、验收标准、资源读写集、隔离策略、关键路径权重、workspace 引用、状态、领取 owner、租约、心跳、尝试次数、失败分类、调度幂等键和绑定的普通 `run_id`；`plan_edges` 保存 `DEPENDENCY`、`CONDITIONAL`、`REWORK` 三类边、确定性条件、优先级、最大回流次数和已回流次数。旧数据库中的边自动迁移为 `DEPENDENCY + ON_SUCCESS`。条件只支持 `ALWAYS`、`ON_SUCCESS`、`ON_FAILURE`、`ON_VALIDATION_FAILURE` 和 `ON_SKIPPED`，由 Server 代码判断，不让模型临时解释布尔逻辑；未命中的分支自动进入 `SKIPPED`，Validation Check 与 `plan_events` 同步收口。`REWORK` 只重置目标节点及其下游分支，并受持久化 `max_traversals` 限制，耗尽后按原失败语义终止 Plan。

`GET /v1/plans/{id}` 和独立的 `/state` 接口会返回结构化 `PlanState`：状态计数、READY/活跃/等待人工 Step、阻塞原因、累计模型 Token、最后事件序号和更新时间。`USER_APPROVAL` 是真实 Human Node，必须通过 `/v1/plan-steps/{id}/decision` 持久化 `APPROVED` 或 `REJECTED`；决定完成后才计算条件边。Console 的 Plan 详情展示类型化边、状态快照、阻塞原因和回流计数，并允许处理等待中的人工节点。

当前内置验证规则支持 `run_status:COMPLETED`、`answer_contains:<text>`、`answer_not_contains:<text>`、`file_exists:<path>`、`file_not_exists:<path>`、`file_contains:<path>::<text>`、`test_report:<path>` 以及普通文字验收标准的最终回答证据匹配。文件与测试报告验证只读取 `paicli.workspace-root` 下的相对路径，拒绝绝对路径和越界路径。这个闸口避免把“模型/工具链路成功结束”误判为“用户目标已经达成”，也为后续命令/API/截图断言和 Reviewer Agent 证据包预留扩展位置。

新增 API 包括 `/v1/sessions/{sessionId}/plans`、`/v1/plans/{id}/dispatch`、`/v1/plans/{id}/dag/batches`、`/v1/plans/{id}/jobs`、`/v1/plans/{id}/validation-checks`、`/v1/async-jobs` 和 `/v1/async-jobs/{id}/cancel`。Console 普通消息区会在当前 Session 顶部以 Plan 摘要作为短任务名，并展示完整目标、状态、步骤进度和当前步骤，保留打开工作台、详情和调度动作。Read-only DAG 仍提供批次分析；执行侧已经具备资源读写集冲突控制、内部 Session 隔离和 workspace 引用，Lite 版暂不自动执行真实 Git worktree merge。

阶段 5/6 增量把上述基础执行闭环推进到受控并行和反馈闭环：Plan JSON 可声明 `resource_read_set`、`resource_write_set`、`isolation_strategy`、`max_parallelism` 和 `critical_path_weight`；调度器会按关键路径优先级领取 Step，并用资源读写集阻止同一计划内的活跃写写或读写冲突。需要隔离的 Step 会创建内部 Session，`GIT_WORKTREE` 当前落为 Lite 受控 workspace 引用和目录边界；真实 Git worktree 的 add/merge 仍预留在后续工具层，不在当前版本自动执行。Plan 验证结果会写入 `agent_feedback`，验证通过时生成可追溯的过程型 Memory，失败时记录 validation/failure class，供后续专家评分、调度策略和人工复盘使用；Actuator 指标同步记录 Plan 验证成功/失败、资源冲突、Agent Feedback 和验证 Memory 写入次数。

所有绑定 Agent Profile 的专家 Run 都会获得受控 Plan 工具：`list_plans`、`get_plan`、`create_plan`、`replan_plan`、`start_plan` 和 `cancel_plan`。读取只限当前项目；创建、调整、启动和取消必须经过既有持久化 Approval 流程，并且只能修改专家自己创建、当前 Step 绑定或父委派明确分配的 Plan。写操作以 ToolCall 为幂等边界，普通未绑定专家 Profile 的 Run 不开放这些工具。

更完整的 Plan、Multi-Agent、Agent Harness、调度恢复和验证闭环说明见 [技术架构与面试指南](PaiCLI%20Platform%20Lite%20技术架构与面试指南.md) 的“Plan 与类型化 Graph Runtime”“Step 调度、租约、资源冲突、隔离与 Validation Gate”章节。

### Approval、附件与 Artifact

```text
GET                         /v1/approvals[?runId={runId}|projectKey={projectKey}]
POST                        /v1/approvals/{approvalId}
GET                         /v1/approvals/policies
DELETE                      /v1/approvals/policies/{policyId}
POST                        /v1/approvals/policies/batch-delete
POST                        /v1/sessions/{sessionId}/attachments/images
POST                        /v1/sessions/{sessionId}/attachments/documents
DELETE                      /v1/sessions/{sessionId}/attachments/{attachmentId}
GET                         /v1/artifacts
GET                         /v1/artifacts/{artifactId}
GET                         /v1/artifacts/{artifactId}/content
GET                         /v1/artifacts/{artifactId}/download
POST                        /v1/artifacts/{artifactId}/reuse
DELETE                      /v1/artifacts/{artifactId}
POST                        /v1/artifacts/batch-delete
```

### Memory、Knowledge 与统一检索

```text
GET/POST                    /v1/memories
GET/PUT/DELETE              /v1/memories/{memoryId}
GET                         /v1/memories/managed
GET                         /v1/memories/wiki
GET                         /v1/memories/{memoryId}/wiki
GET                         /v1/memories/{memoryId}/sources
POST                        /v1/memories/{memoryId}/state
GET                         /v1/memories/{memoryId}/revisions
POST                        /v1/memories/{memoryId}/revisions/{revisionId}/restore
POST                        /v1/memories/{memoryId}/merge
POST                        /v1/memories/batch-delete
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
POST                        /v1/productivity/model-profiles/starter-pack
PUT/DELETE                  /v1/productivity/model-profiles/{id}
GET/POST                    /v1/productivity/agent-profiles
POST                        /v1/productivity/agent-profiles/starter-pack
PUT/DELETE                  /v1/productivity/agent-profiles/{id}
GET/POST                    /v1/productivity/agent-teams
PUT/DELETE                  /v1/productivity/agent-teams/{id}
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

`POST /v1/productivity/queue/batch` 的 `DELETE` 动作，以及 Memory、Artifact、持久化审批策略的 `batch-delete` 接口，单批最多接受 100 个 ID。批量数据库删除采用全有或全无事务；Run 仅允许自身及关联委派树均无活跃执行时删除，Artifact 在元数据提交删除后同步移除本地对象文件。

#### Memory/RAG/Plan-Agent 阶段 2/3/4 增量

- Memory：`memories` 增加 `structured_payload`、`status`、`source_type/source_id/source_revision`、有效期、`supersedes_id` 和 checksum；`memory_extractions` 冻结 Run 消息快照，`memory_sources` 保存 message id 与 sequence span，`memory_conflicts` 承载近重复/变更审计，`memory_usage_feedback` 关联实际召回与终态/验证结果。
- RAG：检索入口增加轻量 Query Plan，识别代码路径、符号、排障、决策和架构类查询；SearchHit 返回 BM25 分、查询类型、检索策略、文档版本、citation 和命中原因，便于后续 UI 解释与排序调参。
- Plan-Agent：`spawn_agent` 在保持旧字段兼容的同时支持 `plan_id`、`plan_step_id`、scope、允许文件/工具、输入 artifact、期望输出契约、验收标准、预算、deadline、依赖、资源读写集、workspace 引用、失败策略和禁止操作。`dependencies` 可引用同一父 Run 下已创建委派的 `delegation_id`、`child_run_id`、唯一专家名或 Plan Step id；未满足依赖的子 Run 保持 `BLOCKED`，不会被 Worker 领取。
- Agent Graph：相同 workspace 中读读可并行，读写/写写冲突串行；不同 `workspace_ref` 映射到不同 workspace owner，可并行执行。上游失败后，下游按 `BLOCK_GRAPH`、`DEGRADE` 或 `REQUIRE_HUMAN` 路由，人工节点由持久化 decision API 和 Console 协作看板处理。
- Agent Result：子 Run 终态事件会在同一数据库事务内更新 `run_delegations.status/result_json/completed_at`、把上游信封注入可执行下游的子 Session、推进节点并唤醒等待父 Run。Result Envelope v2 自动归集摘要、Artifact、Token、文件写入、命令、测试、风险和未完成项；`get_agent_result` 统一读取该信封，不再承担“触发结果落库”的职责。
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

`POST /v1/evaluations/suites/{suiteId}/executions` 可选传 `agentTeamId`。未传时保持单 Agent 基线；传入时每个 Trial 绑定该团队 Leader，并将成员、并发、深度和 Reviewer/Runner 约束固化到普通 Run 的协作策略。

### CollaborationTask 与结构化路由

```text
GET                         /v1/collaboration/history
GET/POST                    /v1/collaboration/tasks
GET/PUT/DELETE             /v1/collaboration/tasks/{taskId}
PUT                         /v1/collaboration/tasks/{taskId}/status
POST                        /v1/collaboration/tasks/{taskId}/actions
POST                        /v1/collaboration/routing/preview
POST                        /v1/collaboration/tasks/{taskId}/triggers
POST                        /v1/collaboration/tasks/{taskId}/comments
PUT                         /v1/collaboration/comments/{commentId}/discussion
GET                         /v1/collaboration/tasks/{taskId}/activities
GET                         /v1/collaboration/teams/{teamId}/metrics
```

`GET /v1/collaboration/history` 为 Console 左侧统一历史返回每个长期任务的一条记录，并附带最新 Session、全部关联 Session 和 Run 数量；客户端据此折叠同一任务的重复执行会话并判断删除入口的审计约束。

`POST /tasks/{taskId}/actions` 支持 `START`、`CONTINUE`、`RESUME`、`BLOCK`、`REQUEST_REWORK`、`ACCEPT`、`CANCEL` 和 `REOPEN`；其中 `BLOCK` 与 `REQUEST_REWORK` 必须提供原因，`ACCEPT` 是唯一进入 `DONE` 的人工动作。`CANCEL` 可在任务存在活跃 Run 时执行：服务端先持久化取消全部关联 Run 树，并中断对应模型请求与 Sandbox 执行，再把长期任务标记为 `CANCELED`，评论、活动和 Run 审计继续保留。`PUT /status` 仅保留为旧客户端兼容入口，不能由人工提交 `IN_REVIEW`。Trigger 请求支持 `triggerType`、`sourceId`、`targetType`、`targetId`、`instruction` 和 `idempotencyKey`；同一 key 只关联一个已创建 Run。评论/提及（MENTION/REPLY）触发的新 Run 会把处于 IN_REVIEW 的任务先置回 IN_PROGRESS，返工结束后再重新提交待验收。`REQUEST_REWORK` 与 `BLOCK` 的原因会先作为人工评论持久化到任务（幂等去重），再触发返工 Run 或落阻塞状态，保证反馈在「评论与决策」与任务摘要中可见。删除存在 Task-Run 历史的任务返回 `409`，客户端应改用 `CANCEL` 保留审计链路；只有从未执行的任务可以物理删除。

## 配置入口

推荐从 [.env.example](.env.example) 复制所需变量到不提交 Git 的 `.env`。主要配置族：

| 配置族 | 用途 |
|---|---|
| `PAICLI_SERVER_ADDRESS`、`PAICLI_API_KEY`、`PAICLI_SECURITY_*` | 回环监听默认值、REST、Actuator、OpenAPI 认证和生产启动门禁 |
| `PAICLI_MODEL_*` | Provider、端点、模型、Key、上下文/输出、思考、重试、流空闲超时、熔断、限流、Fallback、Run/工具预算和相同工具参数循环上限 |
| `PAICLI_WEB_*` | 可选 SearXNG 搜索和 Server 侧 Web 工具 |
| `PAICLI_RAG_*` | Embedding、自动召回、PDF OCR 页数和 DPI |
| `PAICLI_MEMORY_*` | 自动提取、召回数量和最小置信度 |
| `PAICLI_WORKER_COUNT` | Run Worker 并行度，默认 4；实际并行仍受项目预算、Plan/Delegation 依赖和资源锁约束 |
| `paicli.docker.command-timeout-seconds` | Docker 命令请求的最大超时，同时注入 Sandbox Agent 作为请求级 `timeoutSeconds` 上限；默认 90 秒 |
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

当前自动化测试覆盖：

- Common、Server、Sandbox Agent 模块边界。
- RunProcessor、恢复、工具失败 observation、多 ToolCall 顺序和 Approval Flow。
- ContextManager、Context Manifest、稳定缓存前缀、统一预算、结构化摘要、按需工具 Schema、Memory 来源冻结/反馈、Knowledge、RAG、Skill、MCP、Multi-Agent 和附件。
- OpenAI-compatible/DeepSeek/多模态请求与 SSE 解析、模型重试/Fallback。
- SQLite Store、迁移 1–34、CollaborationTask/Trigger/阶段屏障/任务工作区、WAL 并发写入、Delegation Graph 依赖/资源/终态传播、Artifact 原子写入、维护和备份安全相关行为。
- Plan Runtime 的 JSON 解析校验、DAG 循环拒绝、根 Step 就绪、Replan 版本记录、Step 内 ReAct Run 调度、Async Job 状态、Validation Check、Read-only DAG 批次分析、资源冲突推迟、隔离 workspace 引用、Agent Feedback 和验证 Memory 闭环。
- API Key、管理端点/OpenAPI、Console 安全头和结构化表单回归。
- Agent 评测多 Trial、单 Agent/AgentTeam 执行、输出 Token 硬门禁、Baseline、内部 Session 隐藏、审批不旁路，以及 7 套件/28 Case Starter Pack 完整性和幂等安装。

此外已完成：

- Docker Desktop / WSL2 真实容器验收：审批恢复、命令执行、工作区挂载、SSE 重放、资源限制和容器回收。
- Agent 评测真实 REST 冒烟：创建 Suite/Case、双 Trial、Execution 完成、100 分报告和 Baseline 晋升。

## 已知边界

- 单机、单租户、私有部署；不实现 Kubernetes、多地域和分布式高可用。
- SQLite、本地 Artifact、进程内 Worker 和 Docker 分别替代 PostgreSQL、S3、消息队列和 MicroVM。
- Docker 不是硬件级隔离，不适合执行完全不可信的敌对代码。
- Local Sandbox 仍不执行 `execute_command`，避免把开发模式升级为可调用 Windows/Linux 宿主机的任意命令边界；Shell 白名单能力仅在 Docker 模式生效。
- 当前命令输出在 ToolCall 完成后以结构化 Event/Artifact 交付，尚未提供逐行实时 stdout/stderr SSE、PTY 交互终端或可脱离 Run 生命周期的后台服务管理。
- 当前提供可复用智能体专家 Profile/执行小队、确定性能力路由、持久化任务与评论、Leader 动态 Delegation Graph、阶段屏障、依赖门禁、资源隔离、失败路由、Human Node 和结果信封；尚不包含基于历史成功率的学习型路由、复杂工作量均衡、真实 Git worktree 自动合并和跨项目 Memory 联想图谱。
- MCP 当前只支持远程 Streamable HTTP，不管理本地 stdio MCP 进程。
- 默认不依赖外部向量数据库；未配置真实 Embedding 时使用明确的本地降级。
- 图片型 PDF 支持受限 OCR/视觉路径；尚不支持音频和视频理解。
- 评测第一版是确定性安全、工具、关键文本和预算门禁，不等同于开放式语义质量评价或无偏 LLM Judge。
- 单机 SQLite 通过一次性 WAL 初始化、30 秒写锁等待和短事务承载并发；它降低锁冲突但不等同于多节点数据库，高写入规模仍应迁移 PostgreSQL。

## 文档与产品站点

- [技术架构与面试指南（按功能重构版）](PaiCLI%20Platform%20Lite%20技术架构与面试指南.md)
- [技术架构与面试讲解（原始专题版）](PaiCLI%20Platform%20Lite%20技术架构与面试讲解.md)
- [架构说明](docs/architecture.md)
- [阶段状态](docs/phases.md)
- [变更日志与优化复盘](changeLog.md)
- [Docker Sandbox](docs/docker-sandbox.md)
- [在线产品站](https://paicli-platform-lite.fuermalin2002.chatgpt.site)
- 产品站源码：`paicli-site/`

### 文档同步门禁

仓库修改必须把文档同步作为交付条件。任何代码、脚本、配置、测试、文档、静态资源或产品站变更，都要在同一工作项和同一次提交中更新根目录 `changeLog.md`，写明变更、思路与验证。

还必须按影响范围同步对应资料：

| 变更类型 | 必须同步 |
|---|---|
| Runtime 行为、状态机、上下文、Memory、Plan、架构边界 | `README.md`、`docs/architecture.md` |
| 阶段完成度、后续范围 | `docs/phases.md` |
| API 路径、请求、响应、错误语义 | OpenAPI 注解、README API 说明 |
| 配置、环境变量、启动或维护脚本 | README 配置与运行说明 |
| Sandbox 镜像、隔离、命令执行 | `docs/docker-sandbox.md` |
| 产品站能力或展示 | `paicli-site/README.md` 和相关产品说明 |

交付前通过 `git diff --name-only` 核对实际修改文件，再执行 `git diff --check`。遗漏受影响文档时任务不算完成；确实不适用的文档必须在 `changeLog.md` 中说明理由。完整强制规则以 [AGENTS.md](AGENTS.md) 为准。

本地运行产品站：

```powershell
cd paicli-site
npm install
npm run dev
```
