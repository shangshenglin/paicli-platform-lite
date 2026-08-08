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

    @Test
    void expertThreadDigestCarriesReferencesNotFullHistory() throws Exception {
        PlatformProperties properties = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        CollaborationStore collaboration = new CollaborationStore(properties);
        ObjectMapper mapper = new ObjectMapper();
        ExpertThreadDigestBuilder builder = new ExpertThreadDigestBuilder(collaboration, runtime, mapper);

        var task = collaboration.saveTask(null, "default", "build the widget", "description", "IN_PROGRESS", 0,
                "TEAM", "team-a", "done when green", null, 0, null, "USER");
        collaboration.saveTask("task-stage-1", "default", "stage 1", "implement", "IN_REVIEW", 0,
                "AGENT", "backend-a", "works", task.id(), 1, null, "AGENT:leader-a");
        collaboration.saveTask("task-stage-2", "default", "stage 2", "fix csv bom", "TODO", 0,
                "AGENT", "backend-a", "utf8", task.id(), 2, null, "AGENT:leader-a");
        collaboration.addComment(task.id(), null, "USER", null, "修复乱码，不改变接口协议", false, List.of());

        var thread = collaboration.getOrCreateExpertThread(task.id(), "backend-a", "EXPERT");
        var session1 = runtime.createSession("协作任务 · build the widget", "default");
        var run1 = runtime.createRun(session1.id(), "backend work", "auto", "", List.of(),
                null, "backend-a", 0, 0, "bash");
        var session2 = runtime.createSession("协作任务 · build the widget 2", "default");
        var run2 = runtime.createRun(session2.id(), "backend work again", "auto", "", List.of(),
                null, "backend-a", 0, 0, "bash");
        collaboration.attachExpertThreadRun(thread.id(), run1.id());
        collaboration.attachExpertThreadRun(thread.id(), run2.id());
        runtime.appendAssistantMessage(session1.id(), run1.id(), "完成导出接口基础实现，含 ExportController", null);
        runtime.appendToolResult(session1.id(), run1.id(), "call-1", "完整工具输出全文 - secret tool result - 不应出现在摘要");
        runtime.appendMessage(session1.id(), run1.id(), "user", "完整的旧会话评论不应出现在摘要");
        runtime.appendAssistantMessage(session2.id(), run2.id(), "修复 CSV UTF-8 BOM", null);
        runtime.createArtifact(run2.id(), "test-report", "export-test.html", "export-test.html", 2048, "abc");
        java.nio.file.Files.createDirectories(properties.workspaceRoot().resolve(run2.id()));
        java.nio.file.Files.writeString(properties.workspaceRoot().resolve(run2.id()).resolve("ExportController.java"),
                "class ExportController {}");

        String digestJson = builder.build(thread.id());

        assertThat(digestJson)
                .contains("backend-a")
                .contains("build the widget")
                .contains("修复乱码，不改变接口协议")
                .contains("stage 2")
                .contains("\"run_id\":\"" + run2.id() + "\"")
                .contains("修复 CSV UTF-8 BOM")
                .contains("export-test.html")
                .contains("ExportController.java");
        assertThat(digestJson)
                .doesNotContain("secret tool result")
                .doesNotContain("完整的旧会话评论不应出现在摘要");
    }
}
