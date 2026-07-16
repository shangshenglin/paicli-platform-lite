package com.paicli.platform.server.store;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.Executors;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteRuntimeStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsSessionRunMessagesAndEvents() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("test");
        var run = store.createRun(session.id(), "hello");

        assertThat(store.findSession(session.id())).isPresent();
        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(store.messages(session.id())).extracting("role").containsExactly("user");
        assertThat(store.events(run.id(), 0)).extracting("type").containsExactly("run.queued");
    }

    @Test
    void claimsQueuedRunOnlyOnce() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("claim");
        var run = store.createRun(session.id(), "work");

        assertThat(store.claimNextRun()).isPresent();
        assertThat(store.claimNextRun()).isEmpty();
        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void recoversInterruptedRunAndReusesPersistedToolCall() throws Exception {
        SqliteRuntimeStore first = store();
        var session = first.createSession("recovery");
        var run = first.createRun(session.id(), "list");
        first.claimNextRun().orElseThrow();
        var tool = first.createToolCall(run.id(), "provider_1", "list_dir", "{\"path\":\".\"}",
                run.id() + ":0:list");
        first.markToolRunning(tool.id());
        first.markRunStatus(run.id(), RunStatus.WAITING_TOOL);

        SqliteRuntimeStore recovered = new SqliteRuntimeStore(properties());
        recovered.initialize();

        assertThat(recovered.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(recovered.findResumableToolCall(run.id())).isPresent();
        assertThat(recovered.findResumableToolCall(run.id()).orElseThrow().retryCount()).isEqualTo(1);
    }

    @Test
    void migratesLegacyMessagesTableForDeepSeekReasoning() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("paicli.db").toAbsolutePath();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE messages (" +
                    "id TEXT PRIMARY KEY, session_id TEXT NOT NULL, run_id TEXT, role TEXT NOT NULL, " +
                    "content TEXT NOT NULL, tool_call_id TEXT, tool_calls_json TEXT, " +
                    "archived INTEGER NOT NULL DEFAULT 0, sequence INTEGER NOT NULL, created_at TEXT NOT NULL)");
        }

        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();

        boolean found = false;
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
             var columns = statement.executeQuery("PRAGMA table_info(messages)")) {
            while (columns.next()) {
                if ("reasoning_content".equals(columns.getString("name"))) found = true;
            }
        }
        assertThat(found).isTrue();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
             var versions = statement.executeQuery("SELECT version FROM schema_migrations ORDER BY version")) {
            var values = new java.util.ArrayList<Integer>();
            while (versions.next()) values.add(versions.getInt(1));
            assertThat(values).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        }
    }

    @Test
    void persistsUsageMemoryRevisionsAndRecoverableExtractionJobs() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("memory", "project-a");
        var run = store.createRun(session.id(), "remember");
        store.recordModelUsage(run.id(), "demo", 100, 90, 10, 5);
        store.recordModelUsage(run.id(), "demo", 80, 0, 20, 0);
        assertThat(store.modelTokensForRun(run.id())).isEqualTo(200);

        store.upsertAutomaticMemory("project-a", "language", "Java", "preference",
                "L3", "PREFERENCE", 0.9, session.id(), run.id(), null);
        store.upsertAutomaticMemory("project-a", "language", "Kotlin", "preference",
                "L3", "PREFERENCE", 0.95, session.id(), run.id(), null);
        assertThat(store.memoryUnits("project-a", 10)).singleElement()
                .satisfies(unit -> assertThat(unit.content()).isEqualTo("Kotlin"));

        store.enqueueMemoryExtraction(run.id());
        assertThat(store.claimMemoryExtraction()).contains(run.id());
        SqliteRuntimeStore recovered = new SqliteRuntimeStore(properties());
        recovered.initialize();
        assertThat(recovered.claimMemoryExtraction()).contains(run.id());

        store.completeRun(run.id());
        assertThat(store.deleteSession(session.id())).isTrue();
    }

    @Test
    void groupsMovesAndDeletesSessionsWithTheirRuntimeRecords() throws Exception {
        SqliteRuntimeStore store = store();
        var group = store.createSessionGroup("Work");
        var session = store.createSession("grouped", "default", group.id());
        var run = store.createRun(session.id(), "hello");
        var tool = store.createToolCall(run.id(), "provider-1", "write_file", "{}", "delete-test");
        var approval = store.createApproval(run.id(), tool.id(), "confirm");
        var artifact = store.createArtifact(run.id(), "tool-result", "large", "x.txt", 1, "abc");

        assertThat(session.groupId()).isEqualTo(group.id());
        assertThat(store.sessionGroups()).extracting("name").containsExactly("Work");
        assertThatThrownBy(() -> store.deleteSession(session.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active run");

        store.completeRun(run.id());
        assertThat(store.deleteSession(session.id())).isTrue();
        assertThat(store.findSession(session.id())).isEmpty();
        assertThat(store.findRun(run.id())).isEmpty();
        assertThat(store.findApproval(approval.id())).isEmpty();
        assertThat(store.findArtifact(artifact.id())).isEmpty();

        var remaining = store.createSession("remaining", "default", group.id());
        assertThat(store.deleteSessionGroup(group.id())).isTrue();
        assertThat(store.findSession(remaining.id()).orElseThrow().groupId()).isNull();
    }

    @Test
    void persistsPerRunThinkingControls() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("thinking");
        var run = store.createRun(session.id(), "solve", "enabled", "max");

        var persisted = store.findRun(run.id()).orElseThrow();
        assertThat(persisted.thinkingMode()).isEqualTo("enabled");
        assertThat(persisted.reasoningEffort()).isEqualTo("max");
    }

    @Test
    void bindsStagedImageAttachmentsToExactlyOneRun() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("vision");
        var attachment = store.createInputAttachment(session.id(), "screen.png", "image/png",
                session.id() + "/screen.png", 12, "abc123");

        var run = store.createRun(session.id(), "analyze", "disabled", "", List.of(attachment.id()));

        assertThat(store.attachmentsForRun(run.id())).singleElement().satisfies(value -> {
            assertThat(value.runId()).isEqualTo(run.id());
            assertThat(value.messageId()).isNotBlank();
            assertThat(value.mimeType()).isEqualTo("image/png");
        });
        store.completeRun(run.id());
        assertThatThrownBy(() -> store.createRun(session.id(), "reuse", "disabled", "",
                List.of(attachment.id()))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already used");
    }

    @Test
    void createsDelegatedRunsIdempotentlyAndDeletesTheirInternalSessions() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("parent", "project-a");
        var parent = store.createRun(session.id(), "delegate");
        var tool = store.createToolCall(parent.id(), "provider-agent", "spawn_agent", "{}", "agent-key");

        var first = store.createOrGetDelegation(parent.id(), tool.id(), "researcher", "inspect docs");
        var second = store.createOrGetDelegation(parent.id(), tool.id(), "ignored", "ignored");

        assertThat(second).isEqualTo(first);
        assertThat(store.findRun(first.childRunId()).orElseThrow().status())
                .isEqualTo(com.paicli.platform.common.RunStatus.QUEUED);
        assertThat(store.findSession(first.childSessionId()).orElseThrow().projectKey()).isEqualTo("project-a");
        assertThat(store.sessions()).extracting("id").containsExactly(session.id());
        assertThat(store.delegationsForRun(parent.id())).containsExactly(first);
        assertThatThrownBy(() -> store.deleteSession(first.childSessionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent session");

        store.requeueRun(parent.id(), 1);
        assertThat(store.claimNextRun()).get().extracting("id").isEqualTo(first.childRunId());

        store.completeRun(first.childRunId());
        store.completeRun(parent.id());
        assertThat(store.deleteSession(session.id())).isTrue();
        assertThat(store.findSession(first.childSessionId())).isEmpty();
        assertThat(store.findRun(first.childRunId())).isEmpty();
    }

    @Test
    void allocatesEventSequencesAtomicallyAcrossConcurrentWriters() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("events");
        var run = store.createRun(session.id(), "race");
        var executor = Executors.newFixedThreadPool(6);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 60; i++) {
                int event = i;
                futures.add(executor.submit(() -> store.appendEvent(run.id(), "test.concurrent",
                        "{\"event\":" + event + "}")));
            }
            for (var future : futures) future.get();
        } finally {
            executor.shutdownNow();
        }

        var events = store.events(run.id(), 0);
        assertThat(events).hasSize(61);
        assertThat(events).extracting("sequence").containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(1, 61).boxed().toList());
    }

    @Test
    void persistsP1TemplatesProfilesBudgetsQueueSchedulesAndNotifications() throws Exception {
        SqliteRuntimeStore store = store();
        ProductivityStore productivity = new ProductivityStore(properties());
        var profile = productivity.saveModelProfile(null, "project-p1", "快速",
                "http://127.0.0.1:11434/v1", "", "qwen-local", "", 32_000, 2_048,
                0, 0, true, true);
        var template = productivity.saveTemplate(null, "project-p1", "代码审查", "/review",
                "审查 ${repository}", "{\"repository\":\"repo\"}", "代码附件",
                "read_file,search", profile.id());
        var budget = productivity.saveBudget("project-p1", 10_000, 100_000, 1, 10, .8, 2);
        var session = store.createSession("P1", "project-p1");
        var run = store.createRun(session.id(), "review", "disabled", "", List.of(), profile.id(), 5, 0);
        store.recordModelUsage(run.id(), "openai-compatible", profile.model(), 100, 90, 10, 20,
                250, 1, true);
        var schedule = productivity.saveSchedule(null, "project-p1", "日报", template.id(),
                "DAILY", "", "{}", true, java.time.Instant.now().minusSeconds(1));
        var channel = productivity.saveNotification(null, "project-p1", "浏览器", "BROWSER",
                "", "", "COMPLETED,FAILED", true);

        assertThat(productivity.templates("project-p1")).containsExactly(template);
        assertThat(productivity.resolveModelProfile("project-p1", null)).contains(profile);
        assertThat(budget.maxConcurrentRuns()).isEqualTo(2);
        assertThat(productivity.queue("project-p1")).singleElement()
                .satisfies(item -> {
                    assertThat(item.run().priority()).isEqualTo(5);
                    assertThat(item.usedTokens()).isEqualTo(100);
                    assertThat(item.remainingBudgetTokens()).isEqualTo(99_900);
                });
        assertThat(productivity.usage("project-p1", 30)).satisfies(value -> {
            assertThat(value.calls()).isEqualTo(1);
            assertThat(value.inputTokens()).isEqualTo(90);
            assertThat(value.cachedTokens()).isEqualTo(20);
            assertThat(value.averageDurationMs()).isEqualTo(250);
            assertThat(value.estimatedCost()).isZero();
            assertThat(value.breakdown()).singleElement().satisfies(row -> {
                assertThat(row.sessionId()).isEqualTo(session.id());
                assertThat(row.model()).isEqualTo("qwen-local");
                assertThat(row.localModel()).isTrue();
            });
        });
        assertThat(productivity.dueSchedules()).contains(schedule);
        assertThat(productivity.claimSchedule(schedule.id())).isTrue();
        productivity.completeSchedule(schedule.id(), run.id(), java.time.Instant.now().plusSeconds(60));
        assertThat(productivity.notificationChannels("project-p1")).containsExactly(channel);
    }

    @Test
    void persistsApprovalPoliciesAndManagedMemoryStateWithRevisionRestore() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("managed", "project-a");
        var run = store.createRun(session.id(), "remember");
        var policy = store.createApprovalPolicy("SESSION", session.id(), session.projectKey(),
                "execute_command", "a".repeat(64));

        assertThat(store.matchingApprovalPolicy(session.id(), session.projectKey(),
                "execute_command", "a".repeat(64))).contains(policy);
        assertThat(store.approvalPolicies("project-a")).containsExactly(policy);

        store.upsertAutomaticMemory("project-a", "language", "Java", "preference",
                "L3", "PREFERENCE", 0.8, session.id(), run.id(), null);
        store.upsertAutomaticMemory("project-a", "language", "Kotlin", "preference",
                "L3", "PREFERENCE", 0.9, session.id(), run.id(), null);
        var memory = store.memoryUnits("project-a", 10).get(0);
        var managed = store.setMemoryState(memory.id(), true, true, true);
        assertThat(managed.pinned()).isTrue();
        assertThat(managed.confirmedAt()).isNotNull();
        var revision = store.memoryRevisions(memory.id()).get(0);
        assertThat(store.restoreMemoryRevision(memory.id(), revision.id()).content()).isEqualTo("Java");
        var source = store.createMemory("project-a", "framework", "Spring Boot", "java,framework");
        assertThat(store.mergeMemories(memory.id(), List.of(source.id())).content())
                .contains("Java", "Spring Boot");
        assertThat(store.findMemory(source.id())).isEmpty();
        assertThat(store.deleteApprovalPolicy(policy.id())).isTrue();
        assertThat(store.deleteMemory(memory.id())).isTrue();
    }

    @Test
    void createsConversationBranchBeforeSourceRunAndListsProjectArtifacts() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("branch", "project-a");
        var first = store.createRun(session.id(), "first");
        store.appendMessage(session.id(), first.id(), "assistant", "first answer");
        store.completeRun(first.id());
        var source = store.createRun(session.id(), "second");
        store.completeRun(source.id());
        var artifact = store.createArtifact(source.id(), "tool-result", "report", "report.txt", 10, "abc");

        var branch = store.createBranchSession(source.id());
        assertThat(branch.title()).contains("分支");
        assertThat(store.messages(branch.id())).extracting("content")
                .containsExactly("first", "first answer");
        assertThat(store.artifacts("project-a", 10)).containsExactly(artifact);
        var feedback = store.createKnowledgeFeedback("project-a", "guide.md", 2, true, "useful");
        assertThat(store.knowledgeFeedback("project-a")).containsExactly(feedback);
        assertThat(store.deleteArtifact(artifact.id())).isTrue();
    }

    private SqliteRuntimeStore store() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        return store;
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }
}
