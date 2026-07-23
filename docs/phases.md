# 交付阶段

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
- [x] Schema 迁移 10、Console 业务工作台、Store 回归测试和中文文档
- [x] 59 项自动化测试覆盖 Common、Server 与 Sandbox Agent

## 阶段 11：长期使用效率

- [x] 项目级任务模板、`${变量}`、附件/工具要求和 `/review`、`/summarize`、`/research` 快捷指令
- [x] 模型配置方案、项目默认/后备模型、提交前上下文/输出/成本预估和切换模型重试
- [x] 按项目与日期统计 Token、缓存、耗时、失败率、重试和估算成本；日/月预算与接近上限提醒
- [x] Run 优先级、批量取消/重新排队、项目最大并发和 SQLite 单机公平调度
- [x] 一次性/每日/每周/Cron 定时任务，继续复用普通 Session/Run、Approval、Event 和 Audit 链路
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
- [x] 版本化官方 Starter Pack：4 个套件、17 个用例、幂等安装和 Console 用例启停
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
- [x] 新增 `/dispatch`、`/dag/batches`、`/jobs`、`/validation-checks` 和通用 `/v1/async-jobs` API
- [x] 新增 Read-only DAG 批次分析；当前先做保守调度，不绕过同一 Session 的活跃 Run 限制
- [x] Console 效率工作台新增 Plan 工作台，展示计划、调度、Async Job、Validation Check 和 DAG 批次
- [x] 评测 Starter Pack 增加默认关闭的 Plan/DAG/验证模板用例
- [x] 补充 Plan 调度、Async Job、Validation Check 和迁移回归测试

### 阶段 15 后续工作

- [ ] Replan 支持 ACTIVE Plan 的局部下游替换
- [ ] Read-only DAG 真正并行执行、资源锁和会话隔离策略
- [ ] Async Job 接入可审批的长命令、下载、OCR、CI 查询等真实后台执行器
- [ ] Validation Check 拆分为命令/API/截图/文件断言，并沉淀最终回答证据包

## 明确不做

- Kubernetes、MicroVM、多地域和多租户
- Kafka、Redis、PostgreSQL、MinIO
- 已为 Kafka/Redis/MinIO 预留替换端口：Run Dispatch Queue、Run Execution Registry 和 Object Storage Port；当前默认和唯一可用实现仍是 SQLite/进程内注册/本地文件，配置为外部后端会明确失败，避免误判为已接入。
- 跨项目 Memory 联想图谱
- 自治 Planner/Reviewer Agent Team 和复杂团队 Console；当前只做单 Agent 的持久化 Plan Runtime
- 默认 Lite 配置中的外部向量数据库
- 音视频理解和历史原始图片重复注入
