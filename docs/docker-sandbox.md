# Docker Sandbox

## 适用范围

Docker 模式是企业级 MicroVM 池的轻量替代，可改善进程、文件系统和资源隔离，但不等同于面向敌对代码的强化执行服务。应持续更新 Docker Desktop 和基础镜像，并且绝不能把 Docker Socket 挂载进 Sandbox。

## 生命周期

```text
Run 的第一次工具调用
  -> 创建宿主机工作区
  -> 启动受限容器
  -> 通过 docker exec 等待 Sandbox Agent 健康
  -> 通过 docker exec 调用容器 loopback 上的带认证 HTTP

同一 Run 的后续工具调用
  -> 复用容器

Run 完成 / 失败 / 取消
  -> docker rm -f
```

Server 重启后，带 `paicli.platform.managed=true` 标签的容器视为孤儿并清理。工作区保留在磁盘，因此持久化 ToolCall 可在新容器中继续。

本机 Docker 模式使用 `scripts/start-docker.ps1` 启动。脚本发现 8080 已有 PaiCLI 时默认幂等返回；需要重建并重启时使用 `-Restart`，脚本会先停止旧服务再打包，避免 Windows 对运行中 Server JAR 的文件锁。日常启动构建默认跳过测试，完整回归测试由 Maven 命令显式执行；独立的 `build-sandbox.ps1` 仅构建 Common 与 Sandbox Agent，可用 `-RunTests` 运行这两个模块的测试。

## 容器限制

- 使用专用 Docker `--internal` 网络，不提供外部路由。
- 不向宿主机发布任何容器端口。
- 控制通道是 `docker exec` 加容器 `127.0.0.1:8081` 上的带认证 HTTP。
- 每容器使用随机 Bearer Token；缺少 Token 时 Sandbox Agent 拒绝启动，并使用常量时间比较。
- 根文件系统只读，仅 `/tmp` tmpfs 可写。
- 启用 `no-new-privileges` 和 `cap-drop ALL`。
- CPU、内存和 PID 限额可配置。
- 只把当前 Run 工作区以读写方式挂载。
- 工具路径必须解析到 `/workspace` 以下，并检查符号链接。
- 命令只允许 `sh`、`bash`、`powershell` 三个逻辑名称，固定映射到镜像内的 `/bin/sh`、`/bin/bash` 和 PowerShell Core `/usr/bin/pwsh`，不接受解释器路径。
- 命令支持 workspace 内 `cwd`、1 秒至部署上限的 `timeoutSeconds`、1 KiB 至 4 MiB 的 `maxOutputBytes`，以及最多 32 个显式非敏感环境变量。
- 命令进程清空继承环境后再注入固定基础变量和已校验的 `env`，不会继承 Sandbox Agent Token。
- stdout/stderr 分开限额收集并持续排空；结果包含退出码、实际 Shell、耗时、超时和截断元数据，长结果由 Server 外置为 Artifact。
- 超时会终止 Shell 进程树；取消 Run 会 `docker rm -f`，中断容器内活跃命令。
- 普通读取、构建和测试命令在 ToolCall 持久化后直接执行；删除/清空、提权、权限修改、进程/系统控制、破坏性 Git/数据库操作、下载安装、远程执行、发布和部署命令必须先完成持久化审批。命令缺失或参数无法解析时按危险命令处理。

## 配置

```yaml
paicli:
  sandbox-mode: docker
  docker:
    image: paicli-sandbox-agent:0.6.0
    network: paicli-sandbox-internal
    memory: 1g
    cpus: 1.0
    pids-limit: 128
    startup-timeout-seconds: 30
    command-timeout-seconds: 90
```

## 结构化工具证据

- `write_file` 返回结构化 metadata：`path`、`changed`（写入前后 sha256 不同）、`beforeSha256`、`afterSha256`、`bytesWritten`；相同内容重写 `changed=false`，不进入 `files_changed` 证据。
- `execute_command` 返回 `exitCode`、`timedOut`、`shell`、`cwd`、`durationMs` 等 metadata；测试族由 Server 侧 `TestCommandClassifier` 按命令精确识别（`mvn compile`、`./check-status.sh` 不算测试）。
- metadata 随 `ToolResult.metadata` 持久化到 `tool_calls.result_metadata_json`（迁移 39），供 `RunEvidenceCollector` 作为完成验证、AgentResult 与交付清单的统一证据来源。
## 已知限制

- Docker Desktop/WSL2 需要单独安装。
- 尚未实现容器暂停或检查点；新容器会继续使用同一持久化工作区。
- 尚未实现网络白名单；当前网络完全隔离于外网。
- 尚未实现逐行 stdout/stderr SSE、PTY 交互和脱离 Run 生命周期的后台服务管理。
- Local Sandbox 不执行命令；不会回退到 Windows 宿主机 PowerShell。
- Windows Bind Mount 可能比原生 Linux Volume 慢。

## 验收记录

Windows Docker Desktop/WSL2 端到端验收已于 2026-07-03 通过，覆盖审批恢复、经 `docker exec` 的认证执行、工作区持久化、SSE 重放、容器自动清理、无宿主端口的内部网络、只读根文件系统、`cap-drop ALL` 以及 CPU、内存和 PID 限额。
### Completion Evidence 约束

Sandbox 返回的 `execute_command` workspace fingerprint 只说明命令前后工作区是否变化；Server 会跳过已识别测试/构建命令的生成物变化，避免 `target/`、缓存或报告把测试命令标记为源码 mutation。非测试命令的明确变化仍会进入 Run 的 workspace mutation evidence；复合测试命令若包含 `||`、`;`、管道，或测试 invocation 后还有尾部命令，则不会生成 TestEvidence。

- `write_file` 必须在写入前计算 `beforeSha256`，写入后计算 `afterSha256`；只有两者不同才报告 `changed=true`。
- `execute_command` 的结果除退出码、超时、Shell 和 cwd 外，还记录执行前后的 workspace fingerprint 与 `workspaceChanged`，供 Server 判断命令是否产生真实副作用。
- Server 不从 stdout 关键词推断完成或测试；只有 `TestCommandClassifier` 识别的高置信度测试 invocation 才生成 TestEvidence。外置工具输出的 `tool_result` Artifact 不属于业务交付物。
- 当 `execute_command` 的 workspace fingerprint 明确变化但无法可靠列出具体文件时，Server/AgentResult 使用 `workspace_mutations` 传递变更证据，不伪造 `files_changed`；换行、单独 `&`、skip/no-run 参数的命令不计为测试 invocation。
