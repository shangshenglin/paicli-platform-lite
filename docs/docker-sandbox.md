# Docker Sandbox

## Scope

Docker mode is the lightweight substitute for an enterprise MicroVM pool. It improves process, filesystem and resource isolation, but it is not equivalent to a hardened hostile-code service. Keep Docker Desktop and the base image patched, and do not mount the Docker socket into a sandbox.

## Lifecycle

```text
first tool call for Run
  -> create host workspace
  -> start restricted container
  -> wait for Sandbox Agent health via docker exec
  -> execute authenticated HTTP against container loopback via docker exec

next tool call for the same Run
  -> reuse container

Run completed / failed / canceled
  -> docker rm -f
```

After a server restart, containers carrying `paicli.platform.managed=true` are treated as orphans and removed. Workspaces remain on disk, so the durable ToolCall can continue in a fresh container.

## Container restrictions

- Dedicated Docker `--internal` network with no external routing.
- No container port is published to the host.
- The control channel is `docker exec` plus authenticated HTTP on container `127.0.0.1:8081`.
- Random per-container Bearer Token.
- Sandbox Agent startup fails when the token is absent; token comparison is constant time.
- Read-only root filesystem and writable `/tmp` tmpfs.
- `no-new-privileges`, `cap-drop ALL`.
- Configurable CPU, memory and PID limits.
- Only the Run workspace is bind-mounted read/write.
- Tool paths are resolved below `/workspace`, including symlink checks.
- Command output is drained through a bounded collector and timeouts terminate the shell process tree.

## Configuration

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

## Known limits

- Docker Desktop/WSL2 must be installed separately.
- Container pause/checkpoint is not implemented; a fresh container uses the same persisted workspace.
- Network allowlists are not implemented. The current network is entirely internal.
- Windows bind mounts may be slower than native Linux volumes.

## Acceptance record

The Windows Docker Desktop / WSL2 end-to-end check passed on 2026-07-03. It verified approval recovery, authenticated execution through `docker exec`, workspace persistence, replayable SSE, automatic container removal, an internal network with no published host port, a read-only root filesystem, `cap-drop ALL`, and the configured CPU, memory and PID limits.
