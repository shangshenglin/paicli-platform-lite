package com.paicli.platform.server.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.CollaborationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskDigestManifestTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsDigestAndRecordsDeliveryAndSnapshot() throws Exception {
        PlatformProperties properties = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        CollaborationStore collaboration = new CollaborationStore(properties);
        ObjectMapper mapper = new ObjectMapper();
        TaskDigestService digests = new TaskDigestService(collaboration, runtime, mapper);
        DeliveryManifestService manifests = new DeliveryManifestService(collaboration, runtime, mapper);

        var task = collaboration.saveTask(null, "default", "build the widget", "description", "IN_PROGRESS", 0,
                "AGENT", "agent-a", "done when green", null, 0, null, "USER");
        collaboration.saveTask("task-stage-1", "default", "stage 1", "implement", "IN_REVIEW", 0,
                "AGENT", "agent-a", "works", task.id(), 1, null, "AGENT:leader-a");
        collaboration.addComment(task.id(), null, "USER", null, "please finish the widget", false, List.of());

        var digest = digests.build(task.id());
        assertThat(digest.revision()).isEqualTo(1);
        assertThat(digest.digestJson()).contains("build the widget").contains("stage 1");
        assertThat(digests.prompt(task.id())).contains("<task_digest>")
                .contains("please finish the widget").contains("stage 1");

        var delivery = manifests.recordStageDelivery(task.id(), 1, "run-1",
                List.of("index.html"), List.of("artifact-1"), List.of("COMPLETED:"), Map.of());
        assertThat(delivery.contentHash()).isNotBlank();
        assertThat(runtime.deliveriesForTask(task.id())).hasSize(1);

        var snapshot = manifests.accept(task.id(), "looks good");
        assertThat(snapshot.snapshotJson()).contains("build the widget").contains("deliveries");
        assertThat(runtime.latestAcceptedSnapshot(task.id())).hasValueSatisfying(value ->
                assertThat(value.snapshotJson()).contains("accepted_at"));
    }
}
