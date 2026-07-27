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

## 2026-07-28

### Plan 与 Multi-Agent Graph 协作调度

- 变更：默认 `PAICLI_WORKER_COUNT` 从 1 提升为 4，Run Worker 每轮按空闲槽位批量领取任务；项目并发预算继续限制实际并发。
- 变更：Schema 迁移推进到 25，新增委派依赖边和资源读写表；`spawn_agent.dependencies` 成为服务端调度门禁，并新增 workspace 引用、`BLOCK_GRAPH` / `DEGRADE` / `REQUIRE_HUMAN` 失败策略。
- 变更：同 workspace 的读写/写写冲突由 Run Queue 阻塞，不同 workspace 引用映射到隔离 owner；子 Run 终态在同一事务内写 Result Envelope v2、向可执行下游 Session 注入有界上游结果、推进节点、级联失败并唤醒等待父 Run。
- 变更：Result Envelope 从消息、Artifact、ModelUsage 和 ToolCall 自动归集摘要、文件、命令、测试、风险与未完成项；协作 API/Console 展示依赖、阻塞原因、资源和失败策略，并提供人工节点批准/拒绝入口。
- 思路：Plan Graph 继续管理跨步骤条件、回流、验证和 Human Node，Delegation Graph 管理 Leader 动态派发；两层都由后端确定性判断就绪状态并复用普通 Run，模型只负责规划和结果综合。
- 验证：完整 Maven 测试通过，Common 2 项、Server 124 项、Sandbox 2 项，共 128 项；最终 Graph Store 定向回归 31 项通过，覆盖迁移 25、依赖防抢跑、已完成依赖即时解锁、上游信封注入、workspace 隔离、资源冲突、三类失败策略、自动结果信封和父 Run 唤醒。Human Node API 用例通过；组合重跑时出现一次既有 Spring 后台 Worker 的 SQLite 锁竞争，失败用例单独重跑通过。全模块普通 JAR 打包通过；Spring Boot 可执行 JAR 重打包因本机正在运行的旧 Server 锁住目标 JAR 未执行，未擅自停止现有服务。`node --check` 与 `git diff --check` 通过。

### 对话任务短标题与父 Agent 返回刷新修复

- 变更：新增统一的有界任务标题提取；普通占位 Session 在首次提交 Run 或 Plan 后持久化短标题，子专家 Session 使用“专家 · 子任务摘要”，Console 的 Plan、协作看板和 Plan 工作台以摘要作为任务名并保留完整目标说明。
- 变更：修复从子专家返回父 Agent 时旧 Run 事件和轮询未立即失效、父 Run SSE 从 `after=0` 重放全部历史的问题；切换 Session 现在先停止 Plan/审批轮询并清空旧 Run 标识，静默读取持久化时间线确定最新事件游标，只把游标之后的新事件交给实时副作用处理。
- 变更：Plan 的 6 秒轮询改为只替换顶部 Plan 看板，并在看板高度变化时补偿消息容器滚动位置；增加请求互斥，避免慢请求产生重叠刷新。聊天状态改为聚合当前 Run 与关联 Plan，只要 Plan 仍为 `ACTIVE` / `WAITING_APPROVAL`，Leader Run 终态不会把对话提前标为完成，也不会弹出错误的完成提醒或允许提交新任务。
- 变更：Plan 摘要和完整详情中的每个已绑定步骤新增“打开 Run”，通过只读 `/v1/runs/{runId}/audit` 一次展示 Run 所属 Session、模型输入/输出、ToolCall 参数与结果、Approval、事件及 Validation Check 证据；Plan 详情从临时提示升级为完整审计对话框。
- 变更：所有绑定 Agent Profile 的专家获得 `list/get/create/replan/start/cancel` 六个受控 Plan 工具；读取限制在当前项目，写操作复用持久化审批，只允许专家修改自己创建、当前 Step 绑定或父委派明确分配的 Plan，并按 ToolCall 幂等复用。普通 Run 不开放这些工具。
- 思路：任务名称用于快速定位，不能把完整 Prompt 或内部 ID 当标题；历史事件用于恢复右侧审计时间线，但不应再次触发消息加载、完成提醒等实时副作用。聊天页展示的是 Session 级任务状态，不能只用某一个 Run 的终态代替整个 Plan 的终态。
- 思路：步骤状态只回答“进行到哪里”，Run 审计视图负责回答“由谁、在哪个 Session、模型和工具实际做了什么、为何获批、如何验收”；专家可以参与计划演进，但写权限必须落在现有 ToolCall/Approval/幂等与作用域边界内，不能变成任意修改全项目 Plan 的旁路。
- 验证：`node --check paicli-server/src/main/resources/static/app.js` 与 `git diff --check` 通过；`ContextManagerTest`、`TaskTitleTest`、`PlanServiceTest`、`PlanToolProviderTest`、`SqliteRuntimeStoreTest` 及两项 Web 定向用例共 54 项测试通过，覆盖标题与滚动修复、专家 Plan 工具注入和作用域、写工具审批与幂等重放、Run 审计响应及 Console 静态入口。全模块测试执行 120 项时仅既有协作用例受 Windows SQLite `SQLITE_BUSY_SNAPSHOT` 锁竞争中断，该用例随后单独重跑通过；打包验证未完成，原因是 Maven Wrapper 的 PowerShell 启动脚本间歇性 `null array`，直接调用 Wrapper 内 Maven 又被本机依赖 JAR 访问错误阻断。浏览器验证流程已连接，但本地 8080 未运行，因此未执行真实父子会话和 Run 审计对话框的点击回归。

## 2026-07-27

### 协作预算生效、审批聚合、父子导航与执行小队
- 变更：修复本地启动时 `.env` 不覆盖父进程同名环境变量的问题；`run-server.ps1` 和 `start-local.ps1` 现在显式以项目 `.env` 为准，避免残留的 `PAICLI_MODEL_MAX_RUN_TOKENS=200000` 让已配置的 `0` 未实际生效。
- 变更：审批接口支持按 `runId` 返回整棵委派树的待审批项；Console 在父 Run 终止后仍持续轮询协作审批，审批卡标明所属专家和 Run，并保留浏览器通知。协作接口新增父委派导航信息，子专家会话可直接返回父专家。
- 变更：Schema 迁移推进到 24，新增 `agent_teams` 表及 `/v1/productivity/agent-teams` CRUD；“专家创建”新增执行小队管理，可组合 Leader、Expert、Reviewer、Runner 及并发/深度要求，专家协作首页可直接选择小队启动。
- 思路：单 Run token 上限关闭必须以运行进程实际读取到 `0` 为准；审批属于整棵协作树的人工门禁，不能依赖用户主动进入子会话；专家组合属于可复用项目配置，不应每次临时勾选。
- 验证：`node --check paicli-server/src/main/resources/static/app.js` 和 Maven 编译/打包通过；定向运行 `SqliteRuntimeStoreTest`、`ApprovalFlowTest`、`RunProcessorTest`、`WebSecurityIntegrationTest` 共 41 项测试通过，覆盖迁移 24、小队持久化、父子关系、委派树审批、预算执行和静态资源入口。重启 8080 后服务实际上报 `maxRunTokens=0`；浏览器检查协作首页和小队表单在桌面与 390px 移动宽度均无横向溢出，页面控制台无错误。

### Memory 分层展示静态资源刷新
- 变更：将 Console 首页引用的 `app.css` / `app.js` 资源版本号更新为 `20260727-memory-layers`，确保效率工作台 Memory 分层渲染逻辑不会继续命中浏览器旧缓存。
- 思路：上一轮已在 `app.js` / `app.css` 实现 `L1`、`L2`、`L3` 分组和组内按置信度降序，但页面入口仍使用旧 query version，浏览器可能复用旧资源导致界面看起来未变化。
- 验证：运行目录与源码入口将通过资源处理同步，前端脚本继续使用 `node --check` 校验。

## 2026-07-26

### Memory 管理按层级与置信度展示
- 变更：效率工作台的 Memory 管理从平铺列表改为按 `L1`、`L2`、`L3` 三层分组展示，每层标题显示条数和平均置信度，组内按 `confidence` 从高到低排序。
- 变更：Memory 条目继续保留置顶、确认、启用/停用、合并和修订操作；层级为空时显示该层暂无 Memory，避免隐藏层级状态。
- 思路：Layered Memory 的治理视图应直接体现层级边界和可信程度，方便用户先审高置信度事实，再处理过程经验和长期偏好，而不是在一条混合列表里人工筛选。
- 验证：运行 `node --check paicli-server/src/main/resources/static/app.js` 通过。

### IDE 静态检查与删除接口收口
- 变更：新增显式 `JacksonConfiguration`，在缺少自定义 `ObjectMapper` Bean 时通过 Spring 的 Jackson builder 创建默认 Bean，帮助 IDE 和运行时都能识别构造器注入依赖。
- 变更：修复 `ProductivityController` 中删除模板、模型方案、专家、计划任务和通知渠道时未 `throw notFound(...)` 的问题，避免删除不存在资源仍返回 204。
- 变更：将面试讲解文档中的 `SandboxDriver` 接口签名代码块从 `java` 改为 `text`，避免 IDE 对说明性片段做 Java 语法检查。
- 思路：Maven 能通过不代表 IDE 检查没有噪声；显式基础设施 Bean 和文档围栏类型能减少误报，同时把被检查暴露出的真实删除语义问题一并修正。
- 验证：运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=PaiCliServerApplicationTest,WebSecurityIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过，9 项测试验证 Spring 上下文、显式 Jackson Bean、MockMvc 测试上下文和 Web API；检查文档中已无 `java` 代码围栏触发 IDE Java 语法分析。

### 子 Agent 预算触顶收尾
- 变更：`RunProcessor` 在单 Run 执行预算达到上限时，不再把 Run 直接标记为 FAILED；改为停止继续调用模型，写入一条预算停止的最终 assistant 摘要、`run.budget_stopped` 事件，并唤醒等待中的父 Run。
- 思路：子 Agent 触发显式预算保护时，父 Agent 需要拿到可汇总的终态结果和预算快照，而不是被一个硬失败中断整条协作链；预算保护仍然生效，只是把“继续消耗资源”替换为“保留已有证据并让父任务降级汇总”。
- 验证：与后续“单 Run 累计 Token 上限默认关闭”一起，通过 `RunProcessorTest` 覆盖累计 token 超过旧阈值时不再触发默认硬停止。

### 单 Run 累计 Token 上限默认关闭
- 变更：`PAICLI_MODEL_MAX_RUN_TOKENS` 默认值改为 `0`，表示不启用单 Run 累计 Token 硬上限；`RunProcessor` 只有在该值为正数时才按累计 token 停止 Run，步数、工具调用次数和运行时长保护继续生效。
- 变更：上下文压缩器在累计 Token 上限关闭时只按模型上下文窗口触发压缩，不再用单 Run 预算提前压缩；本地 `.env` 和 `.env.example` 同步改为 `PAICLI_MODEL_MAX_RUN_TOKENS=0`。
- 思路：Codex 类任务不应默认暴露或执行“单任务累计 token 上限”，否则长链路子 Agent 会因为历史累计用量而非真实错误中断；保留正数配置作为显式保护阀，方便压测或低成本部署时主动启用。
- 验证：更新应用启动测试断言默认 `maxRunTokens=0`，并调整 `RunProcessorTest` 覆盖累计 token 超过旧阈值时仍继续执行。

### 技术架构与面试指南按功能重构

- 变更：新增《PaiCLI Platform Lite 技术架构与面试指南.md》，保留原《技术架构与面试讲解》作为专题素材，新的主阅读入口按“总体介绍、逐功能技术架构、简历写法、40 个面试问答”重新组织。
- 变更：逐项结合当前代码说明 Session/Run、可恢复 ReAct、ToolCall/Approval、Docker Sandbox、模型网关、Context、Memory、混合 RAG、Skill/MCP、Multi-Agent、Plan Graph、租约恢复、资源冲突、隔离 Workspace、Validation Gate、评测和长期治理；每项统一回答核心思想、当前设计、Lite 简化、企业差距、升级路线和面试表达。
- 变更：补充关键执行、恢复、RAG 融合、Plan 调度和评测伪代码，以及后端岗位版、AI 平台岗位版、三条精简版简历表达；新文档不再使用优先级或开发批次作为讲解主线。
- 变更：根据原《技术架构与面试讲解》和当前代码继续扩写主功能部分，迁回 Session/Run 数据关系、六条恢复契约、崩溃窗口、完整工具目录、写文件时序、Docker 协议、模型重试/取消、Token 预算区别、Memory 生命周期、RAG 分块与融合、Skill/MCP 治理、Multi-Agent 委派、Plan JSON/路由、Validation Evidence、评测对象和 Baseline 等细节。
- 变更：不再强制每章只停留在统一六段式摘要；在共同主线下按功能特点增加 SQL、状态机、时序图、数据表、伪代码、真实边界和代码阅读路线，同时保留原文档作为历史专题素材。
- 变更：补充治理工作台、REST/SSE/Console、SQLite/文件一致性、安全与可观测性、企业组件映射和关键权衡，并校正 Plan JSON 示例为代码实际支持的 Step type 与 execution mode。
- 变更：README 文档索引区分“按功能重构版”和“原始专题版”，Plan 说明及架构文档改为链接新的指南章节。
- 思路：原文档技术内容完整，但同一能力分散在架构、演进、演示、问答和简历章节中；新文档以任务从接收、执行、验证到评测回归的顺序重排，保留原有技术深度并明确已实现能力与不可过度宣传的边界。
- 验证：扩写后新文档共 2558 行、约 7.7 万字符，其中主功能部分约 2052 行；40 个问答编号连续，74 个代码围栏成对，正文不使用 P0/P1 或开发阶段编号叙事。Plan JSON 示例通过代码实际支持的 Step type、execution mode、依赖和 Edge 重复校验；README/架构文档链接目标存在，`git diff --check` 通过（仅有 Windows 行尾转换提示）。本次只调整文档，不改变运行行为，未运行 Maven 测试。

## 2026-07-25

### 面试文档第 16 节演进路线详解

- 变更：扩展《PaiCLI Platform Lite 技术架构与面试讲解.md》第 16 节，从原来的阶段清单改为“演进主线、阶段细节、能力边界、P2/P3 路线和面试话术”结构。
- 变更：补充 P0 正确性、长期效率、评测中心、Plan Runtime、Async Job、Validation Gate、协作可视化、受控并行、资源读写冲突、隔离 workspace、类型化 Graph Runtime、Human Node 和失败回流的面试讲解角度。
- 思路：第 16 节面向面试收束，应回答“项目如何从 Demo 演进为 Runtime”以及“已完成、未完成、企业化如何替换”的问题，而不是只列阶段标题。
- 验证：人工检查第 16 节标题层级、表格和面试话术；本次仅修改文档，未改变运行行为，未运行 Maven 测试。

### 类型化 Graph Runtime 与 Human Node

- 变更：`plan_edges` 从单一 DAG 依赖升级为 `DEPENDENCY`、`CONDITIONAL`、`REWORK` 三类持久化边，增加确定性条件、优先级、最大回流次数和回流计数；Schema 迁移推进到 23，旧边自动兼容为 `DEPENDENCY + ON_SUCCESS`。
- 变更：Plan JSON 支持顶层 `edges`，Server 校验边引用、类型、条件、重复边和 DAG；条件只允许 `ALWAYS`、`ON_SUCCESS`、`ON_FAILURE`、`ON_VALIDATION_FAILURE`、`ON_SKIPPED`，不执行模型生成的任意表达式。
- 变更：条件命中与未命中都会写入 `plan_events`；未选分支及其 Validation Check 同步标记 `SKIPPED`。失败命中 `REWORK` 时只重置目标节点及其下游，保留无关分支，并按持久化次数上限阻止无限回流。
- 变更：新增 `PlanState` 和 `/v1/plans/{id}/state`，统一返回状态计数、READY/活跃/等待人工节点、阻塞原因、累计 Token、最后事件序号和更新时间；Plan 详情直接内嵌同一快照。
- 变更：`USER_APPROVAL` 升级为持久化 Human Node，新增 `/v1/plan-steps/{id}/decision`，只允许等待中的人工节点提交 `APPROVED` 或 `REJECTED`；Console Plan 详情展示类型化边、回流计数、状态与阻塞原因，并可处理人工节点。
- 思路：在已有租约恢复、资源锁、隔离 workspace、Validation Gate 和 Agent Feedback 上补齐图语义；确定性决策留在代码状态机，模型只负责生成候选计划，避免引入 LangGraph、Temporal 或分布式中间件。
- 验证：`PlanServiceTest` 与 `SqliteRuntimeStoreTest` 共 45 项通过，覆盖迁移 23、条件路由、分支级联跳过与汇合、人工决策、回流上限、事件审计和 PlanState；`.\mvnw.cmd test` 全量 113 项通过；跳过 Spring Boot 重打包锁点的模块 `package` 成功；`node --check paicli-server/src/main/resources/static/app.js` 与 `git diff --check` 通过。`clean test` 因正在运行的本地服务锁住旧 runtime JAR 而停在 clean 阶段，未停止用户服务。

### Plan 自依赖生成错误修复

- 变更：修正计划生成提示词中的错误示例，将首步 `dependencies` 从 `["step_1"]` 改为 `[]`，并明确首步不得依赖任何步骤、后续步骤不得依赖自身。
- 变更：模型计划第一次未通过 `PlanParser` 结构校验时，Server 会携带校验错误和有界的无效 JSON 自动重新生成一次；第二次仍不合法才返回错误，手工计划和 replan 继续严格拒绝循环依赖。
- 思路：原提示词一边禁止循环依赖，一边在 Schema 示例里让 `step_1` 依赖自己，模型照抄后必然被正确的 DAG 校验拦截。修正示例解决主因，一次受控重生成兜住模型偶发的未知依赖、自依赖和非法 JSON。
- 验证：运行 `PlanServiceTest` 定向测试，15 个测试通过；运行 `.\mvnw.cmd -pl paicli-server -am test` 完整回归，108 个测试通过；待重新打包并完成本地启动探活。

### 面试文档最终能力说明更新

- 变更：更新《PaiCLI Platform Lite 技术架构与面试讲解.md》，新增推理流合并、Run 累计预算与上下文压缩的区别说明，并补充协作可视化、最终产物交付、Plan 可恢复调度和 Windows 本地启动稳定性的最终能力表达。
- 思路：面试文档面向讲解系统设计，不展开 bug 修改过程，只说明现在系统具备的能力、边界和架构取舍。
- 验证：人工检查新增内容位于模型接口、上下文工程、已完成能力和 10-15 分钟讲解顺序中，正文未保留具体 bug 触发和修复流水。

### Plan 创建后启动 0 步与伪人工步骤卡死修复

- 变更：计划提示词明确要求读取错误、分析、改代码和测试等可自动完成的步骤使用 `REACT`；模型生成的非 `USER_APPROVAL` 手工步骤在持久化前自动规范为 `REACT`。
- 变更：Plan Worker 会把历史上已经卡在 `WAITING_APPROVAL`、没有关联 Run 且并非真正用户审批的 `MANUAL` 步骤恢复成 `READY + REACT`，随后在原 Session 创建并执行 Run；真正的 `USER_APPROVAL` 仍保持等待确认。
- 变更：Console 创建 Plan 后不再紧接着重复调用第二次 dispatch；启动提示改为使用 `/start` 返回的真实步骤状态，避免首轮已被领取后错误显示“启动 0 步”，并继续同步当前 Session 的最新 Run。
- 思路：现场 SQLite 数据显示最新三个 Plan 的首步均为“收集错误信息 / MANUAL / WAITING_APPROVAL / run_id=NULL”，调度器没有创建 Run，前端也没有对应审批单，因此 DAG 永久停住；同时 `/start` 已内置首轮 dispatch，前端再次 dispatch 只能得到 0。
- 验证：运行 `node --check paicli-server/src/main/resources/static/app.js` 通过；运行 `PlanServiceTest` 定向测试，17 个测试通过；运行 `.\mvnw.cmd -pl paicli-server -am test` 完整回归，110 个测试通过；跳过测试重新打包成功。服务重启后最新卡死 Plan 的首步自动恢复为 `REACT`、创建 Run 并完成，第二步也已创建 Run；重复点击产生的两个旧 Plan 已取消。

### Windows 启动环境变量大小写冲突修复

- 变更：`scripts/load-env.ps1` 在加载 `.env` 前合并进程环境中仅大小写不同的重复变量；`PATH/Path` 统一为 `Path`，其他变量统一为大写名称并保留优先值。
- 思路：Windows 环境变量名不区分大小写，但 Codex、IDE、Git 或代理工具可能同时注入 `NO_PROXY` 与 `no_proxy` 等两个条目；PowerShell `Start-Process` 建立大小写不敏感的子进程环境字典时会因重复键抛出 `Item has already been added`。
- 验证：在真实含 5 组大小写重复环境变量的进程中加载 `load-env.ps1`，重复组降为 0；随后通过 `Start-Process` 创建测试子进程，退出码为 0。使用同一脚本启动本地服务后，8080 正常监听，`/v1/system/info` 返回 `paicli-platform-lite`，能力接口返回 `maxRunTokens=1000000`。

### 单 Run 累计 Token 上限提升到 1,000,000

- 变更：将 `PAICLI_MODEL_MAX_RUN_TOKENS` 的本地配置、应用默认值和 Java 缺省兜底从 500,000 提升到 1,000,000，并同步更新启动配置测试。
- 思路：失败截图显示旧服务实际仍使用 200,000 上限，Run 在第 8/30 步累计消耗 204,376 Token；按当前约 25k Token/步估算，完整 30 步可能接近 750k，因此 100,000 会降低现有上限，1,000,000 才能覆盖这类长链路，同时继续保留步骤、工具次数和 30 分钟运行上限。
- 验证：运行 `PaiCliServerApplicationTest` 通过；重新打包并启动本地服务后，`GET /v1/capabilities/status?projectKey=default` 实际返回 `maxRunTokens=1000000`，8080 由新进程正常监听。

## 2026-07-24

### 专家模型思考配置、工作空间恢复与多模态稳定性

- 变更：专家 Profile 新增可选 `thinking_mode` 和 `reasoning_effort`，专家创建页可分别设置模型方案、思考开关和高/最高思考深度；普通专家 Run、Leader Run 和 delegated child Run 都优先使用专家绑定配置，空值继续继承对话设置。
- 变更：Run 新增 `workspace_owner_run_id` 兼容列，同一 Session 后续普通续聊和 Plan Run 继承已有工作空间；协作接口在当前 Run 不是协作根 Run 时回溯 Session 最近一次协作，避免续聊或启用计划模式后协作面板和工作空间消失。
- 变更：Console 按 Session 在 `sessionStorage` 保存消息滚动位置；切换会话、刷新页面和后台消息/Plan/协作刷新时恢复用户历史阅读位置，不再无条件跳到最后。
- 变更：OpenAI-compatible 模型拒绝 `image_url` 多模态内容时自动降级为纯文本请求，并注入中文提示要求明确告知用户切换视觉模型；支持视觉的模型仍直接分析原图。
- 变更：内置基础 Agent、安全规则、Agent 循环、运行环境、专家上下文标签、计划生成、计划步骤执行与 PDF OCR 提示改为中文；协议字段、工具名和 JSON Schema 保持不变。
- 变更：SQLite schema 版本增加到 22，旧库启动时自动补齐专家思考字段和 Run 工作空间归属字段；增加 Store、模型 Client、Web API 和静态 Console 回归覆盖。
- 思路：专家配置必须在父子 Run 全链路生效；工作空间和协作展示属于 Session 连续任务状态，不能被“最新 Run”替换；长对话滚动位置属于用户阅读状态；图片能力不匹配应可解释降级，而不是破坏整次对话。
- 验证：运行 `node --check paicli-server/src/main/resources/static/app.js` 通过；先运行定向 Maven 回归 42 项通过，再运行 `.\mvnw.cmd -pl paicli-server -am test` 完整回归，107 个测试通过；跳过测试的模块打包通过。

### Session 删除依赖清理与 Console 通知弹窗

- 变更：`deleteSession` 在拒绝活跃 Run 后，同一事务额外清理 `model_attempts`、`run_collaboration_policies` 和按 Run 绑定的 `async_jobs`，避免终态 Run 仍因这些外键记录导致 Session 删除返回 SQLite 409。
- 变更：Console 底部 notice 不再承载运行日志和错误详情，改为保留输入快捷键提示；所有 `showNotice` 输出改到独立非阻塞弹窗，长错误和 Skill 安装失败信息可完整查看。
- 变更：按用户反馈将通知弹窗从页面内自定义 `<dialog>` 调整为浏览器原生 `window.alert`，并移除自定义通知对话框节点和样式。
- 变更：Skill Git 导入在处理 GitHub `tree`/`blob` 链接时，会优先从链接路径推断 Skill 目录名；例如 `.../tree/main/skills/skill-creator` 不再误按仓库名 `skills` 查找。
- 变更：扩展 Store 删除测试，覆盖模型尝试记录、协作策略和异步任务挂在同一个 Run 上时仍可删除 Session；扩展 Web 静态资源测试，确保通知不再渲染页面内弹窗并改用浏览器原生 `window.alert`。
- 思路：Session 删除要按数据库依赖边界兜住所有 Run 派生记录，而不是只清理早期核心表；前端通知从输入区移开，避免首页底部堆积日志影响长对话阅读；Skill 导入语义要和 Console 对 GitHub 目录链接的承诺一致。
- 验证：运行 `node --check paicli-server/src/main/resources/static/app.js` 通过；运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=SqliteRuntimeStoreTest,WebSecurityIntegrationTest,SkillServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过，36 个测试通过；运行 `.\mvnw.cmd -pl paicli-server -am package -DskipTests "-Dspring-boot.repackage.skip=true"` 通过；运行 `git diff --check` 通过，仅有 Windows 换行提示。

### 对话页最终交付物与富文本展示

- 变更：`/v1/sessions/{sessionId}/messages` 改为返回 Console 消息视图，在保留原 Message 字段的同时为每条消息附带 `runArtifacts`，让最终 assistant 回答能直接展示关联 Artifact。
- 变更：Console 消息区新增受控 Markdown 渲染，支持标题、列表、引用、表格、代码块、行内代码、网页链接、Artifact 引用和 Windows 本地路径链接，避免把 Markdown 原文直接显示成一整段文本。
- 变更：assistant 消息下方新增“交付成果”卡片区，自动聚合 run Artifact、`http(s)` 链接、`artifact_*` 引用和本地文件路径；Artifact 支持打开/下载和文本预览，本地路径支持 `file:///` 跳转与复制路径。
- 变更：修复切换会话时先渲染消息、后同步最新 Run 导致协作看板拿不到 `runId` 而不可见的问题；现在先同步最新 Run，再渲染消息、Plan 和协作看板。
- 变更：对话页 Artifact 打开/下载不再使用普通 `<a>` 直连，改为前端 `fetch` 携带 `X-API-Key` 后用 Blob 打开或保存，避免新标签页请求 `/v1/artifacts/{id}/download` 时因缺少 API Key 返回 401。
- 变更：成果“打开”按钮改为点击时先创建预览窗口，再把认证读取到的 Blob 填入窗口；如果浏览器拦截弹窗只提示用户，不再自动降级为下载。
- 变更：新增 `GET /v1/runs/{runId}/workspace-file?path={relativePath}`，只允许读取该 Run 所属受控 workspace 内的相对文件；对话页会识别最终回答中的 `tetris.html` 等工作区文件并提供打开/下载按钮。
- 变更：成果区默认过滤 `tool_result` 以及 `read_file`、`read_artifact` 等中间工具结果，只在每个 Run 的最后一条 assistant 回答上展示交付物，避免把验证过程误当作最终产物。
- 变更：消息刷新时在用户上滑查看长会话历史的场景下保持精确 `scrollTop`，不再按新旧内容高度差补偿，避免后台刷新或新消息追加把历史阅读位置推向底部。
- 思路：Agent 最终交付应该落到可操作的页面元素，而不是依赖用户在 Markdown 文本里找 URL、文件路径或 Artifact id；后端只返回轻量元数据，完整内容仍通过既有 Artifact content/download API 按需读取。
- 验证：新增 Web 集成测试覆盖消息接口返回 `runArtifacts`、受控 workspace HTML 文件读取和越界路径拒绝；运行 `node --check paicli-server/src/main/resources/static/app.js` 验证前端脚本语法。

### 父 Run 挂载子 Agent 执行链路

- 变更：`/v1/runs/{runId}/collaboration` 的子任务数据增加 `delegationStatus`、子 Run 当前步数、结束时间、待审批、最近工具调用和最近运行事件摘要，让父 Run 页面能看到子 Agent 的阻塞点与执行轨迹。
- 变更：Console 协作任务看板在父 Run 下直接渲染子 Agent 执行链路，支持展开查看子工具/事件、在父页面处理子审批，并可按需打开子会话或把子链路挂载到右侧执行详情。
- 变更：`get_agent_result` 返回给父 Agent 的 `result` 改为有界摘要，补充 `child_session_id`、`result_truncated` 和完整结果来源说明；结构化 `agent_result.summary` 同步收敛到同一摘要上限，避免子 Agent 长输出直接膨胀父 Run 上下文。
- 思路：父 Run 失败或终止时，用户仍需要看到协作树里是否存在 `WAITING_APPROVAL` 等活跃子 Run；否则删除 Session 只返回 409 会让真实阻塞点不可见。看板只返回轻量摘要，避免把大段工具参数和结果重新带回页面；父/子 Run 的 token 计数保持独立，但跨 Agent 汇总必须通过摘要、artifact 和子会话引用传递，不能把完整子上下文灌回父上下文。
- 验证：新增 Web 集成测试覆盖父协作接口返回 child run、pending approval、tool call 与 event trace，并覆盖 `get_agent_result` 对长子结果只返回截断摘要；运行 `node --check paicli-server/src/main/resources/static/app.js` 和定向 Maven 测试验证。

### 单 Run 执行 Token 预算默认值调优

- 变更：将 `PAICLI_MODEL_MAX_RUN_TOKENS` 的默认值从 200000 提升到 500000，并同步更新 `ModelProperties` 的缺省兜底值。
- 变更：应用启动测试增加默认 `maxRunTokens=500000` 断言，防止配置文件和 Java 默认值再次漂移。
- 变更：ConversationCompactor 增加单 Run 预算感知触发条件，不再只等单次上下文接近 `maxContextTokens * summaryTriggerRatio` 才压缩；当多轮 ReAct/Agent 对话可能快速烧掉累计预算时，会提前归档旧消息并写入 `context.compacted` 事件的触发阈值。
- 变更：`run execution budget exceeded` 错误增加 step、tokens、toolCalls 和 elapsedSeconds 的当前值/上限，避免排障时只能看到笼统预算失败。
- 思路：`run_51cde474058f4624` 在坦克大战多 Agent 编排中累计模型 token 达到 200092，超过原 200000 单 Run 上限；`run_5546378e77b74ba1` 暴露出更深问题：界面只有简短对话和输出，但每次模型调用都会重复携带系统提示、工具 schema、历史消息、工具结果 preview、运行上下文和可用能力，5 次调用累计到 217014 token，而旧压缩器只看单次上下文窗口是否接近 128000，不看累计 Run 预算。调高默认值保留预算保护，同时把压缩触发改成同时考虑单次窗口和累计预算风险。
- 验证：通过本地 SQLite 快照确认相关 Run 失败原因是单 Run token 累计超过 200000，而非步数、工具次数或项目预算；运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=ContextManagerTest,PaiCliServerApplicationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过，其中新增测试覆盖“单次上下文未接近 128000 但累计预算风险已高时提前压缩”；运行 `node --check paicli-server/src/main/resources/static/app.js` 通过。

### 本地启动 Web 工具默认配置修复

- 变更：将 `paicli.web.enabled` 的默认值改为 `false`，保持 README 中“联网默认关闭”的语义；未配置 `PAICLI_WEB_SEARCH_URL` 时本地服务可以正常启动，只有显式设置 `PAICLI_WEB_ENABLED=true` 时才要求提供搜索端点。
- 变更：应用启动测试不再显式关闭 `paicli.web.enabled`，让默认配置参与 Spring Boot 上下文启动验证，防止再次出现默认开启但缺少搜索 URL 导致启动失败。
- 思路：Web 搜索是可选能力，不能让缺省本地开发环境因为未接 SearXNG-compatible 搜索端点而无法启动；配置校验仍保留在显式启用路径上。
- 验证：运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=PaiCliServerApplicationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过；运行 `.\mvnw.cmd -pl paicli-server -am package -DskipTests` 通过；使用同一可运行 JAR 以 local sandbox 启动本地服务后，`/actuator/health` 返回 `UP`，带本地 API Key 访问 `/v1/system/info` 返回 `paicli-platform-lite`。

## 2026-07-23

### Console Plan 轮询、审批刷新与分支语义修复

- 变更：Console 消息刷新增加滚动位置保护；只有用户主动发送、启动 Plan 或切换会话时强制滚到底，后台 Plan 轮询、流式输出和终态刷新会在用户上滑查看历史时保持当前位置。
- 变更：关联 Plan 面板只在 Plan 自身仍为 `ACTIVE` 或 `WAITING_APPROVAL` 时轮询，避免 Plan 已完成或失败后继续刷新消息区；审批栏增加独立轻量轮询，Plan 调度后会同步当前会话最新 Run 并开始监听，避免审批已持久化但前端未刷新导致看不到审批单。
- 变更：新增 `POST /v1/runs/{runId}/branch`，只复制源 Run 之前的历史生成分支会话，不创建新 Run；Console 的“分支”操作改为切到新分支并等待用户下一条消息，“重新执行”仍使用 retry 语义。
- 思路：把“后台状态刷新”“审批可见性”“重新执行”和“迁出分支”拆成不同交互边界，避免刷新循环抢滚动、审批依赖整页刷新，以及用户未发送消息却自动开始新一轮思考。
- 验证：补充 Web 集成测试覆盖分支 API 只创建会话且不创建 retry Run；运行 `node --check paicli-server/src/main/resources/static/app.js` 通过；运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=WebSecurityIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过。

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
