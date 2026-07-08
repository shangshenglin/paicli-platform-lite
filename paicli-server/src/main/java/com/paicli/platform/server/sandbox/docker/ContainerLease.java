package com.paicli.platform.server.sandbox.docker;

import java.nio.file.Path;

public record ContainerLease(
        String runId,
        String containerId,
        String containerName,
        String token,
        Path workspace
) {
}
