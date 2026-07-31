# AGENTS.md

本文件是新线程进入 PaiCLI Platform Lite 的首读入口。

## 定位与边界

- Java 17 + Spring Boot 3.3，单机、单租户、私有部署。
- `paicli-server` 是 Agent Runtime；`paicli-sandbox-agent` 是 Docker 内的执行边界；`paicli-common` 放跨边界 DTO。
- SQLite、本地 Artifact、进程内 Worker、Docker 分别替代 PostgreSQL、MinIO、消息队列、MicroVM。
- 不做 Kubernetes、KVM、MicroVM、多租户和复杂 Console；自动 Memory 采用可审计、可恢复的单机任务实现。

## 硬规则

- 工具调用必须先持久化，再执行；恢复时按 idempotency key 复用。
- 同一模型轮次的全部工具调用必须原子持久化，并保持 provider 顺序执行。
- 危险工具必须经过持久化 Approval，不能重新让模型生成参数。
- 模型密钥只能留在 Server，不能进入 Sandbox 环境。
- 自动 Memory 必须在 Run 完成后先创建持久化 extraction job，再由 Worker 提取；需保留来源、置信度和修订历史，显式 REST CRUD 仍是人工纠错边界。
- 数据库行为修改必须补迁移兼容和 Store 测试；API 行为修改必须同步 README/OpenAPI。
- 项目规则只从受控 data/workspace 根目录读取，并受总字符预算限制。
- 不提交 `.env`、`data/`、`backups/`、`target/`。
- 任意会话、任意工作区、任意分支只要修改代码、脚本、配置、测试、文档、静态资源或产品站点，都必须在同一工作项、同一次提交中同步更新根目录 `changeLog.md`。日志必须明确写出变更、思路和验证；没有验证必须说明原因，不能省略。
- 文档同步是完成条件，不是可选收尾。除 `changeLog.md` 外，必须按变更影响同步对应文档：运行行为/架构边界更新 `README.md` 和 `docs/architecture.md`；阶段完成度或后续范围更新 `docs/phases.md`；API 路径、请求、响应或错误语义更新 OpenAPI 注解和 README API 说明；配置、脚本、启动方式更新 README 配置/运行说明；Sandbox 行为更新 `docs/docker-sandbox.md`；产品站能力或展示更新 `paicli-site/README.md` 及相关产品说明。
- 交付前必须检查实际修改文件与文档覆盖关系。只更新代码和 `changeLog.md`、但遗漏受影响的 README/架构/API/配置/阶段文档，视为任务未完成；如果某类文档确实不适用，必须在 `changeLog.md` 的思路或验证中明确说明“不适用”及理由。
- 纯问答、只读诊断且没有修改仓库文件时，不要求制造文档变更；一旦产生仓库修改，上述文档门禁立即生效。更新 `changeLog.md` 本身不递归要求再新增一条日志。
- 模型 content/reasoning delta 必须批量持久化，不能在网络流回调中对每个 token 同步写 SQLite。
- Console 的 `.app -> .workspace -> .chat/.detail -> .messages/.events` 高度链必须保持 `min-height: 0` 和受控 Grid 行，避免内容撑高视口后让内层滚动失效。
- 删除 Session 必须先拒绝存在活跃 Run 的会话，再于同一事务删除 Approval / ToolCall / Event / Artifact / ModelUsage / MemoryExtraction / Message / Run；删除分组只将会话移到未分组。

## 验证

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd clean package
.\scripts\start-local.ps1
```

交付前还必须执行文档门禁检查：

```powershell
git diff --name-only
git diff --check
```

根据文件清单逐项确认 `changeLog.md` 和受影响文档已经同步；不能仅凭测试通过判定完成。

架构与阶段状态见 `docs/architecture.md`、`docs/phases.md`；完整演进记录和后续修改规范见 `changeLog.md`。
