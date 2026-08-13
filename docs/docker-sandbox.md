# Docker Sandbox

## 适用范围

Docker 模式是企业级 MicroVM 池的轻量替代，可改善进程、文件系统和资源隔离，但不等同于面向敌对代码的强化执行服务。应持续更新 Docker Desktop 和基础镜像，并且绝不能把 Docker Socket 挂载进 Sandbox。默认网络为 Docker `none`；Server 的控制通道不依赖容器网络。

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

- 默认使用 Docker `none` 网络，不提供任何容器网络接口；不同 Run 也不共享内部网络。显式配置自定义网络时，Server 会验证该网络为 internal，不满足时拒绝启动；不存在时仅以 `--internal` 创建。
- 不向宿主机发布任何容器端口。
- 控制通道是 `docker exec` 加容器 `127.0.0.1:8081` 上的带认证 HTTP。
- 每容器使用随机 Bearer Token；缺少 Token 时 Sandbox Agent 拒绝启动，并使用常量时间比较。
- 根文件系统只读，仅 Run workspace、`/tmp` tmpfs 和 `/home/sandbox` tmpfs 可写。HOME tmpfs 只对 UID/GID `10001` 开放，容量独立可配，随 Run 回收。
- 容器显式使用非 root UID/GID `10001`，并启用 Docker init 回收孤儿进程。
- 启用 `no-new-privileges` 和 `cap-drop ALL`。
- CPU、内存、PID、`/tmp`、HOME tmpfs 和共享内存限额可配置。
- 只把当前 Run 工作区以读写方式挂载。
- 工具路径必须解析到 `/workspace` 以下，并检查符号链接。
- 命令只允许 `sh`、`bash`、`powershell` 三个逻辑名称，固定映射到镜像内的 `/bin/sh`、`/bin/bash` 和 PowerShell Core `/usr/bin/pwsh`，不接受解释器路径。
- 命令支持 workspace 内 `cwd`、1 秒至部署上限的 `timeoutSeconds`、1 KiB 至 4 MiB 的 `maxOutputBytes`，以及最多 32 个显式非敏感环境变量。
- 命令进程清空继承环境后再注入固定基础变量和已校验的 `env`，不会继承 Sandbox Agent Token。Maven、Gradle、npm、pip、NuGet、Go/Rust 等缓存路径固定到临时 HOME；Python bytecode 和通用 XDG/.NET 临时数据写入 `/tmp`。
- 默认镜像提供 JDK 17、Maven、Node.js/npm、Python/pip/venv、Git、Bash、PowerShell Core、curl 和 unzip；依赖下载仍受网络策略和 Approval 约束。
- stdout/stderr 分开限额收集并持续排空；结果包含退出码、实际 Shell、耗时、超时和截断元数据，长结果由 Server 外置为 Artifact。
- 超时会终止 Shell 进程树；取消 Run 会 `docker rm -f`，中断容器内活跃命令。
- 普通读取、构建和测试命令在 ToolCall 持久化后直接执行；删除/清空、提权、权限修改、进程/系统控制、破坏性 Git/数据库操作、下载安装、远程执行、发布和部署命令必须先完成持久化审批。命令缺失或参数无法解析时按危险命令处理。

## 配置

```yaml
paicli:
  sandbox-mode: docker
  docker:
    executable: ${PAICLI_DOCKER_EXECUTABLE:docker}
    image: ${PAICLI_DOCKER_IMAGE:paicli-sandbox-agent:0.6.0}
    network: ${PAICLI_DOCKER_NETWORK:none}
    memory: ${PAICLI_DOCKER_MEMORY:1g}
    cpus: ${PAICLI_DOCKER_CPUS:1.0}
    pids-limit: ${PAICLI_DOCKER_PIDS_LIMIT:128}
    tmpfs-size: ${PAICLI_DOCKER_TMPFS_SIZE:256m}
    home-tmpfs-size: ${PAICLI_DOCKER_HOME_TMPFS_SIZE:512m}
    shm-size: ${PAICLI_DOCKER_SHM_SIZE:64m}
    startup-timeout-seconds: ${PAICLI_DOCKER_STARTUP_TIMEOUT_SECONDS:30}
    command-timeout-seconds: ${PAICLI_DOCKER_COMMAND_TIMEOUT_SECONDS:90}
```

## 已知限制

- Docker Desktop/WSL2 需要单独安装。
- 尚未实现容器暂停或检查点；新容器会继续使用同一持久化工作区。
- 尚未实现网络白名单；默认 `none` 网络完全隔离于外网。自定义 internal 网络只适合管理员提供的受控代理/sidecar，不会自动形成域名白名单。
- 尚未实现逐行 stdout/stderr SSE、PTY 交互和脱离 Run 生命周期的后台服务管理。
- Local Sandbox 不执行命令；不会回退到 Windows 宿主机 PowerShell。
- Windows Bind Mount 可能比原生 Linux Volume 慢。

## 验收记录

Windows Docker Desktop/WSL2 端到端验收已于 2026-07-03 通过，覆盖审批恢复、经 `docker exec` 的认证执行、工作区持久化、SSE 重放、容器自动清理、无宿主端口的内部网络、只读根文件系统、`cap-drop ALL` 以及 CPU、内存和 PID 限额。2026-08-13 的默认 `none` 网络、internal 网络校验、固定非 root 用户、可写 HOME 与扩展工具链增强已通过 Server/Sandbox Agent 单元测试；当前开发终端没有 Docker CLI，尚未重建镜像进行新的端到端验收。
