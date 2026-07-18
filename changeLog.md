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
