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
- 命令输出由限额收集器持续排空，超时会终止 Shell 进程树。

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

## 已知限制

- Docker Desktop/WSL2 需要单独安装。
- 尚未实现容器暂停或检查点；新容器会继续使用同一持久化工作区。
- 尚未实现网络白名单；当前网络完全隔离于外网。
- Windows Bind Mount 可能比原生 Linux Volume 慢。

## 验收记录

Windows Docker Desktop/WSL2 端到端验收已于 2026-07-03 通过，覆盖审批恢复、经 `docker exec` 的认证执行、工作区持久化、SSE 重放、容器自动清理、无宿主端口的内部网络、只读根文件系统、`cap-drop ALL` 以及 CPU、内存和 PID 限额。
