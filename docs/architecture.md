# 架构说明

## 部署边界

平台由一个 Spring Boot Server、一个 SQLite 数据库和可选的 Docker Sandbox 组成。`paicli-sandbox-agent` 是 Docker 容器内独立的执行边界。

```text
客户端
  -> REST / SSE
PaiCLI Server
  -> SQLite：Session / Run / Message / Event / ToolCall
             Approval / ApprovalPolicy / Artifact / Memory / ModelUsage
             TaskTemplate / ModelProfile / BudgetPolicy / ScheduledTask / NotificationChannel
             Plan / PlanStep / PlanEdge / PlanRevision / PlanEvent / AsyncJob / ValidationCheck / AgentFeedback
  -> ModelClient：Demo 或 OpenAI 兼容的流式模型
  -> PlanService：Plan JSON / DAG 校验 / Revision
  -> PlanExecutionService + PlanValidator：Step 调度 / ReAct Run 绑定 / Async Job / Validation Gate
  -> ContextManager：Prompt / Memory / 摘要 / Token 预算
  -> ToolRouter
       -> SandboxDriver：Local（仅开发）或 Docker
       -> ServerToolProvider：Skill / Knowledge / Web / MCP / Delegation
```

## Runtime 恢复契约

1. Run 先以 `QUEUED` 提交，Worker 才能看到。
2. Worker 原子地将其改为 `RUNNING`。
3. 模型同一轮的 assistant 消息和全部工具调用在执行前同一事务落库。
4. 工具按 Provider 顺序执行；结果先持久化，再进入下一模型步。
5. 可预期的工具失败写成 ToolCall 失败和 `tool` observation，然后重新排队让模型纠正；审批拒绝、取消和 Runtime 异常仍是终态。
6. 启动时将中断的 `RUNNING`、`WAITING_MODEL`、`WAITING_TOOL` Run 恢复为 `QUEUED`，并把 `RUNNING` ToolCall 恢复为 `REQUESTED`。
7. 已完成工具调用按幂等键复用，不重复产生副作用。
8. 同一 Run 中“工具名 + 完全相同参数”默认最多出现 3 次；再次重复会以明确的 loop error 终止，避免无进展工具循环耗尽 Token 和时间预算。

系统采用“事件支撑的状态”而非完全事件溯源：关系表保存当前状态，`run_events` 用于重放、SSE 和诊断。

## 产品与安全边界

- 配置 `PAICLI_API_KEY` 后保护 `/v1/**`；Actuator 和 OpenAPI 默认使用同一密钥。生产可设置 `PAICLI_SECURITY_REQUIRE_API_KEY=true`，缺少密钥时拒绝启动。
- Console 仅把 API Key 保存在当前标签页 `sessionStorage`，并启用 CSP、防嵌套、MIME 嗅探防护、Referrer 与浏览器权限策略。
- 删除 Session 前拒绝活跃 Run；随后同一事务删除 Approval、ToolCall、Event、Artifact、ModelUsage、MemoryExtraction、Message 和 Run。删除分组只把会话移到未分组。
- 模型密钥只留在 Server，不进入 Sandbox、模型上下文或附件目录。

## P0 业务工作台

- 终态 Run 可以在原 Session 重试；分支操作创建新 Session，并复制源 Run 之前的未归档对话，再用源输入和推理设置创建新 Run。
- 危险工具仍先创建持久化 Approval。用户可选择仅本次、本对话或本项目允许；持久化策略同时匹配工具名和已落库参数的 SHA-256，因此不会扩大到模型后来生成的不同参数。
- `/v1/search` 聚合项目内 Session 标题、Message、Memory、Knowledge SearchHit 和 Artifact 元数据，返回可跳转的 Session/Run 标识。
- Memory 管理保留层级、类型、来源、置信度、访问统计和修订历史，并支持置顶、启停、人工确认、同项目合并及历史恢复。Console 合并表单通过下拉框选择目标并预览内容；修订表单可编辑当前 Key、内容和标签，同时查看、恢复全部历史版本。Runtime 召回只使用启用项并优先置顶项。
- Knowledge 文档维护集合、标签、版本、索引状态、分块数和 Embedding Provider；重建索引继续使用原子替换。检索结果包含文档、分块、标题和字符区间，形成引用定位链；Console 的有用/无用反馈持久化到 SQLite，供后续排序治理使用。
- Artifact 文件先校验路径和 SHA-256，再提供预览、认证下载或复制为指定 Session 的待提交文本附件。

## 长期使用效率

- 任务模板把 Prompt、模型方案、附件要求和工具权限保存到项目级或全局配置；提交前在 Server 端解析 `${repository}`、`${outputFormat}` 等变量。内置 `/review`、`/summarize`、`/research` 快捷入口，Console 草稿按 Session 自动保存。模板、模型方案、定时任务和通知均通过一次性结构化表单创建，避免连续弹窗导致上下文丢失或半途取消。
- 模型方案保存用途、端点、模型、后备模型、上下文上限、输出上限和单价。项目可设置默认方案；每个 Run 固化所用方案，失败重试可切到后备方案，本地模型允许不配置 API Key。
- `model_usage` 记录模型名、输入/输出 Token、缓存命中、耗时、重试和本地模型标志。预算策略按项目限制日/月 Token、日/月费用与最大并发，提交前提供上下文、输出和费用风险估算。
- Run 队列按优先级领取，并在项目最大并发约束下进行公平调度；Console 可调整优先级、批量取消、重新排队，并查看当前状态、耗时和重试次数。
- 定时任务通过下拉框引用当前项目已保存模板，支持一次性、每日、每周和 Cron，并按周期动态收集时间字段；Cron 的首次与后续执行均按服务端系统时区计算。调度器创建普通 Session/Run，因此继续复用审批、审计、预算、恢复和通知链路。
- 完成通知支持浏览器通知，以及由 Server 读取环境变量密钥的 Webhook、邮件网关或企业 IM 网关，事件覆盖完成、失败、等待审批和预算不足。
- Session 可导出 Markdown、JSON 或带 ToolCall、Approval、Event、Artifact 清单的审计包；导出支持隐私脱敏，导入会在另一实例创建新的可继续对话 Session。
- Skill 生命周期记录来源仓库、Ref、Commit、安装时间和作用域，安装前展示文件清单与权限声明，并支持启停、固定、检查更新、升级和单级回滚。MCP Server 可在 Console 新增、测试、启停和删除，敏感 Header 只保存环境变量名，工具 Schema、健康和熔断状态可见。
- 首页把效率工作台作为独立入口。用量区域将近 30 天固定指标与最近调用明细分离：指标使用固定网格，明细进入默认收起、限高滚动的表格式区域，避免 Session/模型维度增长时持续撑高页面。

## Agent 评测中心

评测层不实现第二套 Agent Loop。`evaluation_suites` 和 `evaluation_cases` 保存项目级输入、工具/回答约束、资源上限与通过阈值；启动 Execution 后，每个 Case/Trial 创建隐藏的内部 Session 和普通 Run，因此继续复用现有队列、模型方案、ToolCall 持久化、审批、Event、Audit、Artifact 和恢复边界。内部评测 Run 不参与自动 Memory 提取，避免测试内容污染长期记忆。危险工具仍创建持久化 Approval；评测报告暴露待审批项供人工单次允许或拒绝，不为追求自动化而扩大权限。

`EvaluationService` 在读取报告时同步终态 Trial，并从 Run、Message、ToolCall 和 ModelUsage 构建结构化评分快照。Case 的 `maxTokens` 明确定义为输出 Token，报告同时保留输入、输出和总 Token；工具数、输出 Token、耗时上限属于硬门禁，避免“超预算扣 10 分后仍刚好达到阈值”。Execution 只有在全部 Trial 达到阈值且通过资源门禁时通过；运行中报告保持可刷新，不阻塞 Worker 等待。

`evaluation_baselines` 只接受已通过 Trial，保存人工确认的来源 Run、最终回答、工具名称序列、Token 口径和耗时。新基线使用输出 Token；迁移前基线以 `TOTAL` 标记并继续按总 Token 比较。基线不持久化为新的模型上下文，也不把原始 reasoning 作为严格相等条件；这避免模型升级时因合法路径变化产生大量伪回归。

版本化官方 Starter Pack 位于 classpath `evaluations/starter-pack.json`。安装服务按 Suite/Case 名称幂等合并，只创建缺失项，不覆盖用户已有规则；默认包覆盖基础安全、审批、受管能力和稳定性/预算。依赖 Knowledge、Skill、Web 或 Multi-Agent 前置条件的 Case 默认停用，用户可在 Console 显式启用。

评测中心从效率工作台中抽离为首页一级入口。宽屏使用“套件/报告”双栏布局，套件用例默认折叠，两栏各自限高滚动；窄屏降级为上下两块独立滚动区域。这样套件、Case 和 Trial 增长时不会形成一条无限延长的单列页面。

## Plan Runtime 基础

Plan Runtime 是位于普通 ReAct Run 之上的任务层编排边界。它把“计划”从模型文本变成可恢复、可审计、可调度的数据对象，但不替代现有 RunProcessor，也不绕过 ToolCall、Approval、Event、Artifact 和预算链路。

- `plans` 保存目标、摘要、项目、可选 Session/Run 关联、状态、版本、来源、原始计划 JSON 和失败原因。
- `plan_steps` 保存任务级步骤、执行模式、验收标准、资源读写集、隔离策略、关键路径权重、workspace 引用、状态、领取 owner、租约、心跳、尝试次数、调度幂等键和绑定的普通 `run_id`。Step 是任务层对象，不直接保存工具参数；执行时仍由普通 Run 和 ToolCall 落库真实动作。
- `plan_edges` 保存 DAG 依赖，方向为“依赖 Step -> 被依赖 Step”。启动 Plan 时只把所有依赖已完成的根 Step 标为 `READY`。
- `plan_revisions` 保存 Replan 版本原因和原始 JSON；非 ACTIVE 草稿可整体替换，FAILED/ACTIVE Plan 在没有运行中、等待审批、等待 Job 或验证中的 Step 时支持局部尾部替换，已完成/跳过/取消步骤及其证据会被冻结保留。
- `plan_events` 保存 Plan/Step 状态事件，供后续 Console 时间线和审计使用。
- `async_jobs` 保存异步 Step 和外部长任务的状态、幂等键、payload、result、log 与错误，支持 poll 和 cancel。
- `validation_checks` 保存每个 Step 的 Done Criteria、实际结果、证据和错误，供 Console 与最终回答引用。

Planner 调用现有 `ModelClient` 生成结构化 JSON。Server 会清理 Markdown code fence、重新映射模型给出的 step id、限制步骤数量、校验 Step 类型与执行模式、校验依赖存在和循环依赖。校验失败不会创建可执行 Plan。默认 Demo 模型会生成单步分析计划，保证本地无模型 Key 时仍能验证 API。

计划启动后，`PlanExecutionService` 会先回收过期且尚未绑定 Run 的 `RUNNING` Step 租约，再按关键路径权重、下游数量和 ordinal 领取 `READY` Step。调度前会汇总活跃 Step 的资源读写集，阻止写写或读写冲突；冲突 Step 会写入 `RESOURCE_CONFLICT` 并短暂延后，供下一轮恢复调度。`REACT` Step 创建普通 Run；`ASYNC`/`ASYNC_JOB` Step 同时登记 Async Job，并通过 Job 关联 Run；`NONE` Step 可直接完成；`MANUAL`/`USER_APPROVAL` Step 进入等待人工状态。需要隔离的 Step 会创建内部 Session，并写入受控 workspace 引用；`GIT_WORKTREE` 在 Lite 版当前只表示预留的 worktree 隔离目录和引用，真正的 git worktree add/merge 留给后续工具层。Run 绑定成功后清除领取租约，避免恢复任务误判；如果 Worker 在领取后、创建 Run 前中断，下一轮调度会把过期 Step 恢复为 `READY` 并记录 `plan_step.lease_recovered` 事件。Run 进入 `COMPLETED` 只代表执行链路结束，Step 会先进入 `VALIDATING`，再由 `PlanValidator` 按 done criteria 检查最终回答或明确规则；验证结果会写入 `agent_feedback`，验证通过还会沉淀为过程型 Memory。验证通过才标记 Step/Validation Check/Async Job 完成并推进后续依赖，验证失败会进入 `VALIDATION_FAILED` 并让 Plan 失败。当前内置规则支持 `run_status:COMPLETED`、`answer_contains:<text>`、`answer_not_contains:<text>`、`file_exists:<path>`、`file_not_exists:<path>`、`file_contains:<path>::<text>`、`test_report:<path>` 和普通文字验收标准的最终回答证据匹配；文件与测试报告验证只读取受控 workspace root 下的相对路径。

本阶段已提供 `/v1/plans`、`/v1/plans/generate`、`/v1/plans/{id}/approve|start|dispatch|cancel|replan`、`/v1/plans/{id}/steps|events|jobs|validation-checks`、`/v1/plans/{id}/dag/batches`、`/v1/plan-steps/{id}/retry|skip` 和 `/v1/async-jobs`。Read-only DAG 仍提供批次分析；执行侧已经具备资源读写集冲突控制、内部 Session 隔离和 workspace 引用，Lite 版暂不自动执行真实 Git worktree merge。

## Plan 与 Multi-Agent 协同

Plan 负责“任务如何拆、依赖如何排、每步如何验收”；Multi-Agent 负责“某个步骤是否需要委派给专家 Run”。二者之间通过持久化字段衔接，而不是靠对话文本约定。

`spawn_agent` 是普通 ToolCall，必须先落库并按需要审批。执行时 `DelegationToolProvider` 会根据父 ToolCall 幂等创建或复用内部子 Session/Run，并把 `plan_id`、`plan_step_id`、scope、允许文件/工具、输入 artifact、期望输出、done criteria、预算、deadline、依赖和禁止操作写入 `run_delegations.envelope_json`。子 Agent 仍由普通 `RunProcessor` 执行，因此继续复用正式模型方案、ToolCall 持久化、Approval、Artifact、Event、预算和恢复链路。

Leader 通过 `get_agent_result` 读取子 Run 结果。该工具会把子 Run 终态、摘要、Artifact、Token 用量、失败分类和证据写回 `run_delegations.result_json`，供 Leader 汇总、Plan Step 验证和后续审计使用。Plan Step 最终仍由 `PlanValidator` 验收；子 Agent 声称完成不等于 Step 自动完成，必须有 `validation_checks`、必要 Artifact 或可匹配证据。

阶段 5/6 增加的 `agent_feedback` 是 Plan 与 Agent Harness 的反馈层：每个绑定 Step 的 Run 终态都会记录验证状态、得分、失败分类和证据质量；验证通过时还会写过程型 Memory。这样后续调度、专家评分和人工复盘可以基于结构化事实，而不是翻聊天记录。

更完整的链路、字段、状态机和边界见 [Plan Runtime、Multi-Agent 与 Agent Harness 深度说明](plan-multi-agent-harness.md)。

## Memory 与上下文

Run 完成后先创建持久化 `memory_extractions` 任务，再由 Worker 提取带类型、层级、置信度和来源的 L1/L2/L3 Memory。同 key 变化和人工编辑都先写入 `memory_revisions`。当前 Memory 还保存结构化 payload、生命周期状态、来源类型/ID/修订、有效期、supersedes 和 checksum；自动提取写入 `memory_sources`，同 canonical key 内容变化记录 `memory_conflicts` 供人工审计。召回综合词法/语义相关性、置信度、时间衰减、置顶和稳定 L3 偏好；显式 REST CRUD 是人工纠错边界。

DeepSeek Thinking 的 `reasoning_content`、assistant `tool_calls` 和对应工具结果按同一持久化边界恢复。模型 delta 批量写入 Event，浏览器按动画帧合并显示，避免每 Token 同步写 SQLite 或创建大量 DOM。

项目规则属于受控上下文，不是自治 Memory。系统只从全局、项目和 Run 工作区的数据根目录读取 `AGENTS.md` / `PAI.md`，更具体层覆盖通用层，并受总字符预算约束。

## SQLite 与文件一致性

Lite 版是单机单租户，SQLite WAL 提供并发读取和短事务写入。WAL 只在数据库初始化时设置一次，普通连接不再反复切换日志模式；每个连接设置 30 秒 `busy_timeout`，降低多 Trial/多 Worker 短时争用直接产生 `SQLITE_BUSY` 的概率。`schema_migrations` 当前到版本 21，其中 12 为 Agent 评测，13 为生产级 Run 状态机，14 为评测输出 Token 口径与 SQLite 并发加固，15 为 Plan Runtime 基础表，16 为 Plan 调度、Async Job 与 Validation Check，17–18 为专家 Profile 与 delegated child Run 派发，19 为 Plan Step 领取租约与恢复元数据，20 为类型化 Memory、RAG 查询规划和 Plan 绑定 Agent 委派元数据，21 为受控并行 Plan Step 与 Agent Feedback 闭环。

连接策略和迁移目录与领域 Store 分离。定时维护执行被动 WAL checkpoint，并按显式配置清理过期 Event/Audit、孤儿 Artifact 和临时文件。Knowledge、Attachment 和 Artifact 采用临时文件、fsync、原子替换；索引中断后可按正文元数据重建。

## Server Tool Provider

RAG、历史会话检索、Skill、联网、MCP 和 Multi-Agent 委派都通过普通 ToolCall 进入统一管线，不可绕过持久化、审批、顺序执行、Event/SSE、Audit 和 Artifact 边界。

- Skill 只从受控全局/项目目录发现，稳定排序并按需加载；Git 导入先预检文件与权限声明，再暂存并校验符号链接、文件数和字符预算，不执行仓库代码。生命周期元数据和单级回滚备份均留在受控 Skill 根目录。
- RAG 文档存于 `data/projects/{projectKey}/knowledge`。Tika 提取文本；PDF 无文本层时使用 PDFBox 渲染并由视觉模型 OCR。分块保留标题、句子、列表、表格和代码块结构，BM25 与真实 Embedding 独立排序后以 RRF 融合、去重和限额。检索时会生成轻量 Query Plan，识别代码路径、符号、排障、决策和架构类查询；SearchHit 返回 citation、文档版本、BM25 分、检索策略和命中原因，便于 UI 解释和后续排序调参。
- `session_search` 只在 Agent 调用时检索当前项目的用户可见历史消息，排除当前 Run，并按会话生成抽取式摘要。
- 联网默认关闭；抓取对每次重定向重新校验，拒绝 loopback、链路本地和私网目标。
- MCP Header 只能直接填写非敏感值或引用 `env:VARIABLE_NAME`，真实密钥不写入配置也不回显；Schema、参数和响应都有预算，连续失败触发短时熔断；全部 MCP 工具强制审批。
- `spawn_agent` 经审批后原子创建委派和内部子 Session/Run，以父 ToolCall 幂等，并限制深度、子数量和级联取消。委派可绑定 `plan_id`/`plan_step_id`，并持久化包含 scope、允许文件/工具、输入 artifact、输出契约、验收标准、预算、deadline、依赖和禁止操作的 envelope；`get_agent_result` 会把子 Run 摘要、Artifact、Token 用量、失败分类和证据写回 delegation result，供恢复、审计和最终聚合使用。

## 模型网关与可观测性

OpenAI 兼容客户端会执行限流，并且只在 SSE 响应被接受前重试，避免重复流式 delta。可重试 HTTP 失败采用带抖动的指数退避并尊重 `Retry-After`；流读取有独立空闲超时，连续失败触发带半开探测的熔断。每次 HTTP 尝试写入 `model_attempts`，也可按 Run 的模型方案切换后备模型。每 Run 在新模型调用前检查步骤、Token、总时长和工具调用预算，并在 SQLite 写事务内预留项目预算，最终与 ModelUsage 原子结算。Actuator 暴露 Prometheus、排队 Run、Worker 活跃/队列、待审批、待提取 Memory、活动 SSE、模型重试和工具失败指标。Run id 是 Event、Audit、ToolCall 和 Artifact 的统一关联键。

Run 状态更新与对应 Event 在同一事务提交；终态不可回退。模型一轮产生的全部 ToolCall 按 Provider 顺序原子落库后才允许执行。工具按 `READ_ONLY`、`IDEMPOTENT_WRITE`、`NON_IDEMPOTENT_WRITE` 标记 Effect：进程恢复只自动重放安全 Effect，不确定的非幂等调用进入 `UNKNOWN` 并终止 Run，等待人工对账。工具结果、Tool Message 和下一轮排队同样原子提交。通知先写持久化 Outbox，由受限 Dispatcher 重试投递。

## 多模态与文档输入

Console 每轮最多暂存 4 张 PNG/JPEG/GIF 和 4 个文档。Server 校验真实图片字节、尺寸与大小，必要时压缩；创建 Run 时把附件 id 原子绑定到 user Message，不能被另一 Run 重复绑定。只有当前 Run 的图片作为 OpenAI 兼容格式的 `image_url` 注入，历史图片不重复发送。

文本、Markdown、PDF、Office、CSV、HTML、JSON、XML、RTF、EPUB 和 OpenDocument 由 Tika 提取并写入当前项目知识库，绑定当前 Run 后作为优先 RAG 来源。普通文档不整体塞入模型；扫描 PDF 在 OCR 不可用时保留为视觉 PDF，只把受限页图像注入当前 Run，也不会误报为已索引。

## Sandbox 边界

`LocalSandboxDriver` 仅用于开发，不是安全沙箱。Docker 模式按活跃 Run 创建受限容器，工作区留在宿主机，容器使用无外部路由的内部网络且不发布端口；Server 通过 `docker exec` 调用容器 loopback 的带令牌 HTTP Agent。

Sandbox Agent 缺少每容器随机令牌时拒绝启动。命令和 Docker 输出只保留有限前缀，但持续排空管道；超时会终止进程及后代。危险工具在任何容器调用前创建持久化 Approval，批准后继续执行同一个 ToolCall 和同一组参数，不要求模型重新生成动作。
