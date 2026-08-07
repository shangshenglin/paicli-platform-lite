# 交付阶段
## 2026-08-08 PRD Analysis Agent（阶段 1–10 全量）

- [x] 迁移 38：PRD 分析 10 张表（tasks/sources/source_chunks/nodes/node_dependencies/findings/evidence/questions/checks/runs）。
- [x] `PrdAnalysisStore` 粗粒度幂等提交（submitMap / submitNodeAnalysis / submitReconciliation），finding/evidence ID 全部 Server 生成。
- [x] 来源摄入复用 DocumentTextExtractor + StructuredDocumentChunker，不做第二套 OCR/Tika 链。
- [x] 11 个 `prd_*` 工具 + 后端 run→task/node 绑定权限校验。
- [x] 3 个系统 Profile（mapper/node-analyst/reconciler）+ 3 个内置 Skill（prd-map/prd-node-analyze/prd-reconcile），required skill 全文注入 system 前缀。
- [x] Java 确定性节点调度（依赖就绪 + maxParallelism）、barrier 只创建一次 Reconciler Run。
- [x] Java 8 项确定性校验（证据/引用/重复实体/字段映射/规则冲突/状态转换/阻塞问题/节点完成），FIXABLE 回流与 AMBIGUOUS→WAITING_USER。
- [x] 用户澄清 API（answers）与 WAITING_USER 恢复。
- [x] 5 类产物（analysis.md / domain_model.json / traceability_matrix.json / validation_report.json / questions.json）。
- [x] Plan Handoff 复用 PlanService 生成实施计划。
- [x] Console 独立「PRD 分析」入口（导航/列表/创建/详情/问题回答/Artifacts/生成 Plan）。
- [x] 确定性评测 fixture（simple-order-prd / simple-order-contract）与 22 项 PRD 测试。

## 2026-08-06 Harness Loop v2 · PR1：轻量 WorkingPlan 与交互修复

- [x] 迁移 35：`run_working_plans`（每 Run 单行、revision 自增）；`update_working_plan` 工具（objective + TODO/IN_PROGRESS/COMPLETED/BLOCKED 条目 + evidenceRefs）。
- [x] `ContextManager` 每轮只注入最新 WorkingPlan；简单问答不自动产生计划；Worker 重启从最新 revision 恢复。
- [x] 语言一致性：系统提示按用户语言作答；按当前 Run 用户消息中/英占比注入 `<language>` 指令（中文问中文答、英文问英文答）。
- [x] Console 整页刷新恢复上次页面（最后打开的 Session / 选中的协作任务与视图），不再固定回到中性首页。
- [x] Harness Loop v2 PR2：CompletionVerifier（写操作无工作区变化或测试失败时进入 repair，连续 2 次不过才 FAILED；普通问答行为不变）。
- [x] Harness Loop v2 PR3：失败反思（迁移 36 `run_reflections`，结构化失败分类/决策/证据引用，不含隐藏思维链；测试/工具失败与重复工具调用自动记录，Worker 重启可恢复）。
- [x] Harness Loop v2 PR4：只读工具批次并行（同轮连续只读 ToolCall 单次领取并行执行 ≤4，按模型顺序写 Message；写/审批工具保持顺序屏障）。
- [x] Harness Loop v2 PR5：结构化专家交付（DelegationEnvelopeBuilder + AgentResultValidator，无证据 COMPLETED/虚假测试通过被拒绝；普通 Expert 默认禁止嵌套委派）。
- [x] Harness Loop v2 PR6：工作区写隔离与冲突检测（WorkspaceMode 按角色默认映射；WorkspaceMergeService 检测并行写冲突）。
- [x] Harness Loop v2 PR7：任务摘要与交付清单（迁移 37：task_digests / deliveries / accepted_snapshots；Leader 复唤醒注入摘要；阶段交付记录清单；ACCEPT 生成不可变快照；返工聚焦失败阶段）。
- [x] Harness Loop v2 PR8：路由综合评分（能力+历史通过率+负载）、官方评测集“官方·08 Harness Loop”、Run 审计页 Harness 状态面板、效率工作台“长期记忆”“持久化审批策略”“Artifact 工作台”列表默认收缩，长期记忆移除关系地图视图。

## 2026-08-04 协作阶段收敛修复

- [x] 阶段子 Run 的终态统一通过阶段屏障唤醒 Leader；启动恢复会补偿旧的 `WAITING` Barrier 及已完成但未触发 Leader 的 Barrier。
- [x] Team 根任务进入人工验收前要求阶段交付证据与 Leader 的后置结论评论，避免只完成首个子阶段便提前交付。
- [x] 根协作任务拥有跨 Session/Run 的稳定工作区；Leader 唤醒、阶段 Run 与默认委派后代复用该目录，历史分散目录在启动时归并，显式 `workspace_ref` 继续提供隔离边界。
- [x] 空模型响应不能完成 Run；阶段 Run 缺少本 Run 写文件、Artifact 或评论证据时阻塞阶段和父任务，不再完成 Barrier 或循环派发。

## 阶段 1：持久化 Agent Runtime

- [x] 多模块 Maven 工程与 SQLite WAL 存储
- [x] Session、Run、Message、Event、ToolCall 数据模型
- [x] 可恢复 Worker、工具幂等边界、REST API 与可重放 SSE
- [x] 本地执行器、Sandbox Agent 骨架和离线演示模型

## 阶段 2：Docker 执行边界

- [x] 每 Run 一个容器、随机控制令牌、无宿主端口暴露
- [x] 工作区挂载、CPU/内存/PID/超时限制和内部网络
- [x] 写文件与命令审批、审批恢复、JSONL 审计
- [x] 启动清理孤儿容器、Fake Docker 与真实 Docker 验收

## 阶段 3：真实模型与上下文工程

- [x] OpenAI 兼容的流式模型适配
- [x] GLM、DeepSeek 配置与分层 Prompt
- [x] 大工具结果截断及本地 Artifact Store
- [x] 安全的对话摘要、Token 用量和预算

## 阶段 4：人工 Memory 与产品 API

- [x] Memory 显式 CRUD 和项目级召回
- [x] API Key、OpenAPI、备份恢复命令
- [x] 初版运行时间线（后由阶段 6 聊天 Console 替代）

## 阶段 5：DeepSeek V4 与真实 Docker 验收

- [x] DeepSeek V4 Flash/Pro、思考开关和推理等级
- [x] `reasoning_content` 流式解析与跨工具调用持久化恢复
- [x] Prompt Cache 用量解析、旧 SQLite 结构迁移和真实容器验收

## 阶段 6：Runtime 正确性与聊天 Console

- [x] 同轮全部工具调用原子持久化并按 Provider 顺序执行
- [x] 每工具审批、模型 HTTP/SSE 主动取消和受控项目规则注入
- [x] 编号迁移、响应式聊天 Console、批量 delta 持久化
- [x] 每 Run 思考控制、持久化会话分组和安全删除

## 阶段 7：受管能力 Provider

- [x] 统一 Server Tool Provider SPI，继续复用持久化 ToolCall 边界
- [x] 项目/全局 Skill、按需加载资源和受校验的 HTTPS Git 导入
- [x] 多格式知识文档、Tika 提取、混合向量索引和 `search_knowledge`
- [x] 历史会话 BM25 检索、可选联网、远程 HTTP MCP
- [x] 持久化 Multi-Agent 子 Run、深度限制和公平队列
- [x] 多模态图片/文档附件、扫描 PDF OCR 与视觉降级
- [x] 工具业务失败 observation、SSE 饥饿与终态收敛修复

## 阶段 8：单机企业细节完善

- [x] 结构化分块、BM25/Embedding 双路召回、RRF 融合与去重
- [x] 主模型结构化摘要和确定性失败降级
- [x] L0 到 L1/L2/L3 自动 Memory、置信度、修订与混合召回
- [x] 模型重试/限流/预算、MCP 熔断和 Multi-Agent 配额
- [x] 能力状态、Micrometer 指标、存储健康检查和迁移 8–9

## 阶段 9：运维加固与交付门禁

- [x] Sandbox/Docker 输出限额和超时进程树终止
- [x] Sandbox 强制令牌、常量时间认证和生产 API Key 启动门禁
- [x] Console 安全响应头、SQLite 连接策略、WAL 维护和保留策略
- [x] 原子文件替换、受校验备份恢复和孤儿文件清理
- [x] Runtime 指标、Maven `-Xlint:all`、CI、Dependabot 与 SBOM

## 阶段 10：P0 业务效率工作台

- [x] 终态 Run 原地重试和带历史上下文的分支会话
- [x] “仅本次/本对话/本项目”审批；持久化策略只复用相同工具和完全相同参数
- [x] 对话、消息、Memory、知识和 Artifact 的项目级统一检索
- [x] Memory 来源/置信度展示、置顶、启停、人工确认、下拉选择合并、表单修订及全部历史版本查看与恢复
- [x] 知识文档集合、标签、版本、索引状态、重建索引、引用定位和有用性反馈
- [x] Artifact 列表、预览、认证下载、删除和复用为聊天附件
- [x] Run 队列、Memory、Artifact 和持久化审批策略支持勾选批量永久删除；数据库事务全有或全无，Run 仅允许终态记录
- [x] Schema 迁移 10、Console 业务工作台、Store 回归测试和中文文档
- [x] 59 项自动化测试覆盖 Common、Server 与 Sandbox Agent

## 阶段 11：长期使用效率

- [x] 项目级任务模板、`${变量}`、附件/工具要求和 `/review`、`/summarize`、`/research` 快捷指令
- [x] 模型配置方案、项目默认/后备模型、提交前上下文/输出/成本预估和切换模型重试
- [x] 按项目与日期统计 Token、缓存、耗时、失败率、重试和估算成本；日/月预算与接近上限提醒
- [x] Run 优先级、批量取消/重新排队、项目最大并发和 SQLite 单机公平调度
- [x] 一次性/每日/每周/Cron 定时任务，可独立固定模型方案与执行专家或小队，继续复用普通 Session/Run、Approval、Event、Plan 和 Audit 链路
- [x] 浏览器与通用 Webhook/邮件网关/企业 IM 网关通知，密钥仅引用 Server 环境变量
- [x] 模板、模型方案、定时任务和通知的新建操作统一为结构化表单；定时任务使用模板下拉选择与动态周期字段
- [x] Session Markdown/JSON/完整审计包导出、隐私脱敏和跨实例导入
- [x] Skill 来源/Ref/Commit/作用域、预检、启停、固定、升级、回滚与 MCP Console 配置
- [x] Schema 迁移 11、效率 Console、Store 回归测试和中文文档
- [x] 首页独立效率入口、核心指标固定网格、最近用量折叠明细和限高滚动布局
- [x] 61 项自动化测试覆盖 Common、Server 与 Sandbox Agent

## 阶段 12：Agent 评测中心

- [x] 项目级评测套件、确定性 Case 规则、默认 Trial 次数与通过阈值
- [x] 每个 Case/Trial 复用隐藏内部 Session 和普通 Run，不新增第二套 Agent Loop
- [x] 工具、回答、Token、耗时和 Run 终态的逐项扣分与证据报告
- [x] 多 Trial 全通过的 `pass^k` 稳定性聚合及可刷新异步执行报告
- [x] 人工确认基线、关键工具保留检查和 Token/耗时 150% 退化判断
- [x] 内部评测 Run 排除自动 Memory 提取，避免评测输入污染长期记忆
- [x] 版本化官方 Starter Pack：6 个套件、25 个用例、幂等安装和 Console 用例启停
- [x] 评测中心从效率工作台抽离为首页一级入口，套件/报告双栏展示、用例默认折叠并独立滚动
- [x] Schema 迁移 12、评测 Console、Store/评分回归测试和中文文档
- [x] 65 项自动化测试覆盖 Common、Server 与 Sandbox Agent，并通过真实 REST 多 Trial/基线冒烟

## 阶段 13：生产化运行时加固

- [x] Run 终态单向状态机、状态与 Event 原子提交、每 Session 单活跃 Run 数据库约束
- [x] 同轮 ToolCall 原子持久化和顺序执行；工具 Effect 分类，安全恢复与非幂等 `UNKNOWN` 人工对账
- [x] 模型流空闲超时、带抖动退避、`Retry-After`、持久化 ModelAttempt 和熔断半开探测
- [x] Run 步骤、Token、总时长、单轮/全程工具配额，以及项目预算事务预留与结算
- [x] Worker 有界背压和拒绝回队；通知 Outbox 有界投递、重试与死信状态
- [x] SSE/Event 分页上限、嵌套审计脱敏、MDC 关联、Prometheus 和 Worker/模型/工具指标
- [x] 默认回环监听，非回环监听强制 API Key；Schema 迁移 13 与 Store/安全回归测试
- [x] 76 项自动化测试覆盖 Common、Server 与 Sandbox Agent
- [x] 评测输出 Token/总 Token 分离、资源硬门禁和仅通过 Trial 可晋升 Baseline
- [x] WAL 一次性初始化、30 秒 SQLite 写锁等待和并发写入回归
- [x] 相同工具与参数默认最多重复 3 次，超限立即终止失控 Run；Schema 迁移 14

## 阶段 14：Plan Runtime 基础

- [x] 新增 `plans`、`plan_steps`、`plan_edges`、`plan_revisions` 和 `plan_events` 持久化表；Schema 迁移 15
- [x] 新增 Plan JSON 解析器，清理 Markdown code fence，重映射模型 step id，并校验 Step 类型、执行模式、依赖存在和 DAG 循环
- [x] 新增 Planner 服务，通过现有 ModelClient 生成结构化计划；Demo 模型保留单步计划降级，方便本地无模型 Key 验证
- [x] 新增 Plan 生命周期 API：创建、生成、查看、批准/启动、取消、Replan、Step retry/skip 和 Plan events
- [x] 启动 Plan 时只将依赖已满足的根 Step 推进到 `READY`，不提前绕过 ReAct Run、ToolCall 或 Approval 边界
- [x] Session 删除时同步清理关联 Plan 数据；补充 PlanService 和迁移回归测试

## 阶段 15：Plan 执行闭环、Async Job 和验证证据

- [x] `plan_steps` 增加 `run_id`，新增 `async_jobs` 与 `validation_checks`；Schema 迁移 16
- [x] 新增 `PlanExecutionService` 与 Plan Worker，自动领取 `READY` Step，创建普通 ReAct Run，并在 Run 终态后回写 Step、Plan、Async Job 和 Validation Check
- [x] 新增 `PlanValidator` 验证闸口，Run `COMPLETED` 后先进入 `VALIDATING`，按 done criteria 写入 `actual/evidence/error`，验证通过才完成 Step，验证失败进入 `VALIDATION_FAILED`
- [x] 支持 `REACT`、`ASYNC`/`ASYNC_JOB`、`NONE`、`MANUAL`/`USER_APPROVAL` 的基础 Step 状态推进
- [x] Validation Check 支持最终回答、受控文件存在/内容和 JUnit XML 测试报告验证，并沉淀 EvidenceBundle 字段
- [x] Replan 支持 FAILED/ACTIVE Plan 的局部尾部替换，保留已完成步骤及验证证据
- [x] `plan_steps` 增加领取 owner、租约过期时间、心跳、尝试次数、恢复原因和调度幂等键；Schema 迁移 19
- [x] Plan Worker 每轮调度前回收过期且尚未绑定 Run 的 `RUNNING` Step，避免领取后崩溃造成步骤永久卡住
- [x] 新增 `/dispatch`、`/dag/batches`、`/jobs`、`/validation-checks` 和通用 `/v1/async-jobs` API
- [x] 新增 Read-only DAG 批次分析；当前先做保守调度，不绕过同一 Session 的活跃 Run 限制
- [x] Console 效率工作台新增 Plan 工作台，展示计划、调度、Async Job、Validation Check 和 DAG 批次
- [x] 评测 Starter Pack 增加默认关闭的 Plan/DAG/验证模板用例
- [x] 补充 Plan 调度、Async Job、Validation Check 和迁移回归测试

## 阶段 16：Memory/RAG/Plan-Agent 阶段 2/3/4 增量闭环

- [x] Memory 增加结构化 payload、生命周期状态、来源修订、有效期、supersedes 和 checksum 字段；新增 `memory_sources` 与 `memory_conflicts` 审计表，Schema 迁移 20。
- [x] 自动 Memory 同 key 内容变化保留 revision、来源摘录和 OPEN conflict，召回只使用启用且 ACTIVE、未过期的 Memory。
- [x] RAG 检索增加 Query Plan，识别代码路径、符号、排障、决策和架构查询，并在 SearchHit 中返回 citation、文档版本、BM25 分、检索策略和命中原因。
- [x] Agent Harness 的 `spawn_agent` 支持 PlanStep 绑定和执行 envelope，持久化 scope、允许文件/工具、输入 artifact、输出契约、验收标准、预算、deadline、依赖和禁止操作。
- [x] `get_agent_result` 写回结构化 Agent Result，包含子 Run 状态、摘要、Artifact、Token 用量、失败分类和证据，供 Leader 汇总、恢复和审计使用。
- [x] Kafka/Redis/MinIO 仍保持预留接口，不实现外部适配器；当前 Lite 运行语义仍是 SQLite、进程内协调和本地文件。
- [x] 补充 Store 与 RAG 回归测试，覆盖迁移 20、Memory source/conflict/revision、Plan 绑定委派 metadata 和 citation metadata。

## 阶段 17：受控并行与闭环生产加固

- [x] `plan_steps` 增加资源读集、资源写集、隔离策略、最大并行度、关键路径权重和 workspace 引用；新增 `agent_feedback` 闭环表，Schema 迁移 21。
- [x] Plan JSON 解析支持 `resource_read_set`/`read_set`、`resource_write_set`/`write_set`、`isolation_strategy`、`max_parallelism` 和 `critical_path_weight`。
- [x] Plan 调度按关键路径权重、下游数量和 ordinal 排序，调度前检查活跃 Step 的资源读写集，阻止同一计划内写写和读写冲突。
- [x] 冲突 Step 写入 `RESOURCE_CONFLICT` 并短暂延后；下一轮调度可自动恢复，避免永久失败。
- [x] `INTERNAL_SESSION` 和 `GIT_WORKTREE` 隔离策略会创建内部 Session 与受控 workspace 引用；`GIT_WORKTREE` 当前是 Lite 目录边界和后续真实 worktree 工具层预留，不自动执行 git merge。
- [x] Plan 验证通过/失败都会写入 Agent Feedback；验证通过会生成过程型 Memory，验证失败会保留 failure class 和证据质量。
- [x] 新增 Plan 验证、资源冲突、Agent Feedback 和验证 Memory Micrometer 指标，便于 Actuator/Prometheus 观测闭环效果。
- [x] 补充回归测试，覆盖资源冲突推迟、隔离 workspace 引用、workspace owner 映射、Agent Feedback 幂等写入和验证 Memory 生成。

## 阶段 18：类型化 Graph Runtime

- [x] `plan_edges` 增加边类型、确定性条件、优先级、最大回流次数和回流计数；Schema 迁移 23，旧边兼容为 `DEPENDENCY + ON_SUCCESS`。
- [x] Plan JSON 支持可选 `edges`，解析并校验 `DEPENDENCY`、`CONDITIONAL`、`REWORK`，回流边不参与 DAG 循环判断且必须具有有限次数。
- [x] 条件路由由 Server 按 `ALWAYS`、`ON_SUCCESS`、`ON_FAILURE`、`ON_VALIDATION_FAILURE`、`ON_SKIPPED` 确定性判断；命中与未命中都记录 Plan Event，未选分支和 Validation Check 同步跳过。
- [x] `REWORK` 只重置目标节点及其下游分支，保留无关分支；每次回流持久化计数，耗尽后恢复原失败终态。
- [x] 新增结构化 `PlanState` 与 `/v1/plans/{id}/state`，汇总步骤状态、READY/活跃/人工节点、阻塞原因、Token 和最后事件序号。
- [x] `USER_APPROVAL` 升级为持久化 Human Node，新增 `/v1/plan-steps/{id}/decision` 批准/拒绝接口；Console Plan 详情可查看边、状态与处理人工节点。
- [x] 补充迁移、条件分支、人工决策、回流上限、事件审计和状态快照回归测试。

## 阶段 19：Plan 与 Multi-Agent Graph 协作执行

- [x] 默认 Run Worker 并行度提升到 4，单次轮询填满空闲执行槽；项目 `max_concurrent_runs` 继续作为第二层并发预算。
- [x] Schema 迁移 25 新增 `run_delegation_dependencies` 与 `run_delegation_resources`，委派持久化失败策略、阻塞原因和 workspace 引用。
- [x] `spawn_agent.dependencies` 升级为服务端强制调度边；依赖未终态的子 Run 保持 `BLOCKED`，不能依靠 Leader 提示词绕过。
- [x] 同 workspace 的读写/写写冲突串行，读读并行；不同 workspace 引用映射到隔离 workspace owner。
- [x] 子 Run 终态事务自动写 Result Envelope v2、向下游 Session 注入有界上游结果、推进节点并唤醒父 Run；信封从 ToolCall/Artifact/ModelUsage 归集文件、命令、测试和证据。
- [x] 上游失败支持 `BLOCK_GRAPH`、`DEGRADE`、`REQUIRE_HUMAN`；协作看板和 decision API 可处理等待人工判断的节点。
- [x] Plan Graph 保留跨步骤条件、REWORK、Validation Gate 和 Human Node，Delegation Graph 承担步骤内/Leader 动态派发；两层统一复用普通 Run。

## 阶段 20：多 Shell Docker 命令运行时

- [x] `execute_command` 增加 `shell`、`cwd`、`timeoutSeconds`、`maxOutputBytes` 和显式 `env`，Shell 仅允许 `sh`、`bash`、`powershell` 固定映射。
- [x] Docker Sandbox 镜像同时提供 Java 17、Bash 和 PowerShell Core；部署级命令超时同步注入 Sandbox Agent。
- [x] Schema 迁移 26 为 Run 与 Agent Profile 持久化默认 Shell；模型省略参数时在 ToolCall 原子持久化和 Approval 之前补齐，恢复时复用。
- [x] stdout/stderr 独立排空，记录退出码、Shell、工作目录、耗时、超时、字节数和截断状态；长结果复用 Artifact Store。
- [x] 命令进程清空继承环境，显式 `env` 拒绝敏感变量名；Local 模式继续拒绝宿主机命令执行。
- [x] Run 取消销毁独占容器并返回 Sandbox 取消状态；Console 在首页、对话工具条、专家设置和执行详情展示执行环境。
- [ ] 增加逐行 stdout/stderr SSE、PTY 交互终端和受控后台服务生命周期。

## 阶段 21：Context 与 Memory 认知控制层

- [x] `memory_extractions` 在 job 创建时冻结所属 Run 的不可变 Message 快照；Worker 只读快照，旧 job 保留兼容回退。
- [x] `memory_sources` 保存证据 Message id、起止 sequence 和摘录，来源 API 可回跳到具体消息或工具结果；Schema 迁移 27。
- [x] Context Manifest 记录各区块 Token、PlanState、RAG citation、Memory id/选择理由、动态工具和丢弃来源，Rules/Skills/Tools/History/Summary/Plan/Memory/RAG 进入统一预算。
- [x] Conversation Summary 固定为目标约束、计划、已验证事实、未验证假设、决策、失败尝试、待办和证据八节；模型输出缺节、乱序或超预算时使用结构化确定性降级。
- [x] 默认只常驻核心 Tool Schema 与 `tool_search`；Knowledge、Skill、Web、MCP、Multi-Agent 等扩展工具在发现后按需加载，Agent Profile 白名单继续作为权限上限。
- [x] Memory 增加近重复 canonical key 归并、中相似冲突队列、L1 陈旧标记、类型召回配额和 `memory_usage_feedback`；Run 终态与 Plan 验证结果形成排序反馈。
- [x] 官方 Starter Pack `1.1.0` 增加 Context/Memory Harness 六个专项 Case，并补来源冻结、source span、反馈、结构化摘要、按需工具和预算回归测试。
- [x] Prompt Cache 模板改为稳定前缀、历史前置、动态尾部和持久化 Run 时间；README/架构文档单列命中率优化、增量指标口径及合理失效条件。

- [x] 自动 Memory 仅由委派树根 Run 排队；单根 Run 至多 3 条（L1≤1、L2≤2、L3≤1），过滤协作流程噪声，拒绝空证据或仅 Assistant 证据，并按证据质量、重复和稳定性校准模型置信度；Console 支持主动新增人工 L3 长期记忆。

## 阶段 22：增强 AgentTeam、结构化路由与团队评测

- [x] AgentTeam 增加团队指令、成员角色说明、能力标签、路由策略、完成策略、故障回退 Agent 和最大并发；Route Preview 的有效并发会固化到根协作策略，队列按委派树限制活动子专家数量，项目级最大并发仍作为外层上限；旧协作策略以 `0` 保持不额外限流的兼容行为。
- [x] Route Preview 返回目标类型、Leader、候选 Agent、匹配原因、复杂度、风险和预计并发；预览不创建 Run，真实 Trigger 持久化 Route Decision。
- [x] Agent 评测 Execution 可选绑定 AgentTeam，每个 Trial 使用团队 Leader 和固化的协作策略，继续复用普通 Run、ToolCall、Approval 与评分器。
- [x] Starter Pack `1.2.0` 增加默认关闭的 AgentTeam 协作 Harness，总计 7 个 Suite/28 个 Case。
- [x] Console 专家协作入口增加 Route Preview，小队编辑器开放增强字段，团队列表展示任务完成率、Run 成功率、委派数和人工介入数。

## 阶段 23：持久化 CollaborationTask 与协作时间线

- [x] 新增长期 `CollaborationTask`，支持 Agent/AgentTeam 负责人、状态、优先级、可选完成条件、父子任务、阶段和 Plan 引用；人工作为全流程审核与干预者，不建模为任务负责人；阶段子任务会原子绑定直接专家 Run，避免只创建未执行的孤立任务卡片。
- [x] 新增评论、回复、结论、显式 Mention 和统一 Activity Timeline；评论/状态/Run/Barrier 事件保留 actor、subject、payload 与时间。
- [x] 新增 Task-Run 多对多关联；Trigger Run 和委派 child Run 都回链所属任务，任务完成与 Run 终态保持分离。
- [x] Agent Profile 自动获得受白名单约束的任务读取、评论、进度/阻塞报告和子任务创建工具；`IN_REVIEW` 只由整个 Run 树终态后的平台状态机提交，Agent 不能直接写 `IN_REVIEW/DONE/CANCELED`，并继续服从 ToolCall 先持久化及审批边界。
- [x] Console 新增“协作任务”入口，并按任务、协作、执行三层展示目标与可选完成条件、人工动作、评论时间线和 Run 会话；创建表单默认只保留标题与负责人；三模式切换提升到全局主 Header，在首页、会话和任务过程中持续可见；左侧历史按项目和既有自定义分组统一展示普通、专家、任务三类记录，折叠任务关联的重复 Run 会话，保留打开、会话分组与删除能力，并遵循有执行历史的任务只能取消、不能物理删除的审计边界；协作层使用阶段轨道、参与/执行/评论指标和中文语义动态展示过程，并以同一次三秒轮询同步评论、活动、Run 与状态；执行者、模型、Team、工具和其他实体引用统一映射业务名称。

## 阶段 24：事件驱动 Leader 唤醒与阶段屏障

- [x] 补齐根任务树语义：阶段只在父任务详情内展示；任务执行页聚合根与阶段 Run，并从共享 workspace 直接列出最终交付文件。根任务只有在整个执行树终态后才可进入人工验收，消除“Agent 仍在运行但任务已待验收”的状态错位。
- [x] 终态协作任务支持删除协作层记录，同时保留已结束 Run、会话和交付文件；活跃 Run 仍要求先取消。

- [x] 统一持久化 Trigger，支持 `MANUAL`、`HUMAN_ACTION`、`MENTION`、`REPLY`、`RUN_EVENT`、`STAGE_BARRIER`，使用全局 idempotency key 阻止恢复或重放时重复创建 Run。
- [x] 用户评论默认触发任务负责人；显式 Mention 精确触发 Agent/Team；回复 Agent 评论回到原 Agent；团队成员发布评论或进入 Run 终态时唤醒 Leader。
- [x] 子任务按 `parent_id + stage` 建立持久化 Barrier；同阶段全部 `IN_REVIEW/DONE/CANCELED` 后只完成一次并唤醒父任务负责人，Barrier 完成不替代最终人工验收。阶段派发与后续新 Leader Run 复用根任务级共享工作区，不依赖某个 Leader Session 的临时目录；同一目标已有活跃 Run 时不再由评论、子 Run 终态或 Barrier 并发复唤醒，Leader 最终结论必须晚于其他 Run 终态。
- [x] Run 完成不自动推进任务；单 Agent 任务由被分配 Agent、Team 任务由 Leader 提交 `IN_REVIEW`，人工通过显式 `ACCEPT` 完成最终审核，也可在任意节点评论并启动、继续、阻塞、返工、取消或重新打开；活跃 Run 期间取消任务会同时持久化取消关联 Run 树并中断模型与 Sandbox 执行。
- [x] Schema 迁移记录推进到 34，并增加迁移、Store 幂等、评论提及、Task-Run、Route Decision、团队 Trial、阶段屏障、小队并发领取、取消审批清理、任务工作区迁移与空交付门禁回归测试；主 Header 汇总当前项目待审批项，子专家 Approval 可不进入会话直接处理，已终态 Run 的遗留审批会自动关闭。

## 阶段 25（规划）：外部 Harness 成本与协作效率控制

- [ ] 在普通对话、专家协作与 CollaborationTask 之前增加统一的任务级 Token/费用信封、原子预留和结算；保留现有项目/Run 级预算作为下层硬上限。
- [ ] 增加 Trigger 合并、Leader 单飞、证据版本、429 冷却与可读的 Harness 决策审计，避免每条成员事件或评论都直接创建新的模型 Run。
- [ ] 引入结构化交接包、角色 Context Profile 与确定性工具结果归并，减少跨 Agent 的重复上下文和工具后模型回合。
- [ ] 将模型调用数、输入/输出 Token、缓存、重试、验收通过率和返工率纳入同一团队评测，拒绝只省 Token 却降低交付质量的策略。详见 [外部 Harness Token 成本优化方案](harness-token-optimization.md)。

### 阶段 16 后续工作

- [ ] Memory conflict 的人工解决 API 与 Console 审计入口。
- [ ] RAG citation 在 Console 搜索结果中完整展示查询计划、命中原因和反馈闭环。
- [x] Agent Result 中的 `files_changed`、`commands_executed`、`tests` 由工具事件自动归集，而不是仅预留结构字段。
- [ ] PlanStep 委派与 PlanExecutionService 的状态联动继续细化，例如子 Agent 终态自动触发 Step 验证。

### 阶段 15 后续工作

- [x] Read-only DAG 资源锁和会话隔离策略已落为阶段 17 的 Lite 受控并行；真实 Git worktree merge 仍保留为后续工具层。
- [ ] Async Job 接入可审批的长命令、下载、OCR、CI 查询等真实后台执行器
- [ ] Validation Check 继续扩展命令/API/截图/数据库/安全扫描断言

## 明确不做

- Kubernetes、MicroVM、多地域和多租户
- Kafka、Redis、PostgreSQL、MinIO
- 已为 Kafka/Redis/MinIO 预留替换端口：Run Dispatch Queue、Run Execution Registry 和 Object Storage Port；当前默认和唯一可用实现仍是 SQLite/进程内注册/本地文件，配置为外部后端会明确失败，避免误判为已接入。
- 跨项目 Memory 联想图谱
- 跨项目自治组织、复杂资源排班和无人工边界的自进化团队；Lite 当前提供单项目 AgentTeam、持久化协作任务和三层任务 Console
- 默认 Lite 配置中的外部向量数据库
- 音视频理解和历史原始图片重复注入
