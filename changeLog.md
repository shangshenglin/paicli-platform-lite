# PaiCLI Platform Lite ChangeLog

本文件记录 PaiCLI Platform Lite 从初版到当前 master 的主要演进、优化思路和后续变更记录规范。内容以 Git 提交历史、`README.md`、`docs/phases.md` 和架构说明为依据，用于项目总结、学习复盘和后续交接。

## 记录规范

- 任何会话、任何工作区、任何分支中，只要修改了代码、脚本、配置、测试、文档、静态资源或产品站点，都必须同步更新本文件。
- 变更日志必须和对应代码修改放在同一次提交中；如果只是文档或流程调整，也要记录。
- 每条记录至少说明：日期、提交或工作项、变更内容、为什么这样改、验证方式或未验证原因。
- 未改变运行行为的整理、重命名、UI 文案调整，也要写清楚影响范围，避免后续复盘时只看到代码差异。
- 禁止把 `.env`、`data/`、`backups/`、`target/` 中的本地状态写入日志正文；只记录必要的配置名、行为和结论。

建议格式：

```markdown
## YYYY-MM-DD

### 简短标题

- 变更：做了什么。
- 思路：为什么这样做，取舍是什么。
- 验证：运行了哪些测试、构建或人工检查。
```

## 2026-07-23

### Plan / Multi-Agent / Agent Harness 深度说明合并去重

- 变更：将原独立 Plan/Multi-Agent/Harness 深度说明合并进《PaiCLI Platform Lite 技术架构与面试讲解.md》7.4 节，集中说明 Plan Runtime、Step 状态机、资源读写集、隔离策略、Validation Gate、Agent Feedback、过程型 Memory、Multi-Agent 委派信封、Plan 与子 Agent 协同、当前边界和代码阅读路线。
- 变更：删除独立深度说明文档，`README.md` 与 `docs/architecture.md` 的入口统一指回主技术讲解文档，避免同一套架构解释维护两份。
- 变更：精简主技术讲解文档 8.7 节，只保留 Multi-Agent 工具入口说明，并引用 7.4 的统一深度章节，消除重复展开。
- 思路：主技术文档是面试讲解和长期交接入口，Plan、Multi-Agent、Agent Harness 的字段、链路和边界应集中维护；README 和 architecture 只做导航与概要，避免后续阶段升级时漏改其中一份。
- 验证：运行 Markdown 内容检索，确认公开文档不再引用独立深度说明文件，且主文档只保留一个完整深度章节；运行 `git diff --check` 通过。此次为文档整理，未运行 Maven 测试。

### Memory/RAG/Plan-Agent 阶段 5/6 受控并行与闭环生产加固

- 变更：新增 Schema 迁移 21，`plan_steps` 增加资源读集、资源写集、隔离策略、最大并行度、关键路径权重和 workspace 引用；新增 `agent_feedback` 表，用于记录 Plan Step 关联 Run 的验证状态、得分、失败分类和证据质量。
- 变更：Plan JSON 解析支持 `resource_read_set`/`read_set`、`resource_write_set`/`write_set`、`isolation_strategy`、`max_parallelism` 和 `critical_path_weight`；Plan 调度按关键路径权重、下游数量和 ordinal 排序，并在领取 Step 前检查活跃 Step 的资源读写冲突。
- 变更：冲突 Step 不直接失败，而是写入 `RESOURCE_CONFLICT` 并短暂延后；需要隔离的 Step 创建内部 Session 和受控 workspace 引用，`GIT_WORKTREE` 当前作为 Lite 目录隔离与后续真实 git worktree 工具层的预留边界，不自动执行 merge。
- 变更：Plan 验证通过/失败都会写入 Agent Feedback；验证通过会沉淀过程型 Memory；Runtime Metrics 增加 Plan 验证、资源冲突、Agent Feedback 和验证 Memory 写入计数。
- 思路：把用户方案阶段 5/6 的受控并行、独立会话、关键路径调度、评分反馈、验证结果入 Memory 和全链路观测先落在单机 SQLite Lite 语义里；真实 Git worktree add/merge、Kafka/Redis/MinIO 和跨节点调度继续保持预留边界，不提前引入外部运维复杂度。
- 验证：运行 `.\mvnw.cmd -pl paicli-server -am "-DskipTests" compile` 通过；运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=PlanServiceTest,SqliteRuntimeStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，38 个定向测试通过，覆盖迁移 1-21、资源冲突推迟、隔离 workspace 引用、workspace owner 映射、Agent Feedback 幂等写入和验证 Memory 生成；运行 `.\mvnw.cmd test` 通过，Common 2 个、Server 99 个、Sandbox Agent 2 个测试均通过；运行 `.\mvnw.cmd package -DskipTests "-Dspring-boot.repackage.skip=true"` 通过；运行 `git diff --check` 通过，仅有 Windows 换行提示。

### Memory/RAG/Plan-Agent 阶段 2/3/4 增量闭环

- 变更：新增 Schema 迁移 20，Memory 增加结构化 payload、生命周期状态、来源修订、有效期、supersedes 和 checksum；新增 `memory_sources` 与 `memory_conflicts`，自动 Memory 同 key 内容变化会保留 revision、来源摘录和冲突审计。
- 变更：RAG 检索增加 Query Plan，识别代码路径、符号、排障、决策和架构查询；SearchHit 返回 citation、文档版本、BM25 分、检索策略和命中原因。
- 变更：`spawn_agent` 支持 PlanStep 绑定和执行 envelope，持久化 scope、允许文件/工具、输入 artifact、期望输出契约、验收标准、预算、deadline、依赖和禁止操作；`get_agent_result` 将子 Run 的结构化结果、Artifact、Token 用量、失败分类和证据写回 delegation。
- 思路：把用户方案中阶段 2/3/4 的核心工程闭环先落到现有 SQLite Lite 架构里，保持旧 API 兼容，继续复用 ReAct Run、ToolCall、Approval、Artifact 和预算边界；Kafka、Redis、MinIO 仍只作为预留端口，不引入外部适配器。
- 验证：运行 `.\mvnw.cmd -pl paicli-server -am "-DskipTests" compile` 通过；运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=SqliteRuntimeStoreTest,KnowledgeServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，27 个定向测试通过，覆盖迁移 1-20、Memory source/conflict/revision、Plan 绑定委派 metadata 和 RAG citation metadata；运行 `.\mvnw.cmd test` 通过，Common 2 个、Server 96 个、Sandbox Agent 2 个测试均通过；运行 `.\mvnw.cmd package -DskipTests "-Dspring-boot.repackage.skip=true"` 通过；运行 `git diff --check` 通过，仅有 Windows 换行提示。

### Plan Step 租约、心跳与过期恢复

- 变更：`plan_steps` 新增 `claim_owner`、`lease_expires_at`、`heartbeat_at`、`attempt`、`not_before`、`last_failure_class` 和 `dispatch_idempotency_key`，登记 Schema 迁移 19，并补齐旧库列迁移。
- 变更：Plan Worker 领取 `READY` Step 时写入 owner、60 秒租约、心跳和调度幂等键；Run 绑定、完成、失败、取消、跳过、重试和人工等待等状态出口会清理租约字段。
- 变更：新增 Step 租约心跳与过期恢复逻辑；每轮 Plan 调度前会把已过期且尚未绑定 Run 的 `RUNNING` Step 恢复为 `READY`，记录 `LEASE_EXPIRED` 和 `plan_step.lease_recovered` 事件。
- 思路：补齐方案里的 Agent Harness 恢复边界，重点解决“Worker 领取 Step 后、创建 Run 前中断，Step 永久卡在 RUNNING”的失败场景，同时仍保持 SQLite 单机 Lite 语义，不提前引入 Kafka/Redis。
- 验证：运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=PlanServiceTest,SqliteRuntimeStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，34 个定向测试通过，覆盖 Plan Step 领取租约、心跳 owner 校验、过期恢复事件、迁移 1–19 和既有 Plan 执行闭环；运行 `.\mvnw.cmd test` 通过，common 2 个、paicli-server 94 个、sandbox-agent 2 个测试均通过；运行 `.\mvnw.cmd package -DskipTests "-Dspring-boot.repackage.skip=true"` 通过；运行 `git diff --check` 通过，仅有 Windows 换行提示。

### Plan 确定性验证器与局部 Replan

- 变更：`PlanValidator` 扩展为 EvidenceBundle 结构，单条 done criteria 会记录 `validatorType`、`expected`、`actual`、`status`、`sourceRefs`、`startedAt` 和 `finishedAt`。
- 变更：新增 `file_exists:<path>`、`file_not_exists:<path>`、`file_contains:<path>::<text>` 和 `test_report:<path>` 验证规则；文件与测试报告验证只读取 `paicli.workspace-root` 下相对路径，拒绝绝对路径和越界路径。
- 变更：`replacePlan` 支持 FAILED/ACTIVE Plan 的局部尾部 Replan；当不存在运行中、等待审批、等待 Job 或验证中的 Step 时，保留已完成/跳过/取消步骤及其 Validation Check，删除未完成尾部并插入新步骤，新 Plan 版本重新进入 ACTIVE。
- 思路：先把方案里的确定性验证和失败恢复落地到现有 SQLite 结构中，不额外引入迁移；局部 Replan 只替换未完成尾部，避免无理由重做已经验证通过的工作。
- 验证：运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=PlanServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，10 个 Plan 测试通过，覆盖最终回答验证、文件验证、测试报告验证、路径越界拒绝、验证失败重试和失败尾部局部 Replan；运行 `.\mvnw.cmd test` 通过，paicli-server 91 个测试、sandbox-agent 2 个测试均通过；运行 `.\mvnw.cmd package -DskipTests "-Dspring-boot.repackage.skip=true"` 通过；尝试运行 `.\mvnw.cmd clean test` 时仍在 clean 阶段因 Windows 锁定 `paicli-server-0.6.0-SNAPSHOT.jar` 删除失败，非编译或测试失败。

### Plan Validator Gate 第一阶段落地

- 变更：新增 `PlanValidator`，Run `COMPLETED` 后不再直接完成 Plan Step，而是先进入 `VALIDATING`，按 done criteria 生成 `actual`、`evidence` 和 `error`。
- 变更：Plan Store 新增 `VALIDATING` 与 `VALIDATION_FAILED` 状态流转；验证通过才完成 Step、Async Job 和 Plan，验证失败会把 Validation Check 标记为 `FAILED` 并让 Plan 失败。
- 变更：`retryStep` 会清理旧 run/result/failure 和 Validation Check 结果，并在失败 Plan 中重新激活可重试步骤，避免旧验证失败证据污染下一次执行。
- 变更：当前内置 `run_status:COMPLETED`、`answer_contains:<text>`、`answer_not_contains:<text>` 和普通文字证据匹配，为后续命令/API/截图/文件断言和 Reviewer Agent 证据包预留验证接口。
- 思路：把“执行链路成功结束”和“任务验收标准满足”拆开，避免 Plan 只因为 Run 终态成功就误判完成，同时继续复用现有 SQLite Validation Check 表，不引入新的迁移风险。
- 验证：运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=PlanServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，7 个 Plan 测试通过，覆盖验证通过、验证失败、Async Job 与 DAG 批次。

### Kafka / Redis / MinIO 改造接口预留

- 变更：新增 Run Dispatch Queue、Run Execution Registry 和 Object Storage Port 三类端口，分别为后续 Kafka 队列、Redis 分布式执行注册/锁和 MinIO 对象存储适配器预留接口；当前默认实现仍是 SQLite claim、本进程 in-flight 集合和本地文件。
- 变更：Run Worker 改为依赖队列与执行注册端口；Artifact Controller、ToolRouter 和 ToolResultMaterializer 改为依赖 `ArtifactStore` 接口；本地 Artifact 写入改为经由对象存储端口落盘，元数据仍由 SQLite 管理。
- 变更：新增 `paicli.infrastructure.run-queue`、`paicli.infrastructure.coordination`、`paicli.infrastructure.artifact-storage` 配置项，当前只允许 `local`，显式配置 Kafka、Redis 或 MinIO 会报错说明“接口已预留但适配器未实现”。
- 思路：先把替换边界切清楚，但不引入外部中间件、不改变 Lite 单机运行语义，避免为了未来优化提前承担 Kafka/Redis/MinIO 的部署、事务和运维复杂度。
- 验证：运行 `git diff --check` 通过，仅有 Windows 换行提示；运行 `.\mvnw.cmd test` 通过，共 89 个测试；运行 `.\mvnw.cmd package -DskipTests "-Dspring-boot.repackage.skip=true"` 通过；尝试运行 `.\mvnw.cmd clean test` 时，Windows 仍锁定 `paicli-server-0.6.0-SNAPSHOT.jar` 导致 clean 阶段删除失败，非代码编译或测试失败。

## 2026-07-19

### 效率工作台检索与 Run 队列布局调整

- 变更：将效率工作台的“全局检索”移动到工作台顶部，位于当前项目提示之后，便于先跨 Session、消息、Memory、知识和 Artifact 检索。
- 变更：将效率工作台的 Run 队列改为默认收缩的折叠区，标题行展示当前队列数量并保留刷新按钮；对运行中、等待模型、等待工具和等待审批的非终态 Run 增加“终止”动作，可主动变为 `CANCELED` 终态后再按需重新排队。
- 思路：Memory 管理仍作为长期记忆管理区保留在原模块内；全局检索是跨数据源入口，更适合作为工作台第一操作区。
- 验证：运行 `node --check paicli-server/src/main/resources/static/app.js` 通过；未启动浏览器做视觉检查。

### GitHub 抓取与 MCP 配置优化

- 变更：新增只读工具 `github_repo_fetch`，在联网开启时可直接读取 GitHub 仓库元数据、README 和顶层文件树，避免依赖 `github.com` HTML 页面抓取。
- 变更：`web_fetch` 遇到 GitHub 仓库首页时自动转为 GitHub API 摘要，遇到 `github.com/.../blob/...` 文件页时优先转换为 `raw.githubusercontent.com`；网页超时错误会提示使用搜索结果、raw URL 或 `github_repo_fetch`。
- 变更：能力管理新增“GitHub 快速配置”按钮，保存远程 Streamable HTTP MCP Server `https://api.githubcopilot.com/mcp/` 与 `Authorization: env:GITHUB_MCP_TOKEN`；专家工具白名单支持 `mcp__github__*` 前缀通配。
- 变更：上下文组装会过滤缺少完整 tool 响应的历史 assistant `tool_calls` 片段，避免取消或异常中断后的坏历史在下一轮请求中触发 OpenAI-compatible `insufficient tool messages following tool_calls message` 400。
- 思路：SearXNG 继续负责发现网页，GitHub 仓库内容读取优先走结构化 API 或 GitHub MCP，普通 `web_fetch` 只作为兜底。
- 验证：运行 `node --check paicli-server/src/main/resources/static/app.js` 通过；运行 `mvn -pl paicli-server -am -DskipTests compile` 通过，覆盖 GitHub 工具、MCP 通配和不完整 tool_calls 历史过滤。

### 对话内 Plan 自动触发与进度可视化

- 变更：聊天输入区新增“按计划执行”按钮，可将当前输入生成持久化 Plan、立即启动并调度执行。
- 变更：普通发送会对“创建计划/按计划执行/指定计划/plan”等显式计划意图进入 Plan 工作流；复杂但未明确提及 Plan 的任务交给服务端自动 Leader 编排，避免绕过专家协作链路。
- 变更：当前 Session 的关联 Plan 面板在对话窗口顶部展示步骤进度条、前 8 个步骤状态标签、当前步骤摘要和调度/详情/工作台动作；活跃 Plan 会定时刷新进度。
- 思路：Plan 作为复杂任务编排和恢复边界，普通聊天保持轻量；只有用户明确意图或复杂度明显时才自动升级为持久化 Plan。
- 验证：运行 `node --check paicli-server/src/main/resources/static/app.js` 通过；运行 `mvn -pl paicli-server -am -DskipTests compile` 通过。

### Multica 方向智能体专家 Profile 实验

- 变更：新建 `codex/agent-profile-multica` 独立实验分支，基于本地 `master` 最新提交开发，不依赖远端未推送状态。
- 变更：新增 Schema 迁移 17，增加 `agent_profiles` 表与 `runs.agent_profile_id`，专家 Profile 保存专家指令、模型方案、工具白名单、Skill 白名单、输出契约、协作角色、交接策略、工作区范围、审批策略和启用状态。
- 变更：新增 `/v1/productivity/agent-profiles` CRUD API；创建/重试 Run 支持 `agentProfileId`，未显式指定模型方案时优先使用专家绑定的模型方案。
- 变更：ContextManager 在选择专家时注入专家系统指令，并按专家配置过滤传给模型的 Tool Definition 与可见 Skill 索引；委派子 Run 继承父 Run 的专家配置。
- 变更：聊天输入区新增专家下拉选择，静态资源版本号同步更新；专家创建后续收敛到首页左侧独立入口，避免和效率工作台重复。
- 变更：新增 Schema 迁移 18，为 `run_delegations` 增加 `agent_profile_id`；`spawn_agent` 支持按 `agent_profile_id` 创建子 Run，并优先使用该专家绑定的模型方案。
- 变更：新增只读工具 `list_agent_profiles`，Leader 可先读取当前项目启用的专家目录，再按专家 Profile 分派任务；`list_agents` 和 `get_agent_result` 返回子任务关联的专家 Profile。
- 变更：`/v1/productivity/agent-profiles/starter-pack` 幂等补齐内置专家模板，包含 Leader 任务队长、需求分析、代码实现、测试验证、代码审查和文档交付专家。
- 变更：Console 首页左侧新增“专家创建”独立模块，集中展示专家概览、专家列表、模板补齐和编辑创建；首页空状态新增“普通对话 / 专家协作”模式切换，专家协作模式承载一句话协作启动，会创建独立 Session，并用 Leader Profile 发起第一轮 Run。
- 变更：专家列表新增编辑入口，内置专家模板与用户自建专家都可通过同一表单修改；“选用”改为“用于对话”并显示当前选用状态，明确它只影响聊天输入区下一次 Run 的专家 Profile。
- 变更：专家表单中的工具和 Skill 白名单从逗号文本改为多选下拉，工具提供内置工具清单，Skill 从当前项目 `/v1/skills` 加载；协作角色下拉增加说明，解释 Leader、Expert、Reviewer 和 Runner 的当前运行语义。
- 变更：新增 `run_collaboration_policies` 持久化协作策略，首页协作启动前可设置复杂度、风险、最多专家数、允许专家范围、最大递归深度、是否允许子专家继续分派、Reviewer/Runner 要求和预算上限字段。
- 变更：`list_agent_profiles` 会按协作策略过滤本次可见专家；`spawn_agent` 会在执行时校验 `agent_profile_id`、允许列表、最大专家数、最大子 Run 数、最大深度以及非 Leader 派发限制，避免只靠 Prompt 自觉遵守。
- 变更：新增 `/v1/runs/{runId}/collaboration`，Console 在当前 Session 消息区顶部展示“协作任务看板”，列出 Leader 已分派的专家、子 Run 状态、任务说明、错误和终态专家结果。
- 变更：专家模板新增 `template_key`/`template_version` 元数据；新增内置模板列表、恢复默认模板和复制专家 API；Console 专家列表支持复制为新专家、恢复内置模板版本，并展示模板版本。
- 变更：角色默认工具与审批策略落到创建保存逻辑：Leader 默认获得协作派发工具，Reviewer 默认只读审批和只读工具，Runner 默认包含 `execute_command`，非 Leader 保存时会移除协作派发工具；专家工具/Skill 选择器升级为搜索标签式添加。
- 变更：新增 `/v1/sessions/{sessionId}/plans`，按 Session、Session Run 和 Plan Step 绑定的 Run 查找关联 Plan；Console 普通消息区顶部展示关联 Plan 的目标、状态、步骤进度、当前步骤和工作台/详情/调度动作。
- 变更：移除效率工作台中的“智能体专家”重复列表和“新建专家”入口，专家新建、编辑、复制、恢复默认模板统一留在首页左侧“专家创建”模块；普通对话会按复杂度自动决定是否进入专家协作模式。
- 变更：专家协作首页模式隐藏底部普通聊天发送框，只保留上方协作启动表单；切回普通对话或进入具体 Session 后自动恢复底部输入区。
- 思路：先把 Multica/WorkBuddy 式多专家协作所需的“专家目录”和 Leader 最小协作闭环做成可持久化、可审计、可恢复的基础能力；随后把“模型建议、后端约束”的调度策略落地，让 Leader 负责判断和拆分，后端负责数量、范围、递归和权限边界。
- 验证：运行 `node --check paicli-server/src/main/resources/static/app.js`；运行 `git diff --check`，仅有 Windows 换行提示；运行 `mvn -pl paicli-server -am "-Dtest=WebSecurityIntegrationTest,PlanServiceTest,SqliteRuntimeStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，29 个测试通过，覆盖 Console 静态入口、Plan 执行闭环、协作策略持久化、迁移和 Session 删除级联；运行 `mvn -pl paicli-server -am -DskipTests package` 打包通过；尝试以 8081 local sandbox 启动，前台日志显示 Tomcat 已启动，当前工具环境未能让后台子进程持续保活，因此未完成 HTTP 冒烟。

### Plan 执行闭环、Async Job 与验证证据

- 变更：新增 Schema 迁移 16，为 `plan_steps` 增加 `run_id`，并新增 `async_jobs`、`validation_checks` 表。
- 变更：新增 `PlanExecutionService` 和 Plan Worker，支持 `READY` Step 创建普通 ReAct Run，Run 终态回写 Step、Plan、Async Job 和 Validation Check；`ASYNC`/`ASYNC_JOB` Step 进入 `WAITING_JOB` 并可轮询 Job。
- 变更：新增 `/v1/plans/{id}/dispatch`、`/dag/batches`、`/jobs`、`/validation-checks`、通用 `/v1/async-jobs` 与 Job cancel API；Console 效率工作台新增 Plan 工作台入口。
- 变更：评测 Starter Pack 增加默认关闭的 Plan/DAG/验证模板用例。
- 思路：继续复用现有 RunProcessor、ToolCall、Approval 和预算链路，不让 Plan 调度器直接执行工具；Read-only 并行当前先做 DAG 批次分析和保守调度，真正并行留给资源锁与会话隔离阶段。
- 验证：运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=PlanServiceTest,SqliteRuntimeStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，覆盖 Step 内 ReAct Run 调度、Async Job、Validation Check、Read-only DAG 批次和迁移 1-16。

### Plan Runtime 基础持久化

- 变更：新增 `plans`、`plan_steps`、`plan_edges`、`plan_revisions` 和 `plan_events` 表，登记 Schema 迁移 15，并在删除 Session 时同步清理关联 Plan 数据。
- 变更：新增 Plan JSON 解析校验能力，支持清理 Markdown code fence、重新映射模型生成的 step id、校验步骤类型/执行模式、依赖存在性和 DAG 循环。
- 变更：新增 Planner 服务和 `/v1/plans` 系列 API，支持创建、模型生成、查看、批准/启动、取消、Replan、Step retry/skip 和事件读取；启动阶段只把根 Step 推进到 `READY`，不绕过现有 ReAct Run、ToolCall 和 Approval 边界。
- 思路：先把 Agent 的计划从“模型输出文本”升级为可恢复、可审计的数据对象，再逐步接入 Step 内 ReAct Run、Async Jobs、Read-only 并行 DAG、Validation Checks 和 Console 工作台，避免一次性重写现有 Agent Loop。
- 验证：运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=PlanServiceTest,SqliteRuntimeStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，覆盖 Plan DAG 校验、启动根 Step、Replan 版本和迁移 1-15。

## 2026-07-18

### 补充 Agent 评测中心面试讲解

- 变更：扩展《PaiCLI Platform Lite 技术架构与面试讲解》，系统说明评测驱动开发的思想、为什么需要产品级评测中心，以及 Suite、Case、Execution、Trial、Check、Baseline 的职责。
- 变更：逐一解释官方四类评测套件的目标与前置条件，补充 Baseline 的保存内容、比较方式、适用边界、选择原则和可直接口述的面试回答。
- 思路：用例不是功能和算法的替代品，而是 Agent 功能的可执行验收标准；把真实需求和失败沉淀为可重复执行的 Case，才能在模型、Prompt、上下文和工具变化后持续发现行为与资源退化。
- 验证：检查 Markdown 结构与 Git 差异，执行 `clean test`、`clean package`，并使用本地启动脚本验证服务健康。

## 2026-07-17

### 运行时可靠性与评测预算加固（`f69b114`）

- 变更：修正评测输出 Token 与总 Token 的口径，只有通过的 Trial 才能晋升人工 Baseline；增加评测资源硬门禁，避免预算超限后仍被误判通过。
- 变更：强化 RunProcessor、EvaluationService、SQLite 连接与迁移逻辑，增加 WAL 一次性初始化、30 秒写锁等待和并发写入回归。
- 变更：相同工具与完全相同参数默认最多重复 3 次，超过后立即终止失控 Run，降低无进展循环耗尽 Token 和时间预算的风险。
- 思路：评测中心不能只看最终文字是否正确，还要把 Token、耗时、工具次数和 Baseline 退化作为产品质量门禁。
- 验证：同步补充 Store、RunProcessor 与评测服务回归测试；文档更新到阶段 13 和迁移 14。

### 效率工作台与评测中心 UI 重组（`3917055`）

- 变更：移除界面中的优先级阶段字样，把“效率工作台”和“Agent 评测中心”拆成首页一级入口。
- 变更：长期使用效率页只固定展示核心指标，最近用量改为可折叠、限高滚动明细，避免记录越多页面越长。
- 变更：Agent 评测中心改为套件和报告双栏布局，用例默认折叠，两侧独立滚动。
- 思路：首页应该承载清晰入口，而不是把所有中后台能力堆进一个长面板；评测中心属于质量闭环，应作为独立能力被看见。
- 验证：执行 JavaScript 语法检查、Maven 测试和打包，并用本地浏览器检查首页、效率工作台和评测中心布局。

### 生产化运行时加固与官方评测集（`cc67248`）

- 变更：加入 ToolEffect 分类、状态机单向终态、模型尝试记录、熔断半开探测、Worker 背压、通知 Outbox 与 Prometheus 指标。
- 变更：加入版本化官方 Starter Pack，覆盖基础安全、工具审批、上下文能力、稳定性与预算等 4 个评测套件。
- 变更：加入生产 API Key 启动门禁、部署安全校验和更多审计脱敏。
- 思路：从“能跑”推进到“可恢复、可观测、可对账”；所有新增能力继续走持久化 ToolCall、审批、事件和审计边界。
- 验证：补充评测 Starter Pack、部署安全、审计和 Store 测试；README、架构与阶段文档同步。

### Agent 评测中心落地（`e89c9ed`、`3715c7d`、`6031f2e`）

- 变更：新增 EvaluationController、EvaluationService、EvaluationStore，建立 Suite、Case、Execution、Trial、Baseline 数据模型。
- 变更：每个评测 Trial 复用隐藏内部 Session 和普通 Run，不新造第二套 Agent Loop。
- 变更：评分报告按工具、回答片段、Token、耗时和 Run 终态扣分，并支持人工确认 Baseline。
- 思路：评测应复用真实运行链路，这样才能发现审批、工具、上下文、预算和持久化恢复中的真实退化。
- 验证：补充 EvaluationService 测试、Store 测试与 README 说明。

## 2026-07-16

### Memory 管理与结构化创建体验修复（`6efd68f`）

- 变更：优化 Memory 合并、修订、版本查看和恢复相关弹窗，修复按钮无反应和交互中断问题。
- 变更：继续保持 Memory 管理在工作台内完成，但关键操作改为明确表单和下拉选择。
- 思路：Memory 是长期使用的人工纠错边界，交互必须可确认、可回退，不能依赖容易丢状态的连续弹窗。
- 验证：补充 Console 安全集成回归，更新 README、架构和阶段文档。

### 模板、方案、定时任务与通知创建表单优化（`fb00fe3`）

- 变更：新建模板、模型方案、定时任务和通知从连续弹窗改为一次性结构化表单。
- 变更：定时任务模板选择改为下拉选择，并按一次性、每日、每周、Cron 动态展示字段。
- 思路：高频配置属于长期使用效率入口，应减少手动输入和半途取消导致的脏状态。
- 验证：补充 ProductivityController、ScheduledTaskService 和前端回归；同步 README、架构和阶段文档。

### 本地启动与 API Key 体验修复（`64591c7`、`ced145c`、`a17b13f`、`f21034c`）

- 变更：模板解析支持按 id 解析，修复“task template not found”。
- 变更：`start-local.ps1` 支持端口已有服务时幂等返回，避免重复打包造成 Windows JAR 文件锁。
- 变更：Console 对 401 连接失败给出更明确的连接设置引导。
- 变更：文档区分 `PAICLI_API_KEY` 和 `PAICLI_MODEL_API_KEY`，说明前者是访问本地 PaiCLI API 的密钥，后者才是模型供应商密钥。
- 思路：本地开发链路要先把“能启动、能连上、知道错在哪里”做好，否则后续能力再多也很难使用。
- 验证：补充安全集成测试和 Store 回归；README 同步配置说明。

### 长期使用效率能力完成（`2ee5d38`）

- 变更：加入任务模板、变量、快捷指令、模型配置方案、后备模型、提交前上下文和成本预估。
- 变更：加入用量与成本面板、预算策略、Run 队列管理、定时任务、完成通知、Session 导入导出。
- 变更：扩展 Skill 生命周期管理和 MCP 配置界面。
- 思路：把一次性聊天工具推进到可长期使用的个人 Agent 控制台，核心是把重复配置沉淀为可复用资产。
- 验证：新增 ProductivityController、ProductivityStore、ScheduledTaskService、CompletionNotificationService 和相关回归测试；中文文档同步。

### 产品站点创建（`623a23f`）

- 变更：新增 `paicli-site/` 产品站点工程和 Sites 托管配置。
- 变更：README 增加在线产品站点和本地站点开发说明。
- 思路：Runtime 本体偏工程化，需要一个更适合介绍定位、能力边界和部署方式的产品页面。
- 验证：加入站点渲染测试和站点 README。

## 2026-07-15

### 业务效率工作台完成（`88877c2`）

- 变更：新增终态 Run 原地重试、带历史上下文的分支会话、项目级统一检索。
- 变更：工作台集中管理审批策略、Memory、知识文档、Artifact 和搜索结果。
- 变更：Memory 支持来源、置信度、置顶、启停、确认、合并、修订、历史版本查看与恢复。
- 思路：把散落在 REST API 中的治理能力收敛到日常可用的工作台，让用户能在一个地方处理长期资产。
- 验证：补充 SearchController、GlobalSearchService、Approval、Knowledge、Store 和前端回归；文档同步。

### Runtime 运维与交付加固（`76f681b`）

- 变更：增加 CI、Dependabot、SBOM、Maven 警告检查、SecurityHeadersFilter 和生产安全门禁。
- 变更：强化 Docker/Sandbox 输出限额、令牌认证、超时进程树终止、SQLite 连接策略、WAL 维护和备份恢复校验。
- 变更：增加原子文件写入、孤儿文件清理、Event/Audit 保留策略和安全响应头。
- 思路：把原型推进到可交付状态，先补安全、可维护性、存储健康和自动化门禁。
- 验证：新增 BoundedOutputBuffer、SandboxSecurity、AtomicFileWriter、WebSecurityIntegration、SqliteMaintenanceService 等测试。

## 2026-07-10

### 历史会话检索工具（`acf4427`）

- 变更：新增 `session_search` 工具，允许 Agent 在当前项目内检索历史会话消息。
- 变更：ToolRouter 接入历史检索 Provider，Store 增加对应查询能力。
- 思路：长线使用时，历史对话本身就是上下文资产；先以受控、项目内可见的 BM25 检索接入，不把全部历史直接塞回模型。
- 验证：新增 SessionSearchToolProviderTest；README、架构和阶段文档同步。

## 2026-07-09

### 初版 PaiCLI Platform Lite（`93302a2`）

- 变更：建立 Java 17 + Spring Boot 3.3 + Maven 多模块工程，包括 `paicli-server`、`paicli-sandbox-agent`、`paicli-common`。
- 变更：实现 Session、Run、Message、Event、ToolCall、Approval、Artifact、Memory 等核心数据模型和 SQLite WAL 存储。
- 变更：提供 REST、SSE、RunWorkerCoordinator、RunProcessor、DemoModelClient、OpenAI-compatible 模型客户端、Local/Docker Sandbox 骨架。
- 变更：加入知识检索、Skill、MCP、联网、Multi-Agent、附件、审计、OpenAPI、备份恢复脚本和初版 Console。
- 思路：初版不是聊天壳，而是围绕“持久化、审批、恢复、审计、Sandbox 边界”搭一条可验证的 Agent Runtime 主链路。
- 验证：初始测试覆盖 Common、Server、Sandbox Agent 的核心边界；配套 README、架构、阶段、Docker Sandbox 和技术讲解文档。

## 优化思路总结

1. 先保证 Runtime 正确性，再扩展能力。工具调用、审批、恢复、SSE、事件和审计是底座，后续 Skill、RAG、MCP、Multi-Agent、评测都不能绕过这条链路。
2. 能力增加必须可治理。Memory、知识、Artifact、Skill、MCP、模板、预算和评测都要有管理入口、状态展示和回滚或纠错边界。
3. 长期使用优先减少重复劳动。模板、模型方案、快捷指令、定时任务、通知、队列和导入导出，都是把一次性操作沉淀为可复用资产。
4. 评测要使用真实运行链路。只有复用真实 Session/Run/ToolCall/Approval/Event，才能发现模型升级、Prompt 修改或工具行为变化带来的真实退化。
5. 单机 Lite 也要有生产意识。SQLite、Docker、文件系统、API Key、备份恢复、CI、指标和安全响应头都按可交付标准处理，只是不引入分布式复杂度。
6. Console 交互要随数据增长保持克制。列表、报告、评测和用量明细会越来越多，应使用折叠、滚动、分栏和固定指标区，避免能力堆叠成不可维护的长页面。
## 对话内自动编排 Leader 与专家
- 变更：修复专家协作等待链路。Leader 查询到子 Run 仍在运行或等待审批时会进入 `WAITING_AGENT`，子 Run 结束、失败或被拒绝后自动重新排队 Leader，避免轮询消耗 Run 步骤预算；Console 和工作台同步展示该状态，并补充持久化恢复测试。
- 变更：补齐 local executor 的受限 `write_file` 能力：仅能写入当前 Run 工作区、自动创建目录、以 UTF-8 覆盖写入并限制单次 1 MiB；仍由既有持久化 Approval 边界保护。
- 变更：协作会话的审批卡片改为汇集 Leader 与直接委派子 Run 的待审批项；Leader 处于 `WAITING_AGENT` 时也会刷新，避免子专家审批在前端被隐藏。
- 变更：子 Run 的 local/Docker 沙箱与模型运行上下文统一解析到委派树根 Leader 的工作区，子专家可读取并共同维护 Leader 产物，同时仍保留各自独立的 Run、工具调用、审批和审计记录；工作区内 `write_file` 与 `spawn_agent` 不再逐次审批，`execute_command`、MCP 和取消子任务仍需审批。
- 思路：把“等待异步子任务”建模为持久化状态，而不是继续让模型轮询；保留子任务的独立审批边界和原始工具参数，满足可审计、可恢复要求。
- 验证：`git diff --check`、`node --check paicli-server/src/main/resources/static/app.js`；`mvn -pl paicli-server -am "-Dtest=RunProcessorTest,SqliteRuntimeStoreTest,LocalSandboxDriverTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过（27 tests）。

- 变更：普通对话不再要求用户先切换“专家协作”模式；服务端会按任务长度、动作数量、步骤连接词和风险词进行保守复杂度评估，复杂任务自动选择启用的 Leader Profile。
- 变更：自动协作 Run 会持久化协作策略，Leader 按专家目录、角色、工具/Skill 和策略预算调用 `spawn_agent`，简单问题仍走原来的单 Agent 路径。
- 变更：自动协作会创建当前 Session 可见的根执行计划，并将 Leader Run 绑定为计划进度来源；专家细分任务和结果继续在协作任务看板中实时展示。
- 思路：把“是否需要组队”的判断放到后端统一入口，避免只有首页按钮能触发；把 Plan 作为 Leader 协作的可恢复进度外壳，实际子任务仍由 Leader 依据上下文动态拆分。
- 验证：待完成编译和前端语法检查。
