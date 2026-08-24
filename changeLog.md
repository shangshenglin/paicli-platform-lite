# PaiCLI Platform Lite ChangeLog

## 2026-08-24

### 本地 TEI Cross-Encoder Reranker

- 变更：新增 `paicli.rag.reranker` 配置、TEI `/rerank` 客户端和能力状态；混合检索在 BM25/Embedding RRF 后把有界候选的标题与正文交给本地 Cross-Encoder，按响应 index 映射原 chunk 并使用模型分数排序。成功时 `SearchHit.retrievalStrategy` 明确返回 `BM25+EMBEDDING+RRF+CROSS_ENCODER`；禁用时保持既有 `...+RERANK`。
- 变更：新增 `deploy/reranker/docker-compose.yml` 与 `scripts/reranker.ps1`，固定官方 TEI CPU 1.9 和 `BAAI/bge-reranker-base`，提供 pull/start/stop/status/logs/test；端口只绑定 loopback，模型权重保存于 Docker named volume，镜像、模型、端口、Hugging Face 端点和启动超时均可通过 `.env` 覆盖。能力状态与知识搜索 OpenAPI 注解、README 配置/运行说明、架构边界和阶段完成度已同步。
- 思路：Cross-Encoder 是可选的本机排序加速层，不改变文档、SQLite、本地 JSON 或 Milvus 的权威边界，也不要求重新向量化。对超时、非 2xx、无 ranks、非法 index/score、重复或缺失候选采用整批确定性回退，避免不同评分量纲混排并确保模型冷启动不放大为搜索故障。数据库与 Schema 未变化，迁移/Store 测试不适用；Sandbox 行为未变化，`docs/docker-sandbox.md` 不适用；Console 与产品站展示未变化，`paicli-site/README.md` 不适用。
- 验证：`KnowledgeRerankerTest` 与 `KnowledgeServiceTest` 共 7 项通过，覆盖 TEI 排序位置映射、完整候选校验、HTTP 失败整批回退和原有混合检索；修正模拟 503 的无响应体测试夹具后，`mvnw.cmd test` 全 Reactor 共 330 项通过（Common 3、Server 322、Sandbox Agent 5）。官方 GHCR TEI CPU 1.9 镜像成功拉取，首次启动从 Hugging Face 下载 ONNX 模型并缓存，`/health` 返回 200；真实中文 `/rerank` 请求把 Milvus 相关文本排第 1（0.5757537），两个无关候选分别为 0.000080369544 与 0.000037430847。重建并重启 Server 后，对本机 `default/prompts.md.extracted.txt` 发起真实知识搜索，返回 3 条结果且实际策略为 `BM25+EMBEDDING+RRF+CROSS_ENCODER`；能力状态返回 reranker provider=`tei-cross-encoder`、model=`BAAI/bge-reranker-base`、configured/reachable=true，同时 Milvus 仍 reachable，PaiCLI→TEI→知识排序端到端通过。

### 可选 Milvus 2.6 Docker 向量索引

- 部署验证：按官方 Windows 安装脚本完成用户级 Ollama 0.32.15 安装，后台 API 在 `127.0.0.1:11434` 正常响应；拉取并通过 SHA-256 校验 `nomic-embed-text`，真实 `/api/embeddings` 请求返回 768 维向量。本机忽略的 `.env` 已切换 `PAICLI_RAG_EMBEDDING_PROVIDER=ollama`、Base URL 与模型名，未写入新的密钥；现有 README 和 `.env.example` 已覆盖相同配置，因此无需重复修改。重启 Server 后能力状态为 `embeddingProvider=ollama`、`semanticEmbedding=true`；重建 `prompts.md.extracted.txt` 后，Milvus 新建并加载 COSINE collection `paicli_knowledge_d768`，11 个 chunk 全部写入且 PaiCLI 实际搜索返回 `reachable=true`。旧 384 维 collection 保留，未在未经确认时删除可恢复索引数据。
- 变更：真实 Milvus 2.6.22 REST 联调发现服务内置数据库名称为 `default`，原 `_default` 会返回错误码 800 `database not found`；将 Java 配置归一化、Spring 默认值、`.env.example` 和 README 统一修正为 `default`，并增加未配置/空配置时所有 REST 请求携带 `dbName=default` 的回归断言。本机 `.env` 同步显式设置该值，`.env` 仍保持忽略且不提交。
- 变更：真实启动反馈显示 Docker Desktop 直连 Docker Hub 超时后，Compose 的 Milvus/MinIO/etcd 镜像改为支持 `PAICLI_MILVUS_*_IMAGE` 完整引用覆盖；MinIO 默认源切到同版本 Quay 镜像。启动脚本增加独立 `pull` 动作，按 etcd、MinIO、Milvus 顺序逐项拉取并有限重试三次，在 Registry 失败时显示准确镜像、Docker Desktop Containers proxy 与可信私有镜像覆盖方式，避免 Compose 并行拉取因单项失败中断全部镜像，也避免把拉取失败误报成 Milvus 服务启动失败。
- 变更：新增 `deploy/milvus/docker-compose.yml`，固定 Milvus 2.6.22、etcd 3.5.18 与 MinIO 版本，数据使用 Docker named volumes 持久化且端口仅绑定 loopback；新增 `scripts/milvus.ps1` 提供 start/stop/status/logs。新增 `PAICLI_MILVUS_*` 配置和 REST v2 向量存储适配器，按维度创建 collection，以 project/document/chunk 的 SHA-256 作为稳定主键，写入时 delete+upsert，检索时按 project/provider 过滤 COSINE Top-K 并继续参与 BM25/RRF/rerank。能力状态 API 增加 `rag.vectorStore` 的 backend/configured/reachable/detail。
- 思路：SQLite、知识正文和原子本地 JSON 索引继续作为权威数据，Milvus 只是可重建的检索索引；默认关闭，启用后不可达或请求失败会记录最近状态并回退本地 cosine，避免把外部基础设施故障放大为知识库不可用。Collection 按向量维度隔离，Embedding Provider 作为动态字段过滤，支持本地哈希向量和远程 Embedding 维度变化。API 响应说明、启动配置、架构边界和阶段完成度已同步 README、OpenAPI 注解、`docs/architecture.md` 与 `docs/phases.md`。数据库行为与 Schema 未变化，迁移/Store 测试不适用；Milvus 不改变 Sandbox 隔离，`docs/docker-sandbox.md` 不适用；未修改 Console 或产品站展示，`paicli-site/README.md` 不适用。
- 验证：`mvnw.cmd -pl paicli-server -am -DskipTests compile` 与 `mvnw.cmd package -DskipTests` 通过；Milvus/Knowledge 定向测试 7 项通过；修复测试构造器引起的 Spring 装配选择后，`mvnw.cmd test` 全 Reactor 共 328 项通过（Common 3、Server 320、Sandbox Agent 5），覆盖 REST v2 collection/create/delete/upsert/search、Bearer Token、失败回退、ApplicationContext 与原有混合检索。镜像拉取失败增强后的 PowerShell 解析检查通过。用户侧通过固定版本 DaoCloud Docker Hub 缓存完成 Milvus 镜像拉取，Compose 的 etcd、MinIO、Milvus 2.6.22 三个容器均为 healthy；`http://127.0.0.1:9091/healthz` 返回 200/OK，Server 能加载 `milvus-rest` backend。真实知识检索进一步暴露并定位默认数据库名问题；修复后 `MilvusKnowledgeVectorStoreTest` 2 项通过，真实 `POST /v2/vectordb/collections/list` 携带 `dbName=default` 返回 `code=0`。重启 Server 后通过知识搜索触发真实访问，能力状态返回 `backend=milvus-rest`、`configured=true`、`reachable=true` 和最近成功时间，Server→Milvus 端到端检索通过；`git diff --check` 通过。

## 2026-08-16

### 官方评测集首轮实跑校准

- 变更：首轮 8 套、36 Case、49 Trial 实跑后继续消除 5 个题面歧义：历史检索限定一次精确查询，Skill 加载限定工具范围，Plan 限定 600 汉字，上下文预算与写冲突说明明确禁止工具；避免把额外探索或“修改文件”字样误当成案例目标。
- 思路：只校准案例意图与预算，不降低硬门禁、不掩盖模型真实越界，也不把 Local Sandbox 未提供 `execute_command` 的能力缺口伪装为通过。数据库、REST/OpenAPI、Sandbox 实现、阶段范围和产品站点能力未变化，因此迁移、OpenAPI、`docs/docker-sandbox.md`、`docs/phases.md` 与 `paicli-site/README.md` 不适用。
- 验证：代码层 `mvnw.cmd clean test` 共 326 项通过，校准后 `EvaluationStarterPackServiceTest` 再次通过；最终对 8 套、36 Case、51 Trial 完整复跑，51 个 Trial 全部进入终态，其中 47 通过、4 未通过，套件 4/8 通过。未通过项均保留真实证据：Local Sandbox 拒绝 `execute_command`，以及 3 个模型回答超过案例输出 Token 上限；没有用放宽硬门禁伪造全绿。

### 官方评测集 1.4.0 与误判修复

- 变更：Completion Requirement 根任务分类先按标点切分并剥离“不要/不得/无需/禁止/do not”等否定子句，再从肯定子句识别修改与测试意图；修复固定文本回答被误判 `MUTATION_REQUIRED`、诚实说明未运行测试被误判 `TEST_REQUIRED`、禁止创建子 Agent 的查询被误判为修改任务。
- 变更：所有 `RULE` Trial 使用唯一工作区并注入无密钥的确定性 README、AGENTS、tests 说明夹具；评分时必需工具只有 `COMPLETED` ToolCall 才算命中，必需/禁止工具与回答规则升级为硬门禁。Starter Pack 升级至 `1.4.0`，为工具发现、AgentTeam 和 Harness Loop 收紧提示词并校准调用、输出与模型延迟预算，移除“禁止静默覆盖”被裸字符串反向误杀的规则。
- 思路：本次修复区分模型真实越界、运行时合同误分类、空工作区 Fixture 和机械字符串评分，不通过简单降低阈值制造通过。Starter Pack 安装仍不覆盖已有同名 Case；本机历史官方 Case 会在重跑前通过现有 Case API 显式同步到 1.4.0，保留启用状态。数据库、OpenAPI 路径、Sandbox 隔离实现、阶段范围和产品站点能力未变，因此迁移、OpenAPI、`docs/docker-sandbox.md`、`docs/phases.md` 与 `paicli-site/README.md` 不适用；运行语义已同步 README 与 `docs/architecture.md`。
- 验证：Completion Contract、Evaluation Service、Starter Pack 与 RULE Fixture 定向测试共 16 个通过；继续执行全量测试、构建、本机重启与 8 套件回归，最终结果在本工作项后续补记。

### 评测中心默认停用套件启动修复

- 变更：评测中心按套件实际启用 Case 数决定运行入口；官方 05 Plan、06 Context/Memory、07 AgentTeam 等默认停用套件显示“先启用用例”，点击会展开案例列表并说明需先满足前置条件，不再提交必然返回 `evaluation suite has no enabled cases` 的空 Execution。
- 思路：高级案例默认停用是隔离缺失 Plan、Memory 或 AgentTeam 前置条件的安全边界，不能为消除报错而全量强制启用；前端增加预检和引导，后端继续拒绝无启用 Case 的直接 API 请求。数据库、OpenAPI、Sandbox、阶段范围与产品站点均未改变，因此迁移、Store 测试、OpenAPI、`docs/docker-sandbox.md`、`docs/phases.md` 和 `paicli-site/README.md` 不适用；运行说明与架构边界已同步 README 和 `docs/architecture.md`。
- 验证：`node --check paicli-server/src/main/resources/static/app.js` 与 `git diff --check` 通过，新增静态资源集成断言覆盖无启用 Case 的前端预检。定向 Spring 集成测试第一次完成 13 个用例并仅因中文响应字符集使新增文案断言失败；改为 ASCII 逻辑断言后，重跑受正在运行服务锁定 `paicli-server/target/paicli-server-0.6.0-SNAPSHOT.jar` 影响，无法完成干净构建，故不声明 Maven 测试通过。

### Context Cache 效率量化与 RAG Rerank/Eval

- 变更：`model_usage` 通过兼容迁移 42 增加 `reusable_prefix_tokens` 与 `ttft_ms`；模型调用把 Context Manifest 的稳定前缀和首个流式 delta 延迟持久化。Usage API 与 Console 新增 Reusable Prefix Ratio、Cache Hit Ratio、平均 input/cached/uncached input、TTFT 和估算费用/成功 Run。
- 变更：Knowledge 检索从直接 RRF Top-K 调整为 BM25/Embedding → RRF Top-30 → 确定性跨特征 reranker → Top-K；SearchHit 返回 rerank 分。新增 `POST /v1/knowledge/documents/evaluations`，用调用方标注 Case 对 BM25、Embedding、RRF、RRF+Rerank 做消融并计算 Recall@5/10、MRR、nDCG@10、Citation Hit Rate、Answer Grounded Rate。
- 思路：本工作项只纵向优化 Context 缓存效率的可测量性和 RAG 排序/评测，不增加 Plan、Memory、Multi-Agent、Sandbox 或部署功能。TTFT 缺少流式 delta 时记 0 且不进入平均；Reranker 使用本地确定性特征，保证单机环境无需新增密钥也能复现，未来可在同一候选边界替换为模型实现。产品站点能力与 Sandbox 行为未变，`paicli-site/README.md`、`docs/docker-sandbox.md` 不适用；API、运行架构和阶段完成度已同步 README/OpenAPI 注解、`docs/architecture.md` 与 `docs/phases.md`。
- 验证：`mvnw -pl paicli-server -am -DskipTests compile` 通过；Context、Knowledge、Retrieval Eval、SQLite 定向测试共 58 个通过；`git diff --check` 通过。全量 `mvnw test` 两次均在运行中出现既有测试/依赖 `.class` 消失导致的 `NoClassDefFoundError`（新增测试已通过、无断言失败）；`mvnw clean test` 又因当前运行中的 Java 服务占用 `paicli-server-0.6.0-SNAPSHOT.jar` 无法完成 clean，未擅自停止用户进程。未运行真实模型/业务标注集，因此不声明缓存命中或 Recall 的虚构提升数字。

## 2026-08-14

### Docker Hub Java 基础镜像连接故障规避

- 变更：Sandbox Java 17 构建阶段从 Docker Hub 的 `eclipse-temurin:17-jdk` 切换为 Microsoft Container Registry 的 `mcr.microsoft.com/openjdk/jdk:17-ubuntu`，并复制其 `/usr/lib/jvm/msopenjdk-17` 到既有的运行时 `JAVA_HOME`。
- 思路：构建失败发生于连接 `auth.docker.io` 获取 OAuth token，尚未执行 Dockerfile 指令；Sandbox 同时依赖 Microsoft .NET SDK，统一到同一 Registry 可移除不必要的 Docker Hub 认证路径，同时保持完整 JDK。
- 验证：在禁网临时容器中确认 Microsoft OpenJDK 镜像的 Java 17 路径；随后重建 `paicli-sandbox-agent:0.6.0` 并验证 Java、Maven、Node/npm、Python/venv、Git、PowerShell、curl 和 unzip。API、数据库 Schema、阶段范围与产品站点未变，OpenAPI、架构和阶段文档不适用。

### Docker Sandbox apt 下载可靠性

- 变更：Sandbox 镜像构建改用 HTTPS Debian 源，并为 `apt-get update` 配置 20 秒请求超时与 3 次有限重试；避免 HTTP 主仓库响应截断时以 exit code 100 中断镜像构建。
- 思路：工具链包名和基底镜像均可用，故只加固外部依赖下载边界，不改变镜像内工具集、运行时权限或 Sandbox 执行语义。
- 验证：在 `mcr.microsoft.com/dotnet/sdk:8.0` 临时容器中验证 HTTPS `apt-get update` 成功；随后完成真实 Docker 镜像构建，并在禁网容器中验证 Java、Maven、Node/npm、Python/venv、Git、PowerShell、curl 和 unzip。API、数据库 Schema、产品站点与阶段范围未变，OpenAPI、架构和阶段文档不适用。

## 2026-08-14

### Harness budget and collaboration completion hardening
- Change: budget exhaustion now fails the Run rather than committing a partial-result assistant message as `COMPLETED`; the failure event retains the complete step, token, tool-call, and elapsed-time snapshot. Collaboration completion contracts now derive from the structured current task envelope rather than its historical digest, preventing historical analysis notes from downgrading a repair task to `TEXT_ONLY`. Docker Sandbox startup now probes the configured image for Bash, Git, Maven, Node/npm, Python 3, and PowerShell Core in a network-disabled disposable container and fails early with a rebuild instruction when a stale image is missing a required runtime.
- Rationale: a 50-step collaboration Run exhausted its budget while debugging temporary scripts, yet a `TEXT_ONLY` misclassification allowed the Harness to report it as completed. The same Run used an old Sandbox image without Node, which pushed the model away from the existing test suite. This change makes all three conditions explicit and terminal instead of allowing a false success signal.
- Verification: targeted RunProcessor, CompletionContractService, DockerSandboxDriver, and SqliteRuntimeStore tests cover the regression, including idempotent historical correction of the linked collaboration activity. README, architecture, and Docker Sandbox documentation are updated; phases, REST/OpenAPI, and product-site documentation are not applicable because no user-facing API or delivery scope changed.

## 2026-08-14

### Docker Sandbox 内置工具链补齐

- 变更：Sandbox 镜像在构建阶段安装 Maven、Node.js/npm、Python 3/pip/venv、Git、Bash、curl 和 unzip，并保留 Java 17 与 PowerShell Core；运行时为 `/home/sandbox` 增加可写 tmpfs，同时设置 HOME、XDG 和 .NET CLI 缓存目录，避免只读根文件系统导致 PowerShell 或构建工具启动失败。
- 思路：把稳定的工具链放进版本化镜像，把缓存放进 Run 生命周期内的临时目录；继续保留只读根文件系统、内部网络、非 root 用户和能力收缩约束。
- 验证：Sandbox Agent 模块 Maven 测试通过（common 3 项、sandbox-agent 4 项），`git diff --check` 通过；本机 Docker 构建因容器访问 Debian 软件源在超时内未完成，尚未用新镜像做端到端版本探针，需网络恢复后执行构建与容器验证。

## 2026-08-11

### 协作会话续作状态回填

- 变更：普通会话入口新建 Run 与终态 Run 重试时，若 Session 已关联协作任务，自动写入 `collaboration_task_runs` 并标记为 `SESSION_CONTINUATION`；待验收的当前阶段及根任务同步恢复为 `IN_PROGRESS`。新增 schema migration 41，在服务启动时补偿历史同会话但未关联的 Run，再以完整任务树的活跃 Run 恢复根任务状态；协作任务详情因此可返回并前端可显示所有运行中的续作 Run。OpenAPI、README 与架构文档同步说明该 API 语义。
- 思路：本机任务 `task_30f3f61bee4f46aa` 仍为 `IN_REVIEW`，而 `run_25e90df011ce4030` 处于 `QUEUED` 且使用同一协作 Session，却未出现在 `collaboration_task_runs`。既有修复只处理 Trigger 创建的 Run 和启动时已经关联的活跃树，普通聊天入口的续作没有调用关联逻辑，因此状态修复并未被撤销，而是遗漏了一条创建路径。
- 验证：`CollaborationServiceTest`、`CollaborationStoreTest` 与 `WebSecurityIntegrationTest` 定向回归通过（共 61 项）；覆盖会话续作关联、当前/根任务从待验收恢复进行中、启动补偿历史漏关联记录和 `POST /v1/sessions/{sessionId}/runs` 的实际接口行为。全量 `clean test` 的 Surefire 报告共 304 项、零失败零错误；外层命令在 Maven 收尾时达到桌面 124 秒时限，随后 `package -DskipTests` 构建成功。新 JAR 已实际启动，`GET /v1/system/info` 返回 200；本机 `run_25e90df011ce4030` 已回填为 `SESSION_CONTINUATION`，其本身在本次启动前已经 `COMPLETED`，故根任务维持 `IN_REVIEW` 符合状态机。未修改 Sandbox、阶段范围或产品站，相关文档不适用。

### 联网端点诊断与中文输出约束加固

- 变更：将 `PAICLI_WEB_ENABLED` 的默认值改为 `false`，与可选联网的产品边界保持一致；搜索端点连接失败或超时时，`web_search` 返回包含端点和 `PAICLI_WEB_SEARCH_URL` 的明确诊断，不再只暴露 `ConnectException`。新增可选 `PAICLI_WEB_SEARCH_ENGINES`，将指定引擎透传给 SearXNG，以避开默认聚合中被限流、验证码或超时的引擎。`ContextManager` 在动态上下文末端、当前 Run 的 assistant/tool 消息之前重复最终语言约束，避免英文工具输出或历史回答造成中文任务的中英文夹杂，同时不改变模型最后一条实际会话消息。移除 `WebProperties` 的重载构造函数，保留唯一的 record 规范构造函数，使 Spring Boot 能继续进行 `@ConfigurationProperties` 构造绑定；同时修正 `mvnw.cmd` 对普通 `.m2` 目录空符号链接目标的错误索引。
- 思路：最新 `run_44dbf4e0fd52434c` 的三次 `web_search` 和一次 `web_fetch` 都记录为 `ConnectException`，而本机 `127.0.0.1:8888` 未监听；根因是配置的本地 SearXNG 容器已停止，并非 Web 工具未注册。恢复容器后默认搜索仍为空，日志显示多个默认引擎被验证码、限流或超时影响，而单独使用 Bing 可返回结果，因此提供受配置控制的引擎选择。该 Run 的中文任务曾直接生成英文段落，说明稳定前缀中的语言指令距离当前工具上下文过远，因此在每轮请求尾部追加同一硬约束。新增重载构造函数后，Spring 无法再唯一识别 record 的构造绑定入口并尝试无参实例化，故打包服务启动失败；改为让调用方传完整参数以恢复绑定。随后发现 Wrapper 在普通 `.m2` 路径上访问 `$null.Target[0]`，使打包命令在 Maven 启动前失败，故改为先判空后再读取链接目标。
- 验证：`mvnw.cmd test -pl paicli-server -am -Dtest=PaiCliServerApplicationTest,ContextManagerTest,ToolCatalogTest,WebAccessServiceTest -Dsurefire.failIfNoSpecifiedTests=false` 通过（20 项），其中 `PaiCliServerApplicationTest` 已实际创建完整 Spring ApplicationContext，覆盖 record 配置绑定与所有 Server Tool Provider 装配。调整语言约束位置后，`mvnw.cmd clean test` 全量通过（`paicli-common` 3 项、`paicli-server` 294 项、`paicli-sandbox-agent` 4 项）；`mvnw.cmd clean package` 全量通过。使用打包后的 Server JAR 以 local Sandbox 启动，日志确认 ApplicationContext 与 Tomcat 8080 完成初始化，`GET /v1/system/info` 返回 200。`start-local.ps1` 的重复 Maven 构建在当前桌面子进程的受限网络环境中无法下载父 POM，不影响已获授权的完整打包，也不影响 JAR 实际启动。本地 SearXNG 恢复后 HTTP 探测返回 200，指定 Bing 曾返回 10 条结果，但后续同一引擎探测为空，确认其上游可用性仍有波动。未修改 REST/OpenAPI、Sandbox、阶段范围或产品站，相关文档不适用。

## 2026-08-10

### 撤回 PRD Analysis Agent

- 变更：按确认范围移除 PRD Analysis Agent 的后端 API、调度器、Store、工具、配置、种子 Skill 模板、Console 页面和测试；README、架构及阶段说明同步回退。已删除本地数据库中的 PRD 任务、来源、分块、节点、问题、校验、运行绑定、产物索引与内部运行会话，并物理移除 10 张 PRD 表、40/41 迁移记录、3 个专用 Profile 与 3 个本地 Skill 模板目录；保留用户原始上传会话及其他既有功能数据。
- 思路：该能力整体废弃，使用提交级回退恢复到引入 PRD 垂直切片前的代码边界，避免仅隐藏入口而遗留可调用 API 或后台 Worker。
- 验证：数据库 PRD 业务记录与内部会话计数均为 0，`PRAGMA foreign_key_check` 通过；`node --check paicli-server/src/main/resources/static/app.js` 与 `git diff --check` 通过。Maven 回归未能完成：全量 `mvnw.cmd test` 在 124 秒内无输出而超时，随后 Wrapper 报 `Cannot start maven from wrapper`，系统未发现可替代的 `mvn` 命令；未强制停止本地服务或改写 Wrapper。

本文件记录 PaiCLI Platform Lite 从初版到当前 master 的主要演进、优化思路和后续变更记录规范。内容以 Git 提交历史、`README.md`、`docs/phases.md` 和架构说明为依据，用于项目总结、学习复盘和后续交接。

## 2026-08-13

### 私有仓库 SWE-bench 风格执行式评测

- 变更：Evaluation Case 增加向后兼容的 `RULE/REPOSITORY` 类型以及 fixture 引用/摘要、Grader Spec 和 Patch Policy；迁移 39 为 Trial 增加不可变 Case 快照、为 Baseline 增加 Grader 详情。现有 Starter Pack 与规则评分不变。
- 变更：新增 `RepositoryEvaluationService`。仓库 Trial 在 Run 入队前从 `data/evaluation-fixtures/{ref}/workspace` 复制独立工作区并校验覆盖公开工作区与隐藏测试的 fixture SHA-256，评分前再次拒绝 fixture 漂移；Run 终态后比较文件摘要，拒绝符号链接、Agent 伪造的保留 grader 目录、禁止路径、超量文件和超大 Patch（新增、修改、删除均计入字节预算），再在新的 grader 副本中应用差异并从 fixture `hidden/` 注入模型不可见文件。
- 变更：grader prepare、FAIL_TO_PASS 与 PASS_TO_PASS 都先使用固定 idempotency key 持久化为内部 ToolCall，再按顺序进入现有 Sandbox；刷新或恢复复用已完成 ToolCall。报告分离 `resolved`、Patch 完整性、安全、预算和 Run 完成状态，增加 resolved Trial、稳定 Case 与每 resolved Token 汇总；仓库 Baseline 不再把具体工具路径作为功能正确性的替代指标。
- 变更：新增 fixture inspect API、Console 仓库 Case 表单及 F2P/P2P/改动文件报告；同步 README API/数据目录/运行语义、`docs/architecture.md` 和 `docs/phases.md`。Sandbox 行为边界本身未改变：仓库 Grader 只在已有 Docker `execute_command` 能力中运行，Local 仍拒绝宿主命令，因此 `docs/docker-sandbox.md` 不适用；未修改产品站，`paicli-site/README.md` 不适用。
- 思路：只借鉴 SWE-bench 的固定缺陷快照、隐藏测试、FAIL_TO_PASS、PASS_TO_PASS 和执行式判分，不接入或捆绑公开 SWE-bench Verified，也不宣称生成官方成绩。功能、安全、稳定性与成本保持可分别解释，避免“调用了指定工具/回答包含关键词”被误当成代码已经修复。
- 验证：新增 `RepositoryEvaluationServiceTest`，覆盖完整 fixture 摘要、Run 前及评分前漂移拒绝、Run 前工作区准备、不可变 Trial 快照、Agent/Grader 工作区隔离、隐藏文件注入、F2P/P2P 顺序、Grader ToolCall 持久化和 resolved 汇总；定向 Evaluation/Store 测试通过。以 `paicli.sandbox-mode=local` 执行最终全量回归，Common 3、Server 253、Sandbox Agent 4，共 260 项测试全部通过；前端 `node --check` 与 `git diff --check` 通过。默认 Docker 模式因当前终端没有 Docker CLI 而使 13 个 Spring 上下文测试失败；标准 `clean test`、`clean package` 以及非 clean `package` 均因正在运行的既有 Server 进程锁定 `paicli-server-0.6.0-SNAPSHOT.jar`，分别无法清理或在 Spring Boot repackage 阶段重命名该文件，未擅自停止进程。真实 Docker Grader 端到端验证因此尚未执行。

### Docker Sandbox 工具链、临时 HOME 与网络隔离增强

- 变更：Docker Sandbox 默认网络从共享 internal network 收紧为 `none`，控制面继续使用 `docker exec` + 容器 loopback；如配置自定义网络，启动时验证其 Docker `Internal=true`，非内部网络拒绝启动，不存在时只以 `--internal` 创建。
- 变更：容器显式启用 Docker init 和 UID/GID `10001`，只读根文件系统之外提供容量可配的 `/tmp` 与私有 HOME tmpfs，并增加共享内存限制；命令环境把 Maven、Gradle、npm、pip、NuGet、Go/Rust 缓存定向到临时 HOME，Python/XDG/.NET 临时数据定向到 `/tmp`，不继承 Sandbox Agent Token。
- 变更：Sandbox 镜像由 JRE 升级为 JDK 17，并补齐 Maven、Node.js/npm、Python/pip/venv、Git、curl 和 unzip；镜像、网络、CPU、内存、PID、tmpfs、共享内存与超时通过 `PAICLI_DOCKER_*` 环境变量外部化。
- 思路：保留 PaiCLI 现有 ToolCall 持久化、Approval、幂等恢复和每 Run 容器模型，只增强执行底座；本次不引入 OpenSandbox/Kubernetes，不开放无策略外网，也不把依赖缓存写入持久化 workspace。
- 验证：新增 `DockerSandboxPropertiesTest`，扩展 `DockerSandboxDriverTest` 与 `SandboxSecurityTest`，定向执行 Server 6 项、Sandbox Agent 4 项测试全部通过；使用 `paicli.sandbox-mode=local` 排除既有 `SqliteRuntimeStoreTest` 后执行其余全量回归，Common 3、Server 204、Sandbox Agent 4 项全部通过；Sandbox Agent 与 Common `package -DskipTests` 通过，`git diff --check` 通过。标准 `clean test` 因当前运行中的 Server 锁定 JAR 无法完成 clean；默认 Docker 模式全量测试还会因本终端没有 Docker CLI 失败，现有 Store 迁移断言期望 39–40 而当前代码仅注册到 38，均未在本工作项中扩大修改范围。当前终端也无法重建镜像验证实际 apt 工具版本与容器挂载，因此真实 Docker 端到端验证尚未执行并已在 README/Docker 文档明确记录。同步更新 `.env.example`、README、`docs/architecture.md`、`docs/phases.md` 与 `docs/docker-sandbox.md`；未修改数据库 Schema、REST API/OpenAPI 或产品站展示，迁移、Store 行为、OpenAPI 与 `paicli-site/README.md` 不适用。

## 2026-08-09

### 技术架构与面试材料同步：远端 master 协作连续性与 Harness 证据闭环

- 变更：以远端 `origin/master` 的近期提交（截至 `2520438`）和本文件 2026-08-08/09 条目为事实来源，更新根目录《PaiCLI Platform Lite 技术架构与面试讲解》和《PaiCLI Platform Lite 技术架构与面试指南》。补齐 ExpertThread 的逻辑线程边界、按专家归属的 resume/digest、活跃 Run 评论的原子竞态处理与 `archived` 语义；补齐 `WAITING_EXTERNAL` / `WAITING_AGENT` 的 deferred `get_agent_result` 恢复链路。
- 变更：材料同步 Completion Contract、结构化 ToolResult metadata、保守测试证据分类、统一 AgentResult/DeliveryManifest 证据源、`done_criteria` 的显式 `criterion_evidence` 状态，以及 WorkingPlan 的常驻 allowlist 与触发边界；修正迁移清单至版本 39，并删除“文件/命令/测试证据仍待自动归集”的过期表述。
- 思路：面试材料必须和当前持久化事实口径一致，重点区分“模型自述”“结构化执行证据”“确定性完成验证”以及“任务级连续性”和“专家级连续性”；不把尚未进入文档范围的 PRD 分析在制改动写入材料。
- 验证：执行远端 `master` 提交、近期 `changeLog.md` 条目和两份材料的关键词差异扫描；执行 `git diff --check`。本次仅修改说明文档和本 ChangeLog，不涉及运行行为、架构边界、REST/OpenAPI、配置、Sandbox、阶段范围或产品站，因此 README、`docs/architecture.md`、`docs/phases.md`、`docs/docker-sandbox.md`、OpenAPI 与 `paicli-site/README.md` 不适用。
### WorkingPlan 基础能力常驻与明确触发规则

- 变更：`ContextManager` 在 Agent Profile 配置非空业务 Tool allowlist 时，额外保留 `update_working_plan`，确保 WorkingPlan 作为 Run 内部 Harness 能力不会被业务工具白名单过滤；未扩大其他 Core Tool 权限。
- 变更：Agent 通用 System Prompt 明确 WorkingPlan 的创建条件、简短记录与 `evidenceRefs` 更新要求，以及工具失败、验证失败、用户补充要求或执行方向变化时先更新再继续的规则；简单单步/只读/可直接回答任务仍不要求创建计划。
- 思路：继续由模型在正常 Model Decision 中自主判断 WorkingPlan，保持 Act → Observe → Replan 流程，不新增 Plan 阶段、Run 状态、独立 Model Call、数据结构或 Tool Schema。
- 验证：新增 `ContextManagerTest` 覆盖无 allowlist、有业务 allowlist 时的 `update_working_plan` 可见性，以及其他基础工具不被额外放开；新增 System Prompt 触发规则断言。同步修正已有迁移断言以包含当前代码中的迁移 40，随后执行全量 `clean verify`。同步更新 README 与架构说明；未修改 API、配置、Sandbox、Formal Plan 或产品站，`docs/phases.md`、OpenAPI、`docs/docker-sandbox.md` 与 `paicli-site/README.md` 不适用。

### Completion Evidence 二次复核：命令 mutation 证据统一与测试分类再收紧

- 变更：新增 `WorkspaceMutationEvidence` 和 `RunEvidence.workspaceMutations`；`AgentResultService`、SQLite 终态 delegation envelope、`AgentResultValidator` 与 `RunVerificationService` 统一消费/传递 `workspace_mutations`，命令修改未知具体文件时不伪造 `files_changed`。`TestCommandClassifier` 拒绝换行、单独后台 `&`、`-DskipTests=true`、pytest `--collect-only` 和 cargo `--no-run`。
- 思路：Child CompletionVerifier 与 Parent AgentResultValidator 必须基于同一份结构化证据判断 Completion Contract；测试证据继续采用宁可漏报、不可误报的保守边界。未改变数据库 Schema、REST 路径请求响应或产品站能力，OpenAPI 与产品站文档不适用。
- 验证：新增并通过 21 项定向测试，覆盖分类器绕过、RunEvidence → AgentResultService → AgentResultValidator 端到端命令 mutation，以及 SQLite delegation envelope；随后执行全量 `clean test` 与 `clean package`。

### Completion Evidence 复核收尾：Deferred 批处理、mutation 边界与复合测试命令

- 变更：将 `get_agent_result` 从只读工具集合和 `RunProcessor` 只读并行前缀中排除，保证会停放/唤醒父 Run 的调用先独立持久化；`RunEvidenceCollector` 不再把已识别测试命令的 workspace fingerprint 当作最后一次 mutation，非测试命令的显式 workspace mutation 可作为 `MUTATION_REQUIRED` 证据；`TestCommandClassifier` 拒绝 `||`、`;`、管道以及测试命令后的尾部命令，避免用复合命令最终的 0 退出码生成伪造 PASSED TestEvidence。
- 思路：Completion Contract 必须区分源码/产品变更、测试生成物和可能改变父 Run 生命周期的外部等待；验证器消费统一的 mutation evidence，而不是只看 `write_file`，并让测试证据的退出码与最后一个实际测试 invocation 对齐。数据库 Schema、REST 请求/响应和 OpenAPI 未变，产品站未变。
- 验证：定向 Maven 回归测试 23 项全部通过（`DeferredAgentResultTest`、`RunEvidenceCollectorTest`、`RunVerificationServiceTest`、`TestCommandClassifierTest`）；`git diff --check` 通过。Maven Wrapper 在当前 PowerShell 仍有既有解析问题，测试使用同版本缓存 Maven 3.9.9 并包含 `paicli-common` 上游模块执行。

### Harness Loop v2 · PR：Completion Contract、执行证据与 Deferred get_agent_result（9 个提交）

- 变更（Commit 1·结构化工具证据）：迁移 39 新增 `tool_calls.result_metadata_json`；LocalSandboxDriver 与 Sandbox 代理的 `write_file` 统一返回 `path/changed/beforeSha256/afterSha256/bytesWritten`，`execute_command` 继续带 `exitCode/timedOut/shell/cwd/durationMs`；ToolResult.metadata 持久化，证据不再依赖解析 stdout 文本。
- 变更（Commit 2·测试族分类）：新增 `TestFamily` 与 `TestCommandClassifier`，删除 `contains("mvn")/contains("check")` 粗糙启发式；`mvn compile`、`./check-status.sh` 不再误判为测试。
- 变更（Commit 3·证据收集）：新增 `RunEvidenceCollector` 与 `RunEvidence/FileEvidence/CommandEvidence/TestEvidence/ArtifactEvidence/TestStatus`；`lastMutationOrdinal` 仅由真实 write_file 变更决定，供“最后 mutation 之后测试必须通过”判定。
- 变更（Commit 4·完成合同）：新增 `CompletionMode`、`RunCompletionContractRecord` 与 `run_completion_contracts` 表；`CompletionContractService` 按 DelegationEnvelope → PlanStep → Root 保守分类器 → WorkingPlan completion 的可靠性顺序建立合同，只可加强不可被模型削弱；`CompletionRequirementClassifier` 只识别高置信度命令式任务，问答默认 TEXT_ONLY；`update_working_plan` 增加可选 `completion` 结构化声明。
- 变更（Commit 5·合同驱动验证）：`RunVerificationService` 重构为纯逻辑 `verify(run, finalAnswer, contract, evidence)`，按 TEXT_ONLY / MUTATION_REQUIRED / TEST_REQUIRED / MUTATION_AND_TEST 验证；不同 TestFamily 互不覆盖，required tests 必须在最后一次真实 mutation 之后通过。
- 变更（Commit 6·AgentResult 证据闭环）：新增 `AgentResultService` 自动归集 `files_changed/commands_executed/tests/artifacts/completion_contract/evidence`；`AgentResultValidator` 增加 contract-aware 校验；`DeliveryManifestService.recordStageDelivery(taskId, stage, runId)` 与 `WorkspaceMergeService.ChildChanges.of` 复用统一证据。
- 变更（Commit 7·Deferred get_agent_result）：`ToolCallStatus.WAITING_EXTERNAL`；`tool_calls` 增加 `wait_kind/wait_ref/waiting_since`；`get_agent_result` 在 child 未终态时返回 deferred metadata，RunProcessor 标记 WAITING_EXTERNAL 且 Parent 进入 WAITING_AGENT（不追加最终 tool 消息、不轮询）；child 终态由 `DeferredAgentResultService` 原子完成原始 ToolCall、追加 tool 消息并重排队 Parent。
- 变更（Commit 8·恢复/竞态/审计）：`@PostConstruct` 启动恢复扫描 WAITING_EXTERNAL CHILD_RUN（child 已终态立即 resolve）；Lost Wakeup 双边幂等保护；事件 `tool.deferred / tool.deferred.resolved / agent.result.validated / run.evidence.collected / run.completion_contract.created / strengthened`。
- 变更（Commit 9·文档与回归）：README、docs/architecture.md、docs/phases.md、docs/docker-sandbox.md、changeLog 同步；`.\mvnw.cmd clean test` 全量通过（paicli-common + paicli-server + paicli-sandbox-agent）。
- 思路：Harness 从“行为驱动”升级为“任务要求驱动”——模型负责策略与代码生成，系统环境提供真实执行事实（ToolCall/ToolResult/Workspace/Artifact），CompletionVerifier 用机器可验证的合同 vs 证据决定是否完成；自然语言业务语义仍由 Parent Reviewer/人工/真实测试负责，不引入 Completion Judge LLM，不假装自动理解所有 done_criteria。
- 验证：`.\mvnw.cmd clean test` 全量通过（含新增 TestCommandClassifierTest、RunEvidenceCollectorTest、CompletionRequirementClassifierTest、CompletionContractServiceTest、RunVerificationServiceTest、AgentResultValidatorTest、AgentResultServiceTest、DeferredAgentResultTest、LocalSandboxDriverTest/SqliteRuntimeStoreTest 扩展）；OpenAPI 无 REST 路径变更（get_agent_result 行为变化为 Server 内部协议，不改变请求/响应 schema），`paicli-site/README.md` 无产品可见能力变更，故二者本次不适用；`git diff --check` 通过。
### Harness Loop v2 审查修复：真实变更证据、原子 Deferred 停放与严格完成门禁

- 变更：修复 Docker Sandbox `write_file` 在写入后才计算 before hash 的问题；`execute_command` 持久化 workspace fingerprint，RunEvidence 只接受明确的变更证据，缺少 metadata 时不猜测 `changed=true`。
- 变更：新增 `parkDeferredToolCallAndWaitParent` SQLite 原子操作，在同一事务内提交 ToolCall `WAITING_EXTERNAL`、父 Run `WAITING_AGENT` 和事件，消除 child 终态与 parent 停放之间的 Lost Wakeup 窗口。
- 变更：Formal PlanStep 按绑定 `run_id` 参与 Contract 推导；WorkingPlan completion 更新触发 strengthen；预算停止先经过 CompletionVerifier，合同未满足时进入 FAILED。
- 变更：测试命令按 shell operator boundary 与 executable/参数保守分类，补充 Gradle check、pnpm/yarn、dotnet test，并拒绝 `echo test`、`echo junit`、`test-data.sh` 等假阳性；AgentResult 不再允许普通 command 替代 PASSED TestEvidence。
- 变更：RunEvidence 增加业务 Artifact 视图，`tool_result` 不再进入 AgentResult 或 DeliveryManifest 的业务交付清单；同步 README、架构、阶段、Docker Sandbox 和 Agent prompt 规则。
- 思路：Completion Contract -> Real Evidence -> Deterministic Verification 保持单一闭环，恢复与交付路径复用同一证据源；数据库 schema、REST API/OpenAPI、配置和产品站能力未变，不适用对应文档同步。
- 验证：完整 Maven 测试通过（common 3、server 281、sandbox-agent 4，BUILD SUCCESS）；`git diff --check` 通过。Maven Wrapper 在当前 PowerShell 直接调用存在既有解析问题，测试使用同版本已缓存 Maven 二进制完成。

## 2026-08-08

### ExpertThread：同一专家在协作任务内的逻辑线程 + 模型执行期间新评论竞态保护

- 变更：新增轻量 `ExpertThread`（迁移 38）：`collaboration_expert_threads`（`root_task_id + agent_profile_id + thread_role` 唯一确定一个逻辑线程）+ `collaboration_expert_thread_runs`（thread_id + run_id + ordinal）。新增 `ExpertThreadService`（`getOrCreate` 幂等 / `attachRun` / `findByRun` / `refreshDigest`）与 `ExpertThreadDigestBuilder`。`CollaborationService.trigger` 在确定 agent 与根任务后先 getOrCreate 线程，新 Run 创建后 attachRun；阶段派发子 Run 同样绑定对应专家线程（非致命，异常只记录警告不阻断协作）。
- 变更：终端 Run 永远不复活。同一专家后续再次执行创建新 Session + 新 Run，并挂到原 ExpertThread；不同根任务、不同专家、不同 role 各自独立线程，互不串线。新 Run 不加载旧 Run 完整历史，只在输入注入 `<expert_thread_resume>` 紧凑摘要（最新 Run 状态/摘要、已完成/剩余工作、blockers、changed files、artifact refs、test 报告引用、最新人工指令），不含 ToolResult 全文、Artifact 正文、reasoning 与全量旧对话；需要具体内容时由模型按需 `read_file/read_artifact`。Leader 线程（role=LEADER）继续走既有 TaskDigest，不重复注入 resume。
- 变更：`CollaborationService.onRunTerminal` 末尾统一刷新 ExpertThread Digest（AgentResult/交付清单已落库后再构建），保证顺序为 AgentResult/DeliveryManifest → Digest。
- 变更：Active Run 竞态保护。`ContextManager.PreparedContext` 新增 `maxMessageSequence`（本次模型上下文构建时 Session 最大 message sequence）；`RunProcessor` 在模型返回无工具调用的最终回答后、提交 COMPLETED 前重新查询 `store.maxMessageSequence(sessionId)`，若大于 context 构建值，说明模型执行期间有新用户输入（例如评论被注入活跃 Run 会话），此时：持久化 `run.new_input_during_model` 事件、把当前模型回答保留为 assistant 中间消息、Run 重新 QUEUED、下一轮必含新增消息；无新增消息时保持原完成流程。
- 变更：`GET /v1/collaboration/tasks/{id}` 响应新增 `expertThreads`（每个线程含 threadId/agentProfileId/threadRole/digestJson/latestRunId 与绑定 Runs 的 ordinal/实时状态）；Console 执行层新增“专家线程”分组展示（`#序号 状态`，可直接打开会话），OpenAPI 描述同步。
- 思路：现有协作层用 TaskDigest 服务 Leader 复唤醒，但没有“同一专家多次执行之间的逻辑连续性”。ExpertThread 把 `root task + agent + role` 作为唯一键，保持三层分离：Thread=逻辑连续、Session=单次执行上下文、Run=单次执行事实；Digest 只带引用不携带正文，避免把旧历史逐步重新塞回模型上下文（Session 复用会导致 Run-B1 50K + Run-B2 30K 逐轮膨胀）。竞态修复针对“Context 已构建→模型请求发送→用户追加评论→模型返回 Final→直接完成 Run”的漏消息窗口。
- 验证：`.\mvnw.cmd clean test` 全量 232 项通过（paicli-common 3 + paicli-server 226 + paicli-sandbox-agent 3）。新增测试覆盖：ExpertThread 幂等复用与 Worker 重启恢复、不同任务/专家/角色不串线、Digest 只含引用不含全文、trigger 绑定线程且二次触发复用同线程、专家 Run 注入 resume 而 Leader 不注入、`PreparedContext.maxMessageSequence`、RunProcessor 模型期间新评论不假完成并重排队（事件 + 中间 assistant 消息 + 再完成）、迁移版本 1–38；既有 `terminalRunCannotBeCompletedOrRequeuedAfterCancellation` 继续保证终端 Run 不回退。`node --check app.js` 通过，`git diff --check` 通过。本次未改 Sandbox 协议、Approval 核心、ToolRouter 核心、Plan 执行与 Memory 架构，故 `docs/docker-sandbox.md`、`paicli-site/README.md` 不适用；README、`docs/architecture.md`、`docs/phases.md` 与 OpenAPI 已同步。

### ExpertThread 评审修正：事务化竞态保护、阶段 Resume 注入、Digest 按专家归因、并发挂载与生命周期

- 变更（竞态事务化）：`RunProcessor` 不再“先查 sequence 再完成”，改为 `SqliteRuntimeStore.commitFinalAssistantAndComplete(..., expectedMaxMessageSequence)` 在单个 SQLite 事务内“比对最新 message sequence + 置 COMPLETED”：模型执行期间有新输入时整体回滚、不完成。新输入分支改由 `commitIntermediateAssistantAndRequeue` 单事务持久化 `run.new_input_during_model` 事件 + assistant 中间消息 + 重新 QUEUED，避免“中间回答已写、Run 卡旧状态”的崩溃恢复问题。评论投递侧新增 `appendUserMessageIfRunActive`（事务内重确认 Run 非终态再追加）：`CollaborationService.comment()` 改为“投递失败即回退创建新幂等 Trigger/Run”，消除 `hasActiveRunForTarget→deliverCommentToActiveRuns` 的 TOCTOU（Run 在判断后恰好终态时评论不再挂到已结束 Run 后面）。
- 变更（阶段 Resume）：`createAndDispatchSubtask` 调整为先 `getOrCreate` ExpertThread、构建含 `<expert_thread_resume>` 的阶段输入、再 `createOrGetDelegation`、最后 `attachRun`，因此 Leader 再次派遣同一专家时，阶段子 Run 启动即携带该专家线程的紧凑摘要（此前只在普通 trigger 注入）。
- 变更（Digest 按专家归因）：`ExpertThreadDigestBuilder` 的 `completed_work/remaining_work/blockers` 只统计 `assigneeId == thread.agentProfileId()` 的阶段，不再把其他专家的工作误当作本专家已完成；`changed_files` 不再扫描共享工作区当前文件列表，改为只消费该 Run 自己的 DeliveryManifest.changedFiles（run-scoped、可审计），避免共享 workspace 下文件归属串专家。
- 变更（并发与生命周期）：`CollaborationStore.attachExpertThreadRun` 改为单个 `BEGIN IMMEDIATE` 事务（校验 Run 未绑定其他线程 → ordinal 分配 → INSERT → 成功后才更新 `latest_run_id`）：重复挂载幂等、跨线程挂载抛 `IllegalStateException`，并发挂载 ordinal 唯一且 latest 与绑定一致。`collaboration_expert_threads.latest_run_id` 增加 `REFERENCES runs(id) ON DELETE SET NULL`；`SqliteRuntimeStore.deleteRuns` 删除终态 Run 时清理线程绑定、从剩余绑定重选 `latest_run_id`（无则置空）并把受影响线程摘要置空，避免注入已删除 Run/Artifact 引用，下一 Run 终态重建。
- 修复：`ExpertThreadDigestBuilder` 中经管道写入损坏的中文（`??????`）与省略号（`?`）恢复为正确文案（“专家 X 在任务「Y」中的持续工作”与 `…`），避免污染 `<expert_thread_resume>` 模型上下文。
- 修复（CI）：`mvnw` 在 git 中恢复可执行位（100644→100755），workflow 增加 `chmod +x mvnw` 步骤，`./mvnw -B -ntp clean verify` 不再因 Permission denied（126）跳过 Maven 编译与测试。
- 验证：新增/更新测试——`SqliteRuntimeStoreTest`（commitFinal 拒绝新输入、commitIntermediate 原子重排队、appendUserMessageIfRunActive 拒绝终态 Run）、`CollaborationStoreTest`（并发挂载 6 线程 ordinal 1–6 且 latest 一致、重复挂载幂等、跨线程挂载抛错）、`CollaborationServiceTest`（阶段派发注入 resume、评论投递失败回退新 Trigger）、`TaskDigestManifestTest`（Digest 只含本专家阶段与 Manifest 变更文件，其他专家文件/阶段不串入）。`.\mvnw.cmd clean test` 全量 239 项通过（common 3 + server 233 + sandbox-agent 3），`node --check app.js` 通过，`git diff --check` 通过。README、`docs/architecture.md`、`docs/phases.md` 已同步；`docs/docker-sandbox.md`、`paicli-site/README.md` 不适用（无 Sandbox/产品站变更）。

### PR #13 收尾：stale assistant 归档、单 Agent 使用 EXPERT 线程、done_criteria 接通 AgentResultValidator

- 变更（stale assistant 归档）：`SqliteRuntimeStore.commitIntermediateAssistantAndRequeue` 把模型执行期间生成的旧回答保存为 `archived=1` 的 assistant 消息——完整保留用于审计（`messages(sessionId)` 仍可见），但 `activeMessages(sessionId)` 与下一轮模型上下文自动排除；`run.new_input_during_model` 事件 JSON 增加 `staleAssistantArchived:true`。配套修正 `maxMessageSequence` 只统计 `archived=0` 的 active 消息（与 Context 视图一致），否则归档后的 stale assistant 会让后续完成判定永远“有新输入”而无法 COMPLETED。未在 ContextManager 增加任何特殊过滤，复用既有 archived 机制。
- 变更（单 Agent → EXPERT 线程）：`CollaborationService.resolveThreadRole` 只把真正的小队 Leader（TEAM leaderAgentProfileId）判为 `LEADER`，其余一律 `EXPERT`——包括单 Agent CollaborationTask 的被指派 Agent。因此单 Agent 任务 REQUEST_REWORK/再触发时，Run2 输入同时获得 TaskDigest（整体任务状态）与 `<expert_thread_resume>`（该专家自己之前的摘要），不再被误判为 LEADER 而漏掉 Resume。Team Leader 仍走 TaskDigest、不注入 Resume，行为不变。
- 变更（done_criteria 接通 Validator）：`DelegationToolProvider.result()` 从持久化的 `delegation.envelopeJson` 读取 spawn 时真实写入的 `done_criteria`，传给 `AgentResultValidator.validate(child, result, doneCriteria)`，并在 `get_agent_result` 响应中返回 `done_criteria` 与逐 criterion 的确定性证据状态（`EVIDENCED` 仅当子 Agent 显式提交 `criterion_evidence`（map 或 {criterion,evidence} 列表）且值非空；否则 `UNVERIFIED`）。不做 summary 关键词/字符串 contains 假装语义验证；`UNVERIFIED` 暂不改变 `validation.valid`，保留现有 delegation 行为。`ValidationResult` 新增 `criteria` 列表（保留无 criteria 的 2 参构造兼容）。修复 PlanStep fallback：`doneCriteriaJson` 按 JSON array 解析为 `List<String>`，不再 `List.of(rawJson)` 当作单元素。`done_criteria` 为空时 Validator 行为与旧版完全一致。
- 验证：新增/更新测试——`RunProcessorTest`（stale assistant 归档 + 第二轮 ModelRequest 含新评论、不含旧回答）、`SqliteRuntimeStoreTest`（原子重排队后 assistant 存在于全量消息、不在 activeMessages）、`CollaborationServiceTest`（单 Agent REQUEST_REWORK 触发 EXPERT 线程并注入 Resume、投递回退测试改用 EXPERT 角色）、`DelegationProtocolTest`（Validator criteria：无证据 UNVERIFIED、显式证据 EVIDENCED、无 criteria 旧行为不回归）、`DelegationToolProviderTest`（spawn 持久化 done_criteria、get_agent_result 返回 done_criteria + criteria、PlanStep doneCriteriaJson 数组 fallback）。`.\mvnw.cmd clean test` 全量 245 项通过（common 3 + server 239 + sandbox-agent 3），`git diff --check` 通过。README、`docs/architecture.md`、`docs/phases.md` 已同步；`docs/docker-sandbox.md`、`paicli-site/README.md` 不适用（无 Sandbox/产品站变更）。

### PR #13 收尾（二）：Stage done_criteria 接通 Validator、archived 隔离所有语义链路、空容器证据判定

- 变更（Stage done_criteria）：`CollaborationService.createAndDispatchSubtask` 不再向 `createOrGetDelegation` 传空 envelope `{}`，而是复用 `DelegationEnvelopeBuilder` 在派遣时构建持久化阶段 envelope：`done_criteria` 取阶段任务 `acceptanceCriteria`（按行拆分过滤空白），并写入 `collaboration_task_id`、`parent_run_id`。因此 `get_agent_result` 的 `doneCriteria(delegation)` 能读到阶段验收标准并传给 `AgentResultValidator`（spawn_agent 之外最常见的协作阶段派遣路径也接通）。envelope 构建失败时回退 `{}`，不阻断阶段派遣。
- 变更（archived 语义链路隔离）：新增 `SqliteRuntimeStore.activeMessagesForRun(runId)`（`archived=0`），并让所有“Agent 语义消费者”只读 active 消息、`messages()/messagesForRun()` 仅作为审计历史：`ExpertThreadDigestBuilder.runSummary` 改用 `activeMessagesForRun`；`DelegationToolProvider.result()` 的最终回答与 `latestAssistantAnswer` 改用 `activeMessages`；Memory 提取 `enqueueMemoryExtraction` 的 source snapshot 查询增加 `AND archived=0`，避免 stale assistant 被提炼成长期记忆。语义统一为：**archived = 保留事实，任何 Agent 语义链路都不能再消费**。
- 变更（空容器证据）：`AgentResultValidator` 判定 EVIDENCED 增加 `hasExplicitEvidence`——空字符串、空 List、空 Map 都视为无证据（UNVERIFIED），只有非空标量/数组/对象才算显式证据；仍不做 summary 关键词匹配，`UNVERIFIED` 不改 `valid`。
- 验证：新增/更新测试——`DelegationToolProviderTest.stageDispatchAcceptanceCriteriaBecomeDoneCriteriaForValidator`（Leader → createAndDispatchSubtask，验收标准“测试通过并保持接口兼容” → get_agent_result 的 `done_criteria` 与 `validation.criteria[0].status == UNVERIFIED`）、`DelegationProtocolTest.emptyCriterionEvidenceContainersAreNotEvidenced`（`""`/`[]`/`{}` → UNVERIFIED，`["test-report-1"]`/`"test-report-1"` → EVIDENCED）、`CollaborationServiceTest` 阶段派遣 mock 改为接受真实 envelope。`.\mvnw.cmd clean test` 全量 247 项通过（common 3 + server 241 + sandbox-agent 3），`git diff --check` 通过。README、`docs/architecture.md`、`docs/phases.md` 已同步；`docs/docker-sandbox.md`、`paicli-site/README.md` 不适用（无 Sandbox/产品站变更）。

### 协作执行层状态展示修正：失败 Run 的触发关系与待验收语义不再误导

- 变更：Console 执行层把根任务 Run 的展示标签从原始 relationship（TRIGGERED/HUMAN_ACTION/STAGE_BARRIER…）改为中文触发语义（触发执行/人工发起/阶段完成触发…），失败 Run 仍显示真实状态“失败”，不再让人把“TRIGGERED”误读为卡住的状态。
- 变更：任务处于 IN_REVIEW（待验收）且存在失败 Run 时，执行层提示文案改为“存在失败的执行（可查看协作动态原因），但阶段交付已就绪；请核验后验收或带原因要求返工”，不再声称“Leader 已形成最终结论”。
- 思路：真实案例中 Leader Run 因部署期缺失 prompts/base.md 资源而失败（旧运行实例类路径无该资源；重建并重启后的实例已具备），根任务按“失败但阶段已交付”回到待验收；用户重启后发现该 Run 仍显示“TRIGGERED · 失败”且任务状态不变，误以为没有恢复。TRIGGERED 只是 Task-Run 关联关系，真实状态是 FAILED（终态），待验收本就等待人工 ACCEPT，重启不会自动改变；本次仅修正前端展示，让失败原因与可执行动作一目了然。
- 验证：`node --check app.js` 通过，`git diff --check` 通过；纯前端展示变更，未改后端状态机与数据库（无相关 Java 测试），README 执行层说明已同步。

### 修复：阶段“空交付”被误判为已交付 + 失败 Leader 未把已交付任务送回重新验收

- 变更：`CollaborationService.hasStageDeliveryEvidence` 只把非 `tool_result` 类型的 Artifact 视为交付证据。此前 `artifactsForRun` 会统计 `tool_result`（read_file/execute_command 大结果外置）类型的只读物化产物，导致「只读不动手」的阶段 Run（真实案例 Stage 5 只读文件后直接宣布完成，没有写文件/发评论）也被判定为已交付并进入 IN_REVIEW、记录 DELIVERED 清单；现在这类空交付会正确走「无持久交付证据」分支，阶段与父任务置 BLOCKED，等待 Leader 重新派发。
- 变更：`CollaborationService.onRunTerminal` 根任务失败分支新增 `hasDeliveredStages` 判断：当 Leader Run 失败（例如工具调用循环被限流终止）但任务树中已有 IN_REVIEW/DONE 的已交付阶段时，根任务回到 IN_REVIEW（重新验收），而不是一律 BLOCKED；只有没有任何已交付阶段时才按原有语义 BLOCKED。
- 思路：真实案例中 Stage 5 只做了只读调用就以最终回复结束（模型假完成），旧证据门禁被 `tool_result` 外置产物误放行；Stage 6 修复重派后真正落盘交付，但 Leader Run 因 `list_dir` 重复调用循环被终止，根任务被错误置 BLOCKED。修复后空交付阶段不再冒充交付，失败但已有交付的根任务回到人工验收，避免用户手动 RESUME。
- 验证：新增 `CollaborationServiceTest.readOnlyToolResultArtifactIsNotStageDeliveryEvidence`（仅有 tool_result 物化产物的阶段 Run 完成 → 阶段与父任务 BLOCKED）与 `failedLeaderRunWithDeliveredStagesReturnsTaskToReview`（Leader Run FAILED 但存在 IN_REVIEW 阶段 → 根任务 IN_REVIEW）；paicli-server 全量测试 217 项全部通过，`git diff --check` 通过。本次未修改数据库 Schema、REST 契约、Sandbox、配置、产品站或前端，故迁移、OpenAPI、`docs/docker-sandbox.md`、`docs/phases.md`、`paicli-site/README.md` 不适用；README 与 `docs/architecture.md` 的交付证据/复验语义已同步。

### 协作视图聚合子 Agent 评论/动态、人工评论按时间入列，并统一子 Agent 中文输出

- 变更：`GET /v1/collaboration/tasks/{id}` 的 comments/activities 由「仅根任务」改为整棵树聚合（`CollaborationStore.treeComments/treeActivities`：根 + 全部阶段子任务，按时间/事件序合并），根任务协作视图因此能看到所有子 Agent 的最终回复、并行阶段的多个负责人交付、阶段屏障与阶段动作；每条评论/动态保留所属 taskId，前端据此显示「阶段 N · 负责人」上下文，并在「子任务与阶段」区提示阶段串行依赖（阶段 N 依赖前序阶段）与同阶段并行数量。
- 变更：人工反馈落库。`REQUEST_REWORK` / `BLOCK` 的原因会先持久化为该任务的 USER 评论（幂等去重）再触发/落状态，因此返工/阻塞原因按时间顺序出现在「评论与决策」，成为任务摘要的 latest_human_instruction，并能被复唤醒 Leader 通过 get_collaboration_task 读到；评论列表按时间顺序统一渲染（人工评论不置顶），人工评论带「人工评论」标识、子 Agent 评论带阶段标识。
- 变更：任务摘要修复。`TaskDigestService.prompt` 改为每次重建摘要，注入 Leader 复唤醒/返工 Run 的是实时状态（status/阶段/交付/最新人工指令），而不是创建任务时的旧 revision（旧摘要曾显示 status=TODO、stages=[]，会误导返工 Run 从头重做）。
- 变更：语言统一。`ContextManager.languageDirective` 改为从「用户原始意图」检测语言（协作信封里的 title/description/acceptance_criteria 或阶段任务内容，排除英文键名/ID/枚举等脚手架），含中文即中文、仅纯英文才英文、无信号默认中文；语言指令从 runtime 用户消息提升为 system 消息并强化文案（思考/推理/评论/最终回答一律中文，英文工具输出用中文转述）；`prompts/base.md`、`prompts/agent.md` 同步「默认中文、仅整条纯英文才英文」。
- 思路：根任务详情此前只读自己的 comments/activities，子 Agent 的交付评论落在各自阶段子任务上，导致「只能看见 Leader 评论」；聚合后由前端按 taskId 还原阶段上下文，天然覆盖并行与阶段依赖。语言混乱的根因是协作信封的英文脚手架让 latin>han 触发英文指令，以及专家中途漂移；改为按用户真实意图判定 + system 级强约束后，中文任务稳定中文、英文用户仍得英文。
- 验证：新增 `CollaborationStoreTest.treeCommentsAndActivitiesAggregateRootAndDescendantStages`、`ContextManagerTest.languageDirectiveUsesUserIntentNotCollaborationWrapperScaffolding`、`CollaborationServiceTest.requestReworkPersistsReasonAsHumanCommentBeforeTriggering`、`TaskDigestManifestTest` 摘要刷新断言；paicli-server 全量测试 215 项全部通过，`node --check app.js` 通过，`git diff --check` 通过。本次未修改数据库 Schema、REST 请求/响应/错误契约、Sandbox、配置或产品站，故迁移、`docs/docker-sandbox.md`、`docs/phases.md`、`paicli-site/README.md` 不适用；OpenAPI 的 `GET /tasks/{id}` 行为描述已同步，README 与 `docs/architecture.md` 已同步。

## 2026-08-07

### 修复：评论/提及触发返工时，待验收（IN_REVIEW）协作任务未置回进行中（IN_PROGRESS）

- 变更：`CollaborationService.trigger` 新建 Run 后把任务置回 `IN_PROGRESS` 的触发类型白名单，由 `HUMAN_ACTION/STAGE_BARRIER` 扩展为 `HUMAN_ACTION/STAGE_BARRIER/MENTION/REPLY`。根因是人工在待验收任务上回复评论（或显式 Mention、回复 Agent 评论）时，`comment` 走 `MENTION/REPLY` Trigger 新建返工 Run，但任务状态仍停留在 `IN_REVIEW`：前端一直显示“待验收”，且返工 Run 终态后 `onRunTerminal` 的 `IN_PROGRESS` 守卫不生效，任务不会重新提交待验收，状态永久卡在“待验收”。
- 思路：IN_REVIEW 表示“全部 Run 终态 + 等人工 ACCEPT”；一旦评论/提及又新建了 Run，任务就不再只是等待验收而是正在返工，应先回到 `IN_PROGRESS`，待返工 Run 终态并重新具备交付证据后，由既有 `onRunTerminal` 再置回 `IN_REVIEW`。`STAGE_BARRIER` 保留在名单内仅为兼容：`triggerLeaderForCompletedStage` 已对 IN_REVIEW 父任务提前拦截，不会误唤醒；`RUN_EVENT` 只在子 Run 终态事件时唤醒 Leader，此时任务不会处于 IN_REVIEW，故未纳入。
- 验证：新增 `CollaborationServiceTest.userCommentReworkOnDeliveredTaskMovesItBackToInProgress`（IN_REVIEW + 用户评论默认 Mention 触发 MENTION 新建 Run 后，断言任务被 `updateStatus` 置为 `IN_PROGRESS`）；`CollaborationServiceTest` 26 项全部通过，`git diff --check` 通过。本次未修改数据库 Schema、REST API 请求/响应/错误语义、Sandbox、配置或产品站，故迁移、`docs/docker-sandbox.md`、`docs/phases.md` 和 `paicli-site/README.md` 不适用（属既有协作层状态机缺陷修复，无新阶段里程碑）；OpenAPI 评论接口行为描述已补充（无契约变化），README 与 `docs/architecture.md` 的状态流转说明已同步。

### 修复：已进入人工验收（IN_REVIEW）的协作任务重启后不再被阶段屏障重新唤醒

- 变更：`CollaborationService.triggerLeaderForCompletedStage` 的“不可再自动唤醒”守卫由 `DONE/CANCELED` 扩展为 `IN_REVIEW/DONE/CANCELED`。根因是早前阶段完成时 Leader Run 仍活跃导致 `STAGE_BARRIER` 唤醒被跳过、屏障遗留为“COMPLETED 但无 Trigger”；服务重启后 `reconcileWaitingStageBarriers` 把它当作漏掉的唤醒补发，把已交付待人工验收（IN_REVIEW）的任务又置回 IN_PROGRESS 并新建 Leader Run。
- 思路：IN_REVIEW 表示全部 Run 终态 + Leader 已结论 + 等人工 ACCEPT，历史阶段屏障不应触发重启执行；人工返工应走 `REQUEST_REWORK`（HUMAN_ACTION）路径。此守卫同时作用于启动对账的 `waitingStageBarriers` 与 `completedStageBarriersWithoutTrigger` 两条路径。
- 验证：新增 `CollaborationServiceTest.startupDoesNotWakeLeaderForCompletedBarrierWhenTaskIsInReview`（父任务 IN_REVIEW + COMPLETED 无 Trigger 屏障时不创建唤醒）；依据真实数据审计 `task_f92640a989224db3`（重启后 17:31:43 生成 STAGE_BARRIER stage3 Trigger 并新建 `run_fa14b7d1f182469d`，任务被改回 IN_PROGRESS）。Server 全量测试 211 项全部通过，`git diff --check` 通过。本次未修改数据库、REST API、Sandbox、配置或产品站，故迁移、OpenAPI、`docs/architecture.md`、`docs/phases.md`、`docs/docker-sandbox.md` 和 `paicli-site/README.md` 不适用；README 协作任务状态语义已同步。

### 联网工具默认可见

- 变更：当 `paicli.web.enabled=true` 时，`web_search`、`web_fetch`、`github_repo_fetch` 直接加入普通会话的默认工具上下文，不再只依赖 `tool_search` 发现；禁用时仍完全不可见。`ToolCatalog.definitionsForContext` 从已注册的 Provider 定义中识别 Web 工具并加入“默认可见”集合，避免 `tool_search` 结果上限（12 条、按名称排序）把 `web_*` 挤掉导致模型“搜不到联网工具”。
- 思路：联网是高频能力，启用后应默认可用；仍受 Server 侧 SSRF 防护（`NetworkPolicy` 阻止私有/内网目标）与 `web_fetch` 的公开 URL 校验约束。
- 验证：新增 `ToolCatalogTest`（启用时 `web_search/web_fetch/github_repo_fetch` 默认可见，禁用时不可见）；`ContextManagerTest`、`RunProcessorTest` 回归通过；`PaiCliServerApplicationTest` 显式钉住 `paicli.web.enabled=false`，避免宿主环境开启 Web 但缺 `PAICLI_WEB_SEARCH_URL` 时 `WebProperties` 构造校验失败导致测试上下文无法启动。本次未修改数据库、REST API、Sandbox、配置或产品站，故迁移、OpenAPI、`docs/phases.md`、`docs/docker-sandbox.md` 和 `paicli-site/README.md` 不适用；README 联网说明已同步。

### 中英文输出一致性强化与前端中文化

- 变更：确认中英文混杂的成因——系统提示、Leader 提示与工具 Schema 描述里同时存在中英文，模型会镜像切换。现做三重加固：`prompts/base.md` 明确“用户中文时最终回答、步骤、列表、结论与代码说明一律中文（代码/命令/标识符/专有名词除外），不因提示或工具描述含英文而切换”；`ContextManager.languageDirective` 加强判定（无拉丁字母或中文占比更高即按中文，短指令如“继续”“好”也覆盖）并强化指令文案；`CollaborationService.runInput` 把英文 “Team completion gate…” 段改为中文。
- 变更：前端展示固定中文——Run 审计页的 Harness 面板改为“执行计划（WorkingPlan）/失败反思（Reflection）/完成验证（Verification）”，记忆浏览入口与对话框标题改为中文（“记忆浏览”“长期记忆浏览”），其余 Console 文案本就是中文。
- 思路：语言一致性是“输入语言决定输出语言”，而不是提示词语言；对中文用户应默认中文输出并显式抑制英文漂移，前端展示不混用英文标签。
- 验证：`node --check app.js` 通过，`git diff --check` 通过，Server 编译通过；README 与两份面试文档的语言/前端描述已同步。本次未修改数据库、REST API、Sandbox、配置或产品站，故迁移、OpenAPI、`docs/architecture.md`、`docs/phases.md`、`docs/docker-sandbox.md` 和 `paicli-site/README.md` 不适用。

### 效率工作台展示精简：Artifact 默认收缩、移除长期记忆关系地图

- 变更：效率工作台“Artifact 工作台”的列表与“长期记忆”“持久化审批策略”一致，改为默认收缩（标题与操作按钮保留在头部，列表折叠，可展开）。
- 变更：移除“长期记忆”的关系地图（图谱）功能：删除“打开地图”入口、Wiki 对话框中的“关系地图/页面”视图切换与图谱渲染（前端），保留按页面浏览记忆的来源/关联/审计；“打开地图”按钮改为“记忆 Wiki”。后端 `/v1/memories/wiki` 页面接口保留。
- 思路：工作台以信息密度优先，记忆/审批/Artifact 这类长列表默认折叠；关系图谱属于低频可视化，移除后保留可搜索、可浏览来源的 Wiki 页面视图，减少维护面。
- 验证：`node --check paicli-server/src/main/resources/static/app.js` 通过，`git diff --check` 通过；已扫描确认 app.js 无 `renderMemoryWikiGraph`/`setMemoryWikiView` 等残留引用。本次未修改后端运行逻辑、数据库、REST API、Sandbox、配置或产品站，因此迁移、OpenAPI、Store 测试、`docs/architecture.md`、`docs/phases.md`、`docs/docker-sandbox.md` 和 `paicli-site/README.md` 不适用；README 与两份面试文档中关于效率工作台的描述已同步。

## 2026-08-06

### Harness Loop v2 PR5–PR8：结构化专家交付、工作区冲突检测、任务摘要/交付清单与路由评分

- 变更（PR5 结构化专家交付）：新增 `DelegationEnvelopeBuilder`（服务端统一构建委派信封：目标、范围、约束、允许文件/工具、输入 Artifact、done criteria、工作区模式、父证据引用）与 `AgentResultValidator`（无证据 `COMPLETED` 拒绝、测试通过声明必须有测试证据、`FAILED/BLOCKED` 必须给出错误）；`get_agent_result` 增加 `validation` 结论。普通 Expert 嵌套委派继续由既有 `enforceParentDelegationRole` 守卫（默认仅 LEADER 可委派）。
- 变更（PR6 工作区写隔离）：新增 `WorkspaceMode`（SHARED_READONLY/SHARED_SERIAL/ISOLATED_WORKTREE）与 `WorkspaceMergeService`（并行子交付变更文件冲突检测）；`spawn_agent` 新增 `workspace_mode`，默认按角色映射（实现/文档→ISOLATED_WORKTREE，Runner→SHARED_SERIAL，探索/审查→SHARED_READONLY），写入委派信封。
- 变更（PR7 任务摘要与交付清单）：迁移 37 新增 `collaboration_task_digests`、`collaboration_deliveries`、`collaboration_accepted_snapshots`。`TaskDigestService` 构建并持久化任务摘要（目标、状态、阶段、阻塞、最近人工指令、增量活动、交付），Leader 复唤醒输入自动注入 `<task_digest>`；`DeliveryManifestService` 在阶段交付（IN_REVIEW）时记录清单（变更文件、Artifact、测试证据、内容哈希），人工 `ACCEPT` 时生成不可变验收快照；返工聚焦失败阶段由摘要携带。
- 变更（PR8 路由/评测/前端）：`CollaborationRoutingService` 综合评分（能力匹配为主 + 历史验证通过率 + 当前活跃负载），候选携带 `score`；官方评测集新增“官方·08 Harness Loop”（简单问答无多余循环、无证据不得宣称完成、测试失败不得虚假通过、重复工具调用被阻止、只读批次顺序稳定、子 Agent 摘要隔离、写冲突检测、人工验收门禁）；`GET /runs/{runId}/audit` 增加 workingPlan/reflection/verifications，Run 审计页展示 Harness 状态面板；效率工作台“长期记忆地图”与“持久化审批策略”列表默认收缩。
- 思路：专家协作的核心是“信封-结果协议”而不是信任自由文本；写隔离与冲突检测让并行写可安全合并；任务摘要与交付清单让复唤醒与返工不必重读全量历史；路由评分把“做过且做得好”和“当前不忙”变成可量化信号；评测把 Harness 行为固化为可回归的产品能力。
- 验证：新增 `DelegationProtocolTest`（信封、结果验证、冲突检测、角色→工作区映射）、`TaskDigestManifestTest`（摘要/清单/快照）、`SqliteRuntimeStoreTest`（迁移 37 CRUD 与路由信号查询）、更新 `EvaluationStarterPackServiceTest`（官方·08 Harness Loop，版本 1.3.0）。使用已缓存 Maven 3.9.9 执行 Server 全量测试 208 项全部通过，`node --check app.js` 与 `git diff --check` 通过。本次修改数据库 Schema（迁移 37）、委派信封/结果协议、路由评分、评测集、Run 审计 API 与 Console 展示，因此 README 运行/架构说明、`docs/architecture.md`、`docs/phases.md` 与两份面试文档已同步；未修改 Sandbox、配置项、启动方式或产品站，故 `docs/docker-sandbox.md` 和 `paicli-site/README.md` 不适用。

### Harness Loop v2 PR2–PR4：完成验证、失败反思与只读工具批次

- 变更（PR2 CompletionVerifier）：新增 `RunVerificationService`。模型给出最终答案时不再只检查“非空”：若 Run 执行过写操作但工作区没有文件变化，或执行过测试命令但最后一次测试失败，判定 `REPAIRABLE`；`RunProcessor` 把验证结果作为 `run.verification` Event + `<verification>` 用户消息注入下一轮并重新排队，连续超过 2 次仍无法通过才 `FAILED` 并记录 `VERIFICATION_FAILURE` 反思。普通问答（TEXT_ONLY）行为不变，空回答仍直接失败。
- 变更（PR3 Reflection）：新增 `ReflectionService` 与迁移 36 `run_reflections`（结构化失败分类、诊断、决策、plan patch、证据引用、下一步，不含隐藏思维链）。测试/工具失败自动记录 `TEST_FAILURE`/`TOOL_ERROR` 反思；重复相同工具+参数超过 `maxIdenticalToolCallsPerRun` 时记录 `DUPLICATE_CALL` 反思后停止 Run；`ContextManager` 每轮注入最新 `<reflection>` 块，Worker 重启后可恢复修复流程。
- 变更（PR4 只读工具批次）：`RunProcessor` 把同一模型响应中连续的只读 ToolCall（不要求审批）在单次领取中并行执行（最多 4 并发），按模型原始顺序提交 Tool Message；写工具/审批工具仍是屏障，顺序逐个执行。`commitToolOutcome` 增加无 Run 状态守卫的 `commitToolMessage` 配套，批处理结束后统一重新排队一次，步骤计数语义不变。
- 变更：`prompts/agent.md` 明确允许一次响应调用多个彼此独立的只读工具（PR1 已写入），写/命令/外部副作用工具仍逐个调用。
- 思路：完成判断从“Final Answer ≠ 自动完成”推进为“Final Answer + Completion Policy 验证通过 = 完成”；失败才触发反思，避免每轮额外模型调用；只读工具在“同轮原子持久化”基础上并行执行，减少队列轮次且保持上下文顺序稳定。全部保持既有持久化、幂等、Approval、SSE 恢复与普通聊天向后兼容契约。
- 验证：新增 `RunProcessorTest` 三个用例（只读批次单次领取全部完成且顺序稳定、无证据完成进入 repair 并超限失败、重复工具调用记录 DUPLICATE_CALL 反思）、`SqliteRuntimeStoreTest.savesAndLoadsLatestReflection`、`ContextManagerTest.injectsLatestReflectionIntoContext`；更新迁移版本断言到 36。使用已缓存 Maven 3.9.9 执行 Server 全量测试 199 项全部通过，`git diff --check` 通过。本次修改数据库 Schema（迁移 36）、RunProcessor 完成/执行路径、上下文组装与系统提示，因此 README 运行/架构说明、`docs/architecture.md`、`docs/phases.md` 已同步；未修改 REST API、Sandbox、配置项、启动方式或产品站，故 OpenAPI、`docs/docker-sandbox.md` 和 `paicli-site/README.md` 不适用。

### Harness Loop v2 PR1：轻量 WorkingPlan、语言一致性与刷新恢复

- 变更：新增迁移 35 `run_working_plans`（每 Run 单行、revision 自增，随 Run 归档）。新增 `WorkingPlanService`、`WorkingPlanToolProvider` 与 `update_working_plan` 工具：主 Agent 维护 objective + TODO/IN_PROGRESS/COMPLETED/BLOCKED 条目（`evidenceRefs` 可选），服务端校验 objective/items/status 并做确定性 upsert；工具效果为 `IDEMPOTENT_WRITE`，不创建 PlanStep、不经过 PlanWorker、无 DAG、无 PlanValidator。
- 变更：`ContextManager` 每轮只注入最新修订的 `<working_plan>` 块（计入上下文预算），不注入全部历史；`ToolCatalog` 把 `update_working_plan` 加入核心上下文工具，简单问答不自动产生计划，专家 Profile 可通过 `tool_search` 激活。
- 变更：语言一致性。`prompts/base.md` 不再固定用中文作答，改为“与用户最近一条消息语言一致”；`ContextManager` 按当前 Run 用户消息的汉字/拉丁字符占比注入显式 `<language>` 指令（中文问中文答、英文问英文答），协作任务复唤醒的 Leader Run 同样遵守。
- 变更：`prompts/agent.md` 允许一次响应调用多个彼此独立的只读工具（写/命令/外部副作用仍逐个调用），并提示复杂多步任务使用 `update_working_plan` 维护轻量清单。
- 变更：Console 整页刷新恢复上次查看的页面（最后打开的 Session、选中的协作任务及其视图），不再固定回到中性首页；删除选中任务时同步清理持久化选择。
- 思路：Harness Loop v2 方案把 Plan 拆成“WorkingPlan（Run 内轻量清单）”与“Formal Plan（严格 DAG）”两层；本 PR 只落地第一层与两个交互问题，CompletionVerifier、Reflection/Replan、只读工具批次并行、DelegationEnvelope/AgentResult、Worktree 写隔离、TaskDigest/DeliveryManifest 与路由综合评分（PR2–PR8）按方案后续分批实现，不引入 LangGraph/Spring AI Graph 或新工作流框架。
- 验证：新增 `WorkingPlanTest`（service 创建/修订/校验、provider 工具调用）、`SqliteRuntimeStoreTest.savesAndBumpsWorkingPlanPerRun`、`ContextManagerTest` 的 WorkingPlan 注入与语言指令断言；更新迁移版本断言到 35。使用已缓存 Maven 3.9.9 执行 Server 全量测试 194 项全部通过，`node --check paicli-server/src/main/resources/static/app.js` 通过，`git diff --check` 通过。本次修改数据库 Schema（迁移 35）、上下文组装、系统提示与 Console 行为，因此 README 运行/架构说明、`docs/architecture.md`、`docs/phases.md` 已同步；未修改 REST API、Sandbox、配置项、启动方式或产品站，故 OpenAPI、`docs/docker-sandbox.md` 和 `paicli-site/README.md` 不适用。

### 协作评论送达活跃 Run、返工理由进入执行指令

- 变更：用户评论/显式提及的目标已有活跃 Run 时，不再只是持久化后静默丢弃：`CollaborationService.comment` 会把评论内容作为 user 消息注入该目标（Agent 或 Team Leader）全部活跃 Run 的会话，运行中的专家在下一模型轮次即可读取并回应；没有活跃 Run 时才创建幂等 `MENTION` Trigger 派发新 Run。
- 变更：`REQUEST_REWORK` 等人工指令的理由本就写入新 Run 的 `instruction`（已在运行输入可见），本次进一步在 Team Leader 提示词中明确：用户追加的评论或返工理由必须原样写进 Leader 派发的阶段子任务 `description/acceptance_criteria`，让执行专家直接看到，避免“只重新触发、执行专家看不到返工原因”的重复执行观感。
- 思路：既有“目标已有活跃 Run 时不并发创建第二个 Run”的设计只保证了评论持久化，但运行中的 Run 上下文已固定，新评论永远不会被模型看到，导致“追加评论无效果”；返工则因 Leader 是唯一执行入口、阶段描述由 Leader 生成而可能丢失原因。修复把评论送入活跃会话，并把原因传递固化为提示词约束。
- 验证：新增 `CollaborationServiceTest.userCommentIsDeliveredIntoActiveRunSessionInsteadOfDropped`（活跃 Run 时评论注入会话、不创建 Trigger）与 `requestReworkCarriesReasonIntoTriggerInstruction`（REQUEST_REWORK 理由进入 Trigger instruction）；依据真实数据审计 `task_f92640a989224db3` 01:09 用户评论（评论持久化但无 Trigger、Leader 在 01:13 未读评论即发布结论）与 01:17 REQUEST_REWORK（理由已进入 `run_53295d7373e6487b` 输入并被模型引用）。使用已缓存 Maven 3.9.9 执行 Server 全量测试 188 项全部通过，`git diff --check` 通过。本次未修改数据库 Schema、配置项、Sandbox、启动方式、阶段范围或产品站，因此迁移、Store 测试、`docs/phases.md`、`docs/docker-sandbox.md`、README 配置/运行说明和 `paicli-site/README.md` 不适用；README 协作评论语义、`docs/architecture.md` 协作生命周期章节与 OpenAPI 评论接口说明已同步更新。

### 协作阶段状态一致性修复：Leader 续跑、屏障补唤醒、审批拒绝与整体取消收敛

- 变更：Team Leader 提示词不再要求“派发后停止等待唤醒”，改为明确“派发后本 Run 会等待该子 Run；子 Run 终态后同一 Run 自动恢复，恢复回合必须读取阶段交付证据，派发下一必需阶段或发布最终结论”，并把 completion gate 文案与“原地恢复 / 阶段屏障唤醒新 Run”两种唤醒方式对齐，避免模型按旧提示词在恢复回合空转结束。
- 变更：Leader Run 终态且未发布结论时，`onRunTerminal` 不再立即 `BLOCKED`，而是先扫描该任务下“已完成但没有 `STAGE_BARRIER` Trigger”的 StageBarrier 并补发一次幂等唤醒（每个阶段屏障最多补一次，受固定幂等键约束；`triggerLeaderForCompletedStage` 改为返回是否真正创建唤醒），只有没有任何可唤醒屏障时才保留 `BLOCKED` 兜底。
- 变更：审批拒绝路径不再绕过协作生命周期。`ApprovalService.resolve` 拒绝后除 failTool/failRun 外，补充 `RUN_FAILED` memory outcome，并调用 `collaboration.onRunTerminal(run, "FAILED")`，使阶段子任务/根任务状态与 Run 终态收敛，修复“阶段 Run 已失败但阶段任务仍 `IN_PROGRESS`、根任务却已 `BLOCKED`/结束”的不一致。
- 变更：整体取消协作任务时，`humanAction CANCEL` 在取消活跃 Run 后，把仍处于 `BACKLOG/TODO/IN_PROGRESS` 的后代阶段任务一并置为 `CANCELED`，避免根任务已取消而子树仍显示“执行中”；已交付(`IN_REVIEW`)、失败(`BLOCKED`)、完成(`DONE`) 的阶段保留证据不改动。
- 思路：根因是协作生命周期只由 `RunProcessor` 的 COMPLETED/FAILED notify 驱动，而审批拒绝与 Run 取消两条终态路径绕过了该钩子；同时 Leader 提示词与“同一 Run 原地恢复”的运行时语义矛盾——模型按旧提示词“停止等屏障唤醒”，屏障唤醒又被活跃 Run 守卫抑制，最终只能靠人工 RESUME。修复把三条路径统一为“Run 终态即收敛协作状态、Leader 终态无结论时先补一次屏障唤醒再兜底 BLOCK”。
- 验证：新增 `CollaborationServiceTest.teamRootWakesLeaderForSkippedStageBarrierInsteadOfBlocking`（Leader Run 终态、屏障 `COMPLETED` 且无 Trigger 时创建 `STAGE_BARRIER` 唤醒且不再 BLOCK）、`cancelingRootTaskMarksActiveStageSubtasksCanceled`（整体取消传播到活跃阶段任务）、`ApprovalFlowTest.deniedApprovalNotifiesCollaborationTerminalLifecycle`（审批拒绝后调用协作终态钩子）；并依据真实数据审计 `task_684893236d13410a`（象棋：Stage 2 审批被拒后阶段任务滞留 `IN_PROGRESS`、根任务取消后子树仍显示执行中）与 `task_f92640a989224db3`（别踩白块：每阶段完成后根任务 `BLOCKED`、全程无 `STAGE_BARRIER` 触发）。使用已缓存 Maven 3.9.9 执行 Server 全量测试 186 项全部通过，`git diff --check` 通过。本次未修改数据库 Schema、REST API、Sandbox、配置项、启动方式、阶段范围或产品站，因此迁移、OpenAPI、`docs/phases.md`、`docs/docker-sandbox.md`、README 配置/运行说明和 `paicli-site/README.md` 不适用；README 协作任务状态语义与 `docs/architecture.md` 协作生命周期章节已同步更新。

### 命令按参数风险审批

- 变更：`execute_command` 不再按工具名一刀切强制审批。ToolCall 仍先原子持久化，随后使用已落库的 `command` 做确定性风险分类；读取、搜索、构建和测试命令直接进入 Sandbox，删除/清空、提权/权限修改、进程/系统控制、破坏性 Git/数据库操作、下载安装、远程执行、发布和部署命令创建持久化 Approval。空命令或无法解析参数时保持安全失败并要求审批。
- 变更：MCP 和 Provider 显式声明的危险工具继续强制审批；批准后仍复用原 ToolCall 和原参数。服务初始化会重新分类历史未决命令，安全命令自动批准并重新排队，危险命令保持等待，避免升级后现有专家继续卡在旧的一刀切审批。工具 Schema 文案同步取消 `write_file` 的错误“总是审批”说明，并明确命令级审批范围。
- 思路：审批应保护危险副作用，而不是阻塞每一次测试和只读命令；但风险判断必须基于已经持久化的最终参数，不能在模型生成前猜测，也不能在批准后重新生成。Sandbox 的 workspace、Shell、环境、网络、资源和取消边界继续作为独立防线。
- 验证：新增风险分类测试覆盖 PowerShell/Bash 删除、进程终止、破坏性 Git、Docker 清理、下载执行和数据库删除，并断言目录读取、Node/Maven/NPM 测试及 Git diff 无需审批；`RunProcessorTest` 验证安全命令补齐持久化默认 Shell 后直接执行且不创建 Approval，`ApprovalFlowTest` 验证危险删除命令等待审批、策略复用、仅执行一次，以及历史安全命令自动批准重排队而危险命令保持等待。首轮使用本机 Maven 3.9.9 执行三组定向测试共 12 项全部通过；增加恢复用例后再次执行审批相关回归。本次未修改数据库 Schema、REST API、配置项、阶段范围或产品站，故迁移、OpenAPI、`docs/phases.md` 和 `paicli-site/README.md` 不适用。

### Docker 重启重复测试与运行中 JAR 锁修复

- 变更：`start-docker.ps1` 增加与本地启动一致的 PaiCLI 进程识别和 `-Restart` 语义。8080 已有 PaiCLI 时默认幂等返回；显式重启会先停止旧进程，再以 `-DskipTests` 重建 Server 和 Sandbox，避免运行中的 Server JAR 被 Windows 锁定。
- 变更：`build-sandbox.ps1` 改为只构建 `paicli-common` 与 `paicli-sandbox-agent`，不再因构建 Sandbox 顺带执行 Server 的 180 项测试和 Spring Boot 重打包；默认跳过测试，只有显式传入 `-RunTests` 才运行相关模块测试。
- 思路：服务重启是运维动作，不应隐式等同完整质量门禁；旧实现的全 Reactor `mvn package` 既造成每次启动耗时约两分钟，又会尝试改名正被 Java 进程使用的 Server JAR。启动脚本负责安全停止、快速构建和启动，完整测试继续由 `mvnw.cmd clean test` 显式执行。
- 验证：两个 PowerShell 脚本均通过 Parser 语法检查；对当前 8080 服务执行无 `-Restart` 的 Docker 启动脚本，正确识别 PaiCLI PID 38224 后立即返回，没有触发 Maven 或停止服务；`git diff --check` 通过。附件中的原始完整构建最终为 Server 180 项、Sandbox 3 项测试全部通过、`BUILD SUCCESS`，并于 2026-08-06 00:01:40 成功启动。当前受限工具终端尝试定向构建时，Maven Wrapper 触发既有 `$HOME/.m2` 空数组错误，直接 Maven 又因沙箱网络权限无法解析父 POM，因此未重复下载依赖或中断当前服务。本次未修改数据库、REST API、Runtime 架构、阶段范围、配置项或产品站，故迁移、OpenAPI、`docs/architecture.md`、`docs/phases.md` 和 `paicli-site/README.md` 不适用。

## 2026-08-05

### 子专家 reasoning 空终止、旧工具集与重复阶段派发修复

- 变更：OpenAI-compatible 模型在 HTTP 200 但只有 reasoning、没有正文或 ToolCall 时，后续恢复尝试除了追加“停止分析并立即行动”的纠偏消息，还会强制切换到 `thinking=disabled` 且移除 `reasoning_effort`；Kimi 路由在无法关闭思考时降为 `low`，避免每次重试再次耗尽完整 reasoning 窗口。恢复 ToolCall 若没有 `reasoning_content`，其后续轮次保持无思考模式，避免重新切回 enabled 后被 DeepSeek 以混合模式历史非法拒绝。
- 变更：内置 Agent 模板升级到 v2。读取专家列表时，仍绑定旧内置模板版本的 Profile 会自动刷新为当前提示词和最小工具集，同时保留已有模型方案、思考设置、Shell 和启用状态；这会移除旧代码/测试专家遗留的 `mcp__github__*` 等无关宽工具白名单。
- 变更：`create_collaboration_subtask` 按父任务、阶段、负责人组合拒绝已有活跃或已交付任务的重复派发；同一组合连续两个 `BLOCKED` 尝试后停止自动创建第三份任务，要求 Leader 报告阻塞并等待人工干预。不同负责人仍可在同一阶段并行。
- 思路：真实“坦克大战小游戏”任务的三个 Stage 2 Run 都完成了读取任务、目录和 `requirements.md`，没有审批或 Sandbox 错误；每个 Run 的最终模型轮次又各自执行 3 次 HTTP 尝试，合计 9 次均为 HTTP 200，但都以 `Model stream ended after reasoning without content or tool calls` 结束，工作区始终只有 `requirements.md`。Profile 审计还发现代码专家停留在 v1，旧白名单展开了大量 GitHub MCP Schema。恢复必须改变生成模式和上下文，而不是仅原样重试；协作状态机也必须给失败重派设置确定性上限。
- 验证：SQLite 审计核对 `task_22bf8a65dfb44b84` 的三个 Stage 2 子任务、`run_683de2dc01c54dd6`、`run_febb3cd361314c5d`、`run_5fde128c30b4446f` 的 ToolCall、ModelAttempt、消息与工作区，确认 9 次 reasoning-only 空终止且无 `write_file`。首次真实恢复 Run `run_95b388fab9054755` 在首轮再次耗尽 reasoning 后由禁用 thinking 的恢复请求成功执行 `write_file`，随后准确暴露了无 reasoning ToolCall 切回思考模式的 DeepSeek HTTP 400；补充跨轮模式保持后，`run_763b250b1f904376` 完成 18 个 ToolCall、两条交付评论并进入 `COMPLETED`，Stage 2 转为 `IN_REVIEW`，共享工作区生成 25 KB `index.html` 且内联脚本通过宿主 Node 语法检查。根 Leader `run_ba8f66f6d8ad4bdf` 没有重派 Stage 2，只创建唯一 Stage 3 测试 Run 并进入正常 `WAITING_AGENT`。新增模型请求测试断言恢复请求关闭 thinking及无 reasoning ToolCall 的后续请求继续关闭 thinking，新增协作测试覆盖活跃阶段去重和两次失败上限；最终执行完整 `.\mvnw.cmd test`，Common 3、Server 180、Sandbox 3 项全部通过，并执行 `.\mvnw.cmd package -DskipTests` 生成可执行 JAR。Windows SQLite JDBC 清理旧临时 DLL 的 `AccessDeniedException` 日志不影响结果。本次未修改数据库 Schema、REST API、Sandbox、配置、启动方式、阶段范围或产品站，因此迁移、OpenAPI、Store 测试、`docs/phases.md`、`docs/docker-sandbox.md`、README 配置/运行说明和 `paicli-site/README.md` 不适用。

### 记忆提取降噪与主动 L3 录入

- 变更：自动 Memory 仅在委派树根 Run 完成时创建 extraction job，子 Agent Run 不再独立提取；每个根 Run 总数最多 3 条，并限制 L1≤1、L2≤2、L3≤1。
- 变更：提取器过滤 Stage、task/agent/run/comment 标识及派发/运行/评论等流程事件（技术结论类候选例外）；候选必须显式引用窗口内有效证据，且至少存在用户陈述或成功工具结果。模型置信度改由模型分数、证据质量、重复出现程度和层级稳定性校准，用户证据上限 0.80，用户加工具证据上限 0.95。
- 变更：Console 的长期记忆地图新增“新增 L3 长期记忆”入口，复用既有 `POST /v1/memories` 创建默认人工 L3 记录，保存后刷新分层列表。
- 思路：多 Agent 的过程事件和子 Run 结论会天然重复，长期记忆应以根 Run 的汇总结果为入口，并以可审计的外部证据而非模型自述决定可信度。主动录入维持人工纠错边界，适合稳定偏好和长期约束。
- 验证：新增 `LayeredMemoryServiceTest` 覆盖根 Run 排队、流程事件过滤、证据门禁与置信度上限；执行定向 Maven 测试、前端 `node --check` 和 `git diff --check`。本次未修改数据库 Schema、REST API、Sandbox、启动配置或产品站，因此迁移、OpenAPI、`docs/docker-sandbox.md`、README 配置/启动说明和 `paicli-site/README.md` 不适用。

### 工作区 HTML 交付安全预览修复

- 变更：工作区 `.html/.htm` 的“打开”不再把单个响应直接导航到临时 `blob:` URL；Console 会解析入口文档，用现有认证头读取同一工作区内的相对 JavaScript、CSS、图片和媒体，将其转换为自包含预览后打开。因此 `index.html` 引用同级 `game-core.js` 等多文件交付可以直接运行。
- 变更：主 Console 继续维持不允许 inline/data 脚本的严格 CSP。自包含交付文档由不接收 URL 输入的静态预览壳创建独立 `blob:` URL，并载入不含 `allow-same-origin` 的 sandbox iframe；预览壳和交付文档均禁止 `connect-src`、嵌套页面、object 和 form action。这样已打包脚本可以执行，但不能读取 Console 的 `sessionStorage`/API Key、访问父页面或调用 PaiCLI 管理 API，外部 URL 也保持阻断。
- 思路：原实现只下载入口文件并创建 `blob:` URL，浏览器会把 `./game-core.js` 解析到不存在的 blob 路径；从本地文件夹打开时同级文件存在，所以表现不同。不能简单把工作区 HTML 作为 PaiCLI 同源页面暴露，否则模型生成的脚本可继承 Console 权限；正确边界是“认证读取依赖，自包含打包，隔离执行”。
- 验证：执行 `node --check paicli-server/src/main/resources/static/app.js`；静态 Web 安全测试补充 HTML 依赖打包、sandbox 和 `connect-src 'none'` 契约。随后执行 Maven 测试与 `git diff --check`。本次未修改数据库 Schema、REST API、Sandbox 执行实现、配置、启动方式、阶段范围或产品站，因此迁移、OpenAPI、Store 测试、`docs/phases.md`、`docs/docker-sandbox.md` 和 `paicli-site/README.md` 不适用。

## 2026-08-04

### 协作重复唤醒、结论门禁与工作区交付可见性修复

- 变更：协作评论、回复、子专家终态和 Stage Barrier 在创建 Trigger 前按任务树与最终 Agent 身份检查活跃 Run；评论/提及仍持久化，但同一专家或 Team Leader 已经运行时不再并发创建第二个 Run。通用委派的父 Run 会按既有 Agent Graph 继续等待并消费子结果，不再同时追加独立 `RUN_EVENT` Leader。
- 变更：根 Team Leader 通过 `post_task_comment` 发布 `conclusion=true` 时携带当前 Run id；只要还有其他阶段、委派或并行 Run 未终态，服务端拒绝“最终结论”。这避免 Leader 在代码审查/测试专家仍 `WAITING_MODEL` 时提前写“最终验收”，同时保留根任务必须由人工 `ACCEPT` 才能进入 `DONE` 的边界。
- 变更：显式 `workspace_ref` 仍可建立隔离目录，但若模型误把包含当前 collaboration workspace owner 的 Windows/Linux 文件系统路径填入该字段，则按继承当前共享工作区处理并将有效引用保存为空，避免委派元数据写“共享工作区”而子专家实际看到空目录；Tool schema 同步明确禁止传文件系统路径。
- 变更：Console 整页刷新固定回到“普通对话 / 新对话”，不恢复上次会话、协作任务或执行详情；协作执行页在任务未完成时也展示“当前工作区产物”，将其标为可能继续变化，并把 `WAITING_AGENT` 解释为 Leader 等待子专家，避免与最终验收混淆。
- 思路：真实五子棋任务 `task_6c64c9229b11448c` 中，一条 PowerShell 5 请求在发送前把中文编码成字节 `0x3F`，数据库因此持久化了问号而非前端渲染乱码；该用户回复又在已有 Leader Run 时产生 `MENTION`，第二个 Leader 随后通过 `spawn_agent` 再派一个测试专家。测试专家评论、子 Run 终态与 Stage 3/4 Barrier 又分别产生 `REPLY`、`RUN_EVENT` 和 `STAGE_BARRIER` Leader，形成截图中的重复项。通用委派还把“共享工作区：绝对路径”当成隔离 key，导致第二个测试专家实际工作区为空。正确边界是审计事件持久存在，但同一负责人只有一个活跃执行槽，父 Run 自己消费委派结果，最终结论受整个执行树约束。
- 验证：真实数据审计覆盖根任务/阶段 `collaboration_task_runs`、Trigger 来源、`run_delegations`、模型消息、`model_attempts`、ToolCall、Artifact 与 workspace owner；确认正常 Stage 3/4 专家能读取共享文件，重复通用委派专家因错误 owner 看到空目录，Local Sandbox 下三个测试/审查 Run 的 `execute_command` 均按设计失败，真实可执行证据来自宿主 Node v20.19.6。宿主再次执行 `node --test tests/game-core.test.js`，T1-T16 共 16/16 通过；`node --check paicli-server/src/main/resources/static/app.js` 通过。新增 Service 测试覆盖评论、子 Run 终态、Barrier 去重和 Leader 结论门禁，Store 测试覆盖共享目录路径归一化。最终两次 `.\mvnw.cmd clean package` 均通过，Common 3、Server 173、Sandbox 3，共 179 项测试通过；Windows SQLite JDBC 清理旧临时 DLL 的 `AccessDeniedException` 日志不影响结果。Docker 29.6.1 可用，已重建 `paicli-sandbox-agent:0.6.0` 并以 `sandboxMode=docker` 启动健康服务。修复后的真实任务由唯一 Leader Run `run_4dbc60303dfc41b3` 复核 5 个工作区文件并发布门禁后的结论 `comment_6689bc72c68945c3`，根任务正确进入 `IN_REVIEW`；损坏评论已替换为可读 ASCII 验证文本，历史 Run 未删除。浏览器回归确认执行页显示“等待人工验收”和 `README.md`、`requirements.md`、`game-core.js`、`index.html`、`tests/game-core.test.js`，从任务页整页刷新后 `普通对话` 重新选中且标题为“新对话”。API 评论响应结构未变，仅补充 OpenAPI 触发语义；未修改数据库 Schema、配置项或 Sandbox 实现，因此迁移、`docs/docker-sandbox.md` 与产品站文档不适用。

### 模型空终态与截断工具参数恢复修复

- 变更：`OpenAiCompatibleModelClient` 不再把 HTTP 200 且只有 reasoning、没有最终正文和 ToolCall 的 SSE 终态记为成功；该尝试会持久化为 `RETRY`，并在原请求后追加停止重复分析、立即调用工具或返回最终答案的纠偏消息后重试。
- 变更：流后的 ToolCall 参数被截断、不是有效 JSON 或缺少调用标识时，下一次尝试不再原样重复请求，而是要求模型保持参数小而有效、每轮只写一个文件并把大内容拆到多个 ToolCall/轮次，避免连续耗尽全部重试预算。
- 变更：启动阶段屏障对账改为列表读取和逐屏障隔离。SQLite 短时锁、历史屏障求值或 Leader 补唤醒异常会记录 task/stage 警告并继续，不再从 `ApplicationReadyEvent` 抛出并终止已经开始监听的 Server；未处理屏障保持持久化状态供后续幂等恢复。
- 变更：恢复路径继续服从现有 `maxAttempts`、指数退避、取消、熔断和模型尝试审计；达到上限后仍由 Run 空响应门禁进入 `FAILED`，不放宽 ToolCall 先持久化、协作阶段交付证据或最终完成边界。
- 思路：本机“写一个五子棋游戏”最新协作任务的 Stage 2 Run `run_c916927bb20c4413` 首次工具参数流在 JSON 字符串中途截断，流级重试随后生成 1,154 段 reasoning 并规划了实现与测试，但未产生正文或工具调用。首次修复后的真实重跑又连续两次截断大体积 `write_file` 参数，耗尽了空终态纠偏前的尝试预算；同时两次部署都复现启动对账与 Worker 写事务竞争导致 `SQLITE_BUSY` 终止应用。模型已经生成思路但未越过持久化执行边界时，应尽早用可审计的协议级纠偏改变下一次生成策略；启动恢复则必须保留错误证据但不能把单项对账升级为整站不可用。
- 验证：定向执行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=OpenAiCompatibleModelClientTest,RunProcessorTest,CollaborationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，32 项通过；全量 `.\mvnw.cmd clean package` 通过，Common 3、Server 168、Sandbox 3，共 174 项测试通过并生成可执行 JAR。新增本地 SSE 用例覆盖 reasoning 空终态恢复、截断 ToolCall 参数后的拆分写入纠偏，启动测试覆盖单屏障异常不逃逸；原有真正空响应不能误报完成的门禁继续通过。最终 JAR 启动超过此前 30 秒锁竞争窗口后仍健康监听 `8080`；真实五子棋任务恢复后连续完成 3 个小参数 `write_file`，共享工作区的 `game-core.js`、`index.html`、`tests/game-core.test.js` 已落地，宿主执行 `node --test tests/game-core.test.js` 最终 16/16 通过并把 ASCII 验证评论持久化到 Stage 3。Windows SQLite JDBC 清理既有临时 DLL 时仍记录 `AccessDeniedException`，不影响结果。本次未修改数据库 Schema、REST API、Sandbox、配置、启动方式、阶段范围或产品站，因此迁移与 Store 测试、OpenAPI、`docs/phases.md`、`docs/docker-sandbox.md`、README 配置/运行说明和 `paicli-site/README.md` 不适用。

### 技术架构与面试文档同步

- 变更：同步更新根目录《PaiCLI Platform Lite 技术架构与面试讲解》和《PaiCLI Platform Lite 技术架构与面试指南》，补齐阶段 22–24 的持久化 CollaborationTask、根/子任务展示边界、评论与幂等 Trigger、StageBarrier、人工验收和最终交付门禁。
- 变更：把旧的“每 Run 独立工作区”修正为“容器租约按 Run、挂载目录按有效 workspace owner”。文档说明同一根协作任务的 Leader 唤醒、默认阶段 Run 和委派后代共享稳定任务工作区，显式 `workspace_ref` 才隔离，并补充迁移 34 的历史目录归并和冲突版本保存策略。
- 变更：将两份文档的 Schema 版本从 1–26 更新到 1–34，补齐协作任务相关持久化表；同时补充 Run 队列、长期记忆地图、Artifact 工作台和持久化审批策略的批量物理删除 API、100 ID 上限、完整预检、SQLite 单事务回滚和提交后受控文件清理语义。
- 变更：更新当前能力边界、Console/API 说明和高频面试问答，明确 Run `COMPLETED`、阶段交付、根任务 `IN_REVIEW` 与人工 `ACCEPT -> DONE` 是四个不同层次，协作中的过程文件不能提前标记为最终产物；更新最近完整回归统计为 Server 165、Common 3、Sandbox 3 项。
- 思路：这两份面试材料必须与 README、`docs/architecture.md` 和当前实现保持同一事实口径，重点解释用户实际遇到的状态不流转、子任务文件不可见、重复派发和前端伪删除为什么发生，以及当前通过任务层状态机、共享工作区、证据门禁和事务删除如何解决。
- 验证：执行关键词一致性扫描，确认两份文档不再保留 Schema 1–26、`workspaces/{runId}` 固定挂载或 113 项测试等旧口径；执行 `git diff --check`。本次仅同步说明文档，不改变代码、数据库 Schema、REST/OpenAPI、Sandbox、配置、启动方式、阶段范围或产品站，因此不运行 Maven 测试，README、`docs/architecture.md`、`docs/phases.md`、`docs/docker-sandbox.md`、OpenAPI 和 `paicli-site/README.md` 不适用。

### 协作任务统一工作区与空交付循环修复

- 变更：协作 Trigger 创建 Run 时改为显式绑定根任务级 workspace owner。Stage Barrier 可以继续创建新的 Leader Session/Run，但同一任务的 Leader 唤醒、直接阶段 Run 和默认委派后代始终落在同一受控目录；不同根任务继续隔离，显式 `workspace_ref` 继续作为有意隔离边界。
- 变更：Schema 迁移记录推进到 34。启动时递归扫描 CollaborationTask 树及其 Run 委派后代，把旧的分散 workspace 按 Run 创建顺序归并到任务目录；同路径冲突时先把被覆盖版本保存到 `.paicli/workspace-history`，文件归并成功后再事务更新全部 Run owner。
- 变更：模型返回无 ToolCall 且最终正文为空时，Run 进入 `FAILED`，不再提交伪 `COMPLETED`。阶段 Run 终态还需存在本 Run 的非只读工具写入及对应最近文件、Artifact 或阶段评论；无持久交付证据时阶段和父任务进入 `BLOCKED`，不会求值 Barrier 或继续派发下一阶段。
- 思路：CollaborationTask 才是跨多次 Run 的持久工作项，工作区身份必须跟随根任务而不是某次 Leader Run；同时 Run 的技术终态不能代替可审计的阶段交付。两层门禁共同解决“后续 Agent 看见空目录”和“只读检查后空响应仍循环派发”。
- 验证：定向执行 `.\mvnw.cmd test "-Dtest=SqliteRuntimeStoreTest,CollaborationServiceTest,RunProcessorTest" "-Dsurefire.failIfNoSpecifiedTests=false"`，58 项通过；再执行完整 `.\mvnw.cmd test`，Server 165 项、Common 3 项、Sandbox 3 项全部通过。覆盖跨 Session/委派共享 owner、历史文件启动迁移、空模型响应失败和无证据阶段阻塞；Windows SQLite JDBC 临时 DLL 清理仍输出既有 `AccessDeniedException` 告警，不影响测试。`package` 已完成全部 Java 编译和普通 JAR 生成，但 Spring Boot `repackage` 因运行中的本地服务占用目标 JAR、Windows 无法重命名为 `.jar.original` 而停止，未强制终止用户进程。未改变 REST API、Sandbox 协议、启动配置或产品站，因此 OpenAPI、`docs/docker-sandbox.md`、README 配置/启动说明和 `paicli-site/README.md` 不适用。

## 2026-08-03

### 协作中误显最终交付修复
- 变更：`GET /v1/collaboration/tasks/{id}` 新增 `finalDeliveryReady` 标记，只有根协作任务状态进入 `DONE` 时才允许前端把共享 workspace 文件解释为“最终交付”。
- 变更：协作任务“执行”页改为仅在 `finalDeliveryReady=true` 时渲染“最终交付”区块；任务仍处于 `IN_PROGRESS`、`BLOCKED`、`IN_REVIEW` 或 `CANCELED` 时，不再提前展示最终产物卡片。
- 变更：`GET /v1/collaboration/history` 现在按根任务树递归聚合根会话、阶段子任务会话和复唤醒 Leader 会话；左侧统一历史据此把整棵协作任务折叠成单一任务记录，不再把这些会话误显示为普通对话。
- 变更：协作任务详情的自动刷新签名补入 `children`、`workspaceFiles` 和 `finalDeliveryReady`，阶段新增、复派发或交付门禁变化时会立即重绘“子任务与阶段”和“执行”页，不再停留在旧快照。
- 思路：共享 workspace 中出现文件，只能证明阶段或专家过程里产出了中间结果，不能等价于任务已经完成最终交付。最终交付必须绑定根任务完成态，而不是绑定“当前有文件可看”。
- 验证：执行 `node --check paicli-server/src/main/resources/static/app.js`、`.\mvnw.cmd test "-Dtest=CollaborationStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false"` 和 `git diff --check`；`CollaborationStoreTest` 5 项通过，覆盖根任务历史聚合子任务会话。测试过程中 SQLite JDBC 仍记录 Windows 既有临时 DLL 清理 `AccessDeniedException`，不影响结果。本次未改动数据库结构、Sandbox、启动方式或产品站，因此 `docs/architecture.md`、`docs/phases.md`、`docs/docker-sandbox.md`、README 配置/运行说明和 `paicli-site/README.md` 不适用。

## 2026-08-02

### 协作任务树、验收时机与最终交付修复

- 变更：取消“存在执行历史即禁止删除”的旧边界。终态协作任务现在可以删除整个协作树及其评论、活动、Trigger、路由决策和 Task-Run 关联，同时保留已结束的 Run、会话与交付文件；活跃任务仍必须先取消。
- 变更：协作任务列表和统一历史只返回根任务；阶段子任务保留 `parent_id` 关系，只在父任务的“子任务与阶段”中显示，避免阶段 3、阶段 4 被误呈现为独立协作任务。
- 变更：Agent 不能再直接写 `IN_REVIEW`；阶段 Run 终态由平台提交阶段交付，根任务仅在其整个阶段 Run 树均已终态后进入人工验收。取消根任务时按整个任务树取消活跃 Run，避免阶段仍在运行却出现“待验收”。Schema 迁移 33 会在启动时把仍有活跃阶段 Run 的历史根任务从错误的 `IN_REVIEW` 恢复为 `IN_PROGRESS`。
- 变更：任务详情的执行层聚合根任务及全部阶段 Run，并在顶部新增“最终交付”，列出共享 workspace 的实际文件并提供既有预览/下载入口，明确交付物的位置。
- 思路：阶段是父任务的执行分解而不是新的用户工作项；`IN_REVIEW` 必须代表没有仍在工作的执行者，交付物则必须从模型文字中独立出来成为可操作文件，三者才能让任务树、执行树和人工验收的语义一致。
- 验证：新增 `CollaborationStoreTest` 根任务过滤断言和 `CollaborationServiceTest` 的 Run 树验收时机断言，并同步 Schema 迁移版本断言；`node --check paicli-server/src/main/resources/static/app.js` 与 `git diff --check` 已通过。Maven 定向测试仍受本机缺少 Spring Boot 父 POM 且中央仓库访问受限影响，未能运行；本次不改变 Sandbox、启动配置或产品站，因此 `docs/docker-sandbox.md`、README 配置/运行说明和 `paicli-site/README.md` 不适用。

### 协作阶段交付与遗留审批修复

- 变更：`create_collaboration_subtask` 现在会原子地创建阶段子任务并派发其直接专家 Run，阶段 Run 继承当前 Leader 的共享工作区；Leader 在阶段派发后等待该直接子 Run，完成后恢复同一 Leader Run，而不是新建无父子关系的 Leader Run。
- 变更：阶段 Run 成功结束会将阶段子任务置为待复核；任务读取接口同时返回阶段子任务，避免将仅创建、未执行的阶段卡片误解为已交付。
- 变更：取消 Run 时会自动关闭其未决审批；启动时也会清理已终态 Run 遗留的 `PENDING` 审批，修复全局“待审批”计数残留。
- 思路：阶段产出依赖父子 Run 的直接关系和共享工作区，不能依赖跨会话检索或跨工作区路径；审批不能在已取消/失败/完成的 Run 上继续等待。
- 验证：`node --check paicli-server/src/main/resources/static/app.js` 与 `git diff --check` 通过；新增协作服务与运行时 Store 定向测试。Maven 定向测试因本机缺少 Spring Boot 父 POM、中央仓库访问受限而未能执行。

### 小队并发执行与全局子专家审批

- 变更：修正子专家会话的协作看板布局。子会话只显示紧凑的“当前子专家 / 返回父专家”导航，完整的委派列表留在父会话；父会话中的任务说明限制为四行摘要，避免长任务文本占满消息区。底部模型控制栏允许按控件分组自然换行，标签保持完整词组，不再在窄聊天列中逐字竖排。
- 变更：新增 `run_collaboration_policies.max_concurrent_agent_runs`，Team Trigger 将 Route Preview 的有效并发保存到根协作 Run；队列领取候选 Run 时递归统计同一委派树中非终态、非排队的子 Run，达到上限则跳过该候选并继续尝试其他可运行任务。根 Leader 不计入小队配额，项目 `maxConcurrentRuns` 仍是外层总上限。定时任务和团队评测也使用保存的小队最大并发；旧策略迁移为 `0`，保持既有的不额外限流行为。
- 变更：`GET /v1/approvals` 新增可选 `projectKey` 筛选，Console 主 Header 每三秒汇总当前项目待审批项并提供直接允许/拒绝入口；协作看板把 `SIMPLE · LOW` 改为“路由推断：简单 · 低风险”，不再暗示这是任务的固定难度设置。
- 思路：委派数量和同时执行数量是不同约束。Leader 仍可在专家数量/深度范围内创建多个子任务，而 Harness 在实际领取时控制同时占用模型、工具和工作区的子 Run 数；这样不会靠 Prompt 要求模型“少并发”来赌行为。项目级上限继续保护整个平台，小队上限只保护单个协作树的资源份额。
- 验证：新增 Store 回归用例覆盖并发上限为 1 时第二个子 Run 保持排队、首个完成后才能领取第二个；`ApprovalFlowTest` 新增项目隔离断言，3 项通过。前端 `node --check paicli-server/src/main/resources/static/app.js`、`git diff --check` 通过。Maven Wrapper 在当前 Windows PowerShell 环境的 `$HOME/.m2` 目录非链接分支触发自身的空数组错误，改用已缓存 Maven 3.9.9 二进制执行 `-pl paicli-server -am -Dtest=SqliteRuntimeStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`，Common 与 Server 均成功，`SqliteRuntimeStoreTest` 34 项通过；Windows SQLite JDBC 清理旧临时 DLL 的 `AccessDeniedException` 为既有环境日志，不影响结果。尝试 `package -DskipTests` 时发现既有 Java 进程占用 Server JAR，Spring Boot 重命名 JAR 失败，未终止该进程或改动运行中的服务。本次未改变 Sandbox、启动配置或产品站，因此 `docs/docker-sandbox.md`、README 配置/启动说明和 `paicli-site/README.md` 不适用。

### 外部 Harness Token 成本优化设计基线

- 变更：新增 `docs/harness-token-optimization.md`，以本机“写一个推箱子小游戏”协作任务为复盘样本，记录 8 个关联 Run、53 次实际模型调用、1,379,337 输入 Token、51,075 输出 Token、1,093,376 cached input Token 和 14 次重试，并按初始 Leader、成员委派、终态 `RUN_EVENT`、评论 `REPLY`、供应商 429 限流和取消逐项解释调用放大的原因；同时记录专家档案绑定方案未解析、Task-Run 回退服务端默认 Kimi 的真实路由事实。
- 变更：明确现有 ContextManager/Context Manifest、稳定缓存前缀、按需工具 Schema、Run 级预算和项目级用量的能力边界；当前阶段清单将该任务级外部 Harness 顺延为阶段 26，包括 Token/费用信封、原子预留结算、Trigger 合并、Leader 单飞、429 冷却、结构化交接包、角色 Context Profile、确定性工具结果归并、模型分层和质量/成本联合评测。
- 思路：Prompt Cache 只能降低重复前缀的单价，不能减少不必要的模型调用、输出、动态上下文和失败重试；任务级 Harness 应在模型调用前以确定性规则判断“是否有新证据且值得再决策”，并保留 Trigger/Run/Approval/Sandbox/人工验收的完整审计链路。对 Claude Code 与 Codex 只引用公开文档可验证的项目记忆、回合上限、网关预算/路由、精简 Prompt/工具、缓存与可独立拆分的多 Agent 实践，不推断其未公开的内部实现。
- 文档：README 增加设计入口，architecture 与 phases 同步标记为后续方向；未变更 API、数据库迁移、Sandbox、启动配置或产品站点，故 OpenAPI、`docs/docker-sandbox.md`、README 配置/启动说明和 `paicli-site/README.md` 不适用。
- 验证：只读查询本机 SQLite 的 Task-Run、Run、Trigger 和 Model Usage 数据，并核对 CollaborationService、RunProcessor、ContextManager、ToolCatalog 与 ProductivityStore 的现有实现；外部参照仅查阅 Anthropic 和 OpenAI 官方文档。该次为文档变更，未改变运行时行为，未执行 Maven 测试；交付前执行 `git diff --check`。

### 协作任务职责边界与 Console 人工操作重构

- 变更：新建 CollaborationTask 只允许选择 Agent 或 AgentTeam 负责人，不再把人工作为一种任务负责人。Agent 状态工具收敛为 `IN_PROGRESS/BLOCKED/IN_REVIEW`；单 Agent 任务只能由被分配 Agent、Team 任务只能由 Team Leader 更新任务级状态，团队成员改为通过评论回报并唤醒 Leader，任何 Agent 都不能直接写 `DONE/CANCELED`。Run 正常结束只记录执行完成事实，不自动提交验收。
- 变更：新增 `POST /v1/collaboration/tasks/{taskId}/actions` 人工动作接口，支持启动、继续、恢复、阻塞、要求返工、验收通过、取消和重新打开；`ACCEPT` 是唯一进入 `DONE` 的路径，阻塞与返工必须保留原因，活跃 Run 期间状态干预要求先通过评论追加上下文或处理 Run。旧状态接口保留为受状态机约束的兼容入口，并禁止人工提交 `IN_REVIEW`。
- 变更：阶段屏障把 `IN_REVIEW/DONE/CANCELED` 视为当前执行阶段已经交付，允许 Leader 在子任务等待人工审核时评估下一阶段，但不替代子任务的最终人工验收。团队人工介入指标识别显式 `HUMAN_ACTION`，并避免与同一动作产生的状态活动重复计数。
- 变更：Console 创建区改为标题、负责人类型、负责人三个主字段，任务说明和“完成条件（可选）”收进更多设置；移除人工负责人选项和任意状态下拉框，任务详情以状态徽标及按当前状态显示的人工动作推进。活跃 Run 期间引导用户前往协作层追加评论，审核态明确区分验收通过与要求返工。
- 变更：从协作任务执行层打开关联 Run 会话时记录任务来源，并在会话顶部固定显示“返回协作任务”；来源只保留在当前浏览器标签页，返回后恢复原任务和执行页签，避免进入会话后只能依赖首页重新查找任务。
- 变更：三模式切换从首页内容提升到应用主 Header，在普通对话、专家协作、协作任务、已打开会话和任务执行过程中持续可见；点击任一模式会关闭当前工具弹窗并返回对应首页。桌面使用紧凑三段布局，移动端占用 Header 第一行，避免与会话标题、返回任务和执行详情操作重叠。
- 变更：移除左侧“新建对话”按钮，普通对话仍通过全局模式入口返回首页并在首次发送时惰性创建 Session。左侧历史改为按项目和既有自定义分组统一展示普通对话、专家协作与协作任务，增加类型标签；`GET /v1/collaboration/history` 每个长期任务只返回一条记录及其最新/全部关联 Session 和 Run 数，前端据此折叠同一任务产生的重复执行会话。普通/专家会话继续支持打开、移动分组和删除，任务记录支持打开与删除入口；点击历史项会同步对应的全局模式高亮，专家与团队目录异步加载完成后立即重绘侧栏，首屏负责人直接显示业务名称。
- 变更：协作任务详情和左侧任务菜单均增加删除操作。未产生 Run 的任务确认后永久删除；已有 Task-Run 审计历史的任务会从历史列表或关联会话导航到任务页，并显示不受轮询重绘影响的内联提示，引导使用现有“取消任务”动作保留评论、活动和执行证据。
- 变更：修复进行中任务存在活跃 Run 时看不到“取消任务”的问题。人工操作区现在始终提供取消入口并要求二次确认；服务端取消全部关联的活跃 Run 树，主动中断模型请求和 Sandbox 执行后再把 CollaborationTask 置为 `CANCELED`，不会删除评论、活动、Run 或用量审计。
- 变更：协作层改为每 3 秒读取同一个任务详情响应，同时同步评论、Activity、Task-Run 和任务状态；用户正在评论输入框或提及选择器中操作时延迟 DOM 替换，保留未发布草稿。新增任务建立、执行派发、专家协作、人工验收四阶段轨道，以及参与角色、关联执行、协作评论指标；原始事件代码转换为可读的中文动态，关联专家名称、评论摘要和真实模型，并过滤与人工启动重复的无语义系统状态事件。
- 变更：Console 新增统一实体名称解析，协作任务负责人、评论作者与提及、活动参与者、关联执行、子专家工具链、消息正文、事件详情、队列、评测、计划详情、Run 审计、审批与 Artifact 常规展示不再使用内部 ID 作为主文案；Agent、AgentTeam、模型方案、工具和 Memory 优先映射保存名称，失效引用使用“未知专家/团队”之类稳定占位。协作任务详情的 `runs[]` 新增模型方案引用/名称和最近一次真实 `model_usage.model_name`，执行层直接展示“专家名称 · 实际模型 · 时间”，即使 Run 使用服务端默认模型也能解释用量中的 Kimi 等实际模型。
- 思路：ID 是持久化关联键，不是用户识别对象的名称。名称映射集中在展示边界，API 请求仍使用原 ID，既不破坏幂等、审计和关联能力，也避免把 `agent_*`、Run、ToolCall 等实现细节暴露给常规操作界面。协作任务模型继续由最终执行专家决定：专家绑定模型优先，否则回退项目默认；子专家有独立绑定时覆盖父 Run，否则继承父模型。
- 思路：任务执行完成应由负责 Agent 或 Team Leader 基于执行证据判断并提交，而最终是否接受交付属于人工审核权限。人工干预是贯穿所有节点的控制能力，不应伪装成一种 assignee；把自由状态编辑改成显式动作，才能让权限、前置条件、唤醒行为、原因和审计记录保持一致。
- 思路：评论和活动不是两套独立进度源，而是同一协作事实的讨论视图与审计视图；前端必须以同一任务快照刷新，才能避免时间线已有新事件而评论仍停留在旧内容。阶段轨道负责回答“现在走到哪”，动态时间线负责回答“谁做了什么”，评论区保留完整决策上下文。
- 思路：长期协作任务与一次性执行 Session 不能在历史列表中重复占位；统一侧栏保留项目和人工分组的定位能力，同时以任务为聚合根展示全部关联 Run。删除动作继续服从既有审计边界，不能为了界面一致性级联抹除已经发生的协作事实。
- 思路：任务取消是人工在任意节点都应具备的控制动作，不能因为 Run 活跃而从界面消失；但只改任务状态会让后台执行继续写入结果，因此必须先持久化取消 Run 树并向模型和 Sandbox 发出中断，再落长期任务终态。
- 验证：`node --check paicli-server/src/main/resources/static/app.js` 通过；新增服务测试覆盖人工负责人拒绝、Agent 禁止直接完成、指定 Agent 提交待验收、Team Leader 全部状态权限、成员禁止直接阻塞团队任务和人工最终验收，Store 测试覆盖子任务进入待验收后阶段屏障完成。全模块 `clean package` 通过，Common 3 项、Server 148 项、Sandbox 3 项，共 154 项测试通过并生成可执行 JAR；Windows SQLite JDBC 清理旧临时 DLL 时仍记录既有 `AccessDeniedException`，不影响测试与构建。启动新服务后通过浏览器验证桌面创建主字段对齐、高级文本框布局、移动断点单列、详情状态徽标和 TODO 人工动作，工作区、详情头与操作区均无横向溢出；临时验证任务已删除，本次加载后没有新增前端错误。返回入口修正再次通过 Node 语法检查和 Server `package -DskipTests`，并使用“写一个推箱子小游戏”的真实关联 Run 验证“打开会话 → 顶部返回入口 → 恢复原任务执行层”；桌面与移动断点下 Header 均无控件重叠或溢出。最终版本再次通过 `CollaborationStoreTest`（2 项）、Server `package -DskipTests`、`git diff --check` 和健康检查；真实任务详情的 Trigger/Delegation 执行均返回 `openai-compatible/kimi-k3`，确认 Console 可展示实际模型而不是 Run 内部 ID 或模糊默认值。本次未改变 Sandbox、启动配置或产品站，因此 `docs/docker-sandbox.md`、README 配置/启动说明和 `paicli-site/README.md` 不适用。
- 验证：本轮过程可视化与全局导航修正通过 Node 语法检查、`WebSecurityIntegrationTest` 12 项、全模块 `package -DskipTests`、`git diff --check` 和服务健康检查。使用真实“写一个推箱子小游戏”任务执行无界面 Edge 验收：页面同时显示 3 条评论、4 个协作阶段和 19 条中文语义动态，不再出现 `RUN_TRIGGERED/STATUS_CHANGED/COMMENT_POSTED` 原始代码；等待 3.4 秒观察到下一次详情请求，确认自动同步生效。1600px、1024px 和 390px 三种视口均保持全局三模式入口可见且无横向溢出，打开关联会话后入口与“返回协作任务”同时可用。
- 验证：统一历史与任务删除入口通过 `node --check`、`CollaborationStoreTest` 2 项、`WebSecurityIntegrationTest` 12 项和全模块 `package -DskipTests`；最终静态资源版本再次通过 12 项 Web 安全集成测试。使用本地真实数据验证 8 个 Task-Run Session 折叠为 1 条任务记录，侧栏最终显示普通 5 条、专家 1 条、任务 1 条，保留“工作/未分组”及项目 `default` 分组，任务负责人映射为“前端开发”；点击普通/专家/任务记录会同步模式高亮和内容跳转，普通/专家菜单保留移动分组与删除，任务菜单和详情均提供删除入口。已有执行历史的任务点击删除后显示持久化内联审计提示；1440px 与 390px 视口均无横向溢出，移动抽屉可见全部三类记录。验收过程中停止旧服务产生的历史 `Failed to fetch` 日志未在最终服务启动后继续出现。本次不改变 Sandbox、配置、启动方式或产品站，因此 `docs/docker-sandbox.md`、README 配置/启动说明和 `paicli-site/README.md` 不适用。
- 验证：活跃任务取消修正通过 `CollaborationServiceTest` 7 项与 `WebSecurityIntegrationTest` 12 项，共 19 项；新增用例验证关联 Run 树持久化取消后逐一中断模型请求和 Sandbox。全模块 `package -DskipTests` 成功，服务重启后使用真实“写一个推箱子小游戏”任务确认在 `IN_PROGRESS` 且存在活跃 Run 时，“人工操作”区显示“取消任务”；仅检查入口，未确认取消，现有任务和 Run 数据未被修改。`node --check` 与 `git diff --check` 通过。

## 2026-08-04

### 效率工作台批量物理删除

- 变更：效率工作台的 Run 队列、长期记忆地图、Artifact 工作台和持久化审批策略增加勾选、全选/清空与“永久删除已选”；Run 非终态记录不可勾选，操作前明确提示数据库及关联内容会真实删除且不可恢复。
- 变更：新增 Memory、Artifact、审批策略批量删除 API，并为 `/v1/productivity/queue/batch` 增加 `DELETE` 动作。单批限制 100 个 ID，先验证全部记录；任一 ID 缺失、Run 非终态或关联委派树仍有活跃执行时回滚整批。
- 变更：`SqliteRuntimeStore` 新增批量事务删除。Run 删除显式清理 Message、ToolCall、Approval、Event、Model Usage/Attempt、Memory Extraction/Usage、协作策略、委派关系、任务关联、Agent Feedback 和 Artifact 元数据，并解除 Plan、Trigger、Schedule 与 Memory 的可空引用；事务提交后清理不再被其他 Run 共享的 workspace、Artifact 目录和绑定附件文件。Artifact 独立批删在元数据事务提交后删除对象存储实体。
- 思路：批量删除不能只是前端过滤，也不能逐条调用单删后留下半批状态。数据库部分采用“完整预检 + 单事务”的全有或全无边界；文件系统不参与 SQLite 事务，因此在元数据成功提交后按受控根路径清理，同时保留仍被其他 Run 引用的共享 workspace。
- 验证：执行 `node --check paicli-server/src/main/resources/static/app.js`、`.\mvnw.cmd test "-Dtest=SqliteRuntimeStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false"` 和 `.\mvnw.cmd test "-Dtest=WebSecurityIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"`；Store 36 项、Web/API 集成 12 项均通过。新增用例覆盖缺失 Memory ID 整批回滚、活跃 Run 整批拒绝、运行关联表清空、审批策略删除及 Artifact 数据库行与实体文件同时消失，集成测试确认四组批删控件随静态资源发布。Windows SQLite JDBC 仍记录既有临时 DLL 清理 `AccessDeniedException`，不影响测试结果。本次没有新增表或列，不需要 Schema 迁移；未改变 Sandbox、启动配置或产品站，因此 `docs/docker-sandbox.md`、README 配置/启动说明和 `paicli-site/README.md` 不适用。

### 协作阶段屏障与人工验收收敛修复

- 变更：修复阶段子 Run 成功后直接调用 Store 更新状态、绕过 `persistStatus` 的问题。阶段交付现在会完成同阶段 Barrier，并以幂等 `STAGE_BARRIER` Trigger 唤醒父任务 Leader；唤醒 Run 带有读取阶段交付、继续派发或发布最终结论的明确约束。`get_collaboration_task` 同时返回每个阶段的评论和关联 Run，使新唤醒的 Leader 能读取前序交付证据。
- 变更：Team 根任务不再因为关联 Run 全部终态就直接进入 `IN_REVIEW`。系统要求存在已交付阶段，且 Leader 在最后阶段交付后发布结论评论；缺少证据时任务转为 `BLOCKED`，保留明确原因而不是把未完成编排交给人工验收。
- 变更：应用就绪时重新求值所有 `WAITING` Stage Barrier，并扫描已 `COMPLETED` 但不存在固定 `stage:{taskId}:{stage}` 幂等 Trigger 的 Barrier。对旧路径造成的“子阶段已交付但 Leader 未唤醒”任务，系统会补齐 Trigger、恢复根任务为 `IN_PROGRESS` 并创建一次 Leader 执行；已 `DONE` 或 `CANCELED` 的根任务不参与恢复。
- 思路：Run 终态只说明一次执行结束，不说明 Leader 已消费结果、完成后续阶段或形成可供人工验收的交付结论。把 Barrier、Leader 唤醒和结论证据连成状态机门槛，才能避免首个子 Agent 结束后出现伪交付。
- 验证：新增 Store 断言覆盖 `WAITING` Barrier 查询；新增 CollaborationService 用例覆盖阶段终态走 Barrier、Team 缺少后置结论时阻止 `IN_REVIEW`、满足结论证据时允许进入验收。Maven 定向测试受本机 Wrapper/依赖解析环境阻塞；交付前执行 Node 语法检查、`git diff --check` 和实际 SQLite Task/Run/ToolCall/Barrier 链路核验。未改变 REST API、Sandbox、启动配置或产品站点，因此 OpenAPI、`docs/docker-sandbox.md`、README 配置/启动说明和 `paicli-site/README.md` 不适用。

## 2026-08-01

### 阶段 22–24：AgentTeam、持久化协作任务与事件驱动 Leader

- 变更：Schema 迁移记录推进到 30。AgentTeam 新增团队指令、成员角色说明、能力标签、路由/完成策略、回退 Agent 和最大并发；Evaluation Execution 新增可选团队执行者。新增 CollaborationTask、Comment、Activity、Trigger、Mention、Task-Run、Route Decision 和 Stage Barrier 表及索引，旧库使用可空列或兼容默认值原位升级。
- 变更：新增结构化 Route Preview 与真实 Trigger 两段式路由。Preview 返回 Leader、候选 Agent、匹配原因、复杂度、风险和预计并发且不创建 Run；Trigger 持久化同一 Route Decision，以全局 idempotency key 创建或复用普通 Session/Run。增强团队评测支持在现有 Suite/Case/Trial/评分器上选择 AgentTeam，Trial 固化 Leader 和团队协作策略；Starter Pack 升级到 `1.2.0`、7 个 Suite/28 个 Case。
- 变更：新增长期 CollaborationTask API，区分任务状态与一次 Run 终态，支持人工/Agent/Team 负责人、验收标准、父子任务和阶段。评论、回复、结论、Mention 与统一 Activity Timeline 保留 actor/subject/payload；Trigger Run 和 delegated child Run 都回链所属任务。删除已有执行历史的任务返回冲突，要求使用取消状态保留审计链路。
- 变更：统一 `MANUAL`、`MENTION`、`REPLY`、`RUN_EVENT` 和 `STAGE_BARRIER` 触发。用户评论默认唤醒负责人，显式 Mention 精确路由，回复 Agent 评论回到原 Agent，成员评论/终态事件唤醒 Leader；同阶段子任务全部 `DONE/CANCELED` 后只完成一次 Barrier 并唤醒父任务。Run 全部终态后任务进入 `IN_REVIEW` 或 `BLOCKED`，不会由模型自动标记 `DONE`。
- 变更：Agent 增加任务读取、评论、状态更新和阶段子任务工具，继续服从 ToolCall 先持久化、Provider 顺序、Effect、Profile 白名单和 Approval 边界。Console 首页增加“协作任务”，采用任务/协作/执行三层 Master-Detail 工作区；专家协作增加 Route Preview，小队编辑器开放增强配置和团队指标，评测中心增加单 Agent/AgentTeam 执行选择。
- 变更：统一本阶段新增下拉框的视觉样式；协作任务筛选、负责人类型/对象、任务状态、评论提及和评测团队选择器复用现有深色表单控件的背景、边框、圆角、悬停与焦点反馈，紧凑工具栏场景保持较小高度，不改变数据或交互语义。
- 思路：借鉴 issue/task 与 agent teammate 的分层方式，但保留 PaiCLI 更强的 Run、ToolCall、Approval、Sandbox 和恢复边界。任务是长期协作事实，Run 是短期执行尝试，评论/事件是唤醒信号；把三者分表并用幂等 Trigger 连接，才能让服务恢复、重复事件和多次协作执行保持可审计，而不是依赖聊天文本推断状态。
- 验证：前端 `app.js` 通过 Node `--check`，Starter Pack JSON 通过解析。运行 `CollaborationStoreTest`、`SqliteRuntimeStoreTest`、`EvaluationServiceTest` 共 40 项定向测试全部通过，覆盖迁移 28–30、评论/提及、Trigger 幂等、Task-Run、Route Decision、团队指标、阶段屏障和 AgentTeam Trial；随后执行全模块 `clean package`，Common 3 项、Server 142 项、Sandbox 3 项，共 148 项测试全部通过且 Spring Boot 可执行 JAR 打包成功。启动本地服务后 `/v1/system/info` 返回阶段 24；浏览器核对桌面和移动断点下的协作任务工作区、增强小队表单与团队字段，移动视口无横向溢出，新服务加载后无前端控制台错误。SQLite JDBC 在 Windows 清理旧临时 DLL 时记录既有 `AccessDeniedException`，不影响测试和构建结果。本次未改变 Sandbox 镜像、命令执行协议、启动配置或产品站，因此 `docs/docker-sandbox.md`、README 配置/启动说明和 `paicli-site/README.md` 不适用。
- 验证：下拉框视觉修正通过 Node `--check`、Server `package -DskipTests` 和浏览器计算样式检查；任务筛选、负责人类型和负责人对象均为 40px 高，使用 `#101319` 背景与现有边框色，评测团队选择器与工具栏按钮对齐，浏览器未记录本地脚本错误。该修正只调整 Console 控件样式，不改变 Runtime、API、数据结构或阶段范围，因此无需再次修改 README、架构、阶段和 OpenAPI 文档。

## 2026-07-31

### Context / Memory Harness 核心能力补齐

- 变更：Schema 迁移 27 为 `memory_extractions` 增加不可变 `source_snapshot_json`，job 创建时冻结所属 Run 的 Message id、sequence、role、content 和 tool call 引用；`memory_sources` 增加证据 Message id 列表与起止 sequence。自动提取只能读快照，提取结果保存模型引用的真实证据范围；`GET /v1/memories/{memoryId}/sources` 的 OpenAPI 说明同步公开新增来源字段。
- 变更：Context Manifest 从缓存观测扩展为统一上下文决策记录，新增各区块 Token、PlanState、RAG citation、Memory id/选择理由、动态工具与丢弃来源。Conversation Summary 固定为目标约束、计划、已验证事实、未验证假设、决策、失败尝试、待办和证据八节，模型摘要缺节、乱序或超预算时回退到同 Schema 的确定性摘要。
- 变更：Tool Catalog 默认只向模型常驻核心文件/命令/Artifact 与 `tool_search` Schema；Knowledge、Skill、Web、MCP 和 Multi-Agent Provider 作为延迟目录，模型搜索命中后才在下一轮加载完整 Schema。显式 Agent Profile 白名单继续作为能力上限。
- 变更：Memory Worker 对高相似候选归并 canonical key，中相似候选写入 OPEN conflict，定期把长期未访问的 L1 标记为 STALE；召回增加类型配额和 `memory_usage_feedback`，记录每个 Run 选入的 Memory，并由 Run 终态及 Plan 验证结果回写完成、失败、验证通过或返工结果。
- 变更：官方 Evaluation Starter Pack 升级到 `1.1.0`、6 个 Suite/25 个 Case，新增默认关闭的 Context/Memory Harness 专项套件，覆盖长会话约束、摘要续作、错误记忆抵抗、冲突修正、工具发现和统一预算。README、架构与阶段文档同步说明六项能力、迁移 27、来源 API 和专项评测。
- 思路：执行层可靠之后，Context 与 Memory 必须具有明确的快照边界、总预算、选择清单、证据引用和效果反馈，才能在长会话中稳定续作并可解释地调优。按需工具加载同时减少无关 Schema Token 和缓存前缀抖动，但不会扩大 Agent Profile 已授权工具范围。
- 验证：运行 `.\mvnw.cmd -pl paicli-server -am '-Dtest=MemoryStoreTest,ContextManagerTest,ExtractiveSummarizerTest,ToolCatalogTest,EvaluationStarterPackServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，14 项专项测试通过；随后运行 `.\mvnw.cmd test`，完整 Reactor 146 项测试通过，覆盖快照冻结、source span、Memory 反馈、八节摘要、按需工具发现/跨摘要激活、统一预算、迁移 27 和 Starter Pack 幂等安装，并执行 `git diff --check`。本次未改变 Sandbox 镜像、启动配置或产品站，因此 `docs/docker-sandbox.md`、配置说明和 `paicli-site/README.md` 不适用。

### 强制文档同步交付门禁

- 变更：强化根目录 `AGENTS.md` 的文档硬规则：任何仓库写入都必须在同一工作项和同一次提交更新 `changeLog.md`；并按影响范围强制同步 README、架构、阶段、OpenAPI、配置运行、Sandbox 和产品站文档。新增交付前文件清单与格式检查，遗漏对应文档时不得宣告任务完成。
- 变更：README 新增“文档同步门禁”章节，以变更类型到必更文档的矩阵公开同一规则，避免规则只存在于 Agent 入口文件。
- 思路：`changeLog.md` 负责记录演进，但不能替代当前能力、接口和运行方式文档。将影响映射和“不适用必须说明理由”写成硬门禁，才能避免代码已变化而 README、架构或 API 说明继续陈旧；纯问答和只读诊断没有仓库修改，不制造无意义日志。
- 验证：核对 `AGENTS.md` 与 README 的文档矩阵一致，确认本次规则修改已同步写入 `changeLog.md`；执行 `git diff --check`。本次不改变 Runtime、API、数据库、配置、Sandbox 或阶段完成度，因此 `docs/architecture.md`、`docs/phases.md`、OpenAPI 和 `docs/docker-sandbox.md` 不适用。

### 缓存友好的上下文模板、统一输入预算与 Memory 来源隔离

- 变更：ContextManager 将基础 Prompt、专家配置、项目规则和 Skill 索引组织为稳定系统前缀，将既有摘要和当前 Run 之前的会话历史移到 Run 动态块之前，再追加持久化 Run 基准时间、工作区、RAG、Memory 与当前 Run 消息；同一 Run 的多轮模型调用不再因 `Instant.now()` 改写早期 Prompt，跨 Run 也能在动态检索内容变化前复用既有长会话前缀。
- 变更：统一输入预算新增 Tool Definition Token 估算；必需指令、历史、当前 Run 消息和工具 Schema 先占预算，RAG 与 Memory 在剩余预算中分配并有界裁剪，避免压缩成功后因后置检索块或全量工具 Schema 再次超窗。每轮新增 `context.prepared` Event，记录可复用前缀 Token/SHA-256、工具 Token、消息分区以及 RAG/Memory 纳入和裁剪状态。
- 变更：自动 Memory Worker 改为只读取 extraction job 对应 Run 的持久化消息窗口，不再读取处理时整个 Session 的最新消息，避免快速连续提交多个 Run 时后续内容被错误归因到较早 Run。
- 变更：README 与 `docs/architecture.md` 新增独立“Prompt Cache 命中率优化”章节，记录优化前约 6.25% 的观测基线、旧前缀失效原因、新消息分层顺序、统一预算和 Context Manifest，并明确使用部署后的增量窗口评估效果及供应商侧限制。
- 思路：Prompt Cache 按共同前缀复用；任何位于长历史之前的时间戳、检索结果或 Memory 变化都会让后续缓存失效。因此稳定规则和既有历史必须前置，任务动态材料后置，同时所有实际发送给模型的 Message 与 Tool Schema 必须进入同一个预算。Context Manifest 只保存结构化选择元数据和哈希，不重复保存敏感完整 Prompt。
- 验证：运行 `.\mvnw.cmd -pl paicli-server -am "-Dtest=ContextManagerTest,MemoryStoreTest,RunProcessorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，14 项定向测试通过；随后运行 `.\mvnw.cmd test`，全模块 141 项测试通过，覆盖稳定前缀顺序及哈希、Run 基准时间稳定性、工具 Schema 计入估算、Memory 超限裁剪、按 Run 隔离提取来源及既有审批/恢复/Plan/Sandbox 回归。`clean test` 因正在运行的 Server 锁定既有 JAR 无法清理而停止，未擅自终止服务。

### 定时任务固定模型、专家与小队

- 变更：定时任务新增持久化的模型方案、执行专家和执行小队选择。模型选择直接写入 Run；专家作为 Run 的 Agent Profile；小队使用其 Leader 并将成员、并发和审查要求保存为协作策略与可恢复 Plan。旧任务的新增字段均为空，继续保留原有模板/服务端默认回退行为。
- 思路：聊天工具栏中的模型选择仅代表当前交互，不应隐式影响后台任务。将实际执行配置与定时任务一起保存，才能保证定时触发的可预期性和审计可追溯性；专家与小队互斥，避免两个执行主体产生歧义。
- 验证：新增 SQLite 字段兼容迁移和 Store 回归断言；执行前端语法检查与 Maven 定向测试。

### 多 Shell 技术架构与面试材料同步

- 变更：更新《PaiCLI Platform Lite 技术架构与面试讲解》和《PaiCLI Platform Lite 技术架构与面试指南》，补充 `execute_command` 的 sh/bash/PowerShell Core 固定映射、结构化参数、默认 Shell 持久化时点、Retry/委派继承、环境变量过滤、stdout/stderr 与截断元数据、超时进程树终止和容器级取消；同步 Console 展示、Schema 1–26、简历表述和多 Shell 高频面试问答。
- 思路：把“支持 Bash/PowerShell”讲成受 ToolCall、Approval、幂等恢复和 Docker 边界共同约束的执行协议，避免误导为开放任意解释器或 Windows 宿主 PowerShell；同时明确完成后 ToolResult 与实时终端的区别，保留逐行 SSE、PTY、stdin 和后台进程生命周期作为后续演进项。
- 验证：交叉检索两份文档中的 `execute_command`、PowerShell、`execution_shell`、Schema 版本、PTY 与能力边界，执行 `git diff --check` 并检查 Markdown 标题、代码围栏和 Mermaid 围栏结构。

## 2026-07-30

### Docker Sandbox 多 Shell 命令运行时

- 变更：`execute_command` 新增 `sh`、`bash`、`powershell` 白名单，以及 workspace 内 `cwd`、请求级超时、输出字节上限和显式非敏感环境变量；Docker 镜像改为同时包含 Java 17、Bash 与 PowerShell Core。命令结果区分 stdout/stderr，并记录实际 Shell、退出码、耗时、超时、输出字节数和截断状态，长结果继续由 Artifact Store 外置。
- 变更：Run 和 Agent Profile 新增兼容迁移字段 `execution_shell`。模型未显式传 Shell 时，RunProcessor 在同轮 ToolCall 原子持久化与 Approval 前补齐默认值；委派子专家继承自身 Profile 或父 Run 的执行环境。Console 首页、对话工具条、专家配置与事件详情统一接入 Shell 选择；取消 Run 会销毁独占 Docker 容器并报告是否中断 Sandbox 执行。
- 思路：把“支持 Bash/PowerShell”落实为可持久化、可审批、可恢复的执行协议，而不是开放任意解释器或 Windows 宿主机命令。命令进程从空环境启动，只注入固定基础变量和通过校验的请求变量；Local Sandbox 继续拒绝命令执行。逐行输出 SSE、PTY 与后台服务生命周期仍明确保留为后续增量。
- 验证：`mvn test` 全模块 138 项通过，`mvn -pl paicli-sandbox-agent -am -DskipTests package` 通过；`node --check paicli-server/src/main/resources/static/app.js`、`git diff --check` 通过。真实构建 `paicli-sandbox-agent:shell-test` 镜像后，镜像内解释器和 Sandbox Agent HTTP 链路均返回 `SH_OK/BASH_OK/POWERSHELL_OK`。全模块 `package` 仅因运行中服务锁定既有 `paicli-server` JAR、Boot repackage 无法改名而未完成，未擅自停止该进程。

### Memory Wiki follow-up UI correction

- Change: Restored the productivity workbench to its original vertical section layout. Memory management rows and Wiki pages now use a concise summary derived from memory content as the visible title, while the internal key remains a stable identifier only. Fixed the relationship-map view switch by giving `[hidden]` priority over the layout display rule, and refreshed static asset versions so browsers load the correction.
- Rationale: The Wiki must make existing memories easier to scan without changing, upgrading, overwriting, or deleting their stored data. The relationship map remains a read-only L1/L2/L3 card map, but now reliably replaces the page view when selected.
- Verification: `node --check paicli-server/src/main/resources/static/app.js`, targeted Memory/Console integration tests, and `git diff --check`.

### Memory LLM Wiki（保留全部既有记忆）

- 变更：新增 `/v1/memories/wiki`、单页 Wiki 与来源查询 API，并在 Console 增加可搜索、可跳转的 Memory Wiki 页面和关系地图；页面展示关联、反向引用、来源、置信度与当前治理状态，地图以 L1/L2/L3 分栏卡片呈现并可点击回到对应页面。Wiki 标题从内容首句提炼，内部 key 仅作为稳定标识。效率工作台同步重构为项目控制台：将检索、用量、复用、执行、自动化、记忆、成果与治理拆为色彩区分的面板，记忆入口改名为“长期记忆地图”。
- 思路：Wiki 仅从现有 `memories`、标签和审计来源派生，不创建第二份知识库，也不升级、覆盖或删除旧 Memory。避免把技术 key 当作用户可读标题：自动提取要求首句先给出独立概括，现有 Memory 则动态从内容派生标题；LLM 仅在事实确有依赖时写入 `[[canonical-key]]` 显式关联，人工仍通过原有修订功能纠错。
- 验证：`git diff --check`、`node --check paicli-server/src/main/resources/static/app.js`，以及 `mvn -pl paicli-server -am "-Dtest=MemoryStoreTest,WebSecurityIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过（14 tests）。

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

### 单 Run 默认 30 分钟误截断修复

- 变更：`PAICLI_MODEL_MAX_RUN_DURATION_SECONDS` 默认值由 `1800` 改为 `0`，表示默认不按 Run 墙钟时长截断；`RunProcessor` 仅在该配置为正数时执行时长预算判定，预算快照在关闭时显示 `elapsedSeconds=<当前值>/unlimited`。
- 思路：长思考模型以及等待子 Agent 的父 Run 可能合理运行超过 30 分钟，创建时间到当前时间的墙钟差不等于模型卡死。默认关闭这一硬截断，同时保留最大步骤数、每轮/每 Run 工具调用次数、重复工具调用、单次模型请求和流空闲超时，继续约束循环与网络异常；需要成本或 SLA 硬边界的部署仍可显式设置正秒数。
- 验证：`PaiCliServerApplicationTest` 与 `RunProcessorTest` 定向 6 项通过；`.\mvnw.cmd test` 全模块 133 项通过；`git diff --check` 通过。

### DeepSeek / Kimi K3 双模型自由切换

- 变更：项目模型方案接口幂等补齐 DeepSeek V4 Flash 与 Kimi K3，分别从 Server 环境变量 `PAICLI_DEEPSEEK_API_KEY`、`PAICLI_KIMI_API_KEY` 读取密钥；新增模型 starter-pack API，密钥仍不进入浏览器、SQLite 或 Sandbox。
- 变更：首页新增双引擎模型轨道，对话输入区和智能体专家设置复用同一模型方案；选择状态持久化在浏览器本地，专家仍可绑定自己的独立方案。
- 变更：OpenAI-compatible 客户端按实际路由模型生成请求。Kimi K3 使用 `max_completion_tokens`，省略 K2.x 专属 `thinking`，支持 `reasoning_effort=low|high|max` 并保持 reasoning/tool_calls 多轮回传；K3 Run 强制记录为思考开启。
- 变更：`.env.example`、README、OpenAPI 可见接口与静态资源版本同步更新。
- 思路：模型选择应落到持久化 Model Profile 和每个 Run 的明确路由，而不是在浏览器保存供应商密钥；供应商差异集中在模型客户端能力适配层，避免把 Kimi 参数判断散落到业务 Runtime。
- 验证：`OpenAiCompatibleModelClientTest`、`SqliteRuntimeStoreTest`、`WebSecurityIntegrationTest` 定向 52 项通过；`.\mvnw.cmd test` 全模块 133 项通过；`node --check paicli-server/src/main/resources/static/app.js`、`git diff --check` 与跳过 Spring Boot 重打包的全模块 `package` 通过。标准 `clean test/package` 未完成的唯一原因是本机既有 Java 进程锁定 `paicli-server-0.6.0-SNAPSHOT.jar`，其中 `clean` 无法删除该文件、Boot repackage 无法重命名为 `.original`；未擅自结束该进程。

### 模型流截断恢复

- 变更：OpenAI-compatible 接口返回 HTTP 200 后，如 SSE JSON 在传输中被截断、连接 I/O 中断、流读取超时或 tool call 不完整，模型客户端会记录失败 attempt、退避并重新请求，不再立即将 Run 标记失败。
- 变更：跨流重试保持 model attempt 序号连续，便于审计供应商或网关的间歇性截断。
- 思路：把“成功建立 HTTP 响应”和“完整消费模型流”视为同一次 attempt 的两个阶段；只有流完整解析后才提交成功，截断响应会关闭后重试。
- 验证：新增 `OpenAiCompatibleModelClientTest.retriesWhenSuccessfulHttpResponseContainsTruncatedSseJson`；全项目 127 个测试通过。

### SQLite 并发写入等待修复

- 变更：SQLite 连接为显式事务统一配置 `IMMEDIATE` transaction mode，使写事务在开始时获取写锁并遵循现有的 30 秒 `busy_timeout`。
- 变更：修复父子 Agent 并发持久化时，Tool outcome 从读事务升级为写事务会立即触发 `SQLITE_BUSY`、导致子 Run 失败且 ToolCall 残留 `RUNNING` 的问题。
- 思路：DEFERRED 事务在首次读取后形成读快照，随后升级写锁时遇到并发写入可能直接返回 `SQLITE_BUSY`；IMMEDIATE 让显式写事务在开始时排队竞争唯一写锁，自动提交读取保持不变。
- 验证：新增 `SqliteRuntimeStoreTest.waitsForConcurrentWriterBeforeCommittingToolOutcome`；执行 `.\mvnw.cmd clean package -DskipTests` 成功，修复后的本地服务健康检查通过。

### 物资盘点 Agent 技术实现方案深化

- 变更：扩展 `stockAgent.md`，在周转物资 SKU 盘点和循环物资 SN 盘点业务方案后，新增 Tool Provider + Material Agent Gateway 接入拓扑、事件触发、工具契约、证据聚合、规则与模型分工、数据模型和诊断状态机。
- 变更：补充 Outbox/Inbox、ActionRequest、幂等键、证据快照、expectedVersion、Approval payloadHash、SN 条件更新、线程池隔离、Bulkhead、权限代理、降级、可观测、压测、测试、灰度和生产演进方案。
- 变更：新增 Q11–Q24 技术深挖问答、技术实现版简历写法和三分钟项目介绍，并明确现有事实、建议实现和不可过度宣传的 Lite 边界。
- 思路：将 Agent 定位为受控分析与编排层，盘点服务和 `mall-material` 继续作为单据、库存及 SN 状态的权威来源；用确定性后端机制约束模型不确定性。
- 验证：人工检查 Markdown 标题、表格、代码块和 Mermaid 结构，运行 `git diff --check`；本次仅修改文档，不执行 Maven 测试。

### 技术架构与面试指南补充 Java 后端专题

- 变更：在 Session/Run、Runtime、ToolCall、Sandbox、模型网关、Context、Memory、RAG、MCP、Multi-Agent、Plan、Step 调度、评测、治理、SSE 和 SQLite 章节后，补充事务边界、并发领取、线程池、锁竞争、幂等、限流、背压、异步派生任务、可观测性、安全与容量演进说明。
- 变更：在文末新增 Java 后端简历写法、90 秒项目介绍及 Q41–Q50 高频问答，覆盖 `synchronized` 边界、WAL/IMMEDIATE、线程池拒绝、流式批量持久化、模型限流和慢任务诊断等内容。
- 思路：将现有可运行代码中的具体实现与明确的 Lite 边界一并写清，避免把单机 SQLite/Docker 方案包装成未经验证的分布式高并发能力。
- 验证：人工交叉核对 `RunWorkerCoordinator`、`ExecutorConfiguration`、`SqliteConnectionFactory`、`SqliteRuntimeStore`、`OpenAiCompatibleModelClient`、`LayeredMemoryService`、`PlanExecutionService` 和 `application.yml`；运行文档结构与 diff 检查。

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
# Completion Evidence 复核收尾：终态委派与协作交付统一解码

- 变更：新增纯 `RunEvidenceDecoder`，将 durable ToolCall metadata → `RunEvidence` 的解析从 `RunEvidenceCollector` 中抽出。运行时 Collector 与 `SqliteRuntimeStore` child terminal delegation envelope 共同使用该 Decoder；终态 `result_json` 因而只记录 `changed=true` 的文件、仅由 `TestCommandClassifier` 产生测试证据，并通过 `businessArtifacts()` 排除 `tool_result`，不再保留第二套关键字/路径启发式。
- 变更：`CollaborationService` 的阶段交付门禁改为直接消费 `RunEvidence`，并只调用 `DeliveryManifestService.recordStageDelivery(taskId, stage, runId)`；不再按共享 workspace 的修改时间推断文件归属、不过滤缺失的 `tool_result`、也不从命令文本猜测测试。DeliveryManifest 额外记录 `workspaceMutations`，让仅能由 command fingerprint 证明的交付与门禁、清单保持一致。
- 变更：收紧 `TestCommandClassifier`：`-Dtest`/`-Dit.test` 是 Maven selector 而不是测试 goal；Gradle 仅把显式 `test`/`check` 任务作为测试，`testClasses`、`checkstyleMain` 不再生成 TestEvidence。
- 验证：新增 SQLite terminal delegation envelope、统一 DeliveryManifest 和实际协作阶段调用路径的回归测试，并扩展分类器用例。定向 reactor 测试 `TestCommandClassifierTest`、`CollaborationServiceTest`、`TaskDigestManifestTest`、`SqliteRuntimeStoreTest` 共 96 项通过；`./mvnw.cmd clean test`、`./mvnw.cmd clean package` 全量均通过（common 3 + server 288 + sandbox-agent 4 = 295）。`git diff --check` 与文档覆盖复核通过。
- 文档：README、`docs/architecture.md`、`docs/phases.md` 已同步。本次未修改 Sandbox 行为、配置、REST/OpenAPI 或产品站，故 `docs/docker-sandbox.md`、OpenAPI 与 `paicli-site/README.md` 不适用。
