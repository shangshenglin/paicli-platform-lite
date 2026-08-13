package com.paicli.platform.server.store;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.common.ToolEffect;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.artifact.LocalArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
                    11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
                    28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39);
        }
    }

    @Test
    void migratesEnhancedTeamsAndDurableCollaborationTables() throws Exception {
        store();
        String url = "jdbc:sqlite:" + tempDir.resolve("paicli.db").toAbsolutePath();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            var teamColumns = new java.util.ArrayList<String>();
            try (var columns = statement.executeQuery("PRAGMA table_info(agent_teams)")) {
                while (columns.next()) teamColumns.add(columns.getString("name"));
            }
            assertThat(teamColumns).contains("team_instructions", "member_roles_json", "capability_tags_json",
                    "routing_policy", "completion_policy", "fallback_agent_profile_id", "max_concurrency");

            var tables = new java.util.ArrayList<String>();
            try (var values = statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'collaboration_%'")) {
                while (values.next()) tables.add(values.getString(1));
            }
            assertThat(tables).contains("collaboration_tasks", "collaboration_comments",
                    "collaboration_activities", "collaboration_triggers", "collaboration_mentions",
                    "collaboration_task_runs", "collaboration_route_decisions", "collaboration_stage_barriers");
        }
    }

    @Test
    void migratesTypedPlanEdgeRoutingColumns() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        String url = "jdbc:sqlite:" + tempDir.resolve("paicli.db").toAbsolutePath();

        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            var columns = new java.util.ArrayList<String>();
            try (var values = statement.executeQuery("PRAGMA table_info(plan_edges)")) {
                while (values.next()) columns.add(values.getString("name"));
            }
            assertThat(columns).contains("edge_type", "condition_expression", "priority",
                    "max_traversals", "traversal_count");
        }
    }

    @Test
    void migratesExpertThinkingAndWorkspaceOwnershipColumns() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        String url = "jdbc:sqlite:" + tempDir.resolve("paicli.db").toAbsolutePath();

        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            var agentColumns = new java.util.ArrayList<String>();
            try (var columns = statement.executeQuery("PRAGMA table_info(agent_profiles)")) {
                while (columns.next()) agentColumns.add(columns.getString("name"));
            }
            var runColumns = new java.util.ArrayList<String>();
            try (var columns = statement.executeQuery("PRAGMA table_info(runs)")) {
                while (columns.next()) runColumns.add(columns.getString("name"));
            }
            assertThat(agentColumns).contains("thinking_mode", "reasoning_effort", "execution_shell");
            assertThat(runColumns).contains("workspace_owner_run_id", "execution_shell");
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
    void persistsAgentFeedbackRecordsIdempotently() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("feedback", "project-a");
        var run = store.createRun(session.id(), "validate");

        var feedback = store.recordAgentFeedback("project-a", "agent-a", "plan-a", "step-a", run.id(),
                "COMPLETED", "PASSED", 1.0, "", 0.75);
        var updated = store.recordAgentFeedback("project-a", "agent-a", "plan-a", "step-a", run.id(),
                "COMPLETED", "FAILED", 0.0, "VALIDATION_FAILED", 0.25);

        assertThat(updated.id()).isEqualTo(feedback.id());
        assertThat(store.agentFeedback(run.id(), "step-a")).get().satisfies(value -> {
            assertThat(value.projectKey()).isEqualTo("project-a");
            assertThat(value.agentProfileId()).isEqualTo("agent-a");
            assertThat(value.planId()).isEqualTo("plan-a");
            assertThat(value.validationStatus()).isEqualTo("FAILED");
            assertThat(value.score()).isZero();
            assertThat(value.failureClass()).isEqualTo("VALIDATION_FAILED");
            assertThat(value.evidenceQuality()).isEqualTo(0.25);
        });
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
        PlanStore plans = new PlanStore(properties());
        var group = store.createSessionGroup("Work");
        var session = store.createSession("grouped", "default", group.id());
        var run = store.createRun(session.id(), "hello");
        var tool = store.createToolCall(run.id(), "provider-1", "write_file", "{}", "delete-test");
        var approval = store.createApproval(run.id(), tool.id(), "confirm");
        var artifact = store.createArtifact(run.id(), "tool-result", "large", "x.txt", 1, "abc");
        store.startModelAttempt(run.id(), "provider", "model", 1);
        store.saveCollaborationPolicy(run.id(), true, "medium", "medium",
                "[]", 2, 1, 2, 4_000, 0, false, false, false);
        plans.createAsyncJob(null, null, run.id(), "default", "GENERIC", "{}", "delete-session-job");

        assertThat(session.groupId()).isEqualTo(group.id());
        assertThat(store.sessionGroups()).extracting("name").containsExactly("Work");
        assertThatThrownBy(() -> store.deleteSession(session.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active run");

        store.completeRun(run.id());
        store.recordAgentFeedback("default", null, "plan-delete", "step-delete", run.id(),
                "COMPLETED", "PASSED", 1.0, "", 1.0);
        assertThat(store.deleteSession(session.id())).isTrue();
        assertThat(store.findSession(session.id())).isEmpty();
        assertThat(store.findRun(run.id())).isEmpty();
        assertThat(store.findApproval(approval.id())).isEmpty();
        assertThat(store.findArtifact(artifact.id())).isEmpty();
        assertThat(store.agentFeedback(run.id(), "step-delete")).isEmpty();

        var remaining = store.createSession("remaining", "default", group.id());
        assertThat(store.deleteSessionGroup(group.id())).isTrue();
        assertThat(store.findSession(remaining.id()).orElseThrow().groupId()).isNull();
    }

    @Test
    void replacesOnlyGenericSessionTitlesWithTaskSummaries() throws Exception {
        SqliteRuntimeStore store = store();
        var generic = store.createSession("新对话");
        var named = store.createSession("保留我的标题");

        assertThat(store.renameSessionIfGeneric(generic.id(), "请帮我修复登录超时问题，并补充验证。").title())
                .isEqualTo("修复登录超时问题，并补充验证");
        assertThat(store.renameSessionIfGeneric(named.id(), "另一个任务").title())
                .isEqualTo("保留我的标题");
    }

    @Test
    void persistsPerRunThinkingControls() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("thinking");
        var run = store.createRun(session.id(), "solve", "enabled", "low");

        var persisted = store.findRun(run.id()).orElseThrow();
        assertThat(persisted.thinkingMode()).isEqualTo("enabled");
        assertThat(persisted.reasoningEffort()).isEqualTo("low");
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
        assertThat(store.findSession(first.childSessionId()).orElseThrow()).satisfies(childSession -> {
            assertThat(childSession.projectKey()).isEqualTo("project-a");
            assertThat(childSession.title()).isEqualTo("researcher · inspect docs");
        });
        assertThat(store.sessions()).extracting("id").containsExactly(session.id());
        assertThat(store.delegationsForRun(parent.id())).containsExactly(first);
        assertThat(store.parentDelegationForRun(first.childRunId())).contains(first);
        assertThat(store.delegationRootRunId(first.childRunId())).isEqualTo(parent.id());
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
                "agent-a", "model-a", "enabled", "max", null, null, "{}");

        assertThat(policy.complexity()).isEqualTo("COMPLEX");
        assertThat(policy.risk()).isEqualTo("HIGH");
        assertThat(store.collaborationPolicyForTree(child.childRunId())).get()
                .extracting("runId").isEqualTo(parent.id());
        assertThat(store.delegationDepth(child.childRunId())).isEqualTo(1);
        assertThat(store.delegationCountForTree(child.childRunId())).isEqualTo(1);
        assertThat(store.workspaceOwnerRunId(child.childRunId())).isEqualTo(parent.id());
        assertThat(store.findRun(child.childRunId())).get().satisfies(run -> {
            assertThat(run.thinkingMode()).isEqualTo("enabled");
            assertThat(run.reasoningEffort()).isEqualTo("max");
        });

        store.completeRun(parent.id());
        var continuation = store.createRun(session.id(), "continue in the same workspace");
        assertThat(store.workspaceOwnerRunId(continuation.id())).isEqualTo(parent.id());
        assertThat(store.latestCollaborationRunId(session.id())).contains(parent.id());
    }

    @Test
    void explicitCollaborationWorkspaceIsSharedAcrossSessionsAndDelegations() throws Exception {
        SqliteRuntimeStore store = store();
        String owner = SqliteRuntimeStore.collaborationWorkspaceOwner("task-root");
        var firstSession = store.createSession("leader-1");
        var first = store.createRunInWorkspace(firstSession.id(), "coordinate", "auto", "", List.of(),
                null, null, 0, 0, "bash", owner);
        var tool = store.createToolCall(first.id(), "provider-agent", "spawn_agent", "{}", "shared-agent");
        var child = store.createOrGetDelegation(first.id(), tool.id(), "worker", "implement",
                null, null, "auto", "", null, null, "{}");
        var secondSession = store.createSession("leader-2");
        var second = store.createRunInWorkspace(secondSession.id(), "continue", "auto", "", List.of(),
                null, null, 0, 0, "bash", owner);

        assertThat(store.workspaceOwnerRunId(first.id())).isEqualTo(owner);
        assertThat(store.workspaceOwnerRunId(child.childRunId())).isEqualTo(owner);
        assertThat(store.workspaceOwnerRunId(second.id())).isEqualTo(owner);
    }

    @Test
    void collaborationDelegationTreatsCurrentWorkspacePathAsInheritance() throws Exception {
        SqliteRuntimeStore store = store();
        String owner = SqliteRuntimeStore.collaborationWorkspaceOwner("task-root");
        var parentSession = store.createSession("leader");
        var parent = store.createRunInWorkspace(parentSession.id(), "coordinate", "auto", "", List.of(),
                null, null, 0, 0, "bash", owner);
        var tool = store.createToolCall(parent.id(), "provider-agent", "spawn_agent", "{}", "shared-path");
        String currentWorkspacePath = "shared workspace: C:\\data\\workspaces\\" + owner;

        var child = store.createOrGetDelegation(parent.id(), tool.id(), "runner", "run tests",
                null, null, null, null, null, null, "{}",
                new SqliteRuntimeStore.DelegationOptions(List.of(), List.of(), List.of(),
                        "BLOCK_GRAPH", currentWorkspacePath));

        assertThat(store.workspaceOwnerRunId(child.childRunId())).isEqualTo(owner);
        assertThat(child.workspaceRef()).isNull();
    }

    @Test
    void startupMovesExistingCollaborationRunsIntoTaskWorkspace() throws Exception {
        SqliteRuntimeStore first = store();
        var session = first.createSession("legacy collaboration");
        var run = first.createRun(session.id(), "legacy work");
        CollaborationStore collaboration = new CollaborationStore(properties());
        collaboration.saveTask("task-root", "default", "Legacy", "", "IN_PROGRESS", 0,
                "AGENT", "agent-a", "", null, 0, null, "USER");
        collaboration.linkRun("task-root", run.id(), null, "TRIGGERED");
        Path source = tempDir.resolve("workspaces").resolve(run.id());
        Files.createDirectories(source);
        Files.writeString(source.resolve("delivery.txt"), "legacy delivery");

        SqliteRuntimeStore recovered = new SqliteRuntimeStore(properties());
        recovered.initialize();

        String owner = SqliteRuntimeStore.collaborationWorkspaceOwner("task-root");
        assertThat(recovered.workspaceOwnerRunId(run.id())).isEqualTo(owner);
        assertThat(tempDir.resolve("workspaces").resolve(owner).resolve("delivery.txt"))
                .hasContent("legacy delivery");
    }

    @Test
    void limitsConcurrentDelegatedRunsByCollaborationPolicy() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("concurrency", "project-a");
        var parent = store.createRun(session.id(), "coordinate");
        var policy = store.saveCollaborationPolicy(parent.id(), true, "complex", "medium",
                "[\"agent-a\",\"agent-b\"]", 2, 1, 2, 1, 0, 0,
                false, false, false);
        var firstTool = store.createToolCall(parent.id(), "provider-agent", "spawn_agent", "{}", "agent-first");
        var secondTool = store.createToolCall(parent.id(), "provider-agent", "spawn_agent", "{}", "agent-second");
        var first = store.createOrGetDelegation(parent.id(), firstTool.id(), "first", "first task");
        var second = store.createOrGetDelegation(parent.id(), secondTool.id(), "second", "second task");

        assertThat(policy.maxConcurrentAgentRuns()).isEqualTo(1);
        assertThat(store.waitForAgent(parent.id())).isTrue();
        assertThat(store.claimNextRun()).get().extracting("id").isEqualTo(first.childRunId());
        assertThat(store.claimNextRun()).isEmpty();

        store.completeRun(first.childRunId());
        assertThat(store.claimNextRun()).get().extracting("id").isEqualTo(second.childRunId());
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
        assertThat(store.findRun(parent.id()).orElseThrow().status())
                .isEqualTo(com.paicli.platform.common.RunStatus.QUEUED);
        assertThat(store.requeueWaitingParentRuns(child.childRunId())).isZero();
        assertThat(store.findDelegation(parent.id(), child.childRunId()).orElseThrow().resultJson())
                .contains("\"version\":2", "\"terminal_event\"");
    }

    @Test
    void gatesDelegatedRunsUntilDependenciesCompleteAndPersistsTerminalEnvelope() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("graph", "project-a");
        var parent = store.createRun(session.id(), "coordinate");
        var upstreamTool = store.createToolCall(parent.id(), "spawn-code", "spawn_agent", "{}",
                "spawn-code-" + parent.id());
        var upstream = store.createOrGetDelegation(parent.id(), upstreamTool.id(), "coder", "implement",
                null, null, null, null, null, null, "{}",
                new SqliteRuntimeStore.DelegationOptions(List.of(), List.of("src"), List.of("src/main.java"),
                        "BLOCK_GRAPH", "workspace/root"));
        var reviewerTool = store.createToolCall(parent.id(), "spawn-review", "spawn_agent", "{}",
                "spawn-review-" + parent.id());
        var reviewer = store.createOrGetDelegation(parent.id(), reviewerTool.id(), "reviewer", "review",
                null, null, null, null, null, null, "{}",
                new SqliteRuntimeStore.DelegationOptions(List.of(upstream.id()), List.of("src/main.java"),
                        List.of(), "BLOCK_GRAPH", "workspace/root"));
        store.completeRun(parent.id());

        assertThat(reviewer.status()).isEqualTo("BLOCKED");
        assertThat(store.delegationDependencyIds(reviewer.id())).containsExactly(upstream.id());
        assertThat(store.delegationResources(upstream.id()).get("write")).containsExactly("src/main.java");
        assertThat(store.claimNextRun()).get().extracting("id").isEqualTo(upstream.childRunId());
        assertThat(store.claimNextRun()).isEmpty();

        store.appendMessage(upstream.childSessionId(), upstream.childRunId(), "assistant", "implementation done");
        store.completeRun(upstream.childRunId());

        var completed = store.findDelegation(parent.id(), upstream.childRunId()).orElseThrow();
        assertThat(completed.resultJson()).contains("\"version\":2", "implementation done",
                "\"files_changed\"", "\"commands_executed\"", "\"tests\"");
        assertThat(store.findDelegation(parent.id(), reviewer.childRunId()).orElseThrow().status())
                .isEqualTo("QUEUED");
        assertThat(store.messages(reviewer.childSessionId()).stream()
                .map(com.paicli.platform.server.domain.MessageRecord::content).toList())
                .anySatisfy(content -> assertThat(content).contains(
                        "Upstream dependency results", "implementation done", upstream.id()));
        assertThat(store.claimNextRun()).get().extracting("id").isEqualTo(reviewer.childRunId());
    }

    @Test
    void immediatelyQueuesADelegationWhoseDependenciesAlreadyCompleted() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("late dependency", "project-a");
        var parent = store.createRun(session.id(), "coordinate");
        assertThat(store.claimNextRun()).get().extracting("id").isEqualTo(parent.id());
        var sourceTool = store.createToolCall(parent.id(), "spawn-source-late", "spawn_agent", "{}",
                "spawn-source-late-" + parent.id());
        var source = store.createOrGetDelegation(parent.id(), sourceTool.id(), "source", "produce result",
                null, null);
        assertThat(store.claimNextRun()).get().extracting("id").isEqualTo(source.childRunId());
        store.appendMessage(source.childSessionId(), source.childRunId(), "assistant", "ready result");
        store.completeRun(source.childRunId());

        var downstreamTool = store.createToolCall(parent.id(), "spawn-downstream-late", "spawn_agent", "{}",
                "spawn-downstream-late-" + parent.id());
        var downstream = store.createOrGetDelegation(parent.id(), downstreamTool.id(), "reviewer", "review result",
                null, null, null, null, null, null, "{}",
                new SqliteRuntimeStore.DelegationOptions(List.of(source.id()), List.of(), List.of(),
                        "BLOCK_GRAPH", "workspace/review"));

        assertThat(downstream.status()).isEqualTo("QUEUED");
        assertThat(store.messages(downstream.childSessionId()).stream()
                .map(com.paicli.platform.server.domain.MessageRecord::content).toList())
                .anySatisfy(content -> assertThat(content).contains("ready result"));
    }

    @Test
    void serializesConflictingDelegationResourcesWithinOneWorkspaceOwner() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("resource graph", "project-a");
        var parent = store.createRun(session.id(), "coordinate");
        var firstTool = store.createToolCall(parent.id(), "spawn-a", "spawn_agent", "{}",
                "spawn-a-" + parent.id());
        var first = store.createOrGetDelegation(parent.id(), firstTool.id(), "writer-a", "write a",
                null, null, null, null, null, null, "{}",
                new SqliteRuntimeStore.DelegationOptions(List.of(), List.of(), List.of("src/shared.java"),
                        "BLOCK_GRAPH", "workspace/root"));
        var secondTool = store.createToolCall(parent.id(), "spawn-b", "spawn_agent", "{}",
                "spawn-b-" + parent.id());
        var second = store.createOrGetDelegation(parent.id(), secondTool.id(), "writer-b", "write b",
                null, null, null, null, null, null, "{}",
                new SqliteRuntimeStore.DelegationOptions(List.of(), List.of("src/shared.java"), List.of(),
                        "BLOCK_GRAPH", "workspace/root"));
        var isolatedTool = store.createToolCall(parent.id(), "spawn-isolated", "spawn_agent", "{}",
                "spawn-isolated-" + parent.id());
        var isolated = store.createOrGetDelegation(parent.id(), isolatedTool.id(), "writer-c", "write c",
                null, null, null, null, null, null, "{}",
                new SqliteRuntimeStore.DelegationOptions(List.of(), List.of(), List.of("src/shared.java"),
                        "BLOCK_GRAPH", "workspace/isolated"));
        store.completeRun(parent.id());

        assertThat(store.workspaceOwnerRunId(first.childRunId()))
                .isEqualTo(store.workspaceOwnerRunId(second.childRunId()));
        assertThat(store.workspaceOwnerRunId(isolated.childRunId()))
                .isNotEqualTo(store.workspaceOwnerRunId(first.childRunId()));
        assertThat(store.claimNextRun()).get().extracting("id").isEqualTo(first.childRunId());
        assertThat(store.claimNextRun()).get().extracting("id").isEqualTo(isolated.childRunId());
        assertThat(store.claimNextRun()).isEmpty();
        store.completeRun(first.childRunId());
        assertThat(store.claimNextRun()).get().extracting("id").isEqualTo(second.childRunId());
    }

    @Test
    void routesFailedDependenciesThroughBlockDegradeAndHumanPolicies() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("failure graph", "project-a");
        var parent = store.createRun(session.id(), "coordinate");
        var sourceTool = store.createToolCall(parent.id(), "spawn-source", "spawn_agent", "{}",
                "spawn-source-" + parent.id());
        var source = store.createOrGetDelegation(parent.id(), sourceTool.id(), "source", "fail",
                null, null);
        var blocked = dependent(store, parent, source, "blocked", "BLOCK_GRAPH");
        var degraded = dependent(store, parent, source, "degraded", "DEGRADE");
        var human = dependent(store, parent, source, "human", "REQUIRE_HUMAN");
        store.completeRun(parent.id());

        assertThat(store.claimNextRun()).get().extracting("id").isEqualTo(source.childRunId());
        store.failRun(source.childRunId(), "model failed");

        assertThat(store.findDelegation(parent.id(), blocked.childRunId()).orElseThrow().status())
                .isEqualTo("CANCELED");
        assertThat(store.findDelegation(parent.id(), degraded.childRunId()).orElseThrow().status())
                .isEqualTo("QUEUED");
        assertThat(store.findDelegation(parent.id(), human.childRunId()).orElseThrow().status())
                .isEqualTo("WAITING_HUMAN");
        assertThat(store.decideDelegation(parent.id(), human.id(), "APPROVE", "accept degraded input").status())
                .isEqualTo("QUEUED");
    }

    private static com.paicli.platform.server.domain.RunDelegationRecord dependent(
            SqliteRuntimeStore store, com.paicli.platform.server.domain.RunRecord parent,
            com.paicli.platform.server.domain.RunDelegationRecord source, String name, String policy) {
        var tool = store.createToolCall(parent.id(), "spawn-" + name, "spawn_agent", "{}",
                "spawn-" + name + "-" + parent.id());
        return store.createOrGetDelegation(parent.id(), tool.id(), name, name + " task",
                null, null, null, null, null, null, "{}",
                new SqliteRuntimeStore.DelegationOptions(List.of(source.id()), List.of(), List.of(),
                        policy, "workspace/root"));
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
                "summary, risks, fixes", "REVIEWER", "MANUAL", "PROJECT", "INHERIT",
                "enabled", "max", "powershell", true, "", 0);
        var leader = productivity.saveAgentProfile(null, "project-p1", "Delivery Leader",
                "Coordinates experts", "Delegate and synthesize.", profile.id(),
                "[\"list_agent_profiles\",\"spawn_agent\"]", "[]",
                "summary", "LEADER", "LEADER_ASSIGNED", "PROJECT", "INHERIT",
                "enabled", "max", true, "", 0);
        var team = productivity.saveAgentTeam(null, "project-p1", "Delivery Team",
                "Leader plus reviewer", leader.id(), "[\"" + agent.id() + "\"]",
                2, 1, true, false, true);
        var budget = productivity.saveBudget("project-p1", 10_000, 100_000, 1, 10, .8, 2);
        var session = store.createSession("P1", "project-p1");
        var run = store.createRun(session.id(), "review", "disabled", "", List.of(),
                profile.id(), agent.id(), 5, 0, agent.executionShell());
        store.recordModelUsage(run.id(), "openai-compatible", profile.model(), 100, 90, 10, 20,
                250, 1, true);
        var schedule = productivity.saveSchedule(null, "project-p1", "日报", template.id(),
                "DAILY", "", "{}", profile.id(), agent.id(), null, true,
                java.time.Instant.now().minusSeconds(1));
        var channel = productivity.saveNotification(null, "project-p1", "浏览器", "BROWSER",
                "", "", "COMPLETED,FAILED", true);

        assertThat(productivity.templates("project-p1")).containsExactly(template);
        assertThat(productivity.markTemplateUsed("project-p1", template.id()).id()).isEqualTo(template.id());
        assertThat(productivity.markTemplateUsed("project-p1", "review").id()).isEqualTo(template.id());
        assertThat(productivity.resolveModelProfile("project-p1", null)).contains(profile);
        assertThat(productivity.agentProfiles("project-p1")).containsExactlyInAnyOrder(agent, leader);
        assertThat(productivity.resolveAgentProfile("project-p1", agent.id())).contains(agent);
        assertThat(productivity.agentTeams("project-p1")).containsExactly(team);
        assertThat(productivity.findAgentTeam(team.id())).contains(team);
        assertThat(agent.thinkingMode()).isEqualTo("enabled");
        assertThat(agent.reasoningEffort()).isEqualTo("max");
        assertThat(agent.executionShell()).isEqualTo("powershell");
        assertThat(store.findRun(run.id()).orElseThrow().agentProfileId()).isEqualTo(agent.id());
        assertThat(store.findRun(run.id()).orElseThrow().executionShell()).isEqualTo("powershell");
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
        assertThat(schedule.modelProfileId()).isEqualTo(profile.id());
        assertThat(schedule.agentProfileId()).isEqualTo(agent.id());
        assertThat(schedule.agentTeamId()).isNull();
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
    void batchDeleteIsAtomicAndPhysicallyRemovesRuntimeRecordsAndArtifactFiles() throws Exception {
        SqliteRuntimeStore store = store();
        var memoryA = store.createMemory("project-a", "batch.a", "first", "batch");
        var memoryB = store.createMemory("project-a", "batch.b", "second", "batch");
        store.updateMemory(memoryA.id(), memoryA.memoryKey(), "first revised", memoryA.tags());

        assertThatThrownBy(() -> store.deleteMemories(List.of(memoryA.id(), "missing-memory")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-memory");
        assertThat(store.findMemory(memoryA.id())).isPresent();
        assertThat(store.deleteMemories(List.of(memoryA.id(), memoryB.id())))
                .containsExactly(memoryA.id(), memoryB.id());
        assertThat(store.findMemory(memoryA.id())).isEmpty();
        assertThat(countWhere("memory_revisions", "memory_id", memoryA.id())).isZero();

        var policySession = store.createSession("policies", "project-a");
        var policyA = store.createApprovalPolicy("PROJECT", null, "project-a", "write_file", "a".repeat(64));
        var policyB = store.createApprovalPolicy("SESSION", policySession.id(), "project-a",
                "execute_command", "b".repeat(64));
        assertThat(store.deleteApprovalPolicies(List.of(policyA.id(), policyB.id())))
                .containsExactly(policyA.id(), policyB.id());
        assertThat(store.approvalPolicies("project-a")).isEmpty();

        var artifactSession = store.createSession("artifacts", "project-a");
        var artifactRun = store.createRun(artifactSession.id(), "create artifacts");
        LocalArtifactStore artifacts = new LocalArtifactStore(properties(), store);
        var artifactA = artifacts.saveText(artifactRun.id(), "report", "a", "alpha");
        var artifactB = artifacts.saveText(artifactRun.id(), "report", "b", "beta");
        Path artifactAPath = artifacts.root().resolve(artifactA.relativePath());
        Path artifactBPath = artifacts.root().resolve(artifactB.relativePath());
        assertThat(Files.exists(artifactAPath)).isTrue();
        assertThat(artifacts.deleteBatch(List.of(artifactA.id(), artifactB.id())))
                .containsExactly(artifactA.id(), artifactB.id());
        assertThat(store.findArtifact(artifactA.id())).isEmpty();
        assertThat(Files.exists(artifactAPath)).isFalse();
        assertThat(Files.exists(artifactBPath)).isFalse();

        var failedSession = store.createSession("failed", "project-a");
        var failedRun = store.createRun(failedSession.id(), "failed work");
        var call = store.createToolCall(failedRun.id(), "provider", "write_file", "{}", "batch-run-call");
        var approval = store.createApproval(failedRun.id(), call.id(), "confirm");
        var runArtifact = store.createArtifact(failedRun.id(), "tool-result", "result", "result.txt", 1, "abc");
        store.recordModelUsage(failedRun.id(), "provider", 10, 8, 2, 0);
        store.startModelAttempt(failedRun.id(), "provider", "model", 1);
        store.saveCollaborationPolicy(failedRun.id(), true, "medium", "medium",
                "[]", 2, 1, 2, 4_000, 0, false, false, false);
        store.failRun(failedRun.id(), "expected failure");

        var activeSession = store.createSession("active", "project-a");
        var activeRun = store.createRun(activeSession.id(), "active work");
        assertThatThrownBy(() -> store.deleteRuns(List.of(failedRun.id(), activeRun.id())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only terminal runs");
        assertThat(store.findRun(failedRun.id())).isPresent();
        assertThat(store.findApproval(approval.id())).isPresent();

        var graphSession = store.createSession("delegation graph", "project-a");
        var graphParent = store.createRun(graphSession.id(), "delegate work");
        var graphTool = store.createToolCall(graphParent.id(), "spawn", "spawn_agent", "{}",
                "batch-delete-graph");
        var delegation = store.createOrGetDelegation(graphParent.id(), graphTool.id(), "worker", "child work");
        store.failRun(graphParent.id(), "parent failed");
        assertThatThrownBy(() -> store.deleteRuns(List.of(graphParent.id())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active delegated relatives", delegation.childRunId());

        store.cancelRun(activeRun.id());
        assertThat(store.deleteRuns(List.of(failedRun.id(), activeRun.id())))
                .containsExactly(failedRun.id(), activeRun.id());
        assertThat(store.findRun(failedRun.id())).isEmpty();
        assertThat(store.findApproval(approval.id())).isEmpty();
        assertThat(store.findToolCall(call.id())).isEmpty();
        assertThat(store.findArtifact(runArtifact.id())).isEmpty();
        assertThat(store.messages(failedSession.id())).isEmpty();
        for (String table : List.of("run_events", "model_usage", "model_attempts",
                "memory_extractions", "run_collaboration_policies")) {
            assertThat(countWhere(table, "run_id", failedRun.id())).as(table).isZero();
        }
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
    void cancelingRunClosesItsPendingApprovals() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("cancel-approval");
        var run = store.createRun(session.id(), "work");
        var call = store.createToolCall(run.id(), "command", "execute_command", "{}", "cancel-approval-key");
        var approval = store.createApproval(run.id(), call.id(), "confirm");

        assertThat(store.cancelRun(run.id())).isTrue();

        assertThat(store.findApproval(approval.id())).hasValueSatisfying(value -> {
            assertThat(value.status()).isEqualTo(com.paicli.platform.common.ApprovalStatus.DENIED);
            assertThat(value.resolvedAt()).isNotNull();
        });
        assertThat(store.pendingApprovals()).isEmpty();
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
    void waitsForConcurrentWriterBeforeCommittingToolOutcome() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("tool-outcome-lock");
        var run = store.createRun(session.id(), "list files");
        store.claimNextRun().orElseThrow();
        var call = store.createToolCall(run.id(), "provider-list", "list_dir", "{}",
                "list-files-once");
        store.markRunStatus(run.id(), RunStatus.WAITING_TOOL);
        store.markToolRunning(call.id());

        String url = "jdbc:sqlite:" + tempDir.resolve("paicli.db").toAbsolutePath();
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = DriverManager.getConnection(url)) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.executeUpdate("UPDATE sessions SET updated_at=updated_at WHERE id='" + session.id() + "'");
            }

            var commit = executor.submit(() -> store.commitToolOutcome(
                    session.id(), run.id(), call, true, "[]", null, "{}", 0));
            Thread.sleep(200);
            assertThat(commit.isDone()).isFalse();

            blocker.commit();
            assertThat(commit.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(store.findToolCall(call.id()).orElseThrow().status())
                .isEqualTo(ToolCallStatus.COMPLETED);
        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(store.messages(session.id())).extracting("role").containsExactly("user", "tool");
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

    @Test
    void savesAndBumpsWorkingPlanPerRun() throws Exception {
        var store = store();
        var session = store.createSession("working plan");
        var run = store.createRun(session.id(), "multi step task");

        assertThat(store.latestWorkingPlan(run.id())).isEmpty();

        var first = store.saveWorkingPlan(run.id(), "fix the validator",
                "[{\"id\":\"s1\",\"title\":\"inspect\",\"status\":\"IN_PROGRESS\"}]", "ACTIVE");
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.itemsJson()).contains("\"IN_PROGRESS\"");

        var second = store.saveWorkingPlan(run.id(), "fix the validator",
                "[{\"id\":\"s1\",\"title\":\"inspect\",\"status\":\"COMPLETED\"}]", "ACTIVE");
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.objective()).isEqualTo("fix the validator");

        assertThat(store.latestWorkingPlan(run.id())).hasValueSatisfying(plan -> {
            assertThat(plan.revision()).isEqualTo(2);
            assertThat(plan.itemsJson()).contains("\"COMPLETED\"");
        });

        var otherSession = store.createSession("working plan other");
        var other = store.createRun(otherSession.id(), "another");
        assertThat(store.latestWorkingPlan(other.id())).isEmpty();
    }

    @Test
    void savesAndLoadsLatestReflection() throws Exception {
        var store = store();
        var session = store.createSession("reflections");
        var run = store.createRun(session.id(), "reflect");

        assertThat(store.latestReflection(run.id())).isEmpty();

        store.saveReflection(run.id(), "TOOL_ERROR", "read failed", "CHANGE_ARGUMENTS", "[]", "[\"tool-1\"]", "retry differently");
        var latest = store.latestReflection(run.id()).orElseThrow();
        assertThat(latest.failureClass()).isEqualTo("TOOL_ERROR");
        assertThat(latest.decision()).isEqualTo("CHANGE_ARGUMENTS");
        assertThat(latest.evidenceRefsJson()).contains("tool-1");

        store.saveReflection(run.id(), "TEST_FAILURE", "test red", "CHANGE_APPROACH", "[]", "[]", "fix test");
        assertThat(store.latestReflection(run.id())).hasValueSatisfying(value ->
                assertThat(value.failureClass()).isEqualTo("TEST_FAILURE"));
    }

    @Test
    void storesTaskDigestDeliverySnapshotAndRoutingSignals() throws Exception {
        var store = store();
        var session = store.createSession("digest");
        var run = store.createRun(session.id(), "task");

        assertThat(store.latestTaskDigest("task-x")).isEmpty();
        store.saveTaskDigest("task-x", "{\"ok\":true}", "5");
        assertThat(store.latestTaskDigest("task-x")).hasValueSatisfying(value -> {
            assertThat(value.revision()).isEqualTo(1);
            assertThat(value.lastActivityId()).isEqualTo("5");
        });
        store.saveTaskDigest("task-x", "{\"ok\":false}", "6");
        assertThat(store.latestTaskDigest("task-x")).hasValueSatisfying(value ->
                assertThat(value.revision()).isEqualTo(2));

        store.saveDelivery("task-x", 1, 1, run.id(), "{\"stage\":1}", "hash-1", "DELIVERED");
        assertThat(store.deliveriesForTask("task-x")).hasSize(1);
        assertThat(store.deliveriesForTask("task-x").get(0).contentHash()).isEqualTo("hash-1");

        store.saveAcceptedSnapshot("task-x", "{\"accepted\":true}");
        assertThat(store.latestAcceptedSnapshot("task-x")).hasValueSatisfying(value ->
                assertThat(value.snapshotJson()).contains("accepted"));

        assertThat(store.agentPassRate("default", "ghost-agent")).isEqualTo(0.5);
        assertThat(store.activeRunsForAgent("ghost-agent")).isEqualTo(0);
    }

    private SqliteRuntimeStore store() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        return store;
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }

    @Test
    void commitFinalAssistantAndCompleteRefusesWhenNewInputArrivedDuringModel() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("race-final");
        var run = store.createRun(session.id(), "work");
        store.markRunStatus(run.id(), RunStatus.WAITING_MODEL);
        long contextSeq = store.maxMessageSequence(session.id());

        store.appendMessage(session.id(), run.id(), "user", "评论在模型执行期间到达");

        boolean completed = store.commitFinalAssistantAndComplete(session.id(), run.id(),
                "final answer", null, "{\"content\":\"final answer\"}", contextSeq);

        assertThat(completed).isFalse();
        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.WAITING_MODEL);
        assertThat(store.messages(session.id()).stream()
                .anyMatch(message -> "assistant".equals(message.role()))).isFalse();
    }

    @Test
    void commitIntermediateAssistantAndRequeuePersistsAtomically() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("race-requeue");
        var run = store.createRun(session.id(), "work");
        store.markRunStatus(run.id(), RunStatus.WAITING_MODEL);

        boolean requeued = store.commitIntermediateAssistantAndRequeue(session.id(), run.id(),
                "intermediate answer", null, "{\"contextMessageSequence\":1,\"latestSequence\":2,"
                        + "\"staleAssistantArchived\":true}", 1);

        assertThat(requeued).isTrue();
        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        // Stale answer stays in the full audit history ...
        assertThat(store.messages(session.id())).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("assistant");
            assertThat(message.archived()).isTrue();
        });
        // ... but never enters the next round's active context.
        assertThat(store.activeMessages(session.id()).stream()
                .anyMatch(message -> "assistant".equals(message.role()))).isFalse();
        assertThat(store.events(run.id(), 0)).extracting("type")
                .contains("run.new_input_during_model", "run.queued");
    }

    @Test
    void appendUserMessageIfRunActiveRefusesForTerminalRun() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("race-append");
        var run = store.createRun(session.id(), "work");
        store.completeRun(run.id());

        boolean appended = store.appendUserMessageIfRunActive(session.id(), run.id(), "late comment");

        assertThat(appended).isFalse();
        assertThat(store.messages(session.id()).stream()
                .noneMatch(message -> "late comment".equals(message.content()))).isTrue();
    }

    private long countWhere(String table, String column, String value) throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("paicli.db").toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?")) {
            statement.setString(1, value);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }
}
