package com.paicli.platform.server.store;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.common.ToolEffect;
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
            assertThat(values).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                    11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
        }
    }

    @Test
    void migratesPlanStepLeaseMetadataColumns() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("paicli.db").toAbsolutePath();

        new SqliteRuntimeStore(properties()).initialize();

        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
             var columns = statement.executeQuery("PRAGMA table_info(plan_steps)")) {
            var names = new java.util.ArrayList<String>();
            while (columns.next()) names.add(columns.getString("name"));
            assertThat(names).contains("claim_owner", "lease_expires_at", "heartbeat_at", "attempt",
                    "not_before", "last_failure_class", "dispatch_idempotency_key");
        }
    }

    @Test
    void reconcilesLegacyDuplicateActiveRunsBeforeCreatingUniqueIndex() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("paicli.db").toAbsolutePath();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, " +
                    "project_key TEXT NOT NULL DEFAULT 'default', group_id TEXT, status TEXT NOT NULL, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE runs (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, " +
                    "status TEXT NOT NULL, input TEXT NOT NULL, current_step INTEGER NOT NULL DEFAULT 0, " +
                    "error TEXT, thinking_mode TEXT NOT NULL DEFAULT 'auto', reasoning_effort TEXT NOT NULL DEFAULT '', " +
                    "created_at TEXT NOT NULL, queued_at TEXT, started_at TEXT, finished_at TEXT, " +
                    "version INTEGER NOT NULL DEFAULT 0)");
            statement.execute("INSERT INTO sessions VALUES " +
                    "('session','legacy','default',NULL,'ACTIVE','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
            statement.execute("INSERT INTO runs VALUES " +
                    "('run-1','session','QUEUED','first',0,NULL,'auto','','2026-01-01T00:00:00Z',NULL,NULL,NULL,0)");
            statement.execute("INSERT INTO runs VALUES " +
                    "('run-2','session','RUNNING','second',0,NULL,'auto','','2026-01-01T00:00:01Z',NULL,NULL,NULL,0)");
        }

        new SqliteRuntimeStore(properties()).initialize();

        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT id,status,error FROM runs ORDER BY id")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("id")).isEqualTo("run-1");
            assertThat(result.getString("status")).isEqualTo("QUEUED");
            assertThat(result.next()).isTrue();
            assertThat(result.getString("id")).isEqualTo("run-2");
            assertThat(result.getString("status")).isEqualTo("FAILED");
            assertThat(result.getString("error")).contains("duplicate active run");
        }
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("INSERT INTO runs " +
                    "(id,session_id,status,input,created_at) VALUES " +
                    "('run-3','session','QUEUED','third','2026-01-01T00:00:02Z')"))
                    .hasMessageContaining("UNIQUE constraint failed");
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
        assertThat(store.modelTokenUsageForRun(run.id())).satisfies(usage -> {
            assertThat(usage.inputTokens()).isEqualTo(170);
            assertThat(usage.outputTokens()).isEqualTo(30);
            assertThat(usage.totalTokens()).isEqualTo(200);
        });

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
    void persistsTypedMemorySourcesConflictsAndPlanBoundDelegationMetadata() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("phase-234", "project-a");
        var run = store.createRun(session.id(), "coordinate");

        store.upsertAutomaticMemory("project-a", "decision.storage", "Use sqlite runtime store",
                "decision,storage", "L2", "DECISION", 0.8, session.id(), run.id(), null);
        store.upsertAutomaticMemory("project-a", "decision.storage", "Use sqlite with WAL for runtime store",
                "decision,storage", "L2", "DECISION", 0.9, session.id(), run.id(), "{\"v\":[1]}");

        var unit = store.memoryUnits("project-a", 10).stream()
                .filter(memory -> memory.memoryKey().equals("decision.storage"))
                .findFirst().orElseThrow();
        assertThat(unit).satisfies(memory -> {
            assertThat(memory.memoryType()).isEqualTo("DECISION");
            assertThat(memory.status()).isEqualTo("ACTIVE");
            assertThat(memory.sourceType()).isEqualTo("run");
            assertThat(memory.sourceId()).isEqualTo(run.id());
            assertThat(memory.supersedesId()).isEqualTo(memory.id());
            assertThat(memory.checksum()).isNotBlank();
        });
        assertThat(store.memoryRevisions(unit.id())).singleElement()
                .satisfies(revision -> assertThat(revision.content()).isEqualTo("Use sqlite runtime store"));
        assertThat(store.memorySources(unit.id())).hasSize(2)
                .allSatisfy(source -> assertThat(source.sourceType()).isEqualTo("run"));
        assertThat(store.memoryConflicts("project-a", "OPEN", 10)).singleElement()
                .satisfies(conflict -> {
                    assertThat(conflict.memoryId()).isEqualTo(unit.id());
                    assertThat(conflict.reason()).contains("same canonical key");
                });

        var tool = store.createToolCall(run.id(), "provider-agent", "spawn_agent", "{}", "phase-234-agent");
        var delegation = store.createOrGetDelegation(run.id(), tool.id(), "rag-worker", "refresh citations",
                "agent-profile-a", "model-profile-a", "plan-a", "step-a",
                "{\"scope\":\"docs only\",\"done_criteria\":[\"citations\"]}");
        assertThat(delegation.planId()).isEqualTo("plan-a");
        assertThat(delegation.planStepId()).isEqualTo("step-a");
        assertThat(delegation.envelopeJson()).contains("docs only");

        store.createArtifact(delegation.childRunId(), "report", "result.json", "runs/result.json", 42, "sha");
        var completed = store.completeDelegationResult(delegation.id(), "COMPLETED",
                "{\"summary\":\"ok\",\"artifacts\":[\"result.json\"]}", "");
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.resultJson()).contains("result.json");
        assertThat(completed.completedAt()).isNotNull();
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

        var first = store.createOrGetDelegation(parent.id(), tool.id(), "researcher", "inspect docs",
                "agent-profile-a", "model-profile-a");
        var second = store.createOrGetDelegation(parent.id(), tool.id(), "ignored", "ignored");

        assertThat(second).isEqualTo(first);
        var child = store.findRun(first.childRunId()).orElseThrow();
        assertThat(child.status()).isEqualTo(com.paicli.platform.common.RunStatus.QUEUED);
        assertThat(child.agentProfileId()).isEqualTo("agent-profile-a");
        assertThat(child.modelProfileId()).isEqualTo("model-profile-a");
        assertThat(first.agentProfileId()).isEqualTo("agent-profile-a");
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
    void persistsCollaborationPolicyAndCountsDelegationTree() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("collab", "project-a");
        var parent = store.createRun(session.id(), "coordinate");
        var policy = store.saveCollaborationPolicy(parent.id(), true, "complex", "high",
                "[\"agent-a\",\"agent-b\"]", 2, 1, 3, 12_000, 0.25,
                false, true, true);
        var tool = store.createToolCall(parent.id(), "provider-agent", "spawn_agent", "{}", "agent-key");
        var child = store.createOrGetDelegation(parent.id(), tool.id(), "runner", "run tests",
                "agent-a", "model-a");

        assertThat(policy.complexity()).isEqualTo("COMPLEX");
        assertThat(policy.risk()).isEqualTo("HIGH");
        assertThat(store.collaborationPolicyForTree(child.childRunId())).get()
                .extracting("runId").isEqualTo(parent.id());
        assertThat(store.delegationDepth(child.childRunId())).isEqualTo(1);
        assertThat(store.delegationCountForTree(child.childRunId())).isEqualTo(1);
        assertThat(store.workspaceOwnerRunId(child.childRunId())).isEqualTo(parent.id());
    }

    @Test
    void resumesLeaderWhenAwaitedDelegatedAgentBecomesTerminal() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("parent", "project-a");
        var parent = store.createRun(session.id(), "delegate");
        var tool = store.createToolCall(parent.id(), "provider-agent", "spawn_agent", "{}", "agent-key");
        var child = store.createOrGetDelegation(parent.id(), tool.id(), "expert", "implement");

        assertThat(store.waitForAgent(parent.id())).isTrue();
        assertThat(store.findRun(parent.id()).orElseThrow().status())
                .isEqualTo(com.paicli.platform.common.RunStatus.WAITING_AGENT);

        store.completeRun(child.childRunId());
        assertThat(store.requeueWaitingParentRuns(child.childRunId())).isEqualTo(1);
        assertThat(store.findRun(parent.id()).orElseThrow().status())
                .isEqualTo(com.paicli.platform.common.RunStatus.QUEUED);
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
    void migratesLegacyEvaluationBaselineTokenMetricAsTotal() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("paicli.db").toAbsolutePath();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE evaluation_baselines (" +
                    "case_id TEXT PRIMARY KEY, source_run_id TEXT NOT NULL, response TEXT NOT NULL, " +
                    "tool_names_json TEXT NOT NULL, tokens INTEGER NOT NULL, duration_ms INTEGER NOT NULL, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            statement.execute("INSERT INTO evaluation_baselines VALUES " +
                    "('case-1','run-1','ok','[]',1234,50,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
        }

        new SqliteRuntimeStore(properties()).initialize();
        var baseline = new EvaluationStore(properties()).baseline("case-1").orElseThrow();

        assertThat(baseline.tokens()).isEqualTo(1234);
        assertThat(baseline.tokenMetric()).isEqualTo("TOTAL");
    }

    @Test
    void configuresWalOnceAndWaitsForConcurrentWriters() throws Exception {
        SqliteRuntimeStore store = store();
        String url = "jdbc:sqlite:" + tempDir.resolve("paicli.db").toAbsolutePath();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("PRAGMA journal_mode")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualToIgnoringCase("wal");
            }
        }
        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 24; i++) {
                int ordinal = i;
                futures.add(executor.submit(() -> {
                    var session = store.createSession("concurrent-" + ordinal);
                    var run = store.createRun(session.id(), "work-" + ordinal);
                    store.appendEvent(run.id(), "concurrent.write", "{}");
                }));
            }
            for (var future : futures) future.get();
        } finally {
            executor.shutdownNow();
        }
        assertThat(store.sessions()).hasSize(24);
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
        var agent = productivity.saveAgentProfile(null, "project-p1", "Code Reviewer",
                "Reviews code changes", "Review code for correctness and risk.", profile.id(),
                "[\"read_file\",\"search_knowledge\"]", "[\"java-review\"]",
                "summary, risks, fixes", "REVIEWER", "MANUAL", "PROJECT", "INHERIT", true);
        var budget = productivity.saveBudget("project-p1", 10_000, 100_000, 1, 10, .8, 2);
        var session = store.createSession("P1", "project-p1");
        var run = store.createRun(session.id(), "review", "disabled", "", List.of(), profile.id(), agent.id(), 5, 0);
        store.recordModelUsage(run.id(), "openai-compatible", profile.model(), 100, 90, 10, 20,
                250, 1, true);
        var schedule = productivity.saveSchedule(null, "project-p1", "日报", template.id(),
                "DAILY", "", "{}", true, java.time.Instant.now().minusSeconds(1));
        var channel = productivity.saveNotification(null, "project-p1", "浏览器", "BROWSER",
                "", "", "COMPLETED,FAILED", true);

        assertThat(productivity.templates("project-p1")).containsExactly(template);
        assertThat(productivity.markTemplateUsed("project-p1", template.id()).id()).isEqualTo(template.id());
        assertThat(productivity.markTemplateUsed("project-p1", "review").id()).isEqualTo(template.id());
        assertThat(productivity.resolveModelProfile("project-p1", null)).contains(profile);
        assertThat(productivity.agentProfiles("project-p1")).containsExactly(agent);
        assertThat(productivity.resolveAgentProfile("project-p1", agent.id())).contains(agent);
        assertThat(store.findRun(run.id()).orElseThrow().agentProfileId()).isEqualTo(agent.id());
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

    @Test
    void terminalRunCannotBeCompletedOrRequeuedAfterCancellation() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("cancel-race");
        var run = store.createRun(session.id(), "work");
        store.claimNextRun().orElseThrow();
        store.markRunStatus(run.id(), RunStatus.WAITING_MODEL);

        assertThat(store.cancelRun(run.id())).isTrue();
        assertThat(store.completeRun(run.id())).isFalse();
        assertThat(store.requeueRun(run.id(), 1)).isFalse();
        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.CANCELED);
        assertThat(store.events(run.id(), 0)).extracting("type")
                .contains("run.canceled").doesNotContain("run.completed");
    }

    @Test
    void interruptedNonIdempotentToolBecomesUnknownAndIsNotReplayed() throws Exception {
        SqliteRuntimeStore first = store();
        var session = first.createSession("unknown-tool");
        var run = first.createRun(session.id(), "charge card");
        var call = first.createToolCall(run.id(), "provider-charge", "execute_command", "{}",
                "charge-once", ToolEffect.NON_IDEMPOTENT_WRITE);
        first.markRunStatus(run.id(), RunStatus.WAITING_TOOL);
        first.markToolRunning(call.id());

        SqliteRuntimeStore recovered = new SqliteRuntimeStore(properties());
        recovered.initialize();

        assertThat(recovered.findToolCall(call.id()).orElseThrow().status()).isEqualTo(ToolCallStatus.UNKNOWN);
        assertThat(recovered.findResumableToolCall(run.id())).isEmpty();
        assertThat(recovered.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void pagesEventsAndPersistsModelAttemptsBudgetReservationsAndNotificationOutbox() throws Exception {
        SqliteRuntimeStore store = store();
        ProductivityStore productivity = new ProductivityStore(properties());
        var session = store.createSession("ops", "ops-project");
        var run = store.createRun(session.id(), "observe");
        for (int index = 0; index < 5; index++) store.appendEvent(run.id(), "event." + index, "{}");
        assertThat(store.events(run.id(), 0, 2)).hasSize(2);

        String attempt = store.startModelAttempt(run.id(), "provider", "model", 1);
        store.finishModelAttempt(attempt, "RETRY", 429, "limited");
        assertThat(store.modelRetriesForRun(run.id())).isEqualTo(1);

        productivity.saveBudget("ops-project", 100, 0, 0, 0, .8, 2);
        assertThat(productivity.reserveModelBudget("ops-project", "r1", 60, 0)).isTrue();
        assertThat(productivity.reserveModelBudget("ops-project", "r2", 50, 0)).isFalse();
        productivity.releaseModelBudget("r1");
        assertThat(productivity.reserveModelBudget("ops-project", "r2", 50, 0)).isTrue();

        var channel = productivity.saveNotification(null, "ops-project", "webhook", "WEBHOOK",
                "https://example.com/hook", "", "COMPLETED", true);
        productivity.enqueueNotification(channel, "COMPLETED", run.id(), "done");
        var delivery = productivity.claimNotification().orElseThrow();
        assertThat(delivery.channel()).isEqualTo(channel);
        productivity.finishNotification(delivery.id(), true, delivery.attempts(), null);
        assertThat(productivity.claimNotification()).isEmpty();
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
