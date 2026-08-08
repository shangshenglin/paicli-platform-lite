package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.sandbox.LocalSandboxDriver;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ToolRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunEvidenceCollectorTest {
    @TempDir
    Path tempDir;

    @Test
    void collectsRealFileCommandAndTestEvidence() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ToolRouter router = new ToolRouter(new LocalSandboxDriver(properties));
        RunEvidenceCollector collector = new RunEvidenceCollector(store, new ObjectMapper());

        var session = store.createSession("evidence", "project-e");
        var run = store.createRun(session.id(), "modify and test");
        execute(store, session.id(), run.id(), "write_file",
                "{\"path\":\"src/A.java\",\"content\":\"class A {}\"}",
                "{\"path\":\"src/A.java\",\"changed\":true,\"afterSha256\":\"abc\"}", 0);
        execute(store, session.id(), run.id(), "execute_command",
                "{\"command\":\"./mvnw test\",\"cwd\":\".\",\"shell\":\"bash\"}",
                "{\"shell\":\"bash\",\"cwd\":\".\",\"exitCode\":0,\"timedOut\":false}", 1);

        RunEvidence evidence = collector.collect(run.id());

        assertThat(evidence.filesChanged()).singleElement().satisfies(file -> {
            assertThat(file.path()).isEqualTo("src/A.java");
            assertThat(file.changed()).isTrue();
        });
        assertThat(evidence.commandsExecuted()).singleElement().satisfies(command -> {
            assertThat(command.command()).isEqualTo("./mvnw test");
            assertThat(command.exitCode()).isZero();
        });
        assertThat(evidence.tests()).singleElement().satisfies(test -> {
            assertThat(test.family()).isEqualTo(TestFamily.MAVEN);
            assertThat(test.status()).isEqualTo(TestStatus.PASSED);
        });
        assertThat(evidence.lastMutationOrdinal()).isEqualTo(0);
    }

    @Test
    void identicalWriteIsNotAChangeAndFailingTestIsFailed() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ToolRouter router = new ToolRouter(new LocalSandboxDriver(properties));
        RunEvidenceCollector collector = new RunEvidenceCollector(store, new ObjectMapper());

        var session = store.createSession("evidence2", "project-e");
        var run = store.createRun(session.id(), "no-op write then failing test");
        execute(store, session.id(), run.id(), "write_file",
                "{\"path\":\"src/A.java\",\"content\":\"same\"}",
                "{\"path\":\"src/A.java\",\"changed\":false}", 0);
        execute(store, session.id(), run.id(), "execute_command",
                "{\"command\":\"pytest tests/\"}",
                "{\"shell\":\"bash\",\"exitCode\":1,\"timedOut\":false}", 1);

        RunEvidence evidence = collector.collect(run.id());

        assertThat(evidence.filesChanged()).isEmpty();
        assertThat(evidence.tests()).singleElement().satisfies(test -> {
            assertThat(test.family()).isEqualTo(TestFamily.PYTEST);
            assertThat(test.status()).isEqualTo(TestStatus.FAILED);
        });
        assertThat(evidence.lastMutationOrdinal()).isEqualTo(-1);
    }

    @Test
    void mvnCompileAndCheckStatusAreNotTestEvidence() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ToolRouter router = new ToolRouter(new LocalSandboxDriver(properties));
        RunEvidenceCollector collector = new RunEvidenceCollector(store, new ObjectMapper());

        var session = store.createSession("evidence3", "project-e");
        var run = store.createRun(session.id(), "build only");
        execute(store, session.id(), run.id(), "execute_command",
                "{\"command\":\"mvn compile\"}", "{\"exitCode\":0}", 0);
        execute(store, session.id(), run.id(), "execute_command",
                "{\"command\":\"./check-status.sh\"}", "{\"exitCode\":0}", 1);

        RunEvidence evidence = collector.collect(run.id());

        assertThat(evidence.commandsExecuted()).hasSize(2);
        assertThat(evidence.tests()).isEmpty();
    }

    private static void execute(SqliteRuntimeStore store, String sessionId, String runId,
                                String toolName, String arguments, String metadata, int step) {
        store.markRunStatus(runId, com.paicli.platform.common.RunStatus.WAITING_TOOL);
        var call = store.createToolCall(runId, "provider-" + step, toolName, arguments, "key-" + step);
        store.markToolRunning(call.id());
        boolean ok = store.commitToolOutcome(sessionId, runId, call, true, "ok", null,
                metadata, metadata, step);
        assertThat(ok).isTrue();
    }
}