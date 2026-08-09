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
- Console 仅把 API Key 保存在当前标签页 `sessionStorage`，并启用 CSP、防嵌套、MIME 嗅探防护、Referrer 与浏览器权限策略。交付 HTML 不作为同源页面直接执行：Console 先用认证请求读取入口及工作区内的静态相对依赖，将脚本、样式和媒体转换为自包含文档，再放入不含 `allow-same-origin` 的 sandbox iframe；预览文档的 CSP 禁止网络连接、嵌套页面和对象加载，避免不可信产物读取 Console 凭据或调用管理 API。
- 删除 Session 前拒绝活跃 Run；随后同一事务删除 Approval、ToolCall、Event、Artifact、ModelUsage、ModelAttempt、MemoryExtraction、CollaborationPolicy、AsyncJob、Message 和 Run。删除分组只把会话移到未分组。
- 模型密钥只留在 Server，不进入 Sandbox、模型上下文或附件目录。

## P0 业务工作台

- 终态 Run 可以在原 Session 重试；分支操作创建新 Session，并复制源 Run 之前的未归档对话，再用源输入和推理设置创建新 Run。
- 危险工具仍先创建持久化 Approval。用户可选择仅本次、本对话或本项目允许；持久化策略同时匹配工具名和已落库参数的 SHA-256，因此不会扩大到模型后来生成的不同参数。
- `/v1/search` 聚合项目内 Session 标题、Message、Memory、Knowledge SearchHit 和 Artifact 元数据，返回可跳转的 Session/Run 标识。
- Memory 管理保留层级、类型、来源、置信度、访问统计和修订历史，并支持置顶、启停、人工确认、同项目合并及历史恢复。Console 合并表单通过下拉框选择目标并预览内容；修订表单可编辑当前 Key、内容和标签，同时查看、恢复全部历史版本。Runtime 召回只使用启用项并优先置顶项。
- Knowledge 文档维护集合、标签、版本、索引状态、分块数和 Embedding Provider；重建索引继续使用原子替换。检索结果包含文档、分块、标题和字符区间，形成引用定位链；Console 的有用/无用反馈持久化到 SQLite，供后续排序治理使用。
- Artifact 文件先校验路径和 SHA-256，再提供预览、认证下载或复制为指定 Session 的待提交文本附件。

## 长期使用效率

- 任务模板把 Prompt、模型方案、附件要求和工具权限保存到项目级或全局配置；提交前在 Server 端解析 `${repository}`、`${outputFormat}` 等变量。内置 `/review`、`/summarize`、`/research` 快捷入口，Console 草稿按 Session 自动保存。内置 Agent Profile 使用 `template_key + template_version` 治理：读取专家目录时，旧版本绑定项升级到当前提示词和最小工具集，但保留模型、思考档位、Shell 与启用状态，避免历史宽白名单长期展开无关 MCP Schema。模板、模型方案、定时任务和通知均通过一次性结构化表单创建，避免连续弹窗导致上下文丢失或半途取消。
- 模型方案保存用途、端点、模型、后备模型、上下文上限、输出上限和单价。项目可设置默认方案；每个 Run 固化所用方案，失败重试可切到后备方案，本地模型允许不配置 API Key。
- `model_usage` 记录模型名、输入/输出 Token、缓存命中、耗时、重试和本地模型标志。预算策略按项目限制日/月 Token、日/月费用与最大并发，提交前提供上下文、输出和费用风险估算。
- Run 队列按优先级领取，并在项目最大并发约束下进行公平调度；Console 可调整优先级、批量取消、重新排队，并查看当前状态、耗时和重试次数。效率工作台对终态 Run、Memory、Artifact 和持久化审批策略提供批量永久删除：单批 ID 先完整校验，再在 SQLite 单事务中删除；任一缺失 ID、非终态 Run，或目标 Run 的关联委派树仍有活跃执行，都会回滚整批。Run 删除显式清理运行记录及其消息、ToolCall、Approval、Event、模型用量、委派关系和 Artifact 元数据，并在提交后清理不再共享的 workspace、Artifact 与绑定附件文件；Artifact 独立批删同步清理对象存储文件。
- 定时任务通过下拉框引用当前项目已保存模板，支持一次性、每日、每周和 Cron，并按周期动态收集时间字段；Cron 的首次与后续执行均按服务端系统时区计算。任务可独立持久化模型方案，以及执行专家或执行小队（二选一）；调度时模型优先级为任务显式选择、执行专家模型、模板模型、服务端默认。小队会固化 Leader 与成员策略为协作 Run 和可恢复计划。调度器创建普通 Session/Run，因此继续复用审批、审计、预算、恢复和通知链路。
- 完成通知支持浏览器通知，以及由 Server 读取环境变量密钥的 Webhook、邮件网关或企业 IM 网关，事件覆盖完成、失败、等待审批和预算不足。
- Session 可导出 Markdown、JSON 或带 ToolCall、Approval、Event、Artifact 清单的审计包；导出支持隐私脱敏，导入会在另一实例创建新的可继续对话 Session。
- Skill 生命周期记录来源仓库、Ref、Commit、安装时间和作用域，GitHub tree/blob 链接可从路径推断 Skill 目录名，安装前展示文件清单与权限声明，并支持启停、固定、检查更新、升级和单级回滚。MCP Server 可在 Console 新增、测试、启停和删除，敏感 Header 只保存环境变量名，工具 Schema、健康和熔断状态可见。
- 首页把效率工作台作为独立入口。用量区域将近 30 天固定指标与最近调用明细分离：指标使用固定网格，明细进入默认收起、限高滚动的表格式区域，避免 Session/模型维度增长时持续撑高页面。

## Agent 评测中心

评测层不实现第二套 Agent Loop。`evaluation_suites` 和 `evaluation_cases` 保存项目级输入、工具/回答约束、资源上限与通过阈值；启动 Execution 后，每个 Case/Trial 创建隐藏的内部 Session 和普通 Run，因此继续复用现有队列、模型方案、ToolCall 持久化、审批、Event、Audit、Artifact 和恢复边界。Execution 可选持久化 `agent_team_id`：未选择时保持单 Agent 基线，选择后使用团队 Leader 创建 Trial Run，并把成员、并发、深度和 Reviewer/Runner 要求固化为 Collaboration Policy。内部评测 Run 不参与自动 Memory 提取，避免测试内容污染长期记忆。危险工具仍创建持久化 Approval；评测报告暴露待审批项供人工单次允许或拒绝，不为追求自动化而扩大权限。

`EvaluationService` 在读取报告时同步终态 Trial，并从 Run、Message、ToolCall 和 ModelUsage 构建结构化评分快照。Case 的 `maxTokens` 明确定义为输出 Token，报告同时保留输入、输出和总 Token；工具数、输出 Token、耗时上限属于硬门禁，避免“超预算扣 10 分后仍刚好达到阈值”。Execution 只有在全部 Trial 达到阈值且通过资源门禁时通过；运行中报告保持可刷新，不阻塞 Worker 等待。

`evaluation_baselines` 只接受已通过 Trial，保存人工确认的来源 Run、最终回答、工具名称序列、Token 口径和耗时。新基线使用输出 Token；迁移前基线以 `TOTAL` 标记并继续按总 Token 比较。基线不持久化为新的模型上下文，也不把原始 reasoning 作为严格相等条件；这避免模型升级时因合法路径变化产生大量伪回归。

版本化官方 Starter Pack 位于 classpath `evaluations/starter-pack.json`。安装服务按 Suite/Case 名称幂等合并，只创建缺失项，不覆盖用户已有规则；默认包覆盖基础安全、审批、受管能力和稳定性/预算。依赖 Knowledge、Skill、Web 或 Multi-Agent 前置条件的 Case 默认停用，用户可在 Console 显式启用。

评测中心从效率工作台中抽离为首页一级入口。宽屏使用“套件/报告”双栏布局，套件用例默认折叠，两栏各自限高滚动；窄屏降级为上下两块独立滚动区域。这样套件、Case 和 Trial 增长时不会形成一条无限延长的单列页面。

## Plan Runtime 基础

Plan Runtime 是位于普通 ReAct Run 之上的任务层编排边界。它把“计划”从模型文本变成可恢复、可审计、可调度的数据对象，但不替代现有 RunProcessor，也不绕过 ToolCall、Approval、Event、Artifact 和预算链路。

- `plans` 保存目标、摘要、项目、可选 Session/Run 关联、状态、版本、来源、原始计划 JSON 和失败原因。
- `plan_steps` 保存任务级步骤、执行模式、验收标准、资源读写集、隔离策略、关键路径权重、workspace 引用、状态、领取 owner、租约、心跳、尝试次数、调度幂等键和绑定的普通 `run_id`。Step 是任务层对象，不直接保存工具参数；执行时仍由普通 Run 和 ToolCall 落库真实动作。
- `plan_edges` 保存 `DEPENDENCY`、`CONDITIONAL`、`REWORK` 三类边，方向为“来源 Step -> 目标 Step”，并持久化确定性条件、优先级、最大回流次数和已回流次数。普通依赖满足后推进；条件未命中的分支自动跳过；失败回流只重置目标及其下游且受次数上限控制。
- `plan_revisions` 保存 Replan 版本原因和原始 JSON；非 ACTIVE 草稿可整体替换，FAILED/ACTIVE Plan 在没有运行中、等待审批、等待 Job 或验证中的 Step 时支持局部尾部替换，已完成/跳过/取消步骤及其证据会被冻结保留。
- `plan_events` 保存 Plan/Step 状态事件，供后续 Console 时间线和审计使用。
- `async_jobs` 保存异步 Step 和外部长任务的状态、幂等键、payload、result、log 与错误，支持 poll 和 cancel。
- `validation_checks` 保存每个 Step 的 Done Criteria、实际结果、证据和错误，供 Console 与最终回答引用。

Planner 调用现有 `ModelClient` 生成结构化 JSON。Server 会清理 Markdown code fence、重新映射模型给出的 step id、限制步骤数量、校验 Step 类型与执行模式、校验依赖存在和循环依赖。校验失败不会创建可执行 Plan。默认 Demo 模型会生成单步分析计划，保证本地无模型 Key 时仍能验证 API。

计划启动后，`PlanExecutionService` 会先回收过期且尚未绑定 Run 的 `RUNNING` Step 租约，再按关键路径权重、下游数量和 ordinal 领取 `READY` Step。调度前会汇总活跃 Step 的资源读写集，阻止写写或读写冲突；冲突 Step 会写入 `RESOURCE_CONFLICT` 并短暂延后。`REACT` Step 创建普通 Run；`ASYNC`/`ASYNC_JOB` Step 同时登记 Async Job；`NONE` Step 可直接完成；只有 `USER_APPROVAL` 进入 Human Node 等待状态，并通过持久化 decision API 决定后续条件边。条件命中、未命中和失败回流都写入 `plan_events`，未选分支及其 Validation Check 一起标记 `SKIPPED`。`REWORK` 命中时只重置目标节点和非回流边可达的下游分支，保留其他已完成分支；超过 `max_traversals` 后不再自动回流。Run 进入 `COMPLETED` 只代表执行链路结束，Step 会先进入 `VALIDATING`，验证通过才推进后续边；未被条件边处理且无法回流的失败才终止 Plan。

`PlanState` 是从 Plan、Step、Edge、Event 和 ModelUsage 生成的结构化运行快照，包含状态计数、可运行/活跃/等待人工节点、阻塞原因、累计 Token 和最后事件序号。Console、恢复 Worker、API 客户端和后续 Evaluation 可以共享同一状态视图，不需要各自从聊天文本推断进度。

本阶段已提供 `/v1/plans`、`/v1/plans/generate`、`/v1/plans/{id}/approve|start|dispatch|cancel|replan`、`/v1/plans/{id}/steps|events|jobs|validation-checks`、`/v1/plans/{id}/dag/batches`、`/v1/plan-steps/{id}/retry|skip` 和 `/v1/async-jobs`。Read-only DAG 仍提供批次分析；执行侧已经具备资源读写集冲突控制、内部 Session 隔离和 workspace 引用，Lite 版暂不自动执行真实 Git worktree merge。

## Plan 与 Multi-Agent 协同

Plan 负责“任务如何拆、依赖如何排、每步如何验收”；Multi-Agent 负责“某个步骤是否需要委派给专家 Run”。二者之间通过持久化字段衔接，而不是靠对话文本约定。

`spawn_agent` 是普通 ToolCall，必须先落库并按需要审批。执行时 `DelegationToolProvider` 会根据父 ToolCall 幂等创建或复用内部子 Session/Run，并把 `plan_id`、`plan_step_id`、scope、允许文件/工具、输入 artifact、期望输出、done criteria、预算、deadline、依赖、资源读写集、workspace 引用、失败策略和禁止操作写入执行信封。子 Agent 仍由普通 `RunProcessor` 执行，因此继续复用正式模型方案、ToolCall 持久化、Approval、Artifact、Event、预算和恢复链路。

Delegation Graph 使用 `run_delegation_dependencies` 保存有向依赖边，`run_delegation_resources` 保存规范化读写集。Run Queue 只领取依赖已满足且没有 workspace 资源冲突的节点；同一 `workspace_ref` 共享 workspace owner，不同引用形成隔离目录。上游失败后，下游按 `BLOCK_GRAPH` 级联取消、`DEGRADE` 带风险继续或 `REQUIRE_HUMAN` 进入持久化人工节点。Plan Graph 继续负责跨 Step 的条件边、REWORK、Validation Gate 和 Human Node；Delegation Graph 负责 Leader 动态派发，两者共享“持久化节点/边、确定性就绪判断、普通 Run 执行”的语义。

子 Run 终态与 Event 原子提交时，同一事务会写入 Result Envelope v2、把有界上游信封追加到可执行下游的子 Session、推进依赖节点并唤醒 `WAITING_AGENT` 父 Run。信封自动归集摘要、Artifact、Token、文件写入、命令、测试、失败分类、风险和未完成项。Leader 的 `get_agent_result` 只读取持久化信封；Plan Step 最终仍由 `PlanValidator` 验收，子 Agent 声称完成不等于 Step 自动完成。

阶段 5/6 增加的 `agent_feedback` 是 Plan 与 Agent Harness 的反馈层：每个绑定 Step 的 Run 终态都会记录验证状态、得分、失败分类和证据质量；验证通过时还会写过程型 Memory。这样后续调度、专家评分和人工复盘可以基于结构化事实，而不是翻聊天记录。

## 持久化协作工作层

Collaboration Runtime 位于 Plan 与 Run 之外，解决“工作项跨多次执行持续存在”的问题。`collaboration_tasks` 保存标题、说明、状态、优先级、Agent/AgentTeam 负责人、可选完成条件、父任务、阶段和可选 Plan 引用；`collaboration_task_runs` 把一次 Trigger Run 或 delegated child Run 关联回任务。每个根任务派生稳定的 task workspace owner；所有 Trigger Leader Run、默认阶段 Run 和委派后代在创建时继承该 owner，因此阶段 Barrier 即使通过新 Session/new Run 唤醒 Leader，仍读取同一目录。显式逻辑 `workspace_ref` 保留为有意隔离边界，不自动合并；若模型误传包含当前 collaboration workspace owner 的文件系统路径，则规范化为继承，避免元数据声称共享而子 Run 实际挂载空目录。Run 终态仅表示一次执行结束；阶段进入 `IN_REVIEW` 前还必须存在归属于该 Run 的写文件证据、Artifact 或阶段评论，空模型终态与只读检查不能推进 Barrier，最终只有人工 `ACCEPT` 可以进入 `DONE`。

评论、回复、子专家终态和 Stage Barrier 都可能要求唤醒同一负责人。调度前按任务树和最终 Agent 身份检查活跃 Run：目标已经处于非终态时只保留持久评论/阶段状态，由现有父 Run 消费子结果，不并发创建第二个 Leader 或专家 Run；父 Run 在子 Run 终态后原地恢复并继续推进，提示词明确禁止在未派发下一阶段或未发布结论时空转结束。若 Leader Run 仍提前终态且未发布结论，平台对已完成但缺失 `STAGE_BARRIER` Trigger 的 Barrier 补发一次幂等唤醒，仍无进展才置 `BLOCKED`。没有活跃目标时才创建幂等 Trigger。根 Team Leader 的 `conclusion=true` 还携带当前 Run id 校验，只有其他阶段、委派和并行 Run 全部终态后才允许持久化，防止“最终验收”评论早于审查或测试交付。历史重复 Run 继续保留用于审计，不在 Console 中伪装折叠。

增强后的 `agent_teams` 保存团队指令、成员角色说明、能力标签、路由策略、完成策略、回退 Agent 和最大并发。`CollaborationRoutingService` 以团队成员范围、角色描述、任务词、复杂度和风险生成结构化 Route Preview；复杂度和风险是文本启发式推断，Preview 只返回 Leader、候选、原因与预计并发，不写 Run。Trigger 真正执行时把同一结果写入 `collaboration_route_decisions`，并将有效并发写入根 `run_collaboration_policies`。Worker 领取排队 Run 时以递归委派树统计活动子 Run，根 Leader 不占小队配额，活动子 Run 不超过该值；`budget_policies.max_concurrent_runs` 仍是项目级外层总上限。旧策略的兼容值为 `0`，表示不额外施加团队限流。随后创建普通 Session/Run，并继续复用 Agent Profile、模型方案、审批、预算、Sandbox 和恢复路径。长期协作任务没有独立的首页临时模型选择：最终负责人 Agent/Team Leader 的模型方案优先，未绑定时使用项目默认，项目无默认方案时回退服务端默认；委派子专家有自己的模型方案时覆盖父 Run，否则继承父模型。Task-Run 查询同时返回 Agent、模型方案引用与最近一次 `model_usage.model_name`，既能解释使用服务端默认模型的历史 Run，也让 Console 只在展示边界映射业务名称；持久化关联仍使用稳定 ID。

协作事件使用统一持久化 Trigger：`MANUAL`、`HUMAN_ACTION`、`MENTION`、`REPLY`、`RUN_EVENT` 和 `STAGE_BARRIER` 共用全局 idempotency key。评论、结论、回复和 Mention 分别写入 `collaboration_comments`、`collaboration_mentions` 与 `collaboration_activities`；用户评论默认唤醒任务负责人，回复 Agent 评论路由回原 Agent，成员评论或终态事件唤醒团队 Leader。人工在所有任务节点都可通过评论追加上下文；没有活跃 Run 时还可执行 `START/CONTINUE/RESUME/BLOCK/REQUEST_REWORK/ACCEPT/CANCEL/REOPEN`，避免任意状态下拉框绕过状态机。服务恢复或 SSE 重放只读取已有 Trigger/Run，不会再次派发。目标已有活跃 Run 时，新评论/提及不创建并发 Run，而是注入该活跃 Run 的会话，让运行中的专家在下一轮读到并回应；返工/评论理由进入新 Run 指令，并要求 Leader 原样写入其派发的阶段子任务。评论/提及触发的新 Run 会把处于 IN_REVIEW（待人工验收）的任务先置回 IN_PROGRESS，返工 Run 终态且重新具备交付证据后再回到 IN_REVIEW，状态不会停留在“待验收”却仍有执行在跑。根任务详情按时间顺序聚合整棵任务树（根 + 各阶段子任务）的评论与活动：子 Agent 的最终回复、并行交付、阶段屏障与人工评论都进入同一「评论与决策」/「协作动态」，前端按 taskId 还原“阶段 N · 负责人”上下文；返工/阻塞原因作为人工评论落库并进入复唤醒摘要。阶段交付证据门禁只认真实交付产物：`tool_result`（只读大结果外置）不视为证据，只读后直接结束的阶段会 BLOCKED 而非冒充交付；Leader Run 失败但已有已交付阶段时，根任务回到 IN_REVIEW 等待人工重新验收。输出语言默认中文，仅当用户原始任务为纯英文时才用英文（语言指令按用户真实意图生成并以 system 消息注入，避免协作信封的英文脚手架触发英文）。

子任务以 `parent_id + stage` 组成阶段边界。`collaboration_stage_barriers` 在创建阶段子任务时登记，同阶段全部子任务进入 `IN_REVIEW/DONE/CANCELED` 后原子变为 `COMPLETED`，并用固定幂等键触发父负责人；这里的完成表示执行阶段已交付，不替代每个任务的最终人工验收。Agent 通过 `get_collaboration_task`、`post_task_comment`、`update_collaboration_task` 和 `create_collaboration_subtask` 操作同一工作层；阶段创建会立即返回子 Run 绑定关系而非孤立的 TODO 卡片，阶段 Run 成功结束且通过交付证据门禁后由平台将子任务提交为 `IN_REVIEW`。状态工具只允许 Agent 报告 `IN_PROGRESS/BLOCKED`，不能直接写 `IN_REVIEW/DONE/CANCELED`；单 Agent 任务只接受被分配 Agent、Team 任务只接受 Team Leader 的状态更新，成员进展通过评论回报，并继续受 Profile 白名单、Effect 和 Approval 约束。

阶段派发还按“父任务 + stage + assignee type/id”执行确定性去重：已有活跃任务时复用现有执行，已有交付时读取证据，同一组合连续两个 `BLOCKED` 尝试后停止自动复制并要求人工干预。该约束不阻止同一阶段派给不同负责人的合法并行任务。

Console 不把所有信息塞进聊天看板，而是提供 Master-Detail 工作区：普通对话、专家协作和协作任务的唯一三段切换控件位于主 Header，在首页、会话和任务过程始终可见；同一位置还轮询当前项目的待审批汇总，专家和子专家的持久化 Approval 可直接允许或拒绝，而不必先进入其会话详情。取消 Run 时系统同时关闭该 Run 的未决 Approval，初始化也清理已终态 Run 遗留审批，因此汇总只显示可实际处理的请求。左侧历史按 `projectKey` 和既有 Session 分组统一组织三类记录，普通/专家 Session 继续支持移动分组、删除和跳转，长期 CollaborationTask 通过独立历史查询折叠全部 Task-Run Session，仅在无执行历史时允许物理删除。已有执行历史的任务使用 `CANCEL` 保留审计；该动作在活跃 Run 期间仍可见并可执行，先取消关联 Run 树、模型请求与 Sandbox 执行，再落任务终态。创建区只把标题与 Agent/AgentTeam 负责人作为主要字段，说明和完成条件放入可选高级设置；任务层以状态徽标和受状态机约束的人工动作维护定义、子任务与 Route Preview；协作层把任务建立、执行派发、专家协作、人工验收渲染为阶段轨道，并以指标、评论与决策、中文语义动态解释谁在何时派发、回报或完成执行。任务详情使用同一轮三秒轮询同步 Comment、Activity、Task-Run 与任务状态，输入控件聚焦时只读取而不替换 DOM，避免丢失人工草稿；执行层列出 Task-Run 关联并跳转到真实会话。Agent、Team、模型、工具、Skill 和 Memory 的持久化 ID 只用于关联，常规 UI 通过已加载目录映射名称，失效引用显示稳定占位，不回退展示原 ID。现有一句话专家协作继续作为快速入口，两种模式共用 AgentTeam 和普通 Run 基础设施。

更完整的链路、字段、状态机、企业差距和面试表达见 [PaiCLI Platform Lite 技术架构与面试指南](../PaiCLI%20Platform%20Lite%20技术架构与面试指南.md) 的“持久化 Multi-Agent Harness”“Plan 与类型化 Graph Runtime”“Step 调度、租约、资源冲突、隔离与 Validation Gate”章节。

### 协作任务树、验收与交付补充

- 阶段 Run 完成必须通过 `CollaborationService.persistStatus` 收敛，而不能直接写 Store 状态；该路径负责求值 Barrier，并以 `STAGE_BARRIER` 的幂等 Trigger 新建后续 Leader Run。服务就绪时会重新求值所有 `WAITING` Barrier，并扫描已完成但没有对应固定幂等键 Trigger 的 Barrier，补偿旧生命周期遗漏的唤醒；Leader Run 提前终态且未发布结论时，`onRunTerminal` 也会先执行同样的补唤醒补偿，而不是直接 `BLOCKED`。
- Team 根任务提交 `IN_REVIEW` 还需满足两个证据门槛：存在至少一个 `IN_REVIEW` 或 `DONE` 的阶段交付，且被分配的 Leader 在最后一个阶段交付之后发布了结论评论。这样“Leader 已派发”或“子专家已结束”不会被误显示为完整交付。Leader Run 无结论提前结束时，平台先补发一次阶段屏障唤醒；无法唤醒时才保持 `BLOCKED`。
- 任务树只有根节点是用户可操作的长期工作项；阶段节点只表达根任务内部的执行分解，不能进入根任务列表或统一历史。
- `IN_REVIEW` 的含义是执行树已经停止，不是某个仍在运行的 Agent 的主观判断。Agent 状态工具只用于进度和阻塞；平台在阶段 Run 或根任务 Run 树终态时推进交付状态，人工仍是唯一可将根任务 `ACCEPT` 至 `DONE` 的角色。审批拒绝与 Run 取消同样是终态路径，必须收敛到协作状态：拒绝会触发 `onRunTerminal(FAILED)` 使阶段任务转为 `BLOCKED`，整体取消会把仍处活跃态的后代阶段任务一并置为 `CANCELED`。
- 阶段 Run 复用根任务的稳定 workspace owner，而不是某一次 Leader Run 的临时 owner。启动迁移会归并历史任务 Run/委派后代目录并保留冲突前版本；任务详情从统一受控 workspace 列出文件。普通文件沿用认证预览/下载，HTML 入口会额外解析同一 workspace 中的相对脚本、样式与媒体并生成隔离预览，避免单文件 `blob:` URL 丢失同级依赖。
- 删除终态任务只移除协作工作项及其级联协作数据，不删除已经终态的 Run、Session 和 Artifact；正在执行的任务仍必须先取消，避免删掉任务入口后留下活跃执行。

## Memory 与上下文

### 任务级外部 Harness（后续方向）

当前 ContextManager 已负责单 Run 的上下文预算、稳定前缀、压缩摘要、按需工具 Schema 和 Context Manifest；这不等于任务级成本编排。当前阶段子任务已使用直接父子 Run 唤醒同一 Leader，避免为一次直接交接额外新建 Leader Run；后续仍需在普通对话、专家协作和 CollaborationTask 之前增加统一外部 Harness：用任务预算信封准入模型请求，用事件合并和 Leader 单飞避免每条成员事件都产生新 Run，用引用化交接包替代重复完整上下文，并在确定性聚合/验证、模型分层和质量成本评测之间形成闭环。该层只能决定是否创建、延迟、合并或降级 Run，不能绕过既有 ToolCall 先持久化、Approval、Sandbox、恢复和人工验收边界。详见 [外部 Harness Token 成本优化方案](harness-token-optimization.md)。

ContextManager 把缓存稳定性与输入预算作为同一个组装边界处理。基础指令、专家配置、项目规则和 Skill 索引是稳定系统前缀；摘要及当前 Run 之前的历史位于 Run 动态块之前，使新 Run 可以复用已有长会话前缀；运行基准时间、工作区、RAG、Memory 和当前 Run 消息位于动态尾部。同一 Run 始终使用其持久化创建时间，不在每轮写入变化的墙钟时间。输入估算同时计算 Message 与 Tool Definition，RAG 和 Memory 只使用必需上下文之后的剩余预算，必要时保留 XML 关闭标签并写入明确裁剪标记。

每次模型调用前持久化 `context.prepared` Event，形成轻量 Context Manifest：包含上下文/输出/硬输入上限、总估算 Token、Tool Definition Token、各区块 Token、可复用前缀 Token 与 SHA-256、稳定/摘要/历史/当前 Run 消息数、PlanState 是否注入、RAG citation/命中理由、被选 Memory id/选择理由、完整工具名/选择理由、按需激活工具，以及 RAG/Memory 是否纳入、裁剪或丢弃。它不重复保存完整 Prompt，但可以解释缓存命中、上下文淘汰和 Memory 召回行为。

只有委派树根 Run 完成后才创建一次持久化 `memory_extractions` 任务，避免 Leader 和子 Agent 为同一协作结论重复提取；创建时冻结根 Run 的 Message id、sequence、role、content 和 tool call 引用。Worker 只能读取 `source_snapshot_json`，不能读取稍后变化的 Session。每条提取结果把实际证据 Message id、起止 sequence 和摘录写入 `memory_sources`，API 可将 Memory 回跳到具体消息或工具结果。旧任务的空快照保留兼容读取路径。

Worker 提取带类型、层级和置信度的 L1/L2/L3 Memory；单根 Run 总数上限为 3（L1≤1、L2≤2、L3≤1）。流程型协作事件会被过滤，候选必须带有效 evidence message id，且至少由用户陈述或成功工具结果支持；模型给出的分数会按证据质量、重复出现程度和层级稳定性校准并受来源上限约束。同 key 变化和人工编辑先写 `memory_revisions`；高相似候选复用 canonical key，中等相似候选进入 `memory_conflicts`，长期未访问且未置顶的 L1 进入 `STALE`。召回综合词法/语义相关性、置信度、时间衰减、类型配额、置顶、稳定 L3 偏好和历史反馈。`memory_usage_feedback` 保存每个 Run 实际选入上下文的 Memory；Run 终态以及 Plan 验证通过/返工更新 outcome，形成后续排序信号。显式 REST CRUD 仍是人工纠错边界；Console 也提供主动录入人工 L3 的入口。

Memory Wiki 不是第二份知识库，也不迁移或改写旧 Memory。它从同一项目的现有 Memory 派生页面视图：页面标题从内容首句生成，内部 key 只作为稳定链接标识；LLM 在确有依赖时以 `[[canonical-key]]` 写入明确关联，系统再补充同标签关联和反向引用。Console 以 L1/L2/L3 分栏地图渲染这些关系，并限制单层节点数以保持可读性。页面继续复用原有来源、修订、置信度、启停和确认状态，因此人工可以从 Wiki 直接回到可审计的 Memory 记录。

DeepSeek Thinking 的 `reasoning_content`、assistant `tool_calls` 和对应工具结果按同一持久化边界恢复。模型 delta 批量写入 Event，浏览器按动画帧合并显示，避免每 Token 同步写 SQLite 或创建大量 DOM。

项目规则属于受控上下文，不是自治 Memory。系统只从全局、项目和 Run 工作区的数据根目录读取 `AGENTS.md` / `PAI.md`，更具体层覆盖通用层，并受总字符预算约束。

扩展工具使用两段式发现。默认模型请求只带文件/命令/Artifact 和 `tool_search` 等核心 Schema；Knowledge、Skill、Web、MCP 与 Multi-Agent Provider 仍在 Server 目录注册，但只有模型调用 `tool_search` 命中后才在下一轮注入完整 Schema。显式 Agent Profile 工具白名单仍定义能力上限，发现结果不能扩大权限。这样既降低工具 Schema Token，也避免未使用 Provider 的 Schema 变化破坏缓存前缀。

ConversationCompactor 的工作记忆固定为八节：目标与硬约束、计划状态、已验证事实、未验证假设、技术决策、失败尝试、待办与下一步、证据引用。模型摘要必须通过节名、顺序和字符预算校验；否则使用同 Schema 的确定性降级摘要。证据引用包含 message id/sequence/tool call id，使摘要后的续作仍能区分证据和假设。

## 轻量 WorkingPlan（Harness Loop v2 · PR1）

普通 Run 使用一个轻量、Run 内、随 Run 结束归档的工作清单，而不是把每一步都变成正式 PlanStep：

- `run_working_plans` 每 Run 单行（迁移 35），`update_working_plan` 以 upsert + revision 自增落库；主 Agent 用 objective + TODO/IN_PROGRESS/COMPLETED/BLOCKED 条目维护，`evidenceRefs` 可选引用 ToolCall/Artifact。
- `ContextManager` 每轮只注入最新修订（`<working_plan>` 块，位于 Run 动态上下文内并计入预算），不注入全部历史；Worker 重启后从最新 revision 恢复。
- 简单问答不创建计划：工具不在普通路径自动触发，只有模型调用 `update_working_plan` 才落库；该工具加入核心上下文工具，专家 Profile 走 `tool_search` 激活。
- 与 Formal Plan 的分界：Formal Plan 仍保留多步依赖、跨时长、并行、人工节点、失败回流与严格验收；WorkingPlan 不创建 PlanStep、不经过 PlanWorker、无 DAG、无 PlanValidator。

语言一致性：系统提示不再硬编码中文，改为“与用户最近一条消息语言一致”；`ContextManager` 按当前 Run 用户消息的汉字/拉丁字符占比注入显式 `<language>` 指令（中文问中文答、英文问英文答）。协作任务复唤醒的 Leader Run 同样遵守。

完成验证（PR2）：最终答案先经 `RunVerificationService` 校验再完成。写操作无工作区变化、或测试命令失败时判定 `REPAIRABLE`，验证结果写入 `run.verification` Event 并作为 `<verification>` 用户消息注入下一轮重新排队；连续 2 次仍不过才 `FAILED`。普通问答（TEXT_ONLY）行为不变。

失败反思（PR3）：`run_reflections`（迁移 36）持久化结构化失败分类与决策（`TEST_FAILURE`/`TOOL_ERROR`/`DUPLICATE_CALL`/`VERIFICATION_FAILURE`），不保存隐藏思维链；重复相同工具+参数超限时记录反思后停止 Run；`ContextManager` 每轮注入最新 `<reflection>`，Worker 重启可恢复修复流程。

只读工具批次（PR4）：`RunProcessor` 对同一响应中连续、无需审批的只读 ToolCall 在单次领取内并行执行（≤4 并发），按模型原始顺序提交 Tool Message（`commitToolMessage` 不触碰 Run 状态，批处理结束后统一重新排队一次）；写工具与审批工具仍是顺序执行屏障。步骤计数与恢复语义不变。

专家交付协议（PR5）：`DelegationEnvelopeBuilder` 服务端统一构建委派信封（目标/范围/约束/允许文件工具/输入 Artifact/done criteria/工作区模式/父证据引用）；`AgentResultValidator` 校验子结果——无证据 `COMPLETED`、无测试证据的通过声明、无错误的失败都会被标记，`get_agent_result` 返回 `validation`。普通 Expert 默认禁止嵌套委派（既有 `enforceParentDelegationRole` 守卫）。

工作区写隔离（PR6）：`WorkspaceMode`（SHARED_READONLY/SHARED_SERIAL/ISOLATED_WORKTREE）按角色默认映射并写入委派信封；`WorkspaceMergeService` 对并行子交付的变更文件做冲突检测，冲突路径必须先仲裁再合并，未合并变更不能由 Leader 直接宣称为最终交付。

任务摘要与交付清单（PR7）：迁移 37 新增 `collaboration_task_digests`、`collaboration_deliveries`、`collaboration_accepted_snapshots`。`TaskDigestService` 构建 Leader 复唤醒摘要并注入 `<task_digest>`；`DeliveryManifestService` 在阶段交付时记录清单（变更文件/Artifact/测试证据/内容哈希），`ACCEPT` 时生成覆盖根任务与全部后代阶段 DeliveryManifest（含 task_id/run_id）的不可变验收快照；返工聚焦失败阶段。

路由评分与评测（PR8）：`CollaborationRoutingService` 在能力匹配基础上引入历史验证通过率与当前活跃负载综合评分，候选携带 `score`；官方评测集新增“官方·08 Harness Loop”用例；`GET /runs/{runId}/audit` 返回 workingPlan/reflection/verifications，Run 审计页展示 Harness 状态；效率工作台“长期记忆”“持久化审批策略”“Artifact 工作台”列表默认收缩；长期记忆移除关系地图（图谱）视图，保留 Wiki 页面浏览。

专家线程（PR9）：迁移 38 新增 `collaboration_expert_threads`（root_task_id + agent_profile_id + thread_role 唯一，`latest_run_id` 带 `ON DELETE SET NULL` 外键）与 `collaboration_expert_thread_runs`（thread_id + run_id + ordinal）。`ExpertThreadService` 提供幂等 `getOrCreate`/`attachRun`/`findByRun`/`refreshDigest`；`attachRun` 在单个 `BEGIN IMMEDIATE` 事务内完成“Run 未被其他线程绑定校验 + ordinal 分配 + INSERT + latest_run_id 更新”，并发安全、重复挂载幂等、跨线程挂载抛错。`ExpertThreadDigestBuilder` 只消费本专家可审计证据：阶段按 `assigneeId` 过滤，changed_files 只取该 Run 自己的 DeliveryManifest 记录，artifact/test 引用按 Run 隔离，latest human instruction 取根任务最近人工评论。`CollaborationService.trigger` 与 `createAndDispatchSubtask` 都在 Run 输入注入 `<expert_thread_resume>`（阶段再派先 getOrCreate 线程再构建输入），`onRunTerminal` 终态后刷新 Digest；后续 Run 不加载旧 Run 历史。Active Run 竞态保护：`PreparedContext.maxMessageSequence` 记录上下文构建时的 Session 最大 active sequence，`RunProcessor` 通过 `commitFinalAssistantAndComplete(..., expectedSequence)` 在单个事务内“比对 + 置 COMPLETED”，有新输入则整体回滚，再由 `commitIntermediateAssistantAndRequeue` 单事务持久化 `run.new_input_during_model`（含 `staleAssistantArchived:true`）并把旧模型回答保存为 **archived** assistant 消息——审计保留；`maxMessageSequence` 只统计 `archived=0`，避免归档消息被误判为新输入。archived 语义统一为“保留事实、任何 Agent 语义链路不再消费”：ContextManager 用 `activeMessages`，ExpertThread Digest 用新增的 `activeMessagesForRun`，`get_agent_result` 的最终回答/摘要用 `activeMessages`，Memory 提取 source snapshot 查询 `archived=0`。评论投递用 `appendUserMessageIfRunActive` 事务内重确认，Run 恰好终态时回退创建新 Trigger/Run。永久删除终态 Run 时清理线程绑定、重选 `latest_run_id` 并置空受影响摘要。线程角色：仅 TEAM leaderAgentProfileId 使用 `LEADER`（TaskDigest 连续性），单 Agent 被指派 Agent 与团队专家均使用 `EXPERT` 线程并获得 Resume。`AgentResultValidator.validate(child, result, doneCriteria)` 从 `delegation.envelopeJson` 读取派遣时持久化的 done_criteria（`spawn_agent` 与协作阶段派遣 `createAndDispatchSubtask` 都通过 `DelegationEnvelopeBuilder` 写入），`get_agent_result` 返回逐 criterion 的确定性证据状态（显式且非空的 `criterion_evidence` → EVIDENCED，否则 UNVERIFIED；空字符串/空 List/空 Map 不算证据，不做关键词匹配，UNVERIFIED 不改 `valid`）；PlanStep 的 `doneCriteriaJson` 按 JSON array 解析为 `List<String>` 再作为 envelope fallback。

## 完成合同、执行证据与 Deferred 工具调用（Harness Loop v2 · PR10）

Harness 从“行为驱动”升级为“任务要求驱动”：模型负责策略与代码生成，系统环境提供真实执行事实，Harness 用机器可验证的 **合同 vs 证据** 决定是否完成。

- **结构化工具证据（迁移 39）**：`tool_calls.result_metadata_json` 持久化 ToolResult.metadata；`write_file`（Local 与 Docker/Sandbox 代理）统一返回 `path/changed/beforeSha256/afterSha256/bytesWritten`，`execute_command` 返回 `exitCode/timedOut/shell/cwd/durationMs`。证据以持久化 metadata 为准，不解析 stdout 文本。
- **测试族分类**：`TestCommandClassifier`/`TestFamily` 高精度识别测试命令（`mvn test`、`npm test`、`pytest`、`go test`、`cargo test`、`node --test`、Shell test 脚本等）；Maven skip、Gradle exclude/dry-run、pytest collect-only、cargo no-run、Jest/Vitest/.NET 列表模式不算测试。Node 包管理器只在 `npm/pnpm/yarn exec|dlx jest` 等真实 executable invocation 下生成 Jest 证据，安装依赖不算测试。不同 TestFamily 独立维护最新状态、互不覆盖。
- **统一证据收集**：`RunEvidenceCollector` 通过纯 `RunEvidenceDecoder` 产出 `RunEvidence`（filesChanged/commandsExecuted/tests/artifacts/workspaceMutations/lastMutationOrdinal）；相同 Decoder 也供 SQLite child terminal envelope 使用，因此 `RunVerificationService`、`AgentResultService`、持久化 delegation result、`DeliveryManifestService`、`WorkspaceMergeService` 不会各自解析 metadata。`BuildCommandClassifier` 以三态区分生成型命令、明确直接产品写入和未知/不可信命令；只有 `POTENTIAL_PRODUCT_MUTATION` 加明确 fingerprint 变化才能推进 `lastMutationOrdinal`。
- **完成合同**：`run_completion_contracts`（迁移 39）保存 `CompletionMode`（TEXT_ONLY/MUTATION_REQUIRED/TEST_REQUIRED/MUTATION_AND_TEST）、requires_workspace_change、requires_tests、required_test_families、writeScope、doneCriteria。来源按可靠性排序：DelegationEnvelope（resource_write_set/done_criteria）→ Formal PlanStep（resource_write_set/done_criteria）→ WorkingPlan `completion` 结构化声明 → Root 保守分类器（`CompletionRequirementClassifier`，问答/解释默认 TEXT_ONLY）。合同**只可加强**（false→true 允许，true→false 禁止），模型不能静默降低验收标准；WorkingPlan completion 是“模型对任务要求的结构化声明”，不是完成证据。
- **合同驱动验证**：`RunVerificationService.verify(run, finalAnswer, contract, evidence)` 为纯逻辑；required test families 必须在最后一次真实 mutation 之后通过，否则 REPAIRABLE（注入 `<verification>` 重试，2 次后 FAILED）。
- **AgentResult 证据闭环**：`AgentResultService` 自动归集 child 的 `files_changed/workspace_mutations/commands_executed/tests/artifacts/completion_contract/evidence`；每条 test 含 `ordinal/after_last_mutation`，`AgentResultValidator` 按 family 选择最后 mutation 后 ordinal 最大的结果，后续失败会覆盖早先通过；contract 要求 workspace change/tests 而无真实证据时 invalid。`DeliveryManifestService.recordStageDelivery(taskId, stage, runId)`、实际阶段交付门禁与 `WorkspaceMergeService.ChildChanges.of` 都复用同一证据。
- **Deferred get_agent_result**：`ToolCallStatus.WAITING_EXTERNAL`；`tool_calls.wait_kind/wait_ref/waiting_since` 记录等待外部条件（`CHILD_RUN`）。child 未终态时 `get_agent_result` 返回 deferred metadata，RunProcessor 把 ToolCall 标记为 WAITING_EXTERNAL、Parent 进入 WAITING_AGENT（不追加最终 tool 消息、不占用模型轮次）；child 终态由 `DeferredAgentResultService` 在单个幂等事务内完成原始 ToolCall + 追加 ToolResult + 重排队 Parent。Lost Wakeup 双边保护（设置等待后立即复查 child 终态；resolve 用 `WHERE status=WAITING_EXTERNAL` 保证只有一个 resolver 成功）；`@PostConstruct` 启动恢复补齐 Server 重启期间的等待，重复终态回调幂等。
- **事件/审计**：`tool.deferred`、`tool.deferred.resolved`、`agent.result.validated`、`run.evidence.collected`、`run.completion_contract.created/strengthened` 等事件只用于 SSE/诊断/重放展示，业务事实仍保存在业务表。
## Prompt Cache 命中率优化

优化前观测样本为 `8,714,118` 输入 Token、`544,640` 缓存命中 Token，累计命中率约 `6.25%`。旧组装顺序在历史消息之前注入每轮变化的 `Instant.now()`、运行工作区、RAG 和 Memory；Prompt Cache 按共同前缀复用，因此任一早期动态值变化都会让其后的长会话失去复用机会。

新的上下文顺序固定为：

```text
base/safety/agent Prompt
→ Agent Profile / Project Rules / Skill Index
→ Conversation Summary
→ Prior Runs' Conversation
→ Run Started Time / Workspace / RAG / Memory
→ Current Run Conversation
```

该顺序有三个缓存边界：

1. **静态能力边界**：基础指令、专家、项目规则和 Skill 索引稳定排序；只有配置或受控文件真实变化才失效。
2. **会话复用边界**：已完成历史位于当前 Run 动态块之前；创建下一 Run 时，新查询引起的 RAG/Memory 变化不会破坏已有历史前缀。
3. **Run 内复用边界**：动态块使用持久化 Run 创建时间和稳定工作区；同一 Run 后续 ReAct 轮次只在尾部追加 assistant/tool 消息。

输入预算同时包含 Tool Definition Token。ContextManager 先计算稳定指令、摘要、历史、Runtime、PlanState、当前 Run 和工具 Schema 的必需成本，再把剩余预算分配给 RAG 与 Memory；裁剪不会突破硬输入上限。默认只常驻核心 Tool Schema，扩展 Schema 通过 `tool_search` 按需加载。`context.prepared` Event 保存轻量 Context Manifest，包括输入/输出上限、估算输入、各区块/工具 Token、可复用前缀 Token/SHA-256、检索引用、Memory 选择、工具激活及裁剪状态，支持把供应商返回的 `cachedInputTokens` 与实际上下文结构关联分析。

验证缓存效果时必须使用新版本部署后的增量 `cachedInputTokens / inputTokens`，不能直接用历史累计比率。模型切换、工具白名单变化、项目规则变化、摘要重写和供应商最小可缓存前缀仍会造成合理失效；供应商不支持或未开启 Prompt Cache 时，结构优化不会凭空产生缓存命中。

## SQLite 与文件一致性

Lite 版是单机单租户，SQLite WAL 提供并发读取和短事务写入。WAL 只在数据库初始化时设置一次，普通连接不再反复切换日志模式；每个连接设置 30 秒 `busy_timeout`，降低多 Trial/多 Worker 短时争用直接产生 `SQLITE_BUSY` 的概率。`schema_migrations` 当前到版本 38：1–27 覆盖基础 Runtime、Plan/Graph、执行小队、Delegation Graph、Memory/RAG 与 Context Harness；28–33 覆盖增强 AgentTeam、CollaborationTask、事件 Trigger、阶段屏障、并发上限、审批清理和历史状态修正；34 增加根任务级协作工作区归并与交付证据门禁，35 增加轻量 WorkingPlan，36 增加 Run 反思，37 增加任务摘要/交付清单/验收快照，38 增加 ExpertThread 专家线程与线程-Run 绑定。旧目录先完成可恢复文件归并，再事务更新关联 Run 的 owner。

`ApplicationReadyEvent` 会扫描等待中的阶段屏障并补发缺失的 Leader Trigger。该过程是持久化恢复的尽力对账，不是 Server 可用性的启动门禁：屏障列表读取、单项求值或唤醒遇到 `SQLITE_BUSY`/历史脏数据时记录带 task/stage 的警告并继续其他项，异常不再逃逸到 Spring Boot 主线程。未成功处理的屏障保持原状态，后续阶段终态事件或下次启动仍可幂等重试。

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

SSE 已正常结束但没有 ToolCall 和最终正文时，不把 HTTP 200 等同于有效模型终态。客户端将该次 HTTP 尝试记为 `RETRY`，在原请求尾部追加要求停止重复分析、立即调用工具或给出最终答案的纠偏消息，并把恢复请求切换为禁用 thinking；Kimi 不能关闭思考时改用 `reasoning_effort=low`。这避免兼容模型在每次重试中重新耗尽完整 reasoning 窗口。恢复请求若产生没有 `reasoning_content` 的 ToolCall，后续模型轮次继续使用无思考模式，不能切回 enabled 后把不完整的混合模式历史发送给 DeepSeek。若流后的 ToolCall 参数被截断或无法解析为 JSON，恢复消息改为要求保持参数小而有效，并按单文件、多个轮次拆分大内容写入。两类恢复都继续受 `maxAttempts`、退避、取消和熔断边界约束；若重试后仍无有效终态，RunProcessor 门禁仍将 Run 置为 `FAILED`。这样既能恢复 reasoning 耗尽输出窗口或大参数截断的兼容模型，也不会把空结果提交为 `COMPLETED` 或绕过协作阶段的持久交付证据检查。

Run 状态更新与对应 Event 在同一事务提交；终态不可回退。模型一轮产生的全部 ToolCall 按 Provider 顺序原子落库后才允许执行。工具按 `READ_ONLY`、`IDEMPOTENT_WRITE`、`NON_IDEMPOTENT_WRITE` 标记 Effect：进程恢复只自动重放安全 Effect，不确定的非幂等调用进入 `UNKNOWN` 并终止 Run，等待人工对账。工具结果、Tool Message 和下一轮排队同样原子提交。通知先写持久化 Outbox，由受限 Dispatcher 重试投递。

## 多模态与文档输入

Console 每轮最多暂存 4 张 PNG/JPEG/GIF 和 4 个文档。Server 校验真实图片字节、尺寸与大小，必要时压缩；创建 Run 时把附件 id 原子绑定到 user Message，不能被另一 Run 重复绑定。只有当前 Run 的图片作为 OpenAI 兼容格式的 `image_url` 注入，历史图片不重复发送。模型接口明确拒绝图片内容时，Client 只降级重试一次纯文本请求，并注入中文能力提示；视觉模型仍走原始多模态请求。

文本、Markdown、PDF、Office、CSV、HTML、JSON、XML、RTF、EPUB 和 OpenDocument 由 Tika 提取并写入当前项目知识库，绑定当前 Run 后作为优先 RAG 来源。普通文档不整体塞入模型；扫描 PDF 在 OCR 不可用时保留为视觉 PDF，只把受限页图像注入当前 Run，也不会误报为已索引。

## Sandbox 边界

`LocalSandboxDriver` 仅用于开发，不是安全沙箱。Docker 模式按活跃 Run 创建受限容器，工作区留在宿主机，容器使用无外部路由的内部网络且不发布端口；Server 通过 `docker exec` 调用容器 loopback 的带令牌 HTTP Agent。

Sandbox Agent 缺少每容器随机令牌时拒绝启动。命令只允许固定映射到 `/bin/sh -lc`、`/bin/bash -lc` 或 `/usr/bin/pwsh -NoLogo -NoProfile -NonInteractive -Command`，模型不能传解释器路径。Run 与 Agent Profile 持久化默认 Shell；RunProcessor 在同轮 ToolCall 原子落库前补齐缺省 Shell，因此审批参数、幂等键和恢复执行一致。

命令工作目录必须位于 workspace 内。进程环境从空集合开始，只加入固定 PATH/HOME/LANG、PowerShell 遥测关闭变量和通过名称/数量/长度/敏感词检查的显式 `env`。stdout/stderr 使用独立限额缓冲区持续排空，结果返回退出码、耗时、超时、字节数和截断状态；超过模型内联预算的结果写入 Artifact。超时会终止进程及后代；Run 取消会销毁独占容器，从而中断活跃命令。`execute_command` 在 ToolCall 原子持久化后按已落库的 `command` 分类：读取、构建和测试命令直接进入 Sandbox，删除/清空、提权/权限修改、进程/系统控制、破坏性 Git/数据库操作、下载安装、远程执行、发布和部署等风险命令在任何容器调用前创建持久化 Approval。批准后继续执行同一个 ToolCall 和同一组参数，不要求模型重新生成动作；无法解析或缺少命令时按风险命令处理。服务初始化会重新分类历史未决命令，安全命令原 Approval 自动批准并重新排队，危险命令保持等待。全部 MCP 工具和 Provider 自行声明的危险工具仍强制审批。
## Completion Contract 与证据闭环

`get_agent_result` 虽然读取持久化结果，但可能把父 Run 停在 `WAITING_AGENT`，因此不进入同轮只读工具并行前缀；它先完成原子停放，子 Run 终态再幂等唤醒父 Run。`RunEvidenceCollector` 与 SQLite terminal envelope 共用 `RunEvidenceDecoder`，将高置信度测试 invocation 与 workspace mutation 分开：`BuildCommandClassifier` 返回 `GENERATED_ONLY / POTENTIAL_PRODUCT_MUTATION / UNTRUSTED_OR_UNKNOWN`，只有明确直接产品写入且 fingerprint 变化才推进最后业务 mutation；生成物、未知命令和不安全复合命令都不能凭整体 fingerprint 过关。含 `||`、`;`、管道，或测试后仍有尾部命令的复合命令不计入 TestEvidence。

Run 在开始处理前建立持久化 Completion Contract，来源按 Delegation Envelope、绑定 `plan_steps.run_id` 的 Formal PlanStep、WorkingPlan 声明和根任务分类确定，后续 WorkingPlan 只能 strengthen。最终验证统一消费 `RunEvidenceCollector`：文件变更必须有 pre/post 哈希，`execute_command` 可用 workspace fingerprint 证明副作用，测试必须来自高置信度的真实测试 invocation。

Deferred `get_agent_result` 的 ToolCall `WAITING_EXTERNAL` 与父 Run `WAITING_AGENT` 在同一个 SQLite 事务中提交；子 Run 终态解析保持幂等。业务交付和 AgentResult 只消费非 `tool_result` Artifact，SQLite 的 child terminal result 也使用同一过滤；预算停止也必须先通过 CompletionVerifier，否则 Run 失败并保留可审计的预算事件。

`RunEvidence.workspaceMutations` 是文件路径未知时的统一变更证据：`AgentResultService`、SQLite 终态 delegation envelope 与 DeliveryManifest 都输出或消费 `workspace_mutations`，`AgentResultValidator`、`RunVerificationService` 和协作阶段交付门禁使用相同语义；不得把未知命令或命令修改伪造成 `files_changed`，不得用共享 workspace 的时间戳推断 Run 归属。测试分类对换行、单独 `&`、Maven skip、Gradle exclude/dry-run、pytest collect-only、cargo `--no-run`、依赖安装、Jest/Vitest/.NET 列表模式、孤立 Maven selector（如 `-Dtest=Foo`）以及 Gradle `testClasses`/`checkstyle*` 保守拒绝。Parent Validator 与 Child Verifier 都按 family 使用最后 mutation 后的最新测试状态。
