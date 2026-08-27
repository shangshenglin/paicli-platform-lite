package com.paicli.platform.server.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.common.ToolEffect;
import com.paicli.platform.common.ApprovalStatus;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.domain.AcceptedSnapshotRecord;
import com.paicli.platform.server.domain.ApprovalRecord;
import com.paicli.platform.server.domain.CompletionMode;
import com.paicli.platform.server.domain.RunCompletionContractRecord;
import com.paicli.platform.server.domain.ArtifactRecord;
import com.paicli.platform.server.domain.DeliveryRecord;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.InputAttachmentRecord;
import com.paicli.platform.server.domain.MemoryRecord;
import com.paicli.platform.server.domain.RunEventRecord;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.domain.ReflectionRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.SessionRecord;
import com.paicli.platform.server.domain.TaskDigestRecord;
import com.paicli.platform.server.domain.TaskTitle;
import com.paicli.platform.server.domain.SessionGroupRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.domain.WorkingPlanRecord;
import com.paicli.platform.server.agent.RunEvidence;
import com.paicli.platform.server.agent.RunEvidenceDecoder;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Repository
public class SqliteRuntimeStore {
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([a-zA-Z0-9_.-]{1,120})]]");
    private static final Pattern SAFE_WORKSPACE_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,240}");
    private final Path databasePath;
    private final Path workspaceRoot;
    private final Path artifactRoot;
    private final Path attachmentRoot;
    private final SqliteConnectionFactory connections;
    private final ObjectMapper mapper = new ObjectMapper();

    public SqliteRuntimeStore(PlatformProperties properties) {
        this.databasePath = properties.dataDir().resolve("paicli.db").toAbsolutePath().normalize();
        this.workspaceRoot = properties.workspaceRoot().toAbsolutePath().normalize();
        this.artifactRoot = properties.dataDir().resolve("artifacts").toAbsolutePath().normalize();
        this.attachmentRoot = properties.dataDir().resolve("input-attachments").toAbsolutePath().normalize();
        this.connections = new SqliteConnectionFactory(databasePath);
    }

    @PostConstruct
    public void initialize() throws Exception {
        Files.createDirectories(databasePath.getParent());
        connections.initialize();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (" +
                    "version INTEGER PRIMARY KEY, description TEXT NOT NULL, applied_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS session_groups (" +
                    "id TEXT PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS sessions (" +
                    "id TEXT PRIMARY KEY, title TEXT NOT NULL, project_key TEXT NOT NULL DEFAULT 'default', " +
                    "group_id TEXT, status TEXT NOT NULL, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            SqliteSchemaMigrator.ensureColumn(connection, "sessions", "project_key", "TEXT NOT NULL DEFAULT 'default'");
            SqliteSchemaMigrator.ensureColumn(connection, "sessions", "group_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "sessions", "is_internal", "INTEGER NOT NULL DEFAULT 0");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sessions_group_updated " +
                    "ON sessions(group_id, updated_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS runs (" +
                    "id TEXT PRIMARY KEY, session_id TEXT NOT NULL, status TEXT NOT NULL, input TEXT NOT NULL, " +
                    "current_step INTEGER NOT NULL DEFAULT 0, error TEXT, " +
                    "thinking_mode TEXT NOT NULL DEFAULT 'auto', reasoning_effort TEXT NOT NULL DEFAULT '', " +
                    "created_at TEXT NOT NULL, queued_at TEXT, " +
                    "started_at TEXT, finished_at TEXT, version INTEGER NOT NULL DEFAULT 0, " +
                    "FOREIGN KEY(session_id) REFERENCES sessions(id))");
            SqliteSchemaMigrator.ensureColumn(connection, "runs", "thinking_mode", "TEXT NOT NULL DEFAULT 'auto'");
            SqliteSchemaMigrator.ensureColumn(connection, "runs", "reasoning_effort", "TEXT NOT NULL DEFAULT ''");
            SqliteSchemaMigrator.ensureColumn(connection, "runs", "execution_shell", "TEXT NOT NULL DEFAULT 'bash'");
            SqliteSchemaMigrator.ensureColumn(connection, "runs", "queued_at", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "runs", "priority", "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "runs", "model_profile_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "runs", "agent_profile_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "runs", "retry_count", "INTEGER NOT NULL DEFAULT 0");
            statement.execute("UPDATE runs SET queued_at=created_at WHERE queued_at IS NULL");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_runs_status_created ON runs(status, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_runs_queue_priority " +
                    "ON runs(status, priority DESC, queued_at, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_runs_session ON runs(session_id, created_at)");
            reconcileDuplicateActiveRuns(connection);
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_runs_one_active_session ON runs(session_id) " +
                    "WHERE status NOT IN ('COMPLETED','FAILED','CANCELED')");
            statement.execute("CREATE TABLE IF NOT EXISTS messages (" +
                    "id TEXT PRIMARY KEY, session_id TEXT NOT NULL, run_id TEXT, role TEXT NOT NULL, " +
                    "content TEXT NOT NULL, reasoning_content TEXT, tool_call_id TEXT, tool_calls_json TEXT, " +
                    "archived INTEGER NOT NULL DEFAULT 0, sequence INTEGER NOT NULL, created_at TEXT NOT NULL, " +
                    "FOREIGN KEY(session_id) REFERENCES sessions(id), FOREIGN KEY(run_id) REFERENCES runs(id), " +
                    "UNIQUE(session_id, sequence))");
            SqliteSchemaMigrator.ensureColumn(connection, "messages", "tool_call_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "messages", "tool_calls_json", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "messages", "reasoning_content", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "messages", "archived", "INTEGER NOT NULL DEFAULT 0");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_messages_session_sequence ON messages(session_id, sequence)");
            statement.execute("CREATE TABLE IF NOT EXISTS run_events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, event_type TEXT NOT NULL, " +
                    "event_data TEXT NOT NULL, sequence INTEGER NOT NULL, created_at TEXT NOT NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runs(id), UNIQUE(run_id, sequence))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_events_run_id ON run_events(run_id, id)");
            statement.execute("CREATE TABLE IF NOT EXISTS tool_calls (" +
                    "id TEXT PRIMARY KEY, run_id TEXT NOT NULL, provider_call_id TEXT, tool_name TEXT NOT NULL, " +
                    "arguments TEXT NOT NULL, status TEXT NOT NULL, result TEXT, error TEXT, " +
                    "idempotency_key TEXT NOT NULL UNIQUE, retry_count INTEGER NOT NULL DEFAULT 0, " +
                    "created_at TEXT NOT NULL, finished_at TEXT, FOREIGN KEY(run_id) REFERENCES runs(id))");
            SqliteSchemaMigrator.ensureColumn(connection, "tool_calls", "effect",
                    "TEXT NOT NULL DEFAULT 'NON_IDEMPOTENT_WRITE'");
            SqliteSchemaMigrator.ensureColumn(connection, "tool_calls", "result_metadata_json",
                    "TEXT NOT NULL DEFAULT '{}'");
            SqliteSchemaMigrator.ensureColumn(connection, "tool_calls", "wait_kind", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "tool_calls", "wait_ref", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "tool_calls", "waiting_since", "TEXT");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_tool_calls_run ON tool_calls(run_id, created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS approvals (" +
                    "id TEXT PRIMARY KEY, run_id TEXT NOT NULL, tool_call_id TEXT NOT NULL UNIQUE, " +
                    "status TEXT NOT NULL, reason TEXT NOT NULL, created_at TEXT NOT NULL, resolved_at TEXT, " +
                    "FOREIGN KEY(run_id) REFERENCES runs(id), FOREIGN KEY(tool_call_id) REFERENCES tool_calls(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_approvals_status ON approvals(status, created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS artifacts (" +
                    "id TEXT PRIMARY KEY, run_id TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, " +
                    "relative_path TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, created_at TEXT NOT NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runs(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_artifacts_run ON artifacts(run_id, created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS input_attachments (" +
                    "id TEXT PRIMARY KEY, session_id TEXT NOT NULL, run_id TEXT, message_id TEXT, " +
                    "name TEXT NOT NULL, mime_type TEXT NOT NULL, relative_path TEXT NOT NULL, " +
                    "size INTEGER NOT NULL, sha256 TEXT NOT NULL, created_at TEXT NOT NULL, " +
                    "FOREIGN KEY(session_id) REFERENCES sessions(id), FOREIGN KEY(run_id) REFERENCES runs(id), " +
                    "FOREIGN KEY(message_id) REFERENCES messages(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_input_attachments_run ON input_attachments(run_id, created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS memories (" +
                    "id TEXT PRIMARY KEY, project_key TEXT NOT NULL, memory_key TEXT NOT NULL, " +
                    "content TEXT NOT NULL, tags TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, " +
                    "updated_at TEXT NOT NULL, UNIQUE(project_key, memory_key))");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "layer", "TEXT NOT NULL DEFAULT 'L3'");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "memory_type", "TEXT NOT NULL DEFAULT 'FACT'");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "confidence", "REAL NOT NULL DEFAULT 1.0");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "origin", "TEXT NOT NULL DEFAULT 'manual'");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "source_session_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "source_run_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "embedding_json", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "last_accessed_at", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "access_count", "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "pinned", "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "enabled", "INTEGER NOT NULL DEFAULT 1");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "confirmed_at", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "structured_payload", "TEXT NOT NULL DEFAULT '{}'");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "status", "TEXT NOT NULL DEFAULT 'ACTIVE'");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "source_type", "TEXT NOT NULL DEFAULT 'manual'");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "source_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "source_revision", "TEXT NOT NULL DEFAULT '1'");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "valid_from", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "valid_to", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "supersedes_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "checksum", "TEXT NOT NULL DEFAULT ''");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "scope_type",
                    "TEXT NOT NULL DEFAULT 'PROJECT'");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "scope_agent_profile_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "scope_workspace_owner_run_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "memories", "scope_task_type", "TEXT");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_memories_project ON memories(project_key, updated_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_memories_status " +
                    "ON memories(project_key,status,enabled,updated_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_memories_scope " +
                    "ON memories(project_key,scope_type,scope_agent_profile_id,scope_task_type,updated_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_revisions (" +
                    "id TEXT PRIMARY KEY, memory_id TEXT NOT NULL, content TEXT NOT NULL, tags TEXT NOT NULL, " +
                    "layer TEXT NOT NULL, memory_type TEXT NOT NULL, confidence REAL NOT NULL, " +
                    "replaced_at TEXT NOT NULL, source_run_id TEXT, FOREIGN KEY(memory_id) REFERENCES memories(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_memory_revisions_memory " +
                    "ON memory_revisions(memory_id, replaced_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_sources (" +
                    "id TEXT PRIMARY KEY, memory_id TEXT NOT NULL, source_type TEXT NOT NULL, source_id TEXT, " +
                    "source_revision TEXT NOT NULL DEFAULT '1', excerpt TEXT NOT NULL DEFAULT '', " +
                    "created_at TEXT NOT NULL, FOREIGN KEY(memory_id) REFERENCES memories(id) ON DELETE CASCADE)");
            SqliteSchemaMigrator.ensureColumn(connection, "memory_sources", "source_message_ids_json",
                    "TEXT NOT NULL DEFAULT '[]'");
            SqliteSchemaMigrator.ensureColumn(connection, "memory_sources", "source_start_sequence", "INTEGER");
            SqliteSchemaMigrator.ensureColumn(connection, "memory_sources", "source_end_sequence", "INTEGER");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_memory_sources_memory " +
                    "ON memory_sources(memory_id, created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_conflicts (" +
                    "id TEXT PRIMARY KEY, project_key TEXT NOT NULL, memory_id TEXT NOT NULL, conflicting_memory_id TEXT NOT NULL, " +
                    "reason TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'OPEN', created_at TEXT NOT NULL, resolved_at TEXT, " +
                    "FOREIGN KEY(memory_id) REFERENCES memories(id) ON DELETE CASCADE, " +
                    "FOREIGN KEY(conflicting_memory_id) REFERENCES memories(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_memory_conflicts_project " +
                    "ON memory_conflicts(project_key,status,created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_extractions (" +
                    "run_id TEXT PRIMARY KEY, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, " +
                    "error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runs(id))");
            SqliteSchemaMigrator.ensureColumn(connection, "memory_extractions", "source_snapshot_json",
                    "TEXT NOT NULL DEFAULT '[]'");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_memory_extractions_status " +
                    "ON memory_extractions(status, updated_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_usage_feedback (" +
                    "run_id TEXT NOT NULL, memory_id TEXT NOT NULL, selected_at TEXT NOT NULL, " +
                    "outcome TEXT NOT NULL DEFAULT 'SELECTED', updated_at TEXT NOT NULL, " +
                    "PRIMARY KEY(run_id,memory_id), FOREIGN KEY(run_id) REFERENCES runs(id) ON DELETE CASCADE, " +
                    "FOREIGN KEY(memory_id) REFERENCES memories(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_memory_usage_feedback_memory " +
                    "ON memory_usage_feedback(memory_id,outcome,updated_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS model_usage (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, provider TEXT NOT NULL, " +
                    "estimated_input_tokens INTEGER NOT NULL, input_tokens INTEGER NOT NULL, " +
                    "output_tokens INTEGER NOT NULL, cached_input_tokens INTEGER NOT NULL, created_at TEXT NOT NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runs(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_model_usage_run ON model_usage(run_id, created_at)");
            SqliteSchemaMigrator.ensureColumn(connection, "model_usage", "model_name", "TEXT NOT NULL DEFAULT ''");
            SqliteSchemaMigrator.ensureColumn(connection, "model_usage", "duration_ms", "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "model_usage", "retry_count", "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "model_usage", "local_model", "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "model_usage", "reusable_prefix_tokens",
                    "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "model_usage", "ttft_ms", "INTEGER NOT NULL DEFAULT 0");
            statement.execute("CREATE TABLE IF NOT EXISTS model_attempts (" +
                    "id TEXT PRIMARY KEY,run_id TEXT NOT NULL,provider TEXT NOT NULL,model_name TEXT NOT NULL," +
                    "attempt_ordinal INTEGER NOT NULL,status TEXT NOT NULL,http_status INTEGER,error TEXT," +
                    "started_at TEXT NOT NULL,finished_at TEXT)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_model_attempts_run " +
                    "ON model_attempts(run_id,started_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS approval_policies (" +
                    "id TEXT PRIMARY KEY, scope TEXT NOT NULL, session_id TEXT, project_key TEXT NOT NULL, " +
                    "tool_name TEXT NOT NULL, arguments_sha256 TEXT NOT NULL, created_at TEXT NOT NULL, " +
                    "UNIQUE(scope,session_id,project_key,tool_name,arguments_sha256))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_approval_policies_match " +
                    "ON approval_policies(tool_name,arguments_sha256,project_key,session_id)");
            statement.execute("DELETE FROM approval_policies WHERE rowid NOT IN (SELECT MIN(rowid) " +
                    "FROM approval_policies GROUP BY scope,COALESCE(session_id,''),project_key,tool_name,arguments_sha256)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_approval_policy_project " +
                    "ON approval_policies(project_key,tool_name,arguments_sha256) WHERE scope='PROJECT'");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_approval_policy_session " +
                    "ON approval_policies(session_id,project_key,tool_name,arguments_sha256) WHERE scope='SESSION'");
            statement.execute("CREATE TABLE IF NOT EXISTS knowledge_feedback (" +
                    "id TEXT PRIMARY KEY, project_key TEXT NOT NULL, document_name TEXT NOT NULL, " +
                    "chunk_index INTEGER NOT NULL, helpful INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', " +
                    "created_at TEXT NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_feedback_project " +
                    "ON knowledge_feedback(project_key,created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS run_delegations (" +
                    "id TEXT PRIMARY KEY, parent_run_id TEXT NOT NULL, parent_tool_call_id TEXT NOT NULL UNIQUE, " +
                    "child_session_id TEXT NOT NULL, child_run_id TEXT NOT NULL UNIQUE, agent_name TEXT NOT NULL, " +
                    "agent_profile_id TEXT, task TEXT NOT NULL, plan_id TEXT, plan_step_id TEXT, " +
                    "envelope_json TEXT NOT NULL DEFAULT '{}', result_json TEXT NOT NULL DEFAULT '{}', " +
                    "status TEXT NOT NULL DEFAULT 'QUEUED', failure_class TEXT, completed_at TEXT, created_at TEXT NOT NULL, " +
                    "FOREIGN KEY(parent_run_id) REFERENCES runs(id), " +
                    "FOREIGN KEY(parent_tool_call_id) REFERENCES tool_calls(id), " +
                    "FOREIGN KEY(child_session_id) REFERENCES sessions(id), " +
                    "FOREIGN KEY(child_run_id) REFERENCES runs(id))");
            SqliteSchemaMigrator.ensureColumn(connection, "run_delegations", "agent_profile_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "run_delegations", "plan_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "run_delegations", "plan_step_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "run_delegations", "envelope_json", "TEXT NOT NULL DEFAULT '{}'");
            SqliteSchemaMigrator.ensureColumn(connection, "run_delegations", "result_json", "TEXT NOT NULL DEFAULT '{}'");
            SqliteSchemaMigrator.ensureColumn(connection, "run_delegations", "status", "TEXT NOT NULL DEFAULT 'QUEUED'");
            SqliteSchemaMigrator.ensureColumn(connection, "run_delegations", "failure_class", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "run_delegations", "failure_policy",
                    "TEXT NOT NULL DEFAULT 'BLOCK_GRAPH'");
            SqliteSchemaMigrator.ensureColumn(connection, "run_delegations", "blocked_reason", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "run_delegations", "workspace_ref", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "run_delegations", "completed_at", "TEXT");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_delegations_parent ON run_delegations(parent_run_id, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_delegations_plan_step ON run_delegations(plan_step_id, status)");
            statement.execute("CREATE TABLE IF NOT EXISTS run_delegation_dependencies (" +
                    "delegation_id TEXT NOT NULL, depends_on_delegation_id TEXT NOT NULL, created_at TEXT NOT NULL, " +
                    "PRIMARY KEY(delegation_id,depends_on_delegation_id), " +
                    "FOREIGN KEY(delegation_id) REFERENCES run_delegations(id) ON DELETE CASCADE, " +
                    "FOREIGN KEY(depends_on_delegation_id) REFERENCES run_delegations(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_delegation_dependencies_upstream " +
                    "ON run_delegation_dependencies(depends_on_delegation_id,delegation_id)");
            statement.execute("CREATE TABLE IF NOT EXISTS run_delegation_resources (" +
                    "delegation_id TEXT NOT NULL, resource_key TEXT NOT NULL, access_mode TEXT NOT NULL, " +
                    "created_at TEXT NOT NULL, PRIMARY KEY(delegation_id,resource_key,access_mode), " +
                    "FOREIGN KEY(delegation_id) REFERENCES run_delegations(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_delegation_resources_key " +
                    "ON run_delegation_resources(resource_key,access_mode,delegation_id)");
            statement.execute("CREATE TABLE IF NOT EXISTS run_collaboration_policies (" +
                    "run_id TEXT PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 0, complexity TEXT NOT NULL DEFAULT 'MEDIUM', " +
                    "risk TEXT NOT NULL DEFAULT 'MEDIUM', allowed_agent_profile_ids_json TEXT NOT NULL DEFAULT '[]', " +
                    "max_experts INTEGER NOT NULL DEFAULT 3, max_depth INTEGER NOT NULL DEFAULT 1, " +
                    "max_child_runs INTEGER NOT NULL DEFAULT 6, max_concurrent_agent_runs INTEGER NOT NULL DEFAULT 0, " +
                    "max_estimated_tokens INTEGER NOT NULL DEFAULT 0, " +
                    "max_estimated_cost REAL NOT NULL DEFAULT 0, allow_expert_delegation INTEGER NOT NULL DEFAULT 0, " +
                    "require_reviewer INTEGER NOT NULL DEFAULT 0, require_runner INTEGER NOT NULL DEFAULT 0, " +
                    "created_at TEXT NOT NULL, FOREIGN KEY(run_id) REFERENCES runs(id))");

            statement.execute("CREATE TABLE IF NOT EXISTS run_working_plans (" +
                    "run_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, objective TEXT NOT NULL, " +
                    "items_json TEXT NOT NULL, status TEXT NOT NULL, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runs(id))");
            SqliteSchemaMigrator.ensureColumn(connection, "run_working_plans", "completion_json", "TEXT");
            statement.execute("CREATE TABLE IF NOT EXISTS run_reflections (" +
                    "id TEXT PRIMARY KEY, run_id TEXT NOT NULL, failure_class TEXT NOT NULL, " +
                    "diagnosis TEXT NOT NULL, decision TEXT NOT NULL, plan_patch_json TEXT NOT NULL, " +
                    "evidence_refs_json TEXT NOT NULL, next_action TEXT NOT NULL, created_at TEXT NOT NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runs(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_run_reflections_run ON run_reflections(run_id, created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS run_completion_contracts (" +
                    "run_id TEXT PRIMARY KEY, mode TEXT NOT NULL, " +
                    "requires_workspace_change INTEGER NOT NULL DEFAULT 0, " +
                    "requires_tests INTEGER NOT NULL DEFAULT 0, " +
                    "required_test_families_json TEXT NOT NULL DEFAULT '[]', " +
                    "write_scope_json TEXT NOT NULL DEFAULT '[]', " +
                    "done_criteria_json TEXT NOT NULL DEFAULT '[]', " +
                    "source TEXT NOT NULL, reason TEXT NOT NULL, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runs(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_completion_contracts_run ON run_completion_contracts(run_id)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_task_digests (" +
                    "task_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, digest_json TEXT NOT NULL, " +
                    "last_activity_id TEXT, updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_deliveries (" +
                    "id TEXT PRIMARY KEY, task_id TEXT NOT NULL, stage INTEGER NOT NULL, attempt INTEGER NOT NULL, " +
                    "run_id TEXT NOT NULL, manifest_json TEXT NOT NULL, content_hash TEXT NOT NULL, " +
                    "status TEXT NOT NULL, created_at TEXT NOT NULL, accepted_at TEXT)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_collaboration_deliveries_task " +
                    "ON collaboration_deliveries(task_id, stage, attempt)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_accepted_snapshots (" +
                    "id TEXT PRIMARY KEY, task_id TEXT NOT NULL, snapshot_json TEXT NOT NULL, created_at TEXT NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_collaboration_snapshots_task " +
                    "ON collaboration_accepted_snapshots(task_id, created_at)");
            SqliteSchemaMigrator.ensureColumn(connection, "run_collaboration_policies", "max_concurrent_agent_runs",
                    "INTEGER NOT NULL DEFAULT 0");
            statement.execute("CREATE TABLE IF NOT EXISTS task_templates (" +
                    "id TEXT PRIMARY KEY, project_key TEXT NOT NULL, name TEXT NOT NULL, shortcut TEXT NOT NULL DEFAULT '', " +
                    "prompt TEXT NOT NULL, variables_json TEXT NOT NULL DEFAULT '{}', attachment_requirements TEXT NOT NULL DEFAULT '', " +
                    "allowed_tools TEXT NOT NULL DEFAULT '', model_profile_id TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, " +
                    "last_used_at TEXT, use_count INTEGER NOT NULL DEFAULT 0, UNIQUE(project_key,name))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_templates_project ON task_templates(project_key,updated_at)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_task_templates_shortcut " +
                    "ON task_templates(project_key,shortcut) WHERE shortcut<>''");
            statement.execute("CREATE TABLE IF NOT EXISTS model_profiles (" +
                    "id TEXT PRIMARY KEY, project_key TEXT NOT NULL, name TEXT NOT NULL, base_url TEXT NOT NULL, " +
                    "api_key_env TEXT NOT NULL DEFAULT '', model TEXT NOT NULL, fallback_model TEXT NOT NULL DEFAULT '', " +
                    "max_context_tokens INTEGER NOT NULL, max_output_tokens INTEGER NOT NULL, input_price REAL NOT NULL DEFAULT 0, " +
                    "output_price REAL NOT NULL DEFAULT 0, local_model INTEGER NOT NULL DEFAULT 0, is_default INTEGER NOT NULL DEFAULT 0, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(project_key,name))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_model_profiles_project ON model_profiles(project_key,is_default DESC,name)");
            statement.execute("CREATE TABLE IF NOT EXISTS agent_profiles (" +
                    "id TEXT PRIMARY KEY, project_key TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', " +
                    "system_prompt TEXT NOT NULL, model_profile_id TEXT, tool_names_json TEXT NOT NULL DEFAULT '[]', " +
                    "skill_names_json TEXT NOT NULL DEFAULT '[]', output_schema TEXT NOT NULL DEFAULT '', " +
                    "thinking_mode TEXT NOT NULL DEFAULT '', reasoning_effort TEXT NOT NULL DEFAULT '', " +
                    "collaboration_role TEXT NOT NULL DEFAULT 'EXPERT', handoff_policy TEXT NOT NULL DEFAULT 'MANUAL', " +
                    "workspace_scope TEXT NOT NULL DEFAULT 'PROJECT', approval_policy TEXT NOT NULL DEFAULT 'INHERIT', " +
                    "template_key TEXT NOT NULL DEFAULT '', template_version INTEGER NOT NULL DEFAULT 0, " +
                    "enabled INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, " +
                    "UNIQUE(project_key,name))");
            SqliteSchemaMigrator.ensureColumn(connection, "agent_profiles", "template_key", "TEXT NOT NULL DEFAULT ''");
            SqliteSchemaMigrator.ensureColumn(connection, "agent_profiles", "template_version", "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "agent_profiles", "thinking_mode", "TEXT NOT NULL DEFAULT ''");
            SqliteSchemaMigrator.ensureColumn(connection, "agent_profiles", "reasoning_effort", "TEXT NOT NULL DEFAULT ''");
            SqliteSchemaMigrator.ensureColumn(connection, "agent_profiles", "execution_shell",
                    "TEXT NOT NULL DEFAULT 'bash'");
            SqliteSchemaMigrator.ensureColumn(connection, "runs", "workspace_owner_run_id", "TEXT");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_agent_profiles_project " +
                    "ON agent_profiles(project_key,enabled DESC,name COLLATE NOCASE)");
            statement.execute("CREATE TABLE IF NOT EXISTS agent_teams (" +
                    "id TEXT PRIMARY KEY, project_key TEXT NOT NULL, name TEXT NOT NULL, " +
                    "description TEXT NOT NULL DEFAULT '', leader_agent_profile_id TEXT NOT NULL, " +
                    "member_agent_profile_ids_json TEXT NOT NULL DEFAULT '[]', " +
                    "max_experts INTEGER NOT NULL DEFAULT 3, max_depth INTEGER NOT NULL DEFAULT 1, " +
                    "require_reviewer INTEGER NOT NULL DEFAULT 0, require_runner INTEGER NOT NULL DEFAULT 0, " +
                    "enabled INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, " +
                    "UNIQUE(project_key,name))");
            SqliteSchemaMigrator.ensureColumn(connection, "agent_teams", "team_instructions",
                    "TEXT NOT NULL DEFAULT ''");
            SqliteSchemaMigrator.ensureColumn(connection, "agent_teams", "member_roles_json",
                    "TEXT NOT NULL DEFAULT '{}'");
            SqliteSchemaMigrator.ensureColumn(connection, "agent_teams", "capability_tags_json",
                    "TEXT NOT NULL DEFAULT '[]'");
            SqliteSchemaMigrator.ensureColumn(connection, "agent_teams", "routing_policy",
                    "TEXT NOT NULL DEFAULT 'CAPABILITY_MATCH'");
            SqliteSchemaMigrator.ensureColumn(connection, "agent_teams", "completion_policy",
                    "TEXT NOT NULL DEFAULT 'VALIDATED_REVIEW'");
            SqliteSchemaMigrator.ensureColumn(connection, "agent_teams", "fallback_agent_profile_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "agent_teams", "max_concurrency",
                    "INTEGER NOT NULL DEFAULT 3");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_agent_teams_project " +
                    "ON agent_teams(project_key,enabled DESC,name COLLATE NOCASE)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_tasks (" +
                    "id TEXT PRIMARY KEY,project_key TEXT NOT NULL,title TEXT NOT NULL,description TEXT NOT NULL DEFAULT ''," +
                    "status TEXT NOT NULL DEFAULT 'TODO',priority INTEGER NOT NULL DEFAULT 0," +
                    "assignee_type TEXT NOT NULL DEFAULT 'HUMAN',assignee_id TEXT," +
                    "acceptance_criteria TEXT NOT NULL DEFAULT '',parent_id TEXT,stage INTEGER NOT NULL DEFAULT 0," +
                    "latest_plan_id TEXT,created_by TEXT NOT NULL DEFAULT 'USER'," +
                    "created_at TEXT NOT NULL,updated_at TEXT NOT NULL," +
                    "FOREIGN KEY(parent_id) REFERENCES collaboration_tasks(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_collaboration_tasks_project " +
                    "ON collaboration_tasks(project_key,status,priority DESC,updated_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_collaboration_tasks_parent " +
                    "ON collaboration_tasks(parent_id,stage,status,created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_comments (" +
                    "id TEXT PRIMARY KEY,task_id TEXT NOT NULL,parent_comment_id TEXT," +
                    "author_type TEXT NOT NULL,author_id TEXT,content TEXT NOT NULL," +
                    "resolved INTEGER NOT NULL DEFAULT 0,conclusion INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL," +
                    "FOREIGN KEY(task_id) REFERENCES collaboration_tasks(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(parent_comment_id) REFERENCES collaboration_comments(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_collaboration_comments_task " +
                    "ON collaboration_comments(task_id,created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_activities (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT NOT NULL,activity_type TEXT NOT NULL," +
                    "actor_type TEXT NOT NULL,actor_id TEXT,subject_id TEXT,payload_json TEXT NOT NULL DEFAULT '{}'," +
                    "created_at TEXT NOT NULL,FOREIGN KEY(task_id) REFERENCES collaboration_tasks(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_collaboration_activities_task " +
                    "ON collaboration_activities(task_id,id)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_triggers (" +
                    "id TEXT PRIMARY KEY,task_id TEXT NOT NULL,project_key TEXT NOT NULL,trigger_type TEXT NOT NULL," +
                    "source_id TEXT,target_type TEXT NOT NULL,target_id TEXT NOT NULL,payload_json TEXT NOT NULL DEFAULT '{}'," +
                    "idempotency_key TEXT NOT NULL UNIQUE,status TEXT NOT NULL DEFAULT 'PENDING'," +
                    "created_run_id TEXT,error TEXT,created_at TEXT NOT NULL,processed_at TEXT," +
                    "FOREIGN KEY(task_id) REFERENCES collaboration_tasks(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(created_run_id) REFERENCES runs(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_collaboration_triggers_task " +
                    "ON collaboration_triggers(task_id,created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_collaboration_triggers_status " +
                    "ON collaboration_triggers(status,created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_mentions (" +
                    "comment_id TEXT NOT NULL,target_type TEXT NOT NULL,target_id TEXT NOT NULL,created_at TEXT NOT NULL," +
                    "PRIMARY KEY(comment_id,target_type,target_id)," +
                    "FOREIGN KEY(comment_id) REFERENCES collaboration_comments(id) ON DELETE CASCADE)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_task_runs (" +
                    "task_id TEXT NOT NULL,run_id TEXT NOT NULL UNIQUE,trigger_id TEXT,relationship TEXT NOT NULL DEFAULT 'EXECUTION'," +
                    "created_at TEXT NOT NULL,PRIMARY KEY(task_id,run_id)," +
                    "FOREIGN KEY(task_id) REFERENCES collaboration_tasks(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(run_id) REFERENCES runs(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(trigger_id) REFERENCES collaboration_triggers(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_collaboration_task_runs_task " +
                    "ON collaboration_task_runs(task_id,created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_expert_threads (" +
                    "id TEXT PRIMARY KEY,root_task_id TEXT NOT NULL,agent_profile_id TEXT NOT NULL," +
                    "thread_role TEXT NOT NULL DEFAULT 'EXPERT',status TEXT NOT NULL DEFAULT 'ACTIVE'," +
                    "digest_json TEXT NOT NULL DEFAULT '{}',latest_run_id TEXT," +
                    "created_at TEXT NOT NULL,updated_at TEXT NOT NULL," +
                    "UNIQUE(root_task_id,agent_profile_id,thread_role)," +
                    "FOREIGN KEY(root_task_id) REFERENCES collaboration_tasks(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(latest_run_id) REFERENCES runs(id) ON DELETE SET NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_expert_thread_runs (" +
                    "thread_id TEXT NOT NULL,run_id TEXT NOT NULL UNIQUE,ordinal INTEGER NOT NULL," +
                    "created_at TEXT NOT NULL,PRIMARY KEY(thread_id,run_id),UNIQUE(thread_id,ordinal)," +
                    "FOREIGN KEY(thread_id) REFERENCES collaboration_expert_threads(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(run_id) REFERENCES runs(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_expert_thread_runs_run " +
                    "ON collaboration_expert_thread_runs(run_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_expert_threads_root " +
                    "ON collaboration_expert_threads(root_task_id,updated_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_route_decisions (" +
                    "id TEXT PRIMARY KEY,project_key TEXT NOT NULL,task_id TEXT,trigger_id TEXT,input TEXT NOT NULL," +
                    "complexity TEXT NOT NULL,risk TEXT NOT NULL,target_type TEXT NOT NULL,target_id TEXT," +
                    "leader_agent_profile_id TEXT,recommended_agent_profile_ids_json TEXT NOT NULL DEFAULT '[]'," +
                    "reasons_json TEXT NOT NULL DEFAULT '[]',estimated_concurrency INTEGER NOT NULL DEFAULT 1," +
                    "created_at TEXT NOT NULL,FOREIGN KEY(task_id) REFERENCES collaboration_tasks(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_collaboration_routes_project " +
                    "ON collaboration_route_decisions(project_key,created_at DESC)");
            statement.execute("CREATE TABLE IF NOT EXISTS collaboration_stage_barriers (" +
                    "parent_task_id TEXT NOT NULL,stage INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'WAITING'," +
                    "completed_at TEXT,created_at TEXT NOT NULL,PRIMARY KEY(parent_task_id,stage)," +
                    "FOREIGN KEY(parent_task_id) REFERENCES collaboration_tasks(id) ON DELETE CASCADE)");
            statement.execute("CREATE TABLE IF NOT EXISTS budget_policies (" +
                    "project_key TEXT PRIMARY KEY, daily_tokens INTEGER NOT NULL DEFAULT 0, monthly_tokens INTEGER NOT NULL DEFAULT 0, " +
                    "daily_cost REAL NOT NULL DEFAULT 0, monthly_cost REAL NOT NULL DEFAULT 0, warn_ratio REAL NOT NULL DEFAULT 0.8, " +
                    "max_concurrent_runs INTEGER NOT NULL DEFAULT 4, updated_at TEXT NOT NULL)");
            SqliteSchemaMigrator.ensureColumn(connection, "budget_policies", "max_concurrent_runs",
                    "INTEGER NOT NULL DEFAULT 4");
            statement.execute("CREATE TABLE IF NOT EXISTS budget_reservations (" +
                    "reservation_key TEXT PRIMARY KEY,project_key TEXT NOT NULL,reserved_tokens INTEGER NOT NULL," +
                    "reserved_cost REAL NOT NULL,created_at TEXT NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_budget_reservations_project " +
                    "ON budget_reservations(project_key,created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS scheduled_tasks (" +
                    "id TEXT PRIMARY KEY, project_key TEXT NOT NULL, name TEXT NOT NULL, template_id TEXT NOT NULL, " +
                    "schedule_type TEXT NOT NULL, schedule_value TEXT NOT NULL, variables_json TEXT NOT NULL DEFAULT '{}', " +
                    "model_profile_id TEXT, agent_profile_id TEXT, agent_team_id TEXT, " +
                    "enabled INTEGER NOT NULL DEFAULT 1, next_run_at TEXT, last_run_at TEXT, last_run_id TEXT, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(project_key,name))");
            SqliteSchemaMigrator.ensureColumn(connection, "scheduled_tasks", "model_profile_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "scheduled_tasks", "agent_profile_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "scheduled_tasks", "agent_team_id", "TEXT");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_scheduled_tasks_due ON scheduled_tasks(enabled,next_run_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS notification_channels (" +
                    "id TEXT PRIMARY KEY, project_key TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, endpoint TEXT NOT NULL DEFAULT '', " +
                    "secret_env TEXT NOT NULL DEFAULT '', events TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(project_key,name))");
            statement.execute("CREATE TABLE IF NOT EXISTS notification_outbox (" +
                    "id TEXT PRIMARY KEY,channel_id TEXT NOT NULL,project_key TEXT NOT NULL,event_type TEXT NOT NULL," +
                    "run_id TEXT NOT NULL,message TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0," +
                    "next_attempt_at TEXT NOT NULL,error TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL," +
                    "FOREIGN KEY(channel_id) REFERENCES notification_channels(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_notification_outbox_due " +
                    "ON notification_outbox(status,next_attempt_at)");
            statement.execute("UPDATE notification_outbox SET status='PENDING',next_attempt_at='"+
                    Instant.now()+"' WHERE status='SENDING'");
            statement.execute("CREATE TABLE IF NOT EXISTS evaluation_suites (" +
                    "id TEXT PRIMARY KEY, project_key TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', " +
                    "default_trials INTEGER NOT NULL DEFAULT 1, pass_threshold INTEGER NOT NULL DEFAULT 80, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(project_key,name))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_evaluation_suites_project " +
                    "ON evaluation_suites(project_key,updated_at)");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_suites", "dataset_version",
                    "TEXT NOT NULL DEFAULT 'custom-v1'");
            statement.execute("CREATE TABLE IF NOT EXISTS evaluation_cases (" +
                    "id TEXT PRIMARY KEY, suite_id TEXT NOT NULL, name TEXT NOT NULL, prompt TEXT NOT NULL, " +
                    "required_tools_json TEXT NOT NULL DEFAULT '[]', forbidden_tools_json TEXT NOT NULL DEFAULT '[]', " +
                    "required_response_json TEXT NOT NULL DEFAULT '[]', forbidden_response_json TEXT NOT NULL DEFAULT '[]', " +
                    "max_tool_calls INTEGER NOT NULL DEFAULT 0, max_tokens INTEGER NOT NULL DEFAULT 0, " +
                    "max_duration_ms INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(suite_id,name), " +
                    "FOREIGN KEY(suite_id) REFERENCES evaluation_suites(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_evaluation_cases_suite " +
                    "ON evaluation_cases(suite_id,enabled,name)");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_cases", "case_type",
                    "TEXT NOT NULL DEFAULT 'RULE'");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_cases", "fixture_ref", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_cases", "fixture_sha256", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_cases", "grader_spec_json",
                    "TEXT NOT NULL DEFAULT '{}'");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_cases", "patch_policy_json",
                    "TEXT NOT NULL DEFAULT '{}'");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_cases", "assertion_spec_json",
                    "TEXT NOT NULL DEFAULT '{}'");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_cases", "fixture_spec_json",
                    "TEXT NOT NULL DEFAULT '{}'");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_cases", "judge_spec_json",
                    "TEXT NOT NULL DEFAULT '{}'");
            statement.execute("CREATE TABLE IF NOT EXISTS evaluation_executions (" +
                    "id TEXT PRIMARY KEY, suite_id TEXT NOT NULL, project_key TEXT NOT NULL, status TEXT NOT NULL, " +
                    "model_profile_id TEXT, trial_count INTEGER NOT NULL, pass_threshold INTEGER NOT NULL, " +
                    "average_score REAL, passed INTEGER, created_at TEXT NOT NULL, completed_at TEXT, " +
                    "FOREIGN KEY(suite_id) REFERENCES evaluation_suites(id))");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_executions", "agent_team_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_executions", "fingerprint_json",
                    "TEXT NOT NULL DEFAULT '{}'");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_executions", "gate_status",
                    "TEXT NOT NULL DEFAULT 'PENDING'");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_executions", "gate_details_json",
                    "TEXT NOT NULL DEFAULT '{}'");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_evaluation_executions_suite " +
                    "ON evaluation_executions(suite_id,created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS evaluation_trials (" +
                    "id TEXT PRIMARY KEY, execution_id TEXT NOT NULL, case_id TEXT NOT NULL, ordinal INTEGER NOT NULL, " +
                    "session_id TEXT NOT NULL, run_id TEXT NOT NULL UNIQUE, status TEXT NOT NULL, score INTEGER, " +
                    "passed INTEGER, details_json TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL, completed_at TEXT, " +
                    "UNIQUE(execution_id,case_id,ordinal), " +
                    "FOREIGN KEY(execution_id) REFERENCES evaluation_executions(id) ON DELETE CASCADE, " +
                    "FOREIGN KEY(case_id) REFERENCES evaluation_cases(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_evaluation_trials_execution " +
                    "ON evaluation_trials(execution_id,case_id,ordinal)");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_trials", "case_snapshot_json",
                    "TEXT NOT NULL DEFAULT '{}'");
            statement.execute("CREATE TABLE IF NOT EXISTS evaluation_baselines (" +
                    "case_id TEXT PRIMARY KEY, source_run_id TEXT NOT NULL, response TEXT NOT NULL, " +
                    "tool_names_json TEXT NOT NULL, tokens INTEGER NOT NULL, " +
                    "token_metric TEXT NOT NULL DEFAULT 'TOTAL', duration_ms INTEGER NOT NULL, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, " +
                    "FOREIGN KEY(case_id) REFERENCES evaluation_cases(id) ON DELETE CASCADE)");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_baselines", "token_metric",
                    "TEXT NOT NULL DEFAULT 'TOTAL'");
            SqliteSchemaMigrator.ensureColumn(connection, "evaluation_baselines", "details_json",
                    "TEXT NOT NULL DEFAULT '{}'");
            statement.execute("CREATE TABLE IF NOT EXISTS plans (" +
                    "id TEXT PRIMARY KEY, session_id TEXT, run_id TEXT, project_key TEXT NOT NULL, " +
                    "objective TEXT NOT NULL, summary TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, " +
                    "version INTEGER NOT NULL DEFAULT 1, source TEXT NOT NULL DEFAULT 'MANUAL', " +
                    "raw_plan_json TEXT NOT NULL DEFAULT '{}', validation_errors_json TEXT NOT NULL DEFAULT '[]', " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, started_at TEXT, completed_at TEXT, " +
                    "failure_reason TEXT, FOREIGN KEY(session_id) REFERENCES sessions(id), " +
                    "FOREIGN KEY(run_id) REFERENCES runs(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_plans_project_updated " +
                    "ON plans(project_key, updated_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_plans_session_created " +
                    "ON plans(session_id, created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS plan_steps (" +
                    "id TEXT PRIMARY KEY, plan_id TEXT NOT NULL, client_id TEXT NOT NULL, ordinal INTEGER NOT NULL, " +
                    "title TEXT NOT NULL, description TEXT NOT NULL, type TEXT NOT NULL, status TEXT NOT NULL, " +
                    "execution_mode TEXT NOT NULL DEFAULT 'REACT', done_criteria_json TEXT NOT NULL DEFAULT '[]', " +
                    "run_id TEXT, result_summary TEXT, failure_reason TEXT, claim_owner TEXT, " +
                    "lease_expires_at TEXT, heartbeat_at TEXT, attempt INTEGER NOT NULL DEFAULT 0, " +
                    "not_before TEXT, last_failure_class TEXT, dispatch_idempotency_key TEXT, " +
                    "resource_read_set_json TEXT NOT NULL DEFAULT '[]', " +
                    "resource_write_set_json TEXT NOT NULL DEFAULT '[]', " +
                    "isolation_strategy TEXT NOT NULL DEFAULT 'SHARED_SESSION', " +
                    "max_parallelism INTEGER NOT NULL DEFAULT 1, critical_path_weight INTEGER NOT NULL DEFAULT 0, " +
                    "workspace_ref TEXT, " +
                    "started_at TEXT, completed_at TEXT, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(plan_id, client_id), " +
                    "FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE, " +
                    "FOREIGN KEY(run_id) REFERENCES runs(id))");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "run_id", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "claim_owner", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "lease_expires_at", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "heartbeat_at", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "attempt",
                    "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "not_before", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "last_failure_class", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "dispatch_idempotency_key", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "resource_read_set_json",
                    "TEXT NOT NULL DEFAULT '[]'");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "resource_write_set_json",
                    "TEXT NOT NULL DEFAULT '[]'");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "isolation_strategy",
                    "TEXT NOT NULL DEFAULT 'SHARED_SESSION'");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "max_parallelism",
                    "INTEGER NOT NULL DEFAULT 1");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "critical_path_weight",
                    "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_steps", "workspace_ref", "TEXT");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_plan_steps_plan_status " +
                    "ON plan_steps(plan_id, status, ordinal)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_plan_steps_run " +
                    "ON plan_steps(run_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_plan_steps_lease " +
                    "ON plan_steps(status, lease_expires_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_plan_steps_workspace " +
                    "ON plan_steps(run_id, workspace_ref)");
            statement.execute("CREATE TABLE IF NOT EXISTS plan_edges (" +
                    "plan_id TEXT NOT NULL, from_step_id TEXT NOT NULL, to_step_id TEXT NOT NULL, " +
                    "edge_type TEXT NOT NULL DEFAULT 'DEPENDENCY', " +
                    "condition_expression TEXT NOT NULL DEFAULT 'ON_SUCCESS', " +
                    "priority INTEGER NOT NULL DEFAULT 0, max_traversals INTEGER NOT NULL DEFAULT 0, " +
                    "traversal_count INTEGER NOT NULL DEFAULT 0, " +
                    "created_at TEXT NOT NULL, PRIMARY KEY(plan_id, from_step_id, to_step_id), " +
                    "FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE, " +
                    "FOREIGN KEY(from_step_id) REFERENCES plan_steps(id) ON DELETE CASCADE, " +
                    "FOREIGN KEY(to_step_id) REFERENCES plan_steps(id) ON DELETE CASCADE)");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_edges", "edge_type",
                    "TEXT NOT NULL DEFAULT 'DEPENDENCY'");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_edges", "condition_expression",
                    "TEXT NOT NULL DEFAULT 'ON_SUCCESS'");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_edges", "priority",
                    "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_edges", "max_traversals",
                    "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "plan_edges", "traversal_count",
                    "INTEGER NOT NULL DEFAULT 0");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_plan_edges_to_step " +
                    "ON plan_edges(plan_id, to_step_id)");
            statement.execute("CREATE TABLE IF NOT EXISTS plan_revisions (" +
                    "id TEXT PRIMARY KEY, plan_id TEXT NOT NULL, version INTEGER NOT NULL, reason TEXT NOT NULL, " +
                    "raw_plan_json TEXT NOT NULL, created_at TEXT NOT NULL, " +
                    "FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_plan_revisions_version " +
                    "ON plan_revisions(plan_id, version)");
            statement.execute("CREATE TABLE IF NOT EXISTS plan_events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, plan_id TEXT NOT NULL, step_id TEXT, " +
                    "event_type TEXT NOT NULL, event_data TEXT NOT NULL, sequence INTEGER NOT NULL, " +
                    "created_at TEXT NOT NULL, UNIQUE(plan_id, sequence), " +
                    "FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE, " +
                    "FOREIGN KEY(step_id) REFERENCES plan_steps(id) ON DELETE SET NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_plan_events_plan " +
                    "ON plan_events(plan_id, id)");
            statement.execute("CREATE TABLE IF NOT EXISTS async_jobs (" +
                    "id TEXT PRIMARY KEY, plan_id TEXT, step_id TEXT, run_id TEXT, project_key TEXT NOT NULL, " +
                    "kind TEXT NOT NULL, status TEXT NOT NULL, idempotency_key TEXT NOT NULL UNIQUE, " +
                    "payload_json TEXT NOT NULL DEFAULT '{}', result_json TEXT NOT NULL DEFAULT '{}', " +
                    "log TEXT NOT NULL DEFAULT '', error TEXT, attempts INTEGER NOT NULL DEFAULT 0, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, started_at TEXT, completed_at TEXT, " +
                    "FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE, " +
                    "FOREIGN KEY(step_id) REFERENCES plan_steps(id) ON DELETE SET NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runs(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_async_jobs_status_updated " +
                    "ON async_jobs(status, updated_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_async_jobs_plan " +
                    "ON async_jobs(plan_id, step_id)");
            statement.execute("CREATE TABLE IF NOT EXISTS validation_checks (" +
                    "id TEXT PRIMARY KEY, plan_id TEXT NOT NULL, step_id TEXT, name TEXT NOT NULL, " +
                    "kind TEXT NOT NULL, status TEXT NOT NULL, expected TEXT NOT NULL DEFAULT '', " +
                    "actual TEXT NOT NULL DEFAULT '', evidence TEXT NOT NULL DEFAULT '', error TEXT, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, completed_at TEXT, " +
                    "FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE, " +
                    "FOREIGN KEY(step_id) REFERENCES plan_steps(id) ON DELETE SET NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_validation_checks_plan " +
                    "ON validation_checks(plan_id, step_id, status)");
            statement.execute("CREATE TABLE IF NOT EXISTS agent_feedback (" +
                    "id TEXT PRIMARY KEY, project_key TEXT NOT NULL, agent_profile_id TEXT, plan_id TEXT, " +
                    "step_id TEXT, run_id TEXT NOT NULL, status TEXT NOT NULL, validation_status TEXT NOT NULL, " +
                    "score REAL NOT NULL, failure_class TEXT NOT NULL DEFAULT '', " +
                    "evidence_quality REAL NOT NULL DEFAULT 0, created_at TEXT NOT NULL, " +
                    "UNIQUE(run_id, step_id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_agent_feedback_agent " +
                    "ON agent_feedback(project_key,agent_profile_id,created_at)");
            statement.execute("UPDATE tool_calls SET status='REQUESTED', retry_count=retry_count+1 " +
                    "WHERE status='RUNNING' AND effect IN ('READ_ONLY','IDEMPOTENT_WRITE')");
            statement.execute("UPDATE tool_calls SET status='UNKNOWN', " +
                    "error='Tool outcome is unknown after runtime interruption; reconcile before retry', " +
                    "finished_at='" + Instant.now() + "' WHERE status='RUNNING'");
            statement.execute("UPDATE runs SET status='FAILED', " +
                    "error='A non-idempotent tool outcome is unknown; manual reconciliation is required', " +
                    "finished_at='" + Instant.now() + "',version=version+1 WHERE id IN " +
                    "(SELECT run_id FROM tool_calls WHERE status='UNKNOWN') AND status NOT IN " +
                    "('COMPLETED','FAILED','CANCELED')");
            reconcileBudgetStoppedCompletions(connection);
            statement.execute("UPDATE approvals SET status='DENIED',resolved_at='" + Instant.now()
                    + "' WHERE status='PENDING' AND run_id IN "
                    + "(SELECT id FROM runs WHERE status IN ('COMPLETED','FAILED','CANCELED'))");
            statement.execute("INSERT OR IGNORE INTO collaboration_task_runs(task_id,run_id,trigger_id,relationship,created_at) "
                    + "SELECT (SELECT existing_link.task_id FROM collaboration_task_runs existing_link "
                    + "JOIN runs existing_run ON existing_run.id=existing_link.run_id "
                    + "WHERE existing_run.session_id=continuation.session_id "
                    + "ORDER BY existing_run.created_at DESC,existing_link.created_at DESC,existing_link.task_id LIMIT 1),"
                    + "continuation.id,NULL,'SESSION_CONTINUATION',continuation.created_at FROM runs continuation "
                    + "WHERE NOT EXISTS (SELECT 1 FROM collaboration_task_runs current_link "
                    + "WHERE current_link.run_id=continuation.id) AND EXISTS (SELECT 1 "
                    + "FROM collaboration_task_runs existing_link JOIN runs existing_run "
                    + "ON existing_run.id=existing_link.run_id WHERE existing_run.session_id=continuation.session_id)");
            statement.execute("WITH RECURSIVE task_tree(root_id,task_id) AS ("
                    + "SELECT id,id FROM collaboration_tasks WHERE parent_id IS NULL OR parent_id='' "
                    + "UNION ALL SELECT task_tree.root_id,child.id FROM collaboration_tasks child "
                    + "JOIN task_tree ON child.parent_id=task_tree.task_id) "
                    + "UPDATE collaboration_tasks SET status='IN_PROGRESS',updated_at='" + Instant.now()
                    + "' WHERE status='IN_REVIEW' AND id IN (SELECT DISTINCT root_id FROM task_tree "
                    + "JOIN collaboration_task_runs link ON link.task_id=task_tree.task_id "
                    + "JOIN runs ON runs.id=link.run_id WHERE runs.status NOT IN ('COMPLETED','FAILED','CANCELED'))");
            statement.execute("UPDATE memory_extractions SET status='PENDING', updated_at='" +
                    Instant.now() + "' WHERE status='RUNNING'");
        }
        reconcileCollaborationTaskWorkspaces();
        try (Connection connection = open()) {
            backfillMemoryScopes(connection);
            SqliteSchemaMigrator.recordAppliedVersions(connection);
        }
        recoverInterruptedRuns();
    }

    /**
     * Migration 43 derives retrieval scope for historical automatic memories from their immutable source Run.
     * The update is intentionally idempotent; manually created memories remain project scoped.
     */
    private void backfillMemoryScopes(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE memories SET scope_agent_profile_id=(SELECT r.agent_profile_id " +
                    "FROM runs r WHERE r.id=memories.source_run_id) WHERE origin='automatic' " +
                    "AND source_run_id IS NOT NULL AND COALESCE(structured_payload,'{}')='{}'");
            statement.executeUpdate("UPDATE memories SET scope_workspace_owner_run_id=COALESCE(" +
                    "(SELECT NULLIF(ps.workspace_ref,'') FROM plan_steps ps " +
                    "WHERE ps.run_id=memories.source_run_id ORDER BY ps.updated_at DESC LIMIT 1)," +
                    "(SELECT COALESCE(NULLIF(r.workspace_owner_run_id,''),r.id) FROM runs r " +
                    "WHERE r.id=memories.source_run_id)) WHERE origin='automatic' " +
                    "AND source_run_id IS NOT NULL AND COALESCE(structured_payload,'{}')='{}'");
            statement.executeUpdate("UPDATE memories SET scope_task_type=CASE " +
                    "WHEN EXISTS(SELECT 1 FROM collaboration_task_runs ctr " +
                    "WHERE ctr.run_id=memories.source_run_id) THEN 'COLLABORATION' " +
                    "WHEN EXISTS(SELECT 1 FROM plan_steps ps WHERE ps.run_id=memories.source_run_id) THEN 'PLAN' " +
                    "WHEN EXISTS(SELECT 1 FROM run_delegations rd " +
                    "WHERE rd.child_run_id=memories.source_run_id) THEN 'DELEGATION' " +
                    "WHEN scope_agent_profile_id IS NOT NULL THEN 'AGENT' ELSE 'CHAT' END " +
                    "WHERE origin='automatic' AND source_run_id IS NOT NULL " +
                    "AND COALESCE(structured_payload,'{}')='{}'");
            statement.executeUpdate("UPDATE memories SET scope_type=CASE " +
                    "WHEN layer='L1' OR memory_type='EPISODIC' THEN 'WORKSPACE' " +
                    "WHEN memory_type IN ('PROCEDURAL','LESSON') AND scope_agent_profile_id IS NOT NULL THEN 'AGENT' " +
                    "WHEN memory_type IN ('PROCEDURAL','LESSON') AND scope_task_type<>'CHAT' THEN 'TASK_TYPE' " +
                    "ELSE 'PROJECT' END WHERE origin='automatic' " +
                    "AND COALESCE(structured_payload,'{}')='{}'");
        }
    }

    private void reconcileBudgetStoppedCompletions(Connection connection) throws SQLException {
        String error = "run execution budget exceeded before completion (corrected historical status)";
        String now = Instant.now().toString();
        try (PreparedStatement event = connection.prepareStatement(
                "INSERT INTO run_events(run_id,event_type,event_data,sequence,created_at) "
                        + "SELECT r.id,'run.failed',?,COALESCE((SELECT MAX(e.sequence) FROM run_events e "
                        + "WHERE e.run_id=r.id),0)+1,? FROM runs r WHERE r.status='COMPLETED' "
                        + "AND EXISTS (SELECT 1 FROM run_events stopped WHERE stopped.run_id=r.id "
                        + "AND stopped.event_type='run.budget_stopped') "
                        + "AND NOT EXISTS (SELECT 1 FROM run_events failed WHERE failed.run_id=r.id "
                        + "AND failed.event_type='run.failed' AND failed.event_data LIKE '%corrected historical status%')")) {
            event.setString(1, "{\"status\":\"FAILED\",\"error\":\"" + escape(error) + "\"}");
            event.setString(2, now);
            event.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE runs SET status='FAILED',error=?,finished_at=COALESCE(finished_at,?),version=version+1 "
                        + "WHERE status='COMPLETED' AND EXISTS (SELECT 1 FROM run_events stopped "
                        + "WHERE stopped.run_id=runs.id AND stopped.event_type='run.budget_stopped')")) {
            update.setString(1, error);
            update.setString(2, now);
            update.executeUpdate();
        }
        try (PreparedStatement activity = connection.prepareStatement(
                "INSERT INTO collaboration_activities(task_id,activity_type,actor_type,actor_id,subject_id,"
                        + "payload_json,created_at) SELECT link.task_id,'RUN_FAILED','SYSTEM',NULL,r.id,?,? "
                        + "FROM runs r JOIN collaboration_task_runs link ON link.run_id=r.id "
                        + "WHERE r.status='FAILED' AND r.error=? AND NOT EXISTS "
                        + "(SELECT 1 FROM collaboration_activities existing WHERE existing.task_id=link.task_id "
                        + "AND existing.activity_type='RUN_FAILED' AND existing.subject_id=r.id "
                        + "AND existing.payload_json LIKE '%corrected historical status%')")) {
            activity.setString(1, "{\"status\":\"FAILED\",\"error\":\"" + escape(error) + "\"}");
            activity.setString(2, now);
            activity.setString(3, error);
            activity.executeUpdate();
        }
    }

    public SessionRecord createSession(String title) {
        return createSession(title, "default");
    }

    public SessionRecord createSession(String title, String projectKey) {
        return createSession(title, projectKey, null);
    }

    public SessionRecord createSession(String title, String projectKey, String groupId) {
        return createSession(title, projectKey, groupId, false);
    }

    private void reconcileDuplicateActiveRuns(Connection connection) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE runs SET status='FAILED', " +
                        "error=COALESCE(error,'duplicate active run reconciled during schema upgrade'), " +
                        "finished_at=COALESCE(finished_at,?), version=version+1 " +
                        "WHERE status NOT IN ('COMPLETED','FAILED','CANCELED') AND EXISTS (" +
                        "SELECT 1 FROM runs earlier WHERE earlier.session_id=runs.session_id " +
                        "AND earlier.status NOT IN ('COMPLETED','FAILED','CANCELED') AND (" +
                        "earlier.created_at < runs.created_at OR " +
                        "(earlier.created_at = runs.created_at AND earlier.id < runs.id)))")) {
            statement.setString(1, now);
            statement.executeUpdate();
        }
    }

    public SessionRecord createInternalSession(String title, String projectKey) {
        return createSession(title, projectKey, null, true);
    }

    private SessionRecord createSession(String title, String projectKey, String groupId, boolean internal) {
        String id = id("session");
        Instant now = Instant.now();
        String resolvedTitle = title == null || title.isBlank() ? "New session" : title.trim();
        String resolvedProject = normalizeProjectKey(projectKey);
        String resolvedGroup = normalizeGroupId(groupId);
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO sessions(id,title,project_key,group_id,status,is_internal,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, resolvedTitle);
            ps.setString(3, resolvedProject);
            ps.setString(4, resolvedGroup);
            ps.setString(5, "ACTIVE");
            ps.setInt(6, internal ? 1 : 0);
            ps.setString(7, now.toString());
            ps.setString(8, now.toString());
            ps.executeUpdate();
            return new SessionRecord(id, resolvedTitle, resolvedProject, resolvedGroup, "ACTIVE", now, now);
        } catch (SQLException e) {
            throw failure("create session", e);
        }
    }

    public SessionGroupRecord createSessionGroup(String name) {
        String normalized = normalizeGroupName(name);
        String id = id("group");
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO session_groups(id,name,created_at,updated_at) VALUES(?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, normalized);
            ps.setString(3, now.toString());
            ps.setString(4, now.toString());
            ps.executeUpdate();
            return new SessionGroupRecord(id, normalized, now, now);
        } catch (SQLException e) {
            throw failure("create session group", e);
        }
    }

    public List<SessionGroupRecord> sessionGroups() {
        List<SessionGroupRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM session_groups ORDER BY name COLLATE NOCASE")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapSessionGroup(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list session groups", e);
        }
    }

    public Optional<SessionGroupRecord> renameSessionGroup(String groupId, String name) {
        String normalized = normalizeGroupName(name);
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE session_groups SET name=?, updated_at=? WHERE id=?")) {
            ps.setString(1, normalized);
            ps.setString(2, now.toString());
            ps.setString(3, groupId);
            if (ps.executeUpdate() == 0) return Optional.empty();
            return findSessionGroup(groupId);
        } catch (SQLException e) {
            throw failure("rename session group", e);
        }
    }

    public boolean deleteSessionGroup(String groupId) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement move = connection.prepareStatement(
                        "UPDATE sessions SET group_id=NULL, updated_at=? WHERE group_id=?")) {
                    move.setString(1, Instant.now().toString());
                    move.setString(2, groupId);
                    move.executeUpdate();
                }
                int deleted;
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM session_groups WHERE id=?")) {
                    ps.setString(1, groupId);
                    deleted = ps.executeUpdate();
                }
                connection.commit();
                return deleted > 0;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("delete session group", e);
        }
    }

    public SessionRecord moveSession(String sessionId, String groupId) {
        String resolvedGroup = normalizeGroupId(groupId);
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET group_id=?, updated_at=? WHERE id=?")) {
            ps.setString(1, resolvedGroup);
            ps.setString(2, now.toString());
            ps.setString(3, sessionId);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("session not found: " + sessionId);
            return findSession(sessionId).orElseThrow();
        } catch (SQLException e) {
            throw failure("move session", e);
        }
    }

    public SessionRecord renameSessionIfGeneric(String sessionId, String task) {
        SessionRecord current = findSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
        if (!TaskTitle.isGenericSessionTitle(current.title())) return current;
        String title = TaskTitle.summarize(task, current.title());
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET title=?, updated_at=? WHERE id=? AND title=?")) {
            ps.setString(1, title);
            ps.setString(2, now.toString());
            ps.setString(3, sessionId);
            ps.setString(4, current.title());
            ps.executeUpdate();
            return findSession(sessionId).orElseThrow();
        } catch (SQLException e) {
            throw failure("rename session", e);
        }
    }

    public boolean deleteSession(String sessionId) {
        List<String> runIds = new ArrayList<>();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (!sessionExists(connection, sessionId)) {
                    connection.rollback();
                    return false;
                }
                if (isInternalSession(connection, sessionId)) {
                    throw new IllegalStateException("Delegated sessions can only be deleted with their parent session");
                }
                List<String> sessionIds = collectDelegatedSessions(connection, sessionId);
                for (String currentSession : sessionIds) {
                    rejectActiveRuns(connection, currentSession);
                    runIds.addAll(runIds(connection, currentSession));
                }
                for (String currentSession : sessionIds) {
                    deleteBySessionRuns(connection, "run_delegations", currentSession, "parent_run_id");
                    deleteBySessionRuns(connection, "run_delegations", currentSession, "child_run_id");
                }
                for (String currentSession : sessionIds) {
                    try (PreparedStatement policies = connection.prepareStatement(
                            "DELETE FROM approval_policies WHERE scope='SESSION' AND session_id=?")) {
                        policies.setString(1, currentSession);
                        policies.executeUpdate();
                    }
                    deleteBySessionRuns(connection, "model_usage", currentSession);
                    deleteBySessionRuns(connection, "model_attempts", currentSession);
                    deleteBySessionRuns(connection, "memory_usage_feedback", currentSession);
                    deleteBySessionRuns(connection, "memory_extractions", currentSession);
                    deleteBySessionRuns(connection, "run_collaboration_policies", currentSession);
                    deleteBySessionRuns(connection, "async_jobs", currentSession);
                    deleteBySessionRuns(connection, "approvals", currentSession);
                    deleteBySessionRuns(connection, "tool_calls", currentSession);
                    deleteBySessionRuns(connection, "run_events", currentSession);
                    deleteBySessionRuns(connection, "artifacts", currentSession);
                    deleteBySessionRuns(connection, "agent_feedback", currentSession);
                    deletePlansForSession(connection, currentSession);
                    try (PreparedStatement attachments = connection.prepareStatement(
                            "DELETE FROM input_attachments WHERE session_id=?")) {
                        attachments.setString(1, currentSession);
                        attachments.executeUpdate();
                    }
                    try (PreparedStatement messages = connection.prepareStatement(
                            "DELETE FROM messages WHERE session_id=?")) {
                        messages.setString(1, currentSession);
                        messages.executeUpdate();
                    }
                    try (PreparedStatement runs = connection.prepareStatement(
                            "DELETE FROM runs WHERE session_id=?")) {
                        runs.setString(1, currentSession);
                        runs.executeUpdate();
                    }
                }
                int deleted = 0;
                for (int index = sessionIds.size() - 1; index >= 0; index--) {
                    try (PreparedStatement session = connection.prepareStatement(
                            "DELETE FROM sessions WHERE id=?")) {
                        session.setString(1, sessionIds.get(index));
                        int count = session.executeUpdate();
                        if (sessionIds.get(index).equals(sessionId)) deleted = count;
                    }
                }
                connection.commit();
                if (deleted > 0) cleanupRunFiles(runIds);
                if (deleted > 0) {
                    for (String currentSession : sessionIds) {
                        deleteTree(attachmentRoot, attachmentRoot.resolve(currentSession).normalize());
                    }
                }
                return deleted > 0;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("delete session", e);
        }
    }

    public Optional<SessionRecord> findSession(String id) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sessions WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapSession(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find session", e);
        }
    }

    public List<SessionRecord> sessions() {
        List<SessionRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sessions WHERE is_internal=0 ORDER BY updated_at DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapSession(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list sessions", e);
        }
    }

    public RunRecord createRun(String sessionId, String input) {
        return createRun(sessionId, input, "auto", "", List.of());
    }

    public RunRecord createRun(String sessionId, String input,
                               String thinkingMode, String reasoningEffort) {
        return createRun(sessionId, input, thinkingMode, reasoningEffort, List.of());
    }

    public RunRecord createRun(String sessionId, String input,
                               String thinkingMode, String reasoningEffort,
                               List<String> attachmentIds) {
        return createRun(sessionId, input, thinkingMode, reasoningEffort, attachmentIds, null, 0, 0);
    }

    public RunRecord createRun(String sessionId, String input,
                               String thinkingMode, String reasoningEffort,
                               List<String> attachmentIds, String modelProfileId,
                               int priority, int retryCount) {
        return createRun(sessionId, input, thinkingMode, reasoningEffort, attachmentIds,
                modelProfileId, null, priority, retryCount);
    }

    public RunRecord createRun(String sessionId, String input,
                               String thinkingMode, String reasoningEffort,
                               List<String> attachmentIds, String modelProfileId,
                               String agentProfileId, int priority, int retryCount) {
        return createRun(sessionId, input, thinkingMode, reasoningEffort, attachmentIds,
                modelProfileId, agentProfileId, priority, retryCount, "bash");
    }

    public RunRecord createRun(String sessionId, String input,
                               String thinkingMode, String reasoningEffort,
                               List<String> attachmentIds, String modelProfileId,
                               String agentProfileId, int priority, int retryCount,
                               String executionShell) {
        return createRunInternal(sessionId, input, thinkingMode, reasoningEffort, attachmentIds,
                modelProfileId, agentProfileId, priority, retryCount, executionShell, null);
    }

    public RunRecord createRunInWorkspace(String sessionId, String input,
                                          String thinkingMode, String reasoningEffort,
                                          List<String> attachmentIds, String modelProfileId,
                                          String agentProfileId, int priority, int retryCount,
                                          String executionShell, String workspaceOwner) {
        if (workspaceOwner == null || !SAFE_WORKSPACE_KEY.matcher(workspaceOwner).matches()) {
            throw new IllegalArgumentException("workspace owner must be a safe workspace key");
        }
        return createRunInternal(sessionId, input, thinkingMode, reasoningEffort, attachmentIds,
                modelProfileId, agentProfileId, priority, retryCount, executionShell, workspaceOwner);
    }

    private RunRecord createRunInternal(String sessionId, String input,
                                        String thinkingMode, String reasoningEffort,
                                        List<String> attachmentIds, String modelProfileId,
                                        String agentProfileId, int priority, int retryCount,
                                        String executionShell, String explicitWorkspaceOwner) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        if (findSession(sessionId).isEmpty()) {
            throw new IllegalArgumentException("session not found: " + sessionId);
        }
        if (hasActiveRun(sessionId)) {
            throw new IllegalStateException("session already has an active run");
        }
        String runId = id("run");
        Instant now = Instant.now();
        String resolvedThinking = normalizeThinkingMode(thinkingMode);
        String resolvedEffort = normalizeReasoningEffort(reasoningEffort);
        String resolvedShell = normalizeExecutionShell(executionShell);
        String workspaceOwnerRunId = explicitWorkspaceOwner == null
                ? latestWorkspaceOwner(sessionId) : explicitWorkspaceOwner;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO runs(id,session_id,status,input,current_step,thinking_mode," +
                                "reasoning_effort,execution_shell,priority,model_profile_id,agent_profile_id,retry_count," +
                                "workspace_owner_run_id,created_at,queued_at,version) VALUES(?,?,?,?,0,?,?,?,?,?,?,?,?,?,?,0)")) {
                    ps.setString(1, runId);
                    ps.setString(2, sessionId);
                    ps.setString(3, RunStatus.QUEUED.name());
                    ps.setString(4, input.trim());
                    ps.setString(5, resolvedThinking);
                    ps.setString(6, resolvedEffort);
                    ps.setString(7, resolvedShell);
                    ps.setInt(8, Math.max(-10, Math.min(priority, 10)));
                    ps.setString(9, modelProfileId == null || modelProfileId.isBlank() ? null : modelProfileId);
                    ps.setString(10, agentProfileId == null || agentProfileId.isBlank() ? null : agentProfileId);
                    ps.setInt(11, Math.max(0, retryCount));
                    ps.setString(12, workspaceOwnerRunId);
                    ps.setString(13, now.toString());
                    ps.setString(14, now.toString());
                    ps.executeUpdate();
                }
                MessageRecord userMessage = insertMessage(connection, sessionId, runId, "user", input.trim(),
                        null, null, null, false);
                attachInputs(connection, sessionId, runId, userMessage.id(), attachmentIds);
                insertEvent(connection, runId, "run.queued", "{\"runId\":\"" + runId + "\"}");
                touchSession(connection, sessionId, now);
                connection.commit();
                return new RunRecord(runId, sessionId, RunStatus.QUEUED, input.trim(), 0,
                        null, resolvedThinking, resolvedEffort, resolvedShell,
                        Math.max(-10, Math.min(priority, 10)),
                        modelProfileId, agentProfileId, Math.max(0, retryCount), now, null, null, 0);
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("create run", e);
        }
    }

    public InputAttachmentRecord createInputAttachment(String sessionId, String name, String mimeType,
                                                       String relativePath, long size, String sha256) {
        if (findSession(sessionId).isEmpty()) throw new IllegalArgumentException("session not found: " + sessionId);
        String attachmentId = id("attachment");
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO input_attachments(id,session_id,name,mime_type,relative_path,size,sha256,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, attachmentId);
            ps.setString(2, sessionId);
            ps.setString(3, requireText(name, "name", 200));
            ps.setString(4, requireText(mimeType, "mimeType", 100));
            ps.setString(5, requireText(relativePath, "relativePath", 500));
            ps.setLong(6, size);
            ps.setString(7, requireText(sha256, "sha256", 128));
            ps.setString(8, now.toString());
            ps.executeUpdate();
            return new InputAttachmentRecord(attachmentId, sessionId, null, null, name, mimeType,
                    relativePath, size, sha256, now);
        } catch (SQLException e) {
            throw failure("create input attachment", e);
        }
    }

    public List<InputAttachmentRecord> attachmentsForRun(String runId) {
        List<InputAttachmentRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM input_attachments WHERE run_id=? ORDER BY created_at")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapInputAttachment(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list input attachments", e);
        }
    }

    public Optional<InputAttachmentRecord> findStagedAttachment(String sessionId, String attachmentId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM input_attachments WHERE id=? AND session_id=? AND run_id IS NULL")) {
            ps.setString(1, attachmentId);
            ps.setString(2, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapInputAttachment(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find staged attachment", e);
        }
    }

    public boolean deleteStagedAttachment(String sessionId, String attachmentId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM input_attachments WHERE id=? AND session_id=? AND run_id IS NULL")) {
            ps.setString(1, attachmentId);
            ps.setString(2, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw failure("delete staged attachment", e);
        }
    }

    public Optional<RunRecord> claimNextRun() {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                List<RunRecord> candidates = new ArrayList<>();
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT r.* FROM runs r JOIN sessions s ON s.id=r.session_id WHERE r.status=? " +
                                "AND NOT EXISTS (SELECT 1 FROM run_delegations gate " +
                                "WHERE gate.child_run_id=r.id AND gate.status IN ('BLOCKED','WAITING_HUMAN','CANCELED')) " +
                                "AND NOT EXISTS (SELECT 1 FROM run_delegations current_delegation " +
                                "JOIN run_delegation_resources current_resource " +
                                "ON current_resource.delegation_id=current_delegation.id " +
                                "JOIN run_delegation_resources active_resource " +
                                "ON active_resource.resource_key=current_resource.resource_key " +
                                "AND (active_resource.access_mode='WRITE' OR current_resource.access_mode='WRITE') " +
                                "JOIN run_delegations active_delegation " +
                                "ON active_delegation.id=active_resource.delegation_id " +
                                "JOIN runs active_run ON active_run.id=active_delegation.child_run_id " +
                                "WHERE current_delegation.child_run_id=r.id " +
                                "AND active_delegation.id<>current_delegation.id " +
                                "AND active_delegation.status='RUNNING' " +
                                "AND COALESCE(active_run.workspace_owner_run_id,active_run.id)=" +
                                "COALESCE(r.workspace_owner_run_id,r.id)) " +
                                "AND (SELECT COUNT(*) FROM runs active JOIN sessions owner ON owner.id=active.session_id " +
                                "WHERE owner.project_key=s.project_key AND active.status NOT IN ('QUEUED','COMPLETED','FAILED','CANCELED')) " +
                                "< COALESCE((SELECT max_concurrent_runs FROM budget_policies b " +
                                "WHERE b.project_key=s.project_key),4) " +
                                "ORDER BY r.priority DESC, queued_at, r.created_at LIMIT 64")) {
                    ps.setString(1, RunStatus.QUEUED.name());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) candidates.add(mapRun(rs));
                    }
                }
                RunRecord selected = null;
                for (RunRecord candidate : candidates) {
                    if (canClaimCollaborationRun(connection, candidate)) {
                        selected = candidate;
                        break;
                    }
                }
                if (selected == null) {
                    connection.commit();
                    return Optional.empty();
                }
                Instant now = Instant.now();
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE runs SET status=?, started_at=COALESCE(started_at,?), version=version+1 " +
                                "WHERE id=? AND status=?")) {
                    ps.setString(1, RunStatus.RUNNING.name());
                    ps.setString(2, now.toString());
                    ps.setString(3, selected.id());
                    ps.setString(4, RunStatus.QUEUED.name());
                    if (ps.executeUpdate() == 0) {
                        connection.rollback();
                        return Optional.empty();
                    }
                }
                insertEvent(connection, selected.id(), "run.started",
                        "{\"step\":" + selected.currentStep() + "}");
                try (PreparedStatement delegation = connection.prepareStatement(
                        "UPDATE run_delegations SET status='RUNNING',blocked_reason=NULL " +
                                "WHERE child_run_id=? AND status='QUEUED'")) {
                    delegation.setString(1, selected.id());
                    delegation.executeUpdate();
                }
                connection.commit();
                return findRun(selected.id());
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("claim run", e);
        }
    }

    public Optional<RunRecord> findRun(String id) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM runs WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRun(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find run", e);
        }
    }

    public List<RunRecord> runsForSession(String sessionId) {
        List<RunRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM runs WHERE session_id=? ORDER BY created_at DESC")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapRun(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list runs", e);
        }
    }

    public RunDelegationRecord createOrGetDelegation(String parentRunId, String parentToolCallId,
                                                        String agentName, String task) {
        return createOrGetDelegation(parentRunId, parentToolCallId, agentName, task, null, null);
    }

    public CollaborationPolicy saveCollaborationPolicy(String runId, boolean enabled, String complexity, String risk,
                                                       String allowedAgentProfileIdsJson, int maxExperts,
                                                       int maxDepth, int maxChildRuns, long maxEstimatedTokens,
                                                       double maxEstimatedCost, boolean allowExpertDelegation,
                                                       boolean requireReviewer, boolean requireRunner) {
        return saveCollaborationPolicy(runId, enabled, complexity, risk, allowedAgentProfileIdsJson,
                maxExperts, maxDepth, maxChildRuns, 0, maxEstimatedTokens, maxEstimatedCost,
                allowExpertDelegation, requireReviewer, requireRunner);
    }

    public CollaborationPolicy saveCollaborationPolicy(String runId, boolean enabled, String complexity, String risk,
                                                       String allowedAgentProfileIdsJson, int maxExperts,
                                                       int maxDepth, int maxChildRuns, int maxConcurrentAgentRuns,
                                                       long maxEstimatedTokens, double maxEstimatedCost,
                                                       boolean allowExpertDelegation, boolean requireReviewer,
                                                       boolean requireRunner) {
        findRun(runId).orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        Instant now = Instant.now();
        String resolvedComplexity = normalizeEnum(complexity, Set.of("SIMPLE", "MEDIUM", "COMPLEX"), "MEDIUM");
        String resolvedRisk = normalizeEnum(risk, Set.of("LOW", "MEDIUM", "HIGH"), "MEDIUM");
        int resolvedMaxExperts = Math.max(0, Math.min(maxExperts, 6));
        int resolvedMaxDepth = Math.max(0, Math.min(maxDepth, 3));
        int resolvedMaxChildRuns = Math.max(0, Math.min(maxChildRuns, 12));
        int resolvedMaxConcurrentAgentRuns = Math.max(0, Math.min(maxConcurrentAgentRuns, 6));
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO run_collaboration_policies(run_id,enabled,complexity,risk,allowed_agent_profile_ids_json," +
                        "max_experts,max_depth,max_child_runs,max_concurrent_agent_runs,max_estimated_tokens,max_estimated_cost," +
                        "allow_expert_delegation,require_reviewer,require_runner,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(run_id) DO UPDATE SET " +
                        "enabled=excluded.enabled,complexity=excluded.complexity,risk=excluded.risk," +
                        "allowed_agent_profile_ids_json=excluded.allowed_agent_profile_ids_json," +
                        "max_experts=excluded.max_experts,max_depth=excluded.max_depth," +
                        "max_child_runs=excluded.max_child_runs,max_concurrent_agent_runs=excluded.max_concurrent_agent_runs," +
                        "max_estimated_tokens=excluded.max_estimated_tokens," +
                        "max_estimated_cost=excluded.max_estimated_cost,allow_expert_delegation=excluded.allow_expert_delegation," +
                        "require_reviewer=excluded.require_reviewer,require_runner=excluded.require_runner")) {
            int i = 1;
            ps.setString(i++, runId);
            ps.setInt(i++, enabled ? 1 : 0);
            ps.setString(i++, resolvedComplexity);
            ps.setString(i++, resolvedRisk);
            ps.setString(i++, allowedAgentProfileIdsJson == null || allowedAgentProfileIdsJson.isBlank()
                    ? "[]" : allowedAgentProfileIdsJson);
            ps.setInt(i++, resolvedMaxExperts);
            ps.setInt(i++, resolvedMaxDepth);
            ps.setInt(i++, resolvedMaxChildRuns);
            ps.setInt(i++, resolvedMaxConcurrentAgentRuns);
            ps.setLong(i++, Math.max(0, maxEstimatedTokens));
            ps.setDouble(i++, Math.max(0, maxEstimatedCost));
            ps.setInt(i++, allowExpertDelegation ? 1 : 0);
            ps.setInt(i++, requireReviewer ? 1 : 0);
            ps.setInt(i++, requireRunner ? 1 : 0);
            ps.setString(i, now.toString());
            ps.executeUpdate();
            return collaborationPolicy(runId).orElseThrow();
        } catch (SQLException e) {
            throw failure("save collaboration policy", e);
        }
    }

    public Optional<CollaborationPolicy> collaborationPolicy(String runId) {
        try (Connection connection = open()) {
            return collaborationPolicy(connection, runId);
        } catch (SQLException e) {
            throw failure("find collaboration policy", e);
        }
    }

    public Optional<CollaborationPolicy> collaborationPolicyForTree(String runId) {
        try (Connection connection = open()) {
            return collaborationPolicyForTree(connection, runId);
        } catch (SQLException e) {
            throw failure("find collaboration policy tree", e);
        }
    }

    public int delegationDepth(String runId) {
        try (Connection connection = open()) {
            return delegationDepth(connection, runId);
        } catch (SQLException e) {
            throw failure("delegation depth", e);
        }
    }

    public int delegationCountForTree(String runId) {
        try (Connection connection = open()) {
            String root = rootRunId(connection, runId);
            try (PreparedStatement ps = connection.prepareStatement(
                    "WITH RECURSIVE tree(run_id) AS (" +
                            "SELECT ? UNION ALL SELECT d.child_run_id FROM run_delegations d " +
                            "JOIN tree t ON d.parent_run_id=t.run_id) " +
                            "SELECT COUNT(*) FROM run_delegations d JOIN tree t ON d.parent_run_id=t.run_id")) {
                ps.setString(1, root);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            throw failure("delegation tree count", e);
        }
    }

    public RunDelegationRecord createOrGetDelegation(String parentRunId, String parentToolCallId,
                                                        String agentName, String task, String agentProfileId,
                                                        String modelProfileId) {
        return createOrGetDelegation(parentRunId, parentToolCallId, agentName, task, agentProfileId, modelProfileId,
                null, null, "{}");
    }

    public RunDelegationRecord createOrGetDelegation(String parentRunId, String parentToolCallId,
                                                        String agentName, String task, String agentProfileId,
                                                        String modelProfileId, String planId, String planStepId,
                                                        String envelopeJson) {
        return createOrGetDelegation(parentRunId, parentToolCallId, agentName, task, agentProfileId,
                modelProfileId, null, null, planId, planStepId, envelopeJson);
    }

    public RunDelegationRecord createOrGetDelegation(String parentRunId, String parentToolCallId,
                                                        String agentName, String task, String agentProfileId,
                                                        String modelProfileId, String thinkingMode,
                                                        String reasoningEffort, String planId,
                                                        String planStepId, String envelopeJson) {
        return createOrGetDelegation(parentRunId, parentToolCallId, agentName, task, agentProfileId,
                modelProfileId, thinkingMode, reasoningEffort, planId, planStepId, envelopeJson,
                DelegationOptions.defaults());
    }

    public RunDelegationRecord createOrGetDelegation(String parentRunId, String parentToolCallId,
                                                        String agentName, String task, String agentProfileId,
                                                        String modelProfileId, String thinkingMode,
                                                        String reasoningEffort, String planId,
                                                        String planStepId, String envelopeJson,
                                                        DelegationOptions options) {
        return createOrGetDelegation(parentRunId, parentToolCallId, agentName, task, agentProfileId,
                modelProfileId, thinkingMode, reasoningEffort, null, planId, planStepId,
                envelopeJson, options);
    }

    public RunDelegationRecord createOrGetDelegation(String parentRunId, String parentToolCallId,
                                                        String agentName, String task, String agentProfileId,
                                                        String modelProfileId, String thinkingMode,
                                                        String reasoningEffort, String executionShell,
                                                        String planId, String planStepId, String envelopeJson,
                                                        DelegationOptions options) {
        String name = requireText(agentName, "agentName", 80);
        String input = requireText(task, "task", 32_000);
        String childAgentProfileId = nullableText(agentProfileId);
        String childModelProfileId = nullableText(modelProfileId);
        String parentWorkspaceOwner = workspaceOwnerRunId(parentRunId);
        String normalizedEnvelope = envelopeJson == null || envelopeJson.isBlank() ? "{}" : envelopeJson.trim();
        DelegationOptions graph = options == null ? DelegationOptions.defaults() : options.normalized();
        if (normalizedEnvelope.length() > 64_000) throw new IllegalArgumentException("delegation envelope is too large");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                Optional<RunDelegationRecord> existing = findDelegationByTool(connection, parentToolCallId);
                if (existing.isPresent()) {
                    connection.commit();
                    return existing.get();
                }
                RunRecord parent = findRun(connection, parentRunId)
                        .orElseThrow(() -> new IllegalArgumentException("parent run not found"));
                if (delegationDepth(connection, parentRunId) >= 3) {
                    throw new IllegalStateException("multi-agent delegation depth limit reached");
                }
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM run_delegations WHERE parent_run_id=?")) {
                    count.setString(1, parentRunId);
                    try (ResultSet rs = count.executeQuery()) {
                        if (rs.next() && rs.getInt(1) >= 6) {
                            throw new IllegalStateException("multi-agent child limit reached");
                        }
                    }
                }
                try (PreparedStatement tool = connection.prepareStatement(
                        "SELECT 1 FROM tool_calls WHERE id=? AND run_id=?")) {
                    tool.setString(1, parentToolCallId);
                    tool.setString(2, parentRunId);
                    try (ResultSet rs = tool.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("parent tool call does not belong to run");
                    }
                }
                SessionRecord parentSession = findSession(connection, parent.sessionId()).orElseThrow();
                String resolvedAgentProfileId = childAgentProfileId == null ? parent.agentProfileId() : childAgentProfileId;
                String resolvedModelProfileId = childModelProfileId == null ? parent.modelProfileId() : childModelProfileId;
                String resolvedThinkingMode = nullableText(thinkingMode) == null
                        ? parent.thinkingMode() : normalizeThinkingMode(thinkingMode);
                String resolvedReasoningEffort = nullableText(reasoningEffort) == null
                        ? parent.reasoningEffort() : normalizeReasoningEffort(reasoningEffort);
                String resolvedExecutionShell = nullableText(executionShell) == null
                        ? parent.executionShell() : normalizeExecutionShell(executionShell);
                if (!"enabled".equals(resolvedThinkingMode)) resolvedReasoningEffort = "";
                List<String> dependencyIds = resolveDelegationDependencies(
                        connection, parentRunId, graph.dependencies());
                Instant now = Instant.now();
                String childSessionId = id("session");
                String childRunId = id("run");
                String effectiveWorkspaceRef = effectiveDelegationWorkspaceRef(
                        graph.workspaceRef(), parentWorkspaceOwner);
                String delegatedWorkspaceOwner = resolveDelegatedWorkspaceOwner(
                        connection, parentRunId, effectiveWorkspaceRef, parentWorkspaceOwner, childRunId);
                try (PreparedStatement session = connection.prepareStatement(
                        "INSERT INTO sessions(id,title,project_key,group_id,status,is_internal,created_at,updated_at) " +
                                "VALUES(?,?,?,?,?,?,?,?)")) {
                    session.setString(1, childSessionId);
                    session.setString(2, TaskTitle.delegated(name, input));
                    session.setString(3, parentSession.projectKey());
                    session.setString(4, null);
                    session.setString(5, "ACTIVE");
                    session.setInt(6, 1);
                    session.setString(7, now.toString());
                    session.setString(8, now.toString());
                    session.executeUpdate();
                }
                try (PreparedStatement run = connection.prepareStatement(
                        "INSERT INTO runs(id,session_id,status,input,current_step,thinking_mode,reasoning_effort," +
                                "execution_shell,model_profile_id,agent_profile_id,workspace_owner_run_id,created_at,queued_at,version) " +
                                "VALUES(?,?,?,?,0,?,?,?,?,?,?,?,?,0)")) {
                    run.setString(1, childRunId);
                    run.setString(2, childSessionId);
                    run.setString(3, RunStatus.QUEUED.name());
                    run.setString(4, input);
                    run.setString(5, resolvedThinkingMode);
                    run.setString(6, resolvedReasoningEffort);
                    run.setString(7, resolvedExecutionShell);
                    run.setString(8, resolvedModelProfileId);
                    run.setString(9, resolvedAgentProfileId);
                    run.setString(10, delegatedWorkspaceOwner);
                    run.setString(11, now.toString());
                    run.setString(12, now.toString());
                    run.executeUpdate();
                }
                insertMessage(connection, childSessionId, childRunId, "user", input,
                        null, null, null, false);
                insertEvent(connection, childRunId, "run.queued", "{\"delegatedBy\":\"" + parentRunId + "\"}");
                String delegationId = id("delegation");
                String initialStatus = dependencyIds.isEmpty() ? RunStatus.QUEUED.name() : "BLOCKED";
                String blockedReason = dependencyIds.isEmpty() ? null
                        : "waiting for " + dependencyIds.size() + " upstream delegation(s)";
                try (PreparedStatement delegation = connection.prepareStatement(
                        "INSERT INTO run_delegations(id,parent_run_id,parent_tool_call_id,child_session_id," +
                                "child_run_id,agent_profile_id,agent_name,task,plan_id,plan_step_id,envelope_json," +
                                "status,failure_policy,blocked_reason,workspace_ref,created_at) " +
                                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    delegation.setString(1, delegationId);
                    delegation.setString(2, parentRunId);
                    delegation.setString(3, parentToolCallId);
                    delegation.setString(4, childSessionId);
                    delegation.setString(5, childRunId);
                    delegation.setString(6, resolvedAgentProfileId);
                    delegation.setString(7, name);
                    delegation.setString(8, input);
                    delegation.setString(9, nullableText(planId));
                    delegation.setString(10, nullableText(planStepId));
                    delegation.setString(11, normalizedEnvelope);
                    delegation.setString(12, initialStatus);
                    delegation.setString(13, graph.failurePolicy());
                    delegation.setString(14, blockedReason);
                    delegation.setString(15, nullableText(effectiveWorkspaceRef));
                    delegation.setString(16, now.toString());
                    delegation.executeUpdate();
                }
                insertDelegationDependencies(connection, delegationId, dependencyIds, now);
                insertDelegationResources(connection, delegationId, graph.readSet(), "READ", now);
                insertDelegationResources(connection, delegationId, graph.writeSet(), "WRITE", now);
                insertEvent(connection, parentRunId, "agent.delegated", "{\"childRunId\":\""
                        + childRunId + "\",\"agentName\":\"" + escape(name)
                        + "\",\"status\":\"" + initialStatus + "\"}");
                if (!dependencyIds.isEmpty()) {
                    insertEvent(connection, childRunId, "agent.blocked",
                            "{\"reason\":\"dependencies\",\"count\":" + dependencyIds.size() + "}");
                    for (String dependencyId : dependencyIds) {
                        advanceDependentDelegations(connection, dependencyId);
                    }
                }
                RunDelegationRecord created = findDelegation(connection, delegationId).orElseThrow();
                connection.commit();
                return created;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("create delegated run", e);
        }
    }

    public List<RunDelegationRecord> delegationsForRun(String parentRunId) {
        List<RunDelegationRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM run_delegations WHERE parent_run_id=? ORDER BY created_at")) {
            ps.setString(1, parentRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapDelegation(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list delegated runs", e);
        }
    }

    public Optional<RunDelegationRecord> findDelegation(String parentRunId, String childRunId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM run_delegations WHERE parent_run_id=? AND child_run_id=?")) {
            ps.setString(1, parentRunId);
            ps.setString(2, childRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapDelegation(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find delegated run", e);
        }
    }

    public Optional<RunDelegationRecord> parentDelegationForRun(String childRunId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM run_delegations WHERE child_run_id=? ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, childRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapDelegation(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find parent delegation", e);
        }
    }

    public String delegationRootRunId(String runId) {
        try (Connection connection = open()) {
            return rootRunId(connection, runId);
        } catch (SQLException e) {
            throw failure("resolve delegation root", e);
        }
    }

    public RunDelegationRecord completeDelegationResult(String delegationId, String status,
                                                        String resultJson, String failureClass) {
        String normalizedStatus = status == null || status.isBlank() ? "UNKNOWN" : status.trim().toUpperCase();
        String now = Instant.now().toString();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE run_delegations SET status=?,result_json=?,failure_class=?," +
                        "completed_at=CASE WHEN ? IN ('COMPLETED','FAILED','CANCELED') THEN ? ELSE completed_at END " +
                        "WHERE id=?")) {
            ps.setString(1, normalizedStatus);
            ps.setString(2, resultJson == null || resultJson.isBlank() ? "{}" : resultJson);
            ps.setString(3, failureClass);
            ps.setString(4, normalizedStatus);
            ps.setString(5, now);
            ps.setString(6, delegationId);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("delegation not found: " + delegationId);
            try (PreparedStatement find = connection.prepareStatement("SELECT * FROM run_delegations WHERE id=?")) {
                find.setString(1, delegationId);
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) return mapDelegation(rs);
                }
            }
            throw new IllegalStateException("delegation result was not persisted");
        } catch (SQLException e) {
            throw failure("complete delegated run result", e);
        }
    }

    public List<String> delegationDependencyIds(String delegationId) {
        List<String> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT depends_on_delegation_id FROM run_delegation_dependencies " +
                        "WHERE delegation_id=? ORDER BY created_at")) {
            ps.setString(1, delegationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(rs.getString(1));
            }
            return List.copyOf(values);
        } catch (SQLException e) {
            throw failure("list delegation dependencies", e);
        }
    }

    public Map<String, List<String>> delegationResources(String delegationId) {
        List<String> reads = new ArrayList<>();
        List<String> writes = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT resource_key,access_mode FROM run_delegation_resources " +
                        "WHERE delegation_id=? ORDER BY resource_key,access_mode")) {
            ps.setString(1, delegationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if ("WRITE".equals(rs.getString("access_mode"))) writes.add(rs.getString("resource_key"));
                    else reads.add(rs.getString("resource_key"));
                }
            }
            return Map.of("read", List.copyOf(reads), "write", List.copyOf(writes));
        } catch (SQLException e) {
            throw failure("list delegation resources", e);
        }
    }

    public RunDelegationRecord decideDelegation(String parentRunId, String delegationId,
                                                String decision, String reason) {
        String normalized = normalizeEnum(decision, Set.of("APPROVE", "REJECT"), "");
        if (normalized.isBlank()) throw new IllegalArgumentException("decision must be APPROVE or REJECT");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                RunDelegationRecord delegation = findDelegation(connection, delegationId)
                        .filter(value -> value.parentRunId().equals(parentRunId))
                        .orElseThrow(() -> new IllegalArgumentException("delegation not found for this parent"));
                if (!"WAITING_HUMAN".equals(delegation.status())) {
                    throw new IllegalStateException("delegation is not waiting for a human decision");
                }
                String explanation = reason == null || reason.isBlank() ? "human graph decision" : reason.trim();
                if ("APPROVE".equals(normalized)) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE run_delegations SET status='QUEUED',blocked_reason=NULL WHERE id=?")) {
                        ps.setString(1, delegationId);
                        ps.executeUpdate();
                    }
                    insertEvent(connection, delegation.childRunId(), "agent.human_approved",
                            "{\"reason\":\"" + escape(explanation) + "\"}");
                } else {
                    cancelDependentDelegation(connection, delegation, "human rejected: " + explanation);
                }
                connection.commit();
                return findDelegation(parentRunId, delegation.childRunId()).orElseThrow();
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("decide delegation", e);
        }
    }

    public List<MessageRecord> messages(String sessionId) {
        return messages(sessionId, false);
    }

    public boolean releaseClaim(String runId, String reason) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE runs SET status=?,queued_at=?,error=?,version=version+1 WHERE id=? AND status=?")) {
                ps.setString(1, RunStatus.QUEUED.name());
                ps.setString(2, Instant.now().toString());
                ps.setString(3, reason);
                ps.setString(4, runId);
                ps.setString(5, RunStatus.RUNNING.name());
                boolean changed = ps.executeUpdate() > 0;
                if (changed) insertEvent(connection, runId, "run.dispatch_rejected",
                        "{\"reason\":\"" + escape(reason) + "\"}");
                connection.commit();
                return changed;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("release claimed run", e);
        }
    }

    public boolean isInternalRun(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT s.is_internal FROM runs r JOIN sessions s ON s.id=r.session_id WHERE r.id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt(1) != 0; }
        } catch (SQLException e) {
            throw failure("read internal run flag", e);
        }
    }

    public SessionRecord createBranchSession(String sourceRunId) {
        RunRecord sourceRun = findRun(sourceRunId)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + sourceRunId));
        SessionRecord sourceSession = findSession(sourceRun.sessionId()).orElseThrow();
        SessionRecord branch = createSession(sourceSession.title() + " - 分支",
                sourceSession.projectKey(), sourceSession.groupId());
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                long boundary;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT COALESCE(MIN(sequence),9223372036854775807) FROM messages WHERE run_id=?")) {
                    ps.setString(1, sourceRunId);
                    try (ResultSet rs = ps.executeQuery()) { boundary = rs.next() ? rs.getLong(1) : Long.MAX_VALUE; }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT * FROM messages WHERE session_id=? AND archived=0 AND sequence<? ORDER BY sequence")) {
                    ps.setString(1, sourceSession.id());
                    ps.setLong(2, boundary);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            insertMessage(connection, branch.id(), null, rs.getString("role"),
                                    rs.getString("content"), rs.getString("reasoning_content"),
                                    null, null, false);
                        }
                    }
                }
                connection.commit();
                return branch;
            } catch (Exception e) { rollback(connection); throw e; }
        } catch (Exception e) {
            try { deleteSession(branch.id()); } catch (Exception ignored) { }
            throw e instanceof SQLException sql ? failure("create branch session", sql)
                    : new IllegalStateException("failed to create branch session", e);
        }
    }

    public List<SessionSearchMessage> searchableSessionMessages(String projectKey, List<String> queryTerms, int limit) {
        String project = requireText(projectKey, "projectKey", 120);
        int cappedLimit = limit <= 0 ? 5_000 : Math.min(limit, 20_000);
        List<String> terms = queryTerms == null ? List.of() : queryTerms.stream()
                .filter(value -> value != null && value.length() >= 2)
                .map(String::toLowerCase).distinct().limit(12).toList();
        String predicates = terms.isEmpty() ? "" : " AND (" + String.join(" OR ",
                java.util.Collections.nCopies(terms.size(), "LOWER(m.content) LIKE ?")) + ")";
        List<SessionSearchMessage> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT m.*, s.title AS session_title, s.project_key AS project_key, " +
                        "s.updated_at AS session_updated_at FROM messages m " +
                        "JOIN sessions s ON s.id=m.session_id " +
                        "WHERE s.project_key=? AND s.is_internal=0 AND TRIM(m.content) <> '' " +
                        predicates + " ORDER BY m.created_at DESC LIMIT ?")) {
            ps.setString(1, project);
            int parameter = 2;
            for (String term : terms) ps.setString(parameter++, "%" + term + "%");
            ps.setInt(parameter, cappedLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(new SessionSearchMessage(
                            rs.getString("id"),
                            rs.getString("session_id"),
                            rs.getString("session_title"),
                            rs.getString("project_key"),
                            rs.getString("run_id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getLong("sequence"),
                            instant(rs.getString("created_at")),
                            instant(rs.getString("session_updated_at"))));
                }
            }
            return values;
        } catch (SQLException e) {
            throw failure("list searchable session messages", e);
        }
    }

    public long searchableSessionMessageCount(String projectKey) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM messages m JOIN sessions s ON s.id=m.session_id "
                        + "WHERE s.project_key=? AND s.is_internal=0 AND TRIM(m.content) <> ''")) {
            statement.setString(1, normalizeProjectKey(projectKey));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw failure("count searchable session messages", e);
        }
    }

    public List<MessageRecord> activeMessages(String sessionId) {
        return messages(sessionId, true);
    }

    /**
     * Highest ACTIVE message sequence in the session, mirroring exactly what the model context
     * saw (activeMessages excludes archived messages). Used by the run processor to detect user
     * input that arrived while the model was generating: if the active sequence advanced past
     * what the built context saw, the model may have missed the new message. Archived messages
     * (e.g. a stale intermediate answer) never count as new input.
     */
    public long maxMessageSequence(String sessionId) {
        try (Connection connection = open()) {
            return maxMessageSequence(connection, sessionId);
        } catch (SQLException e) {
            throw failure("read max message sequence", e);
        }
    }

    private long maxMessageSequence(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(MAX(sequence),0) FROM messages WHERE session_id=? AND archived=0")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /**
     * Atomically appends a user message only while the run is still active (not terminal).
     * Returns false when the run already terminated, so callers (e.g. collaboration comment
     * delivery) can fall back to creating a new Run instead of losing the comment behind a
     * terminal Run. The status re-check and the append share one SQLite transaction.
     */
    public boolean appendUserMessageIfRunActive(String sessionId, String runId, String content) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                boolean active;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT status FROM runs WHERE id=?")) {
                    ps.setString(1, runId);
                    try (ResultSet rs = ps.executeQuery()) {
                        active = rs.next() && !RunStatus.valueOf(rs.getString("status")).terminal();
                    }
                }
                if (!active) {
                    connection.rollback();
                    return false;
                }
                insertMessage(connection, sessionId, runId, "user", content == null ? "" : content,
                        null, null, null, false);
                connection.commit();
                return true;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("append user message to active run", e);
        }
    }

    /**
     * Persists the model's intermediate answer and requeues the run in one transaction. Used when
     * user input arrived while the model was generating: the answer is preserved for audit as an
     * ARCHIVED assistant message (never part of the next round's active context) and the run re-runs
     * to see the new message. A crash mid-way cannot leave the answer written while the run is still
     * stuck in its old status.
     */
    public boolean commitIntermediateAssistantAndRequeue(String sessionId, String runId, String content,
                                                         String reasoningContent, String eventJson, int nextStep) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                insertMessage(connection, sessionId, runId, "assistant", content == null ? "" : content,
                        reasoningContent, null, null, true);
                insertEvent(connection, runId, "run.new_input_during_model",
                        eventJson == null ? "{}" : eventJson);
                Instant now = Instant.now();
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE runs SET status=?,current_step=?,error=NULL,queued_at=?,version=version+1 " +
                                "WHERE id=? AND status NOT IN ('COMPLETED','FAILED','CANCELED')")) {
                    ps.setString(1, RunStatus.QUEUED.name());
                    ps.setInt(2, Math.max(0, nextStep));
                    ps.setString(3, now.toString());
                    ps.setString(4, runId);
                    if (ps.executeUpdate() == 0) {
                        connection.rollback();
                        return false;
                    }
                }
                insertEvent(connection, runId, "run.queued", "{\"status\":\"QUEUED\"}");
                connection.commit();
                return true;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("commit intermediate assistant and requeue", e);
        }
    }

    public List<MessageRecord> messagesForRun(String runId) {
        List<MessageRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM messages WHERE run_id=? ORDER BY sequence")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapMessage(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list run messages", e);
        }
    }

    /**
     * Semantic run history: only active (archived=0) messages of the run. Archived messages are
     * preserved for audit (see {@link #messagesForRun(String)}) but must never be consumed by any
     * agent-semantic chain such as digests, agent results or memory extraction.
     */
    public List<MessageRecord> activeMessagesForRun(String runId) {
        List<MessageRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM messages WHERE run_id=? AND archived=0 ORDER BY sequence")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapMessage(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list active run messages", e);
        }
    }

    public String planContextForRun(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT p.id plan_id,p.objective,p.status plan_status,ps.id step_id,ps.title," +
                        "ps.description,ps.status step_status,ps.done_criteria_json,ps.attempt," +
                        "ps.result_summary,ps.failure_reason FROM plan_steps ps " +
                        "JOIN plans p ON p.id=ps.plan_id WHERE ps.run_id=? ORDER BY ps.updated_at DESC LIMIT 1")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "";
                return "<plan_state plan_id=\"" + rs.getString("plan_id") + "\" step_id=\""
                        + rs.getString("step_id") + "\">\n"
                        + "objective: " + rs.getString("objective") + "\n"
                        + "plan_status: " + rs.getString("plan_status") + "\n"
                        + "step_title: " + rs.getString("title") + "\n"
                        + "step_description: " + rs.getString("description") + "\n"
                        + "step_status: " + rs.getString("step_status") + "\n"
                        + "attempt: " + rs.getInt("attempt") + "\n"
                        + "done_criteria: " + rs.getString("done_criteria_json") + "\n"
                        + "result_summary: " + String.valueOf(rs.getString("result_summary")) + "\n"
                        + "failure_reason: " + String.valueOf(rs.getString("failure_reason")) + "\n"
                        + "</plan_state>";
            }
        } catch (SQLException e) {
            throw failure("read plan context for run", e);
        }
    }

    private List<MessageRecord> messages(String sessionId, boolean activeOnly) {
        List<MessageRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM messages WHERE session_id=?" + (activeOnly ? " AND archived=0" : "")
                        + " ORDER BY sequence")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapMessage(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list messages", e);
        }
    }

    public MessageRecord appendMessage(String sessionId, String runId, String role, String content) {
        try (Connection connection = open()) {
            return insertMessage(connection, sessionId, runId, role, content == null ? "" : content,
                    null, null, null, false);
        } catch (SQLException e) {
            throw failure("append message", e);
        }
    }

    public MessageRecord appendAssistantMessage(String sessionId, String runId, String content,
                                                 String reasoningContent) {
        try (Connection connection = open()) {
            return insertMessage(connection, sessionId, runId, "assistant", content == null ? "" : content,
                    reasoningContent, null, null, false);
        } catch (SQLException e) {
            throw failure("append assistant message", e);
        }
    }

    public MessageRecord appendAssistantToolCall(String sessionId, String runId, String content,
                                                  String reasoningContent, String toolCallsJson) {
        try (Connection connection = open()) {
            return insertMessage(connection, sessionId, runId, "assistant", content == null ? "" : content,
                    reasoningContent, null, toolCallsJson, false);
        } catch (SQLException e) {
            throw failure("append assistant tool call", e);
        }
    }

    public List<ToolCallRecord> appendAssistantAndCreateToolCalls(
            String sessionId, String runId, String content, String reasoningContent,
            String toolCallsJson, List<ToolCallDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) throw new IllegalArgumentException("tool calls must not be empty");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (!runHasStatus(connection, runId, RunStatus.WAITING_MODEL)) {
                    connection.rollback();
                    return List.of();
                }
                insertMessage(connection, sessionId, runId, "assistant", content == null ? "" : content,
                        reasoningContent, null, toolCallsJson, false);
                List<ToolCallRecord> records = new ArrayList<>();
                Instant batchTime = Instant.now();
                for (int index = 0; index < drafts.size(); index++) {
                    ToolCallDraft draft = drafts.get(index);
                    Optional<ToolCallRecord> existing = findToolCallByIdempotencyKey(
                            connection, draft.idempotencyKey());
                    if (existing.isPresent()) {
                        records.add(existing.get());
                        continue;
                    }
                    String id = id("tool");
                    Instant createdAt = batchTime.plusNanos(index);
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO tool_calls(id,run_id,provider_call_id,tool_name,arguments,status," +
                                    "idempotency_key,created_at,effect) VALUES(?,?,?,?,?,?,?,?,?)")) {
                        ps.setString(1, id);
                        ps.setString(2, runId);
                        ps.setString(3, draft.providerCallId());
                        ps.setString(4, draft.toolName());
                        ps.setString(5, draft.arguments());
                        ps.setString(6, ToolCallStatus.REQUESTED.name());
                        ps.setString(7, draft.idempotencyKey());
                        ps.setString(8, createdAt.toString());
                        ps.setString(9, draft.effect().name());
                        ps.executeUpdate();
                    }
                    records.add(new ToolCallRecord(id, runId, draft.providerCallId(), draft.toolName(),
                            draft.arguments(), ToolCallStatus.REQUESTED, null, null,
                            draft.idempotencyKey(), 0, createdAt, null));
                }
                connection.commit();
                return List.copyOf(records);
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("append assistant and create tool calls", e);
        }
    }

    public MessageRecord appendToolResult(String sessionId, String runId, String toolCallId, String content) {
        try (Connection connection = open()) {
            return insertMessage(connection, sessionId, runId, "tool", content == null ? "" : content,
                    null, toolCallId, null, false);
        } catch (SQLException e) {
            throw failure("append tool result", e);
        }
    }

    public MessageRecord archiveAndAddSummary(String sessionId, String runId,
                                               List<String> messageIds, String summary) {
        if (messageIds == null || messageIds.isEmpty()) {
            throw new IllegalArgumentException("messageIds must not be empty");
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE messages SET archived=1 WHERE session_id=? AND id=?")) {
                    for (String id : messageIds) {
                        ps.setString(1, sessionId);
                        ps.setString(2, id);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                MessageRecord record = insertMessage(connection, sessionId, runId, "summary", summary,
                        null, null, null, false);
                connection.commit();
                return record;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("archive messages", e);
        }
    }

    public RunEventRecord appendEvent(String runId, String type, String json) {
        try (Connection connection = open()) {
            return insertEvent(connection, runId, type, json == null ? "{}" : json);
        } catch (SQLException e) {
            throw failure("append event", e);
        }
    }

    public List<RunEventRecord> events(String runId, long afterId) {
        return events(runId, afterId, 1_000);
    }

    public List<RunEventRecord> events(String runId, long afterId, int requestedLimit) {
        List<RunEventRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM run_events WHERE run_id=? AND id>? ORDER BY id LIMIT ?")) {
            ps.setString(1, runId);
            ps.setLong(2, Math.max(0, afterId));
            ps.setInt(3, Math.max(1, Math.min(requestedLimit, 1_000)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapEvent(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list events", e);
        }
    }

    public ToolCallRecord createToolCall(String runId, String providerCallId, String toolName,
                                         String arguments, String idempotencyKey) {
        return createToolCall(runId, providerCallId, toolName, arguments, idempotencyKey,
                ToolEffect.READ_ONLY);
    }

    public ToolCallRecord createToolCall(String runId, String providerCallId, String toolName,
                                         String arguments, String idempotencyKey, ToolEffect effect) {
        Optional<ToolCallRecord> existing = findToolCallByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return existing.get();
        String id = id("tool");
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tool_calls(id,run_id,provider_call_id,tool_name,arguments,status,idempotency_key,created_at,effect) " +
                        "VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, runId);
            ps.setString(3, providerCallId);
            ps.setString(4, toolName);
            ps.setString(5, arguments);
            ps.setString(6, ToolCallStatus.REQUESTED.name());
            ps.setString(7, idempotencyKey);
            ps.setString(8, now.toString());
            ps.setString(9, effect.name());
            ps.executeUpdate();
            return new ToolCallRecord(id, runId, providerCallId, toolName, arguments,
                    ToolCallStatus.REQUESTED, null, null, idempotencyKey, 0, now, null);
        } catch (SQLException e) {
            throw failure("create tool call", e);
        }
    }

    public Optional<ToolCallRecord> findToolCallByIdempotencyKey(String key) {
        try (Connection connection = open()) {
            return findToolCallByIdempotencyKey(connection, key);
        } catch (SQLException e) {
            throw failure("find tool call", e);
        }
    }

    private Optional<ToolCallRecord> findToolCallByIdempotencyKey(Connection connection, String key)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM tool_calls WHERE idempotency_key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapToolCall(rs)) : Optional.empty();
            }
        }
    }

    public Optional<ToolCallRecord> findResumableToolCall(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM tool_calls WHERE run_id=? AND status=? ORDER BY created_at ASC LIMIT 1")) {
            ps.setString(1, runId);
            ps.setString(2, ToolCallStatus.REQUESTED.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapToolCall(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find resumable tool call", e);
        }
    }

    public ApprovalRecord createApproval(String runId, String toolCallId, String reason) {
        Optional<ApprovalRecord> existing = findApprovalByToolCall(toolCallId);
        if (existing.isPresent()) return existing.get();
        String id = id("approval");
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO approvals(id,run_id,tool_call_id,status,reason,created_at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, runId);
            ps.setString(3, toolCallId);
            ps.setString(4, ApprovalStatus.PENDING.name());
            ps.setString(5, reason);
            ps.setString(6, now.toString());
            ps.executeUpdate();
            return new ApprovalRecord(id, runId, toolCallId, ApprovalStatus.PENDING, reason, now, null);
        } catch (SQLException e) {
            throw failure("create approval", e);
        }
    }

    public Optional<ApprovalRecord> findApproval(String id) {
        return findApproval("id", id);
    }

    public Optional<ApprovalRecord> findApprovalByToolCall(String toolCallId) {
        return findApproval("tool_call_id", toolCallId);
    }

    public List<ApprovalRecord> pendingApprovals() {
        List<ApprovalRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM approvals WHERE status=? ORDER BY created_at")) {
            ps.setString(1, ApprovalStatus.PENDING.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapApproval(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list approvals", e);
        }
    }

    public ApprovalRecord resolveApproval(String id, ApprovalStatus decision) {
        if (decision != ApprovalStatus.APPROVED && decision != ApprovalStatus.DENIED) {
            throw new IllegalArgumentException("decision must be APPROVED or DENIED");
        }
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE approvals SET status=?, resolved_at=? WHERE id=? AND status=?")) {
            ps.setString(1, decision.name());
            ps.setString(2, now.toString());
            ps.setString(3, id);
            ps.setString(4, ApprovalStatus.PENDING.name());
            if (ps.executeUpdate() == 0) {
                ApprovalRecord existing = findApproval(id)
                        .orElseThrow(() -> new IllegalArgumentException("approval not found: " + id));
                if (existing.status() != decision) {
                    throw new IllegalStateException("approval is already " + existing.status());
                }
                return existing;
            }
            return findApproval(id).orElseThrow();
        } catch (SQLException e) {
            throw failure("resolve approval", e);
        }
    }

    public ArtifactRecord createArtifact(String runId, String type, String name,
                                         String relativePath, long size, String sha256) {
        String id = id("artifact");
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO artifacts(id,run_id,type,name,relative_path,size,sha256,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, runId);
            ps.setString(3, type);
            ps.setString(4, name);
            ps.setString(5, relativePath);
            ps.setLong(6, size);
            ps.setString(7, sha256);
            ps.setString(8, now.toString());
            ps.executeUpdate();
            return new ArtifactRecord(id, runId, type, name, relativePath, size, sha256, now);
        } catch (SQLException e) {
            throw failure("create artifact", e);
        }
    }

    public Optional<ArtifactRecord> findArtifact(String artifactId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM artifacts WHERE id=?")) {
            ps.setString(1, artifactId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapArtifact(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find artifact", e);
        }
    }

    public List<ArtifactRecord> artifactsForRun(String runId) {
        List<ArtifactRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM artifacts WHERE run_id=? ORDER BY created_at")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapArtifact(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list artifacts", e);
        }
    }

    public MemoryRecord createMemory(String projectKey, String memoryKey, String content, String tags) {
        String id = id("memory");
        Instant now = Instant.now();
        String project = normalizeProjectKey(projectKey);
        String key = requireText(memoryKey, "memoryKey", 120);
        String value = requireText(content, "content", 32_000);
        String normalizedTags = tags == null ? "" : tags.trim();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO memories(id,project_key,memory_key,content,tags,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, project);
            ps.setString(3, key);
            ps.setString(4, value);
            ps.setString(5, normalizedTags);
            ps.setString(6, now.toString());
            ps.setString(7, now.toString());
            ps.executeUpdate();
            return new MemoryRecord(id, project, key, value, normalizedTags, now, now);
        } catch (SQLException e) {
            throw failure("create memory", e);
        }
    }

    public MemoryRecord upsertAutomaticMemory(String projectKey, String memoryKey, String content, String tags,
                                              String layer, String memoryType, double confidence,
                                              String sessionId, String runId, String embeddingJson) {
        return upsertAutomaticMemory(projectKey, memoryKey, content, tags, layer, memoryType, confidence,
                sessionId, runId, embeddingJson, List.of(), null, null, "");
    }

    public MemoryRecord upsertAutomaticMemory(String projectKey, String memoryKey, String content, String tags,
                                              String layer, String memoryType, double confidence,
                                              String sessionId, String runId, String embeddingJson,
                                              List<String> sourceMessageIds, Long sourceStartSequence,
                                              Long sourceEndSequence, String sourceExcerpt) {
        MemoryScope sourceScope = runId == null ? MemoryScope.project() : memoryScopeForRun(runId);
        return upsertAutomaticMemory(projectKey, memoryKey, content, tags, layer, memoryType, confidence,
                sessionId, runId, embeddingJson, sourceMessageIds, sourceStartSequence, sourceEndSequence,
                sourceExcerpt, defaultMemoryScope(sourceScope, layer, memoryType));
    }

    public MemoryRecord upsertAutomaticMemory(String projectKey, String memoryKey, String content, String tags,
                                              String layer, String memoryType, double confidence,
                                              String sessionId, String runId, String embeddingJson,
                                              List<String> sourceMessageIds, Long sourceStartSequence,
                                              Long sourceEndSequence, String sourceExcerpt, MemoryScope memoryScope) {
        String project = normalizeProjectKey(projectKey);
        String key = requireText(memoryKey, "memoryKey", 120);
        String value = requireText(content, "content", 32_000);
        String normalizedTags = tags == null ? "" : tags.trim();
        String normalizedLayer = Set.of("L1", "L2", "L3").contains(layer) ? layer : "L1";
        String normalizedType = memoryType == null || memoryType.isBlank() ? "FACT" : memoryType.trim().toUpperCase();
        MemoryScope normalizedScope = normalizeMemoryScope(memoryScope);
        String structuredPayload = memoryScopePayload(normalizedScope);
        double normalizedConfidence = Math.max(0, Math.min(1, confidence));
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                MemoryUnit existing = findMemoryUnit(connection, project, key).orElse(null);
                if (existing != null && !existing.content().equals(value)) {
                    insertMemoryRevision(connection, existing, runId);
                    recordMemoryConflict(connection, project, existing.id(), existing.id(),
                            "same canonical key changed from a different extraction");
                }
                String memoryId = existing == null ? id("memory") : existing.id();
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO memories(id,project_key,memory_key,content,tags,created_at,updated_at," +
                                "layer,memory_type,confidence,origin,source_session_id,source_run_id,embedding_json," +
                                "structured_payload,status,source_type,source_id,source_revision,valid_from,supersedes_id,checksum," +
                                "scope_type,scope_agent_profile_id,scope_workspace_owner_run_id,scope_task_type) " +
                                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                                "ON CONFLICT(project_key,memory_key) DO UPDATE SET " +
                                "content=excluded.content,tags=excluded.tags,updated_at=excluded.updated_at," +
                                "layer=excluded.layer,memory_type=excluded.memory_type,confidence=excluded.confidence," +
                                "origin='automatic',source_session_id=excluded.source_session_id," +
                                "source_run_id=excluded.source_run_id,embedding_json=excluded.embedding_json," +
                                "structured_payload=excluded.structured_payload,status=excluded.status," +
                                "source_type=excluded.source_type,source_id=excluded.source_id," +
                                "source_revision=excluded.source_revision,valid_from=COALESCE(memories.valid_from,excluded.valid_from)," +
                                "supersedes_id=excluded.supersedes_id,checksum=excluded.checksum," +
                                "scope_type=excluded.scope_type,scope_agent_profile_id=excluded.scope_agent_profile_id," +
                                "scope_workspace_owner_run_id=excluded.scope_workspace_owner_run_id," +
                                "scope_task_type=excluded.scope_task_type")) {
                    ps.setString(1, memoryId);
                    ps.setString(2, project);
                    ps.setString(3, key);
                    ps.setString(4, value);
                    ps.setString(5, normalizedTags);
                    ps.setString(6, existing == null ? now.toString() : existing.createdAt().toString());
                    ps.setString(7, now.toString());
                    ps.setString(8, normalizedLayer);
                    ps.setString(9, normalizedType);
                    ps.setDouble(10, normalizedConfidence);
                    ps.setString(11, "automatic");
                    ps.setString(12, sessionId);
                    ps.setString(13, runId);
                    ps.setString(14, embeddingJson);
                    ps.setString(15, structuredPayload);
                    ps.setString(16, "ACTIVE");
                    ps.setString(17, "run");
                    ps.setString(18, runId);
                    ps.setString(19, runId == null ? "1" : runId);
                    ps.setString(20, existing == null ? now.toString() : existing.validFrom() == null
                            ? existing.createdAt().toString() : existing.validFrom().toString());
                    ps.setString(21, existing == null || existing.content().equals(value) ? null : existing.id());
                    ps.setString(22, checksum(key + "\n" + value));
                    ps.setString(23, normalizedScope.scopeType());
                    ps.setString(24, normalizedScope.agentProfileId());
                    ps.setString(25, normalizedScope.workspaceOwnerRunId());
                    ps.setString(26, normalizedScope.taskType());
                    ps.executeUpdate();
                }
                insertMemorySource(connection, memoryId, "run", runId, runId == null ? "1" : runId,
                        sourceExcerpt == null || sourceExcerpt.isBlank() ? excerpt(value) : excerpt(sourceExcerpt),
                        sourceMessageIds, sourceStartSequence, sourceEndSequence);
                connection.commit();
                return findMemory(memoryId).orElseThrow();
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (Exception e) {
            throw e instanceof SQLException sql ? failure("upsert automatic memory", sql)
                    : new IllegalStateException("failed to upsert automatic memory", e);
        }
    }

    public List<MemoryUnit> memoryUnits(String projectKey, int limit) {
        List<MemoryUnit> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM memories WHERE project_key=? AND enabled=1 AND status='ACTIVE' " +
                        "AND (valid_to IS NULL OR valid_to>?) ORDER BY pinned DESC, updated_at DESC LIMIT ?")) {
            ps.setString(1, normalizeProjectKey(projectKey));
            ps.setString(2, Instant.now().toString());
            ps.setInt(3, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapMemoryUnit(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list memory units", e);
        }
    }

    public void touchMemories(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE memories SET last_accessed_at=?,access_count=access_count+1 WHERE id=?")) {
            String now = Instant.now().toString();
            for (String id : ids) {
                ps.setString(1, now);
                ps.setString(2, id);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw failure("touch memories", e);
        }
    }

    public void enqueueMemoryExtraction(String runId) {
        String now = Instant.now().toString();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                List<MemoryExtractionMessage> snapshot = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id,sequence,role,content,tool_call_id FROM messages WHERE run_id=? AND archived=0 "
                                + "ORDER BY sequence")) {
                    select.setString(1, runId);
                    try (ResultSet rs = select.executeQuery()) {
                        while (rs.next()) snapshot.add(new MemoryExtractionMessage(
                                rs.getString("id"), rs.getLong("sequence"), rs.getString("role"),
                                rs.getString("content"), rs.getString("tool_call_id")));
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR IGNORE INTO memory_extractions(run_id,status,attempts,error,created_at,updated_at," +
                                "source_snapshot_json) VALUES(?,'PENDING',0,NULL,?,?,?)")) {
                    ps.setString(1, runId);
                    ps.setString(2, now);
                    ps.setString(3, now);
                    ps.setString(4, mapper.writeValueAsString(snapshot));
                    ps.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("enqueue memory extraction", e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to snapshot memory extraction source", e);
        }
    }

    public void recordMemorySelections(String runId, List<String> memoryIds) {
        if (runId == null || memoryIds == null || memoryIds.isEmpty()) return;
        String now = Instant.now().toString();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO memory_usage_feedback(run_id,memory_id,selected_at,outcome,updated_at) " +
                        "VALUES(?,? ,?,'SELECTED',?) ON CONFLICT(run_id,memory_id) DO NOTHING")) {
            for (String memoryId : memoryIds.stream().filter(value -> value != null && !value.isBlank())
                    .distinct().toList()) {
                ps.setString(1, runId);
                ps.setString(2, memoryId);
                ps.setString(3, now);
                ps.setString(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw failure("record memory selections", e);
        }
    }

    public void recordMemoryOutcome(String runId, String outcome) {
        if (runId == null || outcome == null || outcome.isBlank()) return;
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE memory_usage_feedback SET outcome=?,updated_at=? WHERE run_id=?")) {
            ps.setString(1, outcome.trim().toUpperCase());
            ps.setString(2, Instant.now().toString());
            ps.setString(3, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("record memory outcome", e);
        }
    }

    public Map<String, Double> memoryFeedbackScores(List<String> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) return Map.of();
        Map<String, Double> scores = new HashMap<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(memoryIds.size(), "?"));
        String sql = "SELECT memory_id," +
                "SUM(CASE WHEN outcome IN ('RUN_COMPLETED','VALIDATED','PASSED') THEN 1 ELSE 0 END) positive," +
                "SUM(CASE WHEN outcome NOT IN ('SELECTED') THEN 1 ELSE 0 END) terminal " +
                "FROM memory_usage_feedback WHERE memory_id IN (" + placeholders + ") GROUP BY memory_id";
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int index = 0; index < memoryIds.size(); index++) ps.setString(index + 1, memoryIds.get(index));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int terminal = rs.getInt("terminal");
                    scores.put(rs.getString("memory_id"), terminal == 0 ? 0d
                            : (double) rs.getInt("positive") / terminal);
                }
            }
            return Map.copyOf(scores);
        } catch (SQLException e) {
            throw failure("read memory feedback scores", e);
        }
    }

    public int markStaleMemories(Instant cutoff) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE memories SET status='STALE',updated_at=? WHERE status='ACTIVE' AND layer='L1' " +
                        "AND pinned=0 AND updated_at<? AND (last_accessed_at IS NULL OR last_accessed_at<?)")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, cutoff.toString());
            ps.setString(3, cutoff.toString());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("mark stale memories", e);
        }
    }

    public void openMemoryConflict(String projectKey, String memoryId,
                                   String conflictingMemoryId, String reason) {
        try (Connection connection = open()) {
            recordMemoryConflict(connection, normalizeProjectKey(projectKey), memoryId,
                    conflictingMemoryId, requireText(reason, "reason", 1_000));
        } catch (SQLException e) {
            throw failure("open memory conflict", e);
        }
    }

    public List<MemoryExtractionMessage> memoryExtractionSnapshot(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT source_snapshot_json FROM memory_extractions WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString(1);
                    if (json != null && !json.isBlank() && !"[]".equals(json)) {
                        return List.of(mapper.readValue(json, MemoryExtractionMessage[].class));
                    }
                }
            }
            return messagesForRun(runId).stream().map(message -> new MemoryExtractionMessage(
                    message.id(), message.sequence(), message.role(), message.content(), message.toolCallId())).toList();
        } catch (SQLException e) {
            throw failure("read memory extraction snapshot", e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to decode memory extraction snapshot", e);
        }
    }

    public Optional<String> claimMemoryExtraction() {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String runId = null;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT run_id FROM memory_extractions WHERE status IN ('PENDING','FAILED') " +
                                "AND attempts < 3 ORDER BY updated_at LIMIT 1")) {
                    try (ResultSet rs = select.executeQuery()) { if (rs.next()) runId = rs.getString(1); }
                }
                if (runId == null) {
                    connection.commit();
                    return Optional.empty();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE memory_extractions SET status='RUNNING',attempts=attempts+1,error=NULL," +
                                "updated_at=? WHERE run_id=? AND status IN ('PENDING','FAILED')")) {
                    update.setString(1, Instant.now().toString());
                    update.setString(2, runId);
                    if (update.executeUpdate() != 1) {
                        rollback(connection);
                        return Optional.empty();
                    }
                }
                connection.commit();
                return Optional.of(runId);
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (Exception e) {
            throw e instanceof SQLException sql ? failure("claim memory extraction", sql)
                    : new IllegalStateException("failed to claim memory extraction", e);
        }
    }

    public void finishMemoryExtraction(String runId, String error) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE memory_extractions SET status=?,error=?,updated_at=? WHERE run_id=?")) {
            ps.setString(1, error == null ? "COMPLETED" : "FAILED");
            ps.setString(2, error);
            ps.setString(3, Instant.now().toString());
            ps.setString(4, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("finish memory extraction", e);
        }
    }

    private Optional<MemoryUnit> findMemoryUnit(Connection connection, String projectKey, String memoryKey)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM memories WHERE project_key=? AND memory_key=?")) {
            ps.setString(1, projectKey);
            ps.setString(2, memoryKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapMemoryUnit(rs)) : Optional.empty();
            }
        }
    }

    public Optional<MemoryRecord> findMemory(String id) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM memories WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapMemory(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find memory", e);
        }
    }

    public List<MemoryRecord> memories(String projectKey, String query, int limit) {
        List<MemoryRecord> values = new ArrayList<>();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        boolean search = !normalizedQuery.isBlank();
        String sql = "SELECT * FROM memories WHERE project_key=?" +
                (search ? " AND (LOWER(memory_key) LIKE ? OR LOWER(content) LIKE ? OR LOWER(tags) LIKE ?)" : "") +
                " ORDER BY updated_at DESC LIMIT ?";
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            ps.setString(index++, normalizeProjectKey(projectKey));
            if (search) {
                String pattern = "%" + normalizedQuery + "%";
                ps.setString(index++, pattern);
                ps.setString(index++, pattern);
                ps.setString(index++, pattern);
            }
            ps.setInt(index, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapMemory(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list memories", e);
        }
    }

    public MemoryRecord updateMemory(String id, String memoryKey, String content, String tags) {
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                MemoryUnit current;
                try (PreparedStatement find = connection.prepareStatement("SELECT * FROM memories WHERE id=?")) {
                    find.setString(1, id);
                    try (ResultSet rs = find.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("memory not found: " + id);
                        current = mapMemoryUnit(rs);
                    }
                }
                insertMemoryRevision(connection, current, current.sourceRunId());
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE memories SET memory_key=?,content=?,tags=?,updated_at=? WHERE id=?")) {
                    ps.setString(1, requireText(memoryKey, "memoryKey", 120));
                    ps.setString(2, requireText(content, "content", 32_000));
                    ps.setString(3, tags == null ? "" : tags.trim());
                    ps.setString(4, now.toString()); ps.setString(5, id); ps.executeUpdate();
                }
                connection.commit();
                return findMemory(id).orElseThrow();
            } catch (Exception e) { rollback(connection); throw e; }
        } catch (Exception e) {
            throw e instanceof SQLException sql ? failure("update memory", sql)
                    : e instanceof IllegalArgumentException argument ? argument
                    : new IllegalStateException("failed to update memory", e);
        }
    }

    public long countToolCallsForRun(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM tool_calls WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        } catch (SQLException e) {
            throw failure("count tool calls", e);
        }
    }

    public boolean commitFinalAssistantAndComplete(String sessionId, String runId, String content,
                                                    String reasoningContent, String completedEventJson) {
        return commitFinalAssistantAndComplete(sessionId, runId, content, reasoningContent,
                completedEventJson, -1);
    }

    /**
     * Atomically completes the run only if no message was appended after the model context was
     * built. This closes the check-then-act window where user input arriving during the model call
     * could be missed: the sequence check and the COMPLETED transition share one SQLite transaction.
     * Returns false when a new message appeared (run left untouched) or when the run was no longer
     * WAITING_MODEL (e.g. already canceled by another actor).
     */
    public boolean commitFinalAssistantAndComplete(String sessionId, String runId, String content,
                                                    String reasoningContent, String completedEventJson,
                                                    long expectedMaxMessageSequence) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (expectedMaxMessageSequence >= 0
                        && maxMessageSequence(connection, sessionId) > expectedMaxMessageSequence) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE runs SET status=?,error=NULL,finished_at=?,version=version+1 " +
                                "WHERE id=? AND status=?")) {
                    ps.setString(1, RunStatus.COMPLETED.name());
                    ps.setString(2, Instant.now().toString());
                    ps.setString(3, runId);
                    ps.setString(4, RunStatus.WAITING_MODEL.name());
                    if (ps.executeUpdate() == 0) {
                        connection.rollback();
                        return false;
                    }
                }
                insertMessage(connection, sessionId, runId, "assistant", content == null ? "" : content,
                        reasoningContent, null, null, false);
                insertEvent(connection, runId, "model.completed",
                        completedEventJson == null ? "{}" : completedEventJson);
                insertEvent(connection, runId, "run.completed", "{\"status\":\"COMPLETED\"}");
                finalizeDelegationGraph(connection, runId, RunStatus.COMPLETED, null);
                connection.commit();
                return true;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("commit final model response", e);
        }
    }

    /**
     * Commits one tool outcome (message + event) without touching the Run status.
     * Used by the read-only batch path, which marks all calls RUNNING up front and
     * requeues the Run once after all outcomes are committed in model order.
     */
    public boolean commitToolMessage(String sessionId, String runId, ToolCallRecord call, boolean success,
                                     String modelContent, String error, String metadataJson, String eventJson) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                ToolCallStatus toolStatus = success ? ToolCallStatus.COMPLETED : ToolCallStatus.FAILED;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE tool_calls SET status=?,result=?,error=?,result_metadata_json=?,finished_at=? " +
                                "WHERE id=? AND status=?")) {
                    ps.setString(1, toolStatus.name());
                    ps.setString(2, success ? modelContent : null);
                    ps.setString(3, success ? null : error);
                    ps.setString(4, metadataJson == null ? "{}" : metadataJson);
                    ps.setString(5, Instant.now().toString());
                    ps.setString(6, call.id());
                    ps.setString(7, ToolCallStatus.RUNNING.name());
                    if (ps.executeUpdate() == 0) throw new IllegalStateException("tool call is no longer running");
                }
                insertMessage(connection, sessionId, runId, "tool", modelContent == null ? "" : modelContent,
                        null, call.providerCallId(), null, false);
                insertEvent(connection, runId, success ? "tool.completed" : "tool.failed",
                        eventJson == null ? "{}" : eventJson);
                connection.commit();
                return true;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("commit tool message", e);
        }
    }

    public boolean commitToolOutcome(String sessionId, String runId, ToolCallRecord call,
                                     boolean success, String modelContent, String error,
                                     String metadataJson, String eventJson, int currentStep) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (!runHasStatus(connection, runId, RunStatus.WAITING_TOOL)) {
                    connection.rollback();
                    return false;
                }
                ToolCallStatus toolStatus = success ? ToolCallStatus.COMPLETED : ToolCallStatus.FAILED;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE tool_calls SET status=?,result=?,error=?,result_metadata_json=?,finished_at=? " +
                                "WHERE id=? AND status=?")) {
                    ps.setString(1, toolStatus.name());
                    ps.setString(2, success ? modelContent : null);
                    ps.setString(3, success ? null : error);
                    ps.setString(4, metadataJson == null ? "{}" : metadataJson);
                    ps.setString(5, Instant.now().toString());
                    ps.setString(6, call.id());
                    ps.setString(7, ToolCallStatus.RUNNING.name());
                    if (ps.executeUpdate() == 0) throw new IllegalStateException("tool call is no longer running");
                }
                insertMessage(connection, sessionId, runId, "tool", modelContent == null ? "" : modelContent,
                        null, call.providerCallId(), null, false);
                insertEvent(connection, runId, success ? "tool.completed" : "tool.failed",
                        eventJson == null ? "{}" : eventJson);
                boolean hasMore;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT 1 FROM tool_calls WHERE run_id=? AND status=? LIMIT 1")) {
                    ps.setString(1, runId);
                    ps.setString(2, ToolCallStatus.REQUESTED.name());
                    try (ResultSet rs = ps.executeQuery()) { hasMore = rs.next(); }
                }
                int nextStep = hasMore ? currentStep : currentStep + 1;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE runs SET status=?,current_step=?,error=NULL,queued_at=?,version=version+1 " +
                                "WHERE id=? AND status=?")) {
                    ps.setString(1, RunStatus.QUEUED.name());
                    ps.setInt(2, nextStep);
                    ps.setString(3, Instant.now().toString());
                    ps.setString(4, runId);
                    ps.setString(5, RunStatus.WAITING_TOOL.name());
                    if (ps.executeUpdate() == 0) throw new IllegalStateException("run is no longer waiting for tool");
                }
                insertEvent(connection, runId, "run.queued", "{\"status\":\"QUEUED\"}");
                connection.commit();
                return true;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("commit tool outcome", e);
        }
    }

    public List<ToolCallRecord> toolCallsForRun(String runId) {
        List<ToolCallRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM tool_calls WHERE run_id=? ORDER BY created_at")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(mapToolCall(rs)); }
            return values;
        } catch (SQLException e) { throw failure("list run tool calls", e); }
    }

    public List<ApprovalRecord> approvalsForRun(String runId) {
        List<ApprovalRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM approvals WHERE run_id=? ORDER BY created_at")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(mapApproval(rs)); }
            return values;
        } catch (SQLException e) { throw failure("list run approvals", e); }
    }

    public boolean deleteMemory(String id) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement revisions = connection.prepareStatement(
                        "DELETE FROM memory_revisions WHERE memory_id=?")) {
                    revisions.setString(1, id); revisions.executeUpdate();
                }
                int deleted;
                try (PreparedStatement memory = connection.prepareStatement("DELETE FROM memories WHERE id=?")) {
                    memory.setString(1, id); deleted = memory.executeUpdate();
                }
                connection.commit();
                return deleted > 0;
            } catch (Exception e) { rollback(connection); throw e; }
        } catch (SQLException e) { throw failure("delete memory", e); }
        catch (Exception e) { throw new IllegalStateException("failed to delete memory", e); }
    }

    public List<String> deleteMemories(List<String> memoryIds) {
        List<String> ids = normalizedDeleteIds(memoryIds, "memory");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                requireAllRows(connection, "memories", ids, "memory");
                deleteRows(connection, "memory_revisions", "memory_id", ids);
                deleteRows(connection, "memory_sources", "memory_id", ids);
                deleteRows(connection, "memory_usage_feedback", "memory_id", ids);
                deleteRows(connection, "memory_conflicts", "memory_id", ids);
                deleteRows(connection, "memory_conflicts", "conflicting_memory_id", ids);
                deleteRows(connection, "memories", "id", ids);
                connection.commit();
                return ids;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("batch delete memories", e);
        }
    }

    public void markToolRunning(String id) {
        updateTool(id, ToolCallStatus.RUNNING, null, null, false);
    }

    public void completeTool(String id, String result) {
        updateTool(id, ToolCallStatus.COMPLETED, result, null, true);
    }

    public void failTool(String id, String error) {
        updateTool(id, ToolCallStatus.FAILED, null, error, true);
    }

    public boolean requeueRun(String runId, int nextStep) {
        return updateRun(runId, RunStatus.QUEUED, nextStep, null, false);
    }

    public boolean markRunStatus(String runId, RunStatus status) {
        return updateRun(runId, status, null, null, false);
    }

    /** Delegated agents share the root leader's workspace while retaining separate Run state. */
    public String workspaceOwnerRunId(String runId) {
        try (Connection connection = open()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT workspace_ref FROM plan_steps WHERE run_id=? " +
                            "AND workspace_ref IS NOT NULL AND workspace_ref<>'' ORDER BY updated_at DESC LIMIT 1")) {
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            }
            return workspaceOwnerRunId(connection, runId);
        } catch (SQLException e) {
            throw failure("resolve delegated workspace", e);
        }
    }

    public int memorySelectionsForRun(String runId) {
        if (runId == null || runId.isBlank()) return 0;
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM memory_usage_feedback WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) {
            throw failure("count run memory selections", e);
        }
    }

    public MemoryScope memoryScopeForRun(String runId) {
        if (runId == null || runId.isBlank()) return MemoryScope.project();
        String workspace = workspaceOwnerRunId(runId);
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT r.agent_profile_id,CASE " +
                        "WHEN EXISTS(SELECT 1 FROM collaboration_task_runs ctr WHERE ctr.run_id=r.id) " +
                        "THEN 'COLLABORATION' " +
                        "WHEN EXISTS(SELECT 1 FROM plan_steps step WHERE step.run_id=r.id) THEN 'PLAN' " +
                        "WHEN EXISTS(SELECT 1 FROM run_delegations d WHERE d.child_run_id=r.id) THEN 'DELEGATION' " +
                        "WHEN r.agent_profile_id IS NOT NULL THEN 'AGENT' ELSE 'CHAT' END task_type " +
                        "FROM runs r WHERE r.id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return MemoryScope.project();
                return new MemoryScope("PROJECT", rs.getString("agent_profile_id"), workspace,
                        rs.getString("task_type"));
            }
        } catch (SQLException e) {
            throw failure("resolve memory scope", e);
        }
    }

    public Optional<String> latestCollaborationRunId(String sessionId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT r.id FROM runs r JOIN run_collaboration_policies p ON p.run_id=r.id " +
                        "WHERE r.session_id=? ORDER BY r.created_at DESC LIMIT 1")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("resolve latest collaboration run", e);
        }
    }

    private String workspaceOwnerRunId(Connection connection, String runId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT workspace_owner_run_id FROM runs WHERE id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String owner = rs.getString(1);
                    if (owner != null && !owner.isBlank()) return owner;
                }
            }
        }
        return rootRunId(connection, runId);
    }

    private String latestWorkspaceOwner(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id,workspace_owner_run_id FROM runs WHERE session_id=? ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String owner = rs.getString("workspace_owner_run_id");
                return owner == null || owner.isBlank() ? rs.getString("id") : owner;
            }
        }
    }

    private String latestWorkspaceOwner(String sessionId) {
        try (Connection connection = open()) {
            return latestWorkspaceOwner(connection, sessionId);
        } catch (SQLException e) {
            throw failure("resolve latest workspace owner", e);
        }
    }

    /**
     * Parks a leader after it has observed that a delegated child is still active.
     * The child terminal transition requeues the leader through
     * {@link #requeueWaitingParentRuns(String)}.
     */
    public boolean waitForAgent(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE runs SET status=?,version=version+1 WHERE id=? AND status IN (?,?)")) {
            ps.setString(1, RunStatus.WAITING_AGENT.name());
            ps.setString(2, runId);
            ps.setString(3, RunStatus.QUEUED.name());
            ps.setString(4, RunStatus.WAITING_TOOL.name());
            boolean updated = ps.executeUpdate() == 1;
            if (updated) insertEvent(connection, runId, "run.waiting_agent", "{\"status\":\"WAITING_AGENT\"}");
            return updated;
        } catch (SQLException e) {
            throw failure("wait for delegated agent", e);
        }
    }

    /** Marks a submitted tool call as waiting on an external future condition. */
    public boolean markToolCallWaitingExternal(String toolCallId, String waitKind, String waitRef) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE tool_calls SET status=?,wait_kind=?,wait_ref=?,waiting_since=? WHERE id=? AND status=?")) {
            ps.setString(1, ToolCallStatus.WAITING_EXTERNAL.name());
            ps.setString(2, waitKind);
            ps.setString(3, waitRef);
            ps.setString(4, Instant.now().toString());
            ps.setString(5, toolCallId);
            ps.setString(6, ToolCallStatus.RUNNING.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw failure("mark tool call waiting external", e);
        }
    }

    /**
     * Atomically parks a deferred tool call and its parent Run. Keeping these
     * transitions in one SQLite transaction closes the window in which a child
     * can become terminal after the tool is parked but before the parent is
     * visible as WAITING_AGENT.
     */
    public boolean parkDeferredToolCallAndWaitParent(String toolCallId, String runId,
                                                      String waitKind, String waitRef) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                int toolUpdated;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE tool_calls SET status=?,wait_kind=?,wait_ref=?,waiting_since=? "
                                + "WHERE id=? AND run_id=? AND status=?")) {
                    ps.setString(1, ToolCallStatus.WAITING_EXTERNAL.name());
                    ps.setString(2, waitKind);
                    ps.setString(3, waitRef);
                    ps.setString(4, Instant.now().toString());
                    ps.setString(5, toolCallId);
                    ps.setString(6, runId);
                    ps.setString(7, ToolCallStatus.RUNNING.name());
                    toolUpdated = ps.executeUpdate();
                }
                if (toolUpdated != 1) {
                    connection.rollback();
                    return false;
                }
                int runUpdated;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE runs SET status=?,version=version+1 WHERE id=? AND status IN (?,?)")) {
                    ps.setString(1, RunStatus.WAITING_AGENT.name());
                    ps.setString(2, runId);
                    ps.setString(3, RunStatus.QUEUED.name());
                    ps.setString(4, RunStatus.WAITING_TOOL.name());
                    runUpdated = ps.executeUpdate();
                }
                if (runUpdated != 1) {
                    connection.rollback();
                    return false;
                }
                insertEvent(connection, runId, "tool.deferred", "{\"toolCallId\":\""
                        + escape(toolCallId) + "\",\"waitKind\":\"" + escape(waitKind)
                        + "\",\"waitRef\":\"" + escape(waitRef) + "\"}");
                insertEvent(connection, runId, "run.waiting_agent", "{\"status\":\"WAITING_AGENT\"}");
                connection.commit();
                return true;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("park deferred tool call and wait for agent", e);
        }
    }

    /** Tool calls parked on an external condition (e.g. a delegated child run). */
    public List<ToolCallRecord> waitingExternalToolCalls(String waitKind, String waitRef) {
        List<ToolCallRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM tool_calls WHERE status=? AND wait_kind=? AND wait_ref=? ORDER BY created_at")) {
            ps.setString(1, ToolCallStatus.WAITING_EXTERNAL.name());
            ps.setString(2, waitKind);
            ps.setString(3, waitRef);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapToolCall(rs));
            }
            return List.copyOf(values);
        } catch (SQLException e) {
            throw failure("list waiting external tool calls", e);
        }
    }

    /** Distinct child run refs parked by deferred CHILD_RUN tool calls (startup recovery). */
    public List<String> waitingExternalChildRunRefs() {
        List<String> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT wait_ref FROM tool_calls WHERE status=? AND wait_kind=? AND wait_ref IS NOT NULL")) {
            ps.setString(1, ToolCallStatus.WAITING_EXTERNAL.name());
            ps.setString(2, "CHILD_RUN");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(rs.getString(1));
            }
            return List.copyOf(values);
        } catch (SQLException e) {
            throw failure("list waiting external child run refs", e);
        }
    }

    /**
     * Atomically completes the original deferred tool call, appends the final
     * tool message to the parent session and requeues the parked parent. Only
     * the first resolver wins (WHERE status='WAITING_EXTERNAL'), so duplicate
     * terminal callbacks are idempotent no-ops.
     */
    public boolean completeDeferredToolCallAndAppendResult(String sessionId, String runId, String toolCallId,
                                                           String result, String metadataJson) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE tool_calls SET status=?,result=?,result_metadata_json=?,finished_at=? " +
                                "WHERE id=? AND status=?")) {
                    ps.setString(1, ToolCallStatus.COMPLETED.name());
                    ps.setString(2, result);
                    ps.setString(3, metadataJson == null ? "{}" : metadataJson);
                    ps.setString(4, Instant.now().toString());
                    ps.setString(5, toolCallId);
                    ps.setString(6, ToolCallStatus.WAITING_EXTERNAL.name());
                    updated = ps.executeUpdate();
                }
                if (updated == 0) {
                    connection.rollback();
                    return false;
                }
                String providerCallId = null;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT provider_call_id FROM tool_calls WHERE id=?")) {
                    ps.setString(1, toolCallId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) providerCallId = rs.getString(1);
                    }
                }
                insertMessage(connection, sessionId, runId, "tool", result == null ? "" : result,
                        null, providerCallId, null, false);
                insertEvent(connection, runId, "tool.deferred.resolved", "{\"toolCallId\":\""
                        + escape(toolCallId) + "\"}");
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE runs SET status=?,queued_at=?,version=version+1 WHERE id=? AND status=?")) {
                    ps.setString(1, RunStatus.QUEUED.name());
                    ps.setString(2, Instant.now().toString());
                    ps.setString(3, runId);
                    ps.setString(4, RunStatus.WAITING_AGENT.name());
                    ps.executeUpdate();
                }
                insertEvent(connection, runId, "run.queued", "{\"reason\":\"deferred_agent_result_resolved\"}");
                connection.commit();
                return true;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("complete deferred tool call", e);
        }
    }

    /** Requeues direct leaders that were parked awaiting the supplied child run. */
    public int requeueWaitingParentRuns(String childRunId) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                List<String> parents = new ArrayList<>();
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT parent_run_id FROM run_delegations WHERE child_run_id=?")) {
                    ps.setString(1, childRunId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) parents.add(rs.getString(1));
                    }
                }
                int resumed = 0;
                for (String parent : parents) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE runs SET status=?,queued_at=?,version=version+1 WHERE id=? AND status=?")) {
                        ps.setString(1, RunStatus.QUEUED.name());
                        ps.setString(2, Instant.now().toString());
                        ps.setString(3, parent);
                        ps.setString(4, RunStatus.WAITING_AGENT.name());
                        if (ps.executeUpdate() == 1) {
                            insertEvent(connection, parent, "run.queued", "{\"reason\":\"delegated_agent_terminal\"}");
                            resumed++;
                        }
                    }
                }
                connection.commit();
                return resumed;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("resume delegated parent runs", e);
        }
    }

    public boolean completeRun(String runId) {
        return updateRun(runId, RunStatus.COMPLETED, null, null, true);
    }

    public void recordModelUsage(String runId, String provider, int estimatedInputTokens,
                                 int inputTokens, int outputTokens, int cachedInputTokens) {
        recordModelUsage(runId, provider, "", estimatedInputTokens, inputTokens, outputTokens,
                cachedInputTokens, 0, 0, false);
    }

    public void recordModelUsage(String runId, String provider, String modelName, int estimatedInputTokens,
                                 int inputTokens, int outputTokens, int cachedInputTokens,
                                 long durationMs, int retryCount, boolean localModel) {
        recordModelUsage(runId, provider, modelName, estimatedInputTokens, inputTokens, outputTokens,
                cachedInputTokens, durationMs, retryCount, localModel, null);
    }

    public void recordModelUsage(String runId, String provider, String modelName, int estimatedInputTokens,
                                 int inputTokens, int outputTokens, int cachedInputTokens,
                                 long durationMs, int retryCount, boolean localModel, String reservationKey) {
        recordModelUsage(runId, provider, modelName, estimatedInputTokens, inputTokens, outputTokens,
                cachedInputTokens, durationMs, retryCount, localModel, reservationKey, 0, 0);
    }

    public void recordModelUsage(String runId, String provider, String modelName, int estimatedInputTokens,
                                 int inputTokens, int outputTokens, int cachedInputTokens,
                                 long durationMs, int retryCount, boolean localModel, String reservationKey,
                                 int reusablePrefixTokens, long ttftMs) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO model_usage(run_id,provider,estimated_input_tokens,input_tokens," +
                        "output_tokens,cached_input_tokens,model_name,duration_ms,retry_count,local_model," +
                        "reusable_prefix_tokens,ttft_ms,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            connection.setAutoCommit(false);
            ps.setString(1, runId);
            ps.setString(2, provider == null ? "unknown" : provider);
            ps.setInt(3, Math.max(0, estimatedInputTokens));
            ps.setInt(4, Math.max(0, inputTokens));
            ps.setInt(5, Math.max(0, outputTokens));
            ps.setInt(6, Math.max(0, cachedInputTokens));
            ps.setString(7, modelName == null ? "" : modelName);
            ps.setLong(8, Math.max(0, durationMs));
            ps.setInt(9, Math.max(0, retryCount));
            ps.setInt(10, localModel ? 1 : 0);
            ps.setInt(11, Math.max(0, Math.min(reusablePrefixTokens, Math.max(0, estimatedInputTokens))));
            ps.setLong(12, Math.max(0, ttftMs));
            ps.setString(13, Instant.now().toString());
            ps.executeUpdate();
            if (reservationKey != null && !reservationKey.isBlank()) {
                try (PreparedStatement release = connection.prepareStatement(
                        "DELETE FROM budget_reservations WHERE reservation_key=?")) {
                    release.setString(1, reservationKey);
                    release.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw failure("record model usage", e);
        }
    }

    public int modelTokensForRun(String runId) {
        return modelTokenUsageForRun(runId).totalTokens();
    }

    public ModelTokenUsage modelTokenUsageForRun(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(CASE WHEN input_tokens>0 THEN input_tokens " +
                        "ELSE estimated_input_tokens END),0),COALESCE(SUM(output_tokens),0) " +
                        "FROM model_usage WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new ModelTokenUsage(0, 0);
                return new ModelTokenUsage(rs.getInt(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            throw failure("read model usage", e);
        }
    }

    public AgentFeedback recordAgentFeedback(String projectKey, String agentProfileId, String planId, String stepId,
                                             String runId, String status, String validationStatus, double score,
                                             String failureClass, double evidenceQuality) {
        String project = normalizeProjectKey(projectKey);
        String now = Instant.now().toString();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO agent_feedback(id,project_key,agent_profile_id,plan_id,step_id,run_id,status," +
                        "validation_status,score,failure_class,evidence_quality,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(run_id,step_id) DO UPDATE SET " +
                        "status=excluded.status,validation_status=excluded.validation_status,score=excluded.score," +
                        "failure_class=excluded.failure_class,evidence_quality=excluded.evidence_quality")) {
            ps.setString(1, id("agent_feedback"));
            ps.setString(2, project);
            ps.setString(3, nullableText(agentProfileId));
            ps.setString(4, nullableText(planId));
            ps.setString(5, nullableText(stepId));
            ps.setString(6, requireText(runId, "runId", 120));
            ps.setString(7, status == null || status.isBlank() ? "UNKNOWN" : status.trim().toUpperCase());
            ps.setString(8, validationStatus == null || validationStatus.isBlank()
                    ? "UNKNOWN" : validationStatus.trim().toUpperCase());
            ps.setDouble(9, Math.max(0, Math.min(1, score)));
            ps.setString(10, failureClass == null ? "" : failureClass.trim());
            ps.setDouble(11, Math.max(0, Math.min(1, evidenceQuality)));
            ps.setString(12, now);
            ps.executeUpdate();
            return agentFeedback(runId, stepId).orElseThrow();
        } catch (SQLException e) {
            throw failure("record agent feedback", e);
        }
    }

    public Optional<AgentFeedback> agentFeedback(String runId, String stepId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM agent_feedback WHERE run_id=? AND step_id=?")) {
            ps.setString(1, runId);
            ps.setString(2, nullableText(stepId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new AgentFeedback(rs.getString("id"), rs.getString("project_key"),
                        rs.getString("agent_profile_id"), rs.getString("plan_id"), rs.getString("step_id"),
                        rs.getString("run_id"), rs.getString("status"), rs.getString("validation_status"),
                        rs.getDouble("score"), rs.getString("failure_class"),
                        rs.getDouble("evidence_quality"), instant(rs.getString("created_at")))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("read agent feedback", e);
        }
    }

    public boolean failRun(String runId, String error) {
        return updateRun(runId, RunStatus.FAILED, null, error, true);
    }

    public boolean cancelRun(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE runs SET status=?, finished_at=?, version=version+1 " +
                        "WHERE id=? AND status NOT IN (?,?,?)")) {
            connection.setAutoCommit(false);
            ps.setString(1, RunStatus.CANCELED.name());
            ps.setString(2, Instant.now().toString());
            ps.setString(3, runId);
            ps.setString(4, RunStatus.COMPLETED.name());
            ps.setString(5, RunStatus.FAILED.name());
            ps.setString(6, RunStatus.CANCELED.name());
            boolean changed = ps.executeUpdate() > 0;
            if (changed) {
                insertEvent(connection, runId, "run.canceled", "{}");
                closePendingApprovals(connection, runId);
                finalizeDelegationGraph(connection, runId, RunStatus.CANCELED, "run canceled");
            }
            connection.commit();
            return changed;
        } catch (SQLException e) {
            throw failure("cancel run", e);
        }
    }

    public List<String> delegatedRunTree(String rootRunId) {
        List<String> values = new ArrayList<>();
        values.add(rootRunId);
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "WITH RECURSIVE children(run_id) AS (" +
                        "SELECT child_run_id FROM run_delegations WHERE parent_run_id=? " +
                        "UNION ALL SELECT d.child_run_id FROM run_delegations d " +
                        "JOIN children c ON d.parent_run_id=c.run_id) SELECT run_id FROM children")) {
            ps.setString(1, rootRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(rs.getString(1));
            }
            return List.copyOf(values);
        } catch (SQLException e) {
            throw failure("read delegated run tree", e);
        }
    }

    public List<String> cancelRunTree(String rootRunId) {
        List<String> tree = delegatedRunTree(rootRunId);
        List<String> canceled = new ArrayList<>();
        for (int index = tree.size() - 1; index >= 0; index--) {
            String runId = tree.get(index);
            if (cancelRun(runId)) canceled.add(runId);
        }
        return List.copyOf(canceled);
    }

    public Optional<WorkingPlanRecord> latestWorkingPlan(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM run_working_plans WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new WorkingPlanRecord(rs.getString("run_id"),
                        rs.getInt("revision"), rs.getString("objective"), rs.getString("items_json"),
                        rs.getString("status"), rs.getString("completion_json"),
                        instant(rs.getString("created_at")), instant(rs.getString("updated_at")))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("read latest working plan", e);
        }
    }

    /** Upserts the latest working plan of a Run; every save bumps the revision. */
    public WorkingPlanRecord saveWorkingPlan(String runId, String objective, String itemsJson, String status) {
        return saveWorkingPlan(runId, objective, itemsJson, status, null);
    }

    /** Upserts the latest working plan of a Run; every save bumps the revision. */
    public WorkingPlanRecord saveWorkingPlan(String runId, String objective, String itemsJson, String status,
                                             String completionJson) {
        String now = Instant.now().toString();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO run_working_plans(run_id,revision,objective,items_json,status,completion_json,created_at,updated_at) "
                            + "VALUES(?,1,?,?,?,?,?,?) "
                            + "ON CONFLICT(run_id) DO UPDATE SET revision=revision+1,objective=excluded.objective,"
                            + "items_json=excluded.items_json,status=excluded.status,"
                            + "completion_json=excluded.completion_json,updated_at=excluded.updated_at")) {
                ps.setString(1, runId);
                ps.setString(2, objective == null ? "" : objective);
                ps.setString(3, itemsJson == null ? "[]" : itemsJson);
                ps.setString(4, status == null ? "ACTIVE" : status);
                ps.setString(5, completionJson);
                ps.setString(6, now);
                ps.setString(7, now);
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            throw failure("save working plan", e);
        }
        return latestWorkingPlan(runId).orElseThrow();
    }

    /** Saves a completion contract; a later save may only strengthen the contract. */
    public RunCompletionContractRecord saveCompletionContract(RunCompletionContractRecord contract) {
        String now = Instant.now().toString();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO run_completion_contracts(run_id,mode,requires_workspace_change,requires_tests," +
                            "required_test_families_json,write_scope_json,done_criteria_json,source,reason,created_at,updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(run_id) DO UPDATE SET mode=excluded.mode," +
                            "requires_workspace_change=excluded.requires_workspace_change," +
                            "requires_tests=excluded.requires_tests," +
                            "required_test_families_json=excluded.required_test_families_json," +
                            "write_scope_json=excluded.write_scope_json," +
                            "done_criteria_json=excluded.done_criteria_json," +
                            "source=excluded.source,reason=excluded.reason,updated_at=excluded.updated_at")) {
                ps.setString(1, contract.runId());
                ps.setString(2, contract.mode().name());
                ps.setInt(3, contract.requiresWorkspaceChange() ? 1 : 0);
                ps.setInt(4, contract.requiresTests() ? 1 : 0);
                ps.setString(5, listJson(contract.requiredTestFamilies()));
                ps.setString(6, listJson(contract.writeScope()));
                ps.setString(7, listJson(contract.doneCriteria()));
                ps.setString(8, contract.source() == null ? "" : contract.source());
                ps.setString(9, contract.reason() == null ? "" : contract.reason());
                ps.setString(10, now);
                ps.setString(11, now);
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            throw failure("save completion contract", e);
        }
        return completionContract(contract.runId()).orElseThrow();
    }

    public Optional<RunCompletionContractRecord> completionContract(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM run_completion_contracts WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapCompletionContract(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("read completion contract", e);
        }
    }

    private RunCompletionContractRecord mapCompletionContract(ResultSet rs) throws SQLException {
        return new RunCompletionContractRecord(rs.getString("run_id"),
                CompletionMode.valueOf(rs.getString("mode")),
                rs.getInt("requires_workspace_change") == 1,
                rs.getInt("requires_tests") == 1,
                jsonList(rs.getString("required_test_families_json")),
                jsonList(rs.getString("write_scope_json")),
                jsonList(rs.getString("done_criteria_json")),
                rs.getString("source"), rs.getString("reason"),
                instant(rs.getString("created_at")), instant(rs.getString("updated_at")));
    }

    public Optional<ReflectionRecord> latestReflection(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM run_reflections WHERE run_id=? ORDER BY created_at DESC,id DESC LIMIT 1")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new ReflectionRecord(rs.getString("id"),
                        rs.getString("run_id"), rs.getString("failure_class"), rs.getString("diagnosis"),
                        rs.getString("decision"), rs.getString("plan_patch_json"),
                        rs.getString("evidence_refs_json"), rs.getString("next_action"),
                        instant(rs.getString("created_at")))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("read latest reflection", e);
        }
    }

    public ReflectionRecord saveReflection(String runId, String failureClass, String diagnosis, String decision,
                                           String planPatchJson, String evidenceRefsJson, String nextAction) {
        String id = "reflection_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String now = Instant.now().toString();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO run_reflections(id,run_id,failure_class,diagnosis,decision,plan_patch_json,"
                        + "evidence_refs_json,next_action,created_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, runId);
            ps.setString(3, failureClass == null ? "FAILURE" : failureClass);
            ps.setString(4, diagnosis == null ? "" : diagnosis);
            ps.setString(5, decision == null ? "CHANGE_APPROACH" : decision);
            ps.setString(6, planPatchJson == null ? "[]" : planPatchJson);
            ps.setString(7, evidenceRefsJson == null ? "[]" : evidenceRefsJson);
            ps.setString(8, nextAction == null ? "" : nextAction);
            ps.setString(9, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("save reflection", e);
        }
        return new ReflectionRecord(id, runId, failureClass == null ? "FAILURE" : failureClass,
                diagnosis == null ? "" : diagnosis, decision == null ? "CHANGE_APPROACH" : decision,
                planPatchJson == null ? "[]" : planPatchJson,
                evidenceRefsJson == null ? "[]" : evidenceRefsJson,
                nextAction == null ? "" : nextAction, Instant.parse(now));
    }

    public long countRunEvents(String runId, String eventType) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM run_events WHERE run_id=? AND event_type=?")) {
            ps.setString(1, runId);
            ps.setString(2, eventType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw failure("count run events", e);
        }
    }

    public Optional<TaskDigestRecord> latestTaskDigest(String taskId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM collaboration_task_digests WHERE task_id=?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new TaskDigestRecord(rs.getString("task_id"),
                        rs.getInt("revision"), rs.getString("digest_json"), rs.getString("last_activity_id"),
                        instant(rs.getString("updated_at")))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("read latest task digest", e);
        }
    }

    public TaskDigestRecord saveTaskDigest(String taskId, String digestJson, String lastActivityId) {
        String now = Instant.now().toString();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO collaboration_task_digests(task_id,revision,digest_json,last_activity_id,updated_at) "
                            + "VALUES(?,1,?,?,?) "
                            + "ON CONFLICT(task_id) DO UPDATE SET revision=revision+1,digest_json=excluded.digest_json,"
                            + "last_activity_id=excluded.last_activity_id,updated_at=excluded.updated_at")) {
                ps.setString(1, taskId);
                ps.setString(2, digestJson == null ? "{}" : digestJson);
                ps.setString(3, lastActivityId);
                ps.setString(4, now);
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            throw failure("save task digest", e);
        }
        return latestTaskDigest(taskId).orElseThrow();
    }

    public List<DeliveryRecord> deliveriesForTask(String taskId) {
        List<DeliveryRecord> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM collaboration_deliveries WHERE task_id=? ORDER BY stage,attempt,created_at")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(new DeliveryRecord(rs.getString("id"), rs.getString("task_id"),
                        rs.getInt("stage"), rs.getInt("attempt"), rs.getString("run_id"),
                        rs.getString("manifest_json"), rs.getString("content_hash"), rs.getString("status"),
                        instant(rs.getString("created_at")), instant(rs.getString("accepted_at"))));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list collaboration deliveries", e);
        }
    }

    public DeliveryRecord saveDelivery(String taskId, int stage, int attempt, String runId,
                                       String manifestJson, String contentHash, String status) {
        String id = "delivery_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String now = Instant.now().toString();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO collaboration_deliveries(id,task_id,stage,attempt,run_id,manifest_json,"
                        + "content_hash,status,created_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, taskId);
            ps.setInt(3, stage);
            ps.setInt(4, attempt);
            ps.setString(5, runId);
            ps.setString(6, manifestJson == null ? "{}" : manifestJson);
            ps.setString(7, contentHash == null ? "" : contentHash);
            ps.setString(8, status == null ? "DELIVERED" : status);
            ps.setString(9, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("save delivery", e);
        }
        return deliveriesForTask(taskId).stream()
                .filter(delivery -> delivery.id().equals(id)).findFirst().orElseThrow();
    }

    public Optional<AcceptedSnapshotRecord> latestAcceptedSnapshot(String taskId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM collaboration_accepted_snapshots WHERE task_id=? ORDER BY created_at DESC,id DESC LIMIT 1")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new AcceptedSnapshotRecord(rs.getString("id"),
                        rs.getString("task_id"), rs.getString("snapshot_json"),
                        instant(rs.getString("created_at")))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("read latest accepted snapshot", e);
        }
    }

    public AcceptedSnapshotRecord saveAcceptedSnapshot(String taskId, String snapshotJson) {
        String id = "snapshot_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String now = Instant.now().toString();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO collaboration_accepted_snapshots(id,task_id,snapshot_json,created_at) VALUES(?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, taskId);
            ps.setString(3, snapshotJson == null ? "{}" : snapshotJson);
            ps.setString(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("save accepted snapshot", e);
        }
        return new AcceptedSnapshotRecord(id, taskId, snapshotJson == null ? "{}" : snapshotJson, Instant.parse(now));
    }

    /** Historical pass rate for an agent from plan validation feedback (PR8 routing signal). */
    public double agentPassRate(String projectKey, String agentProfileId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) AS total, "
                        + "SUM(CASE WHEN validation_status IN ('PASSED','VALIDATED','PASS','COMPLETED') THEN 1 ELSE 0 END) "
                        + "FROM agent_feedback WHERE project_key=? AND agent_profile_id=?")) {
            ps.setString(1, projectKey);
            ps.setString(2, agentProfileId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0.5;
                long total = rs.getLong("total");
                if (total == 0) return 0.5;
                return Math.min(1.0, Math.max(0.0, (double) rs.getLong(2) / total));
            }
        } catch (SQLException e) {
            throw failure("read agent pass rate", e);
        }
    }

    /** Active (non-terminal) run count for an agent (PR8 availability signal). */
    public long activeRunsForAgent(String agentProfileId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM runs WHERE agent_profile_id=? "
                        + "AND status NOT IN ('COMPLETED','FAILED','CANCELED')")) {
            ps.setString(1, agentProfileId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw failure("count active runs for agent", e);
        }
    }

    public Path databasePath() {
        return databasePath;
    }

    public String startModelAttempt(String runId, String provider, String modelName, int ordinal) {
        String attemptId = id("model_attempt");
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO model_attempts(id,run_id,provider,model_name,attempt_ordinal,status,started_at) " +
                        "VALUES(?,?,?,?,?,'RUNNING',?)")) {
            ps.setString(1, attemptId);
            ps.setString(2, runId == null ? "" : runId);
            ps.setString(3, provider == null ? "unknown" : provider);
            ps.setString(4, modelName == null ? "" : modelName);
            ps.setInt(5, Math.max(1, ordinal));
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
            return attemptId;
        } catch (SQLException e) {
            throw failure("start model attempt", e);
        }
    }

    public void finishModelAttempt(String attemptId, String status, Integer httpStatus, String error) {
        if (attemptId == null || attemptId.isBlank()) return;
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE model_attempts SET status=?,http_status=?,error=?,finished_at=? WHERE id=?")) {
            ps.setString(1, status);
            if (httpStatus == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setInt(2, httpStatus);
            ps.setString(3, error == null ? null : error.substring(0, Math.min(error.length(), 4_000)));
            ps.setString(4, Instant.now().toString());
            ps.setString(5, attemptId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("finish model attempt", e);
        }
    }

    public int modelRetriesForRun(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM model_attempts WHERE run_id=? AND status='RETRY'")) {
            connection.setAutoCommit(false);
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) {
            throw failure("count model retries", e);
        }
    }

    public List<MemoryUnit> managedMemoryUnits(String projectKey, int limit) {
        List<MemoryUnit> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM memories WHERE project_key=? ORDER BY enabled DESC,pinned DESC,updated_at DESC LIMIT ?")) {
            ps.setString(1, normalizeProjectKey(projectKey));
            ps.setInt(2, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(mapMemoryUnit(rs)); }
            return values;
        } catch (SQLException e) { throw failure("list managed memory units", e); }
    }

    /**
     * Returns a navigable projection of project Memory.  The projection deliberately derives links
     * from durable Memory content/tags, so the wiki never becomes a second unsynchronised source of truth.
     */
    public List<MemoryWikiPage> memoryWiki(String projectKey, String query, int limit) {
        List<MemoryUnit> all = managedMemoryUnits(projectKey, 500);
        String needle = query == null ? "" : query.trim().toLowerCase();
        List<MemoryUnit> selected = all.stream()
                .filter(memory -> needle.isBlank() || wikiSearchText(memory).contains(needle))
                .limit(Math.max(1, Math.min(limit, 200)))
                .toList();
        return wikiPages(all, selected);
    }

    public Optional<MemoryWikiPage> memoryWikiPage(String memoryId) {
        MemoryUnit selected = findMemoryUnit(memoryId).orElse(null);
        if (selected == null) return Optional.empty();
        List<MemoryWikiPage> pages = wikiPages(managedMemoryUnits(selected.projectKey(), 500), List.of(selected));
        return pages.stream().findFirst();
    }

    public Optional<ToolCallRecord> findToolCall(String id) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM tool_calls WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapToolCall(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find tool call", e); }
    }

    public ApprovalPolicy createApprovalPolicy(String scope, String sessionId, String projectKey,
                                               String toolName, String argumentsSha256) {
        String normalizedScope = scope == null ? "" : scope.trim().toUpperCase();
        if (!Set.of("SESSION", "PROJECT").contains(normalizedScope)) {
            throw new IllegalArgumentException("approval policy scope must be SESSION or PROJECT");
        }
        String id = id("approval_policy");
        Instant now = Instant.now();
        String resolvedSession = normalizedScope.equals("SESSION") ? requireText(sessionId, "sessionId", 160) : null;
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO approval_policies(id,scope,session_id,project_key,tool_name," +
                        "arguments_sha256,created_at) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, normalizedScope); ps.setString(3, resolvedSession);
            ps.setString(4, normalizeProjectKey(projectKey)); ps.setString(5, requireText(toolName, "toolName", 120));
            ps.setString(6, requireText(argumentsSha256, "argumentsSha256", 64)); ps.setString(7, now.toString());
            ps.executeUpdate();
            try (PreparedStatement find = connection.prepareStatement(
                    "SELECT * FROM approval_policies WHERE scope=? AND COALESCE(session_id,'')=COALESCE(?,'') " +
                            "AND project_key=? AND tool_name=? AND arguments_sha256=?")) {
                find.setString(1, normalizedScope); find.setString(2, resolvedSession);
                find.setString(3, normalizeProjectKey(projectKey)); find.setString(4, toolName);
                find.setString(5, argumentsSha256);
                try (ResultSet rs = find.executeQuery()) { if (rs.next()) return mapApprovalPolicy(rs); }
            }
            throw new IllegalStateException("approval policy was not persisted");
        } catch (SQLException e) { throw failure("create approval policy", e); }
    }

    public Optional<ApprovalPolicy> matchingApprovalPolicy(String sessionId, String projectKey,
                                                           String toolName, String argumentsSha256) {
        String sql = "SELECT * FROM approval_policies WHERE tool_name=? AND arguments_sha256=? " +
                "AND project_key=? AND (scope='PROJECT' OR (scope='SESSION' AND session_id=?)) " +
                "ORDER BY CASE scope WHEN 'SESSION' THEN 0 ELSE 1 END LIMIT 1";
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, toolName); ps.setString(2, argumentsSha256);
            ps.setString(3, normalizeProjectKey(projectKey)); ps.setString(4, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapApprovalPolicy(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("match approval policy", e); }
    }

    public List<ApprovalPolicy> approvalPolicies(String projectKey) {
        List<ApprovalPolicy> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM approval_policies WHERE project_key=? ORDER BY created_at DESC")) {
            ps.setString(1, normalizeProjectKey(projectKey));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(mapApprovalPolicy(rs)); }
            return values;
        } catch (SQLException e) { throw failure("list approval policies", e); }
    }

    public boolean deleteApprovalPolicy(String id) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM approval_policies WHERE id=?")) {
            ps.setString(1, id); return ps.executeUpdate() > 0;
        } catch (SQLException e) { throw failure("delete approval policy", e); }
    }

    public static String collaborationWorkspaceOwner(String rootTaskId) {
        String value = rootTaskId == null ? "" : rootTaskId.trim();
        if (value.length() <= 180 && SAFE_WORKSPACE_KEY.matcher(value).matches()) {
            return "collaboration_" + value;
        }
        return "collaboration_" + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private void reconcileCollaborationTaskWorkspaces() throws SQLException {
        Map<String, List<String>> ownersByRoot = new LinkedHashMap<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "WITH RECURSIVE task_tree(root_id,task_id) AS ("
                        + "SELECT id,id FROM collaboration_tasks WHERE parent_id IS NULL OR parent_id='' "
                        + "UNION ALL SELECT tree.root_id,child.id FROM collaboration_tasks child "
                        + "JOIN task_tree tree ON child.parent_id=tree.task_id), "
                        + "run_tree(root_id,run_id) AS (SELECT tree.root_id,link.run_id FROM task_tree tree "
                        + "JOIN collaboration_task_runs link ON link.task_id=tree.task_id "
                        + "UNION SELECT tree.root_id,delegation.child_run_id FROM run_tree tree "
                        + "JOIN run_delegations delegation ON delegation.parent_run_id=tree.run_id) "
                        + "SELECT tree.root_id,COALESCE(NULLIF(run.workspace_owner_run_id,''),run.id) owner "
                        + "FROM run_tree tree JOIN runs run ON run.id=tree.run_id "
                        + "ORDER BY tree.root_id,run.created_at,run.id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> owners = ownersByRoot.computeIfAbsent(rs.getString("root_id"), ignored -> new ArrayList<>());
                    String owner = rs.getString("owner");
                    if (!owners.contains(owner)) owners.add(owner);
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : ownersByRoot.entrySet()) {
            mergeCollaborationWorkspaces(entry.getKey(), entry.getValue());
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "WITH RECURSIVE task_tree(task_id) AS (SELECT id FROM collaboration_tasks WHERE id=? "
                            + "UNION ALL SELECT child.id FROM collaboration_tasks child "
                            + "JOIN task_tree tree ON child.parent_id=tree.task_id), "
                            + "run_tree(run_id) AS (SELECT link.run_id FROM collaboration_task_runs link "
                            + "JOIN task_tree tree ON tree.task_id=link.task_id "
                            + "UNION SELECT delegation.child_run_id FROM run_delegations delegation "
                            + "JOIN run_tree tree ON delegation.parent_run_id=tree.run_id) "
                            + "UPDATE runs SET workspace_owner_run_id=? WHERE id IN (SELECT run_id FROM run_tree)")) {
                for (String rootTaskId : ownersByRoot.keySet()) {
                    ps.setString(1, rootTaskId);
                    ps.setString(2, collaborationWorkspaceOwner(rootTaskId));
                    ps.addBatch();
                }
                ps.executeBatch();
                connection.commit();
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        }
    }

    private void mergeCollaborationWorkspaces(String rootTaskId, List<String> sourceOwners) {
        String targetOwner = collaborationWorkspaceOwner(rootTaskId);
        Path target = workspaceRoot.resolve(targetOwner).normalize();
        if (!target.startsWith(workspaceRoot)) {
            throw new IllegalStateException("Invalid collaboration workspace target");
        }
        for (String sourceOwner : sourceOwners) {
            if (targetOwner.equals(sourceOwner)) continue;
            Path source = workspaceRoot.resolve(sourceOwner).normalize();
            if (!source.startsWith(workspaceRoot) || !Files.isDirectory(source)) continue;
            try (Stream<Path> files = Files.walk(source)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    Path relative = source.relativize(file).normalize();
                    if (relative.isAbsolute() || relative.startsWith("..")
                            || relative.startsWith(Path.of(".paicli", "workspace-history"))) continue;
                    Path destination = target.resolve(relative).normalize();
                    if (!destination.startsWith(target)) continue;
                    Files.createDirectories(destination.getParent());
                    if (Files.exists(destination) && Files.mismatch(destination, file) != -1) {
                        Path history = target.resolve(".paicli").resolve("workspace-history")
                                .resolve("before-" + safeWorkspaceHistorySegment(sourceOwner))
                                .resolve(relative).normalize();
                        if (!history.startsWith(target)) {
                            throw new IllegalStateException("Invalid collaboration workspace history path");
                        }
                        Files.createDirectories(history.getParent());
                        Files.copy(destination, history, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    if (!Files.exists(destination) || Files.mismatch(destination, file) != -1) {
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to merge collaboration workspace " + sourceOwner, e);
            }
        }
    }

    private static String safeWorkspaceHistorySegment(String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.length() <= 120 ? safe : safe.substring(0, 120);
    }

    public boolean hasCompletedMutatingToolCall(String runId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM tool_calls WHERE run_id=? AND status='COMPLETED' "
                        + "AND effect<>'READ_ONLY' LIMIT 1")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw failure("check Run delivery tool evidence", e);
        }
    }

    public List<String> deleteApprovalPolicies(List<String> policyIds) {
        List<String> ids = normalizedDeleteIds(policyIds, "approval policy");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                requireAllRows(connection, "approval_policies", ids, "approval policy");
                deleteRows(connection, "approval_policies", "id", ids);
                connection.commit();
                return ids;
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("batch delete approval policies", e);
        }
    }

    public KnowledgeFeedback createKnowledgeFeedback(String projectKey, String documentName, int chunk,
                                                      boolean helpful, String note) {
        String id = id("knowledge_feedback");
        Instant now = Instant.now();
        String normalizedNote = note == null ? "" : note.trim();
        if (normalizedNote.length() > 2_000) normalizedNote = normalizedNote.substring(0, 2_000);
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO knowledge_feedback(id,project_key,document_name,chunk_index,helpful,note,created_at) " +
                        "VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, normalizeProjectKey(projectKey));
            ps.setString(3, requireText(documentName, "documentName", 200)); ps.setInt(4, Math.max(0, chunk));
            ps.setInt(5, helpful ? 1 : 0); ps.setString(6, normalizedNote);
            ps.setString(7, now.toString()); ps.executeUpdate();
            return new KnowledgeFeedback(id, normalizeProjectKey(projectKey), documentName, Math.max(0, chunk),
                    helpful, normalizedNote, now);
        } catch (SQLException e) { throw failure("create knowledge feedback", e); }
    }

    public List<KnowledgeFeedback> knowledgeFeedback(String projectKey) {
        List<KnowledgeFeedback> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM knowledge_feedback WHERE project_key=? ORDER BY created_at DESC")) {
            ps.setString(1, normalizeProjectKey(projectKey));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(new KnowledgeFeedback(rs.getString("id"), rs.getString("project_key"),
                        rs.getString("document_name"), rs.getInt("chunk_index"), rs.getInt("helpful") != 0,
                        rs.getString("note"), instant(rs.getString("created_at"))));
            }
            return values;
        } catch (SQLException e) { throw failure("list knowledge feedback", e); }
    }

    public Optional<MemoryUnit> findMemoryUnit(String id) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM memories WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapMemoryUnit(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find memory unit", e); }
    }

    public MemoryUnit setMemoryState(String id, Boolean pinned, Boolean enabled, boolean confirm) {
        StringBuilder sql = new StringBuilder("UPDATE memories SET updated_at=?");
        if (pinned != null) sql.append(",pinned=?");
        if (enabled != null) sql.append(",enabled=?");
        if (confirm) sql.append(",confirmed_at=?");
        sql.append(" WHERE id=?");
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int index = 1;
            String now = Instant.now().toString();
            ps.setString(index++, now);
            if (pinned != null) ps.setInt(index++, pinned ? 1 : 0);
            if (enabled != null) ps.setInt(index++, enabled ? 1 : 0);
            if (confirm) ps.setString(index++, now);
            ps.setString(index, id);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("memory not found: " + id);
            return findMemoryUnit(id).orElseThrow();
        } catch (SQLException e) { throw failure("update memory state", e); }
    }

    public List<MemoryRevision> memoryRevisions(String memoryId) {
        List<MemoryRevision> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM memory_revisions WHERE memory_id=? ORDER BY replaced_at DESC")) {
            ps.setString(1, memoryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapMemoryRevision(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list memory revisions", e); }
    }

    public List<MemorySource> memorySources(String memoryId) {
        List<MemorySource> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM memory_sources WHERE memory_id=? ORDER BY created_at DESC")) {
            ps.setString(1, memoryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(new MemorySource(rs.getString("id"), rs.getString("memory_id"),
                        rs.getString("source_type"), rs.getString("source_id"), rs.getString("source_revision"),
                        rs.getString("excerpt"), readStringList(rs.getString("source_message_ids_json")),
                        nullableLong(rs, "source_start_sequence"), nullableLong(rs, "source_end_sequence"),
                        instant(rs.getString("created_at"))));
            }
            return values;
        } catch (SQLException e) { throw failure("list memory sources", e); }
    }

    public List<MemoryConflict> memoryConflicts(String projectKey, String status, int limit) {
        List<MemoryConflict> values = new ArrayList<>();
        String normalizedStatus = status == null || status.isBlank() ? "OPEN" : status.trim().toUpperCase();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM memory_conflicts WHERE project_key=? AND status=? " +
                        "ORDER BY created_at DESC LIMIT ?")) {
            ps.setString(1, normalizeProjectKey(projectKey));
            ps.setString(2, normalizedStatus);
            ps.setInt(3, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(new MemoryConflict(rs.getString("id"), rs.getString("project_key"),
                        rs.getString("memory_id"), rs.getString("conflicting_memory_id"),
                        rs.getString("reason"), rs.getString("status"), instant(rs.getString("created_at")),
                        instant(rs.getString("resolved_at"))));
            }
            return values;
        } catch (SQLException e) { throw failure("list memory conflicts", e); }
    }

    public MemoryUnit restoreMemoryRevision(String memoryId, String revisionId) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                MemoryRevision revision = null;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT * FROM memory_revisions WHERE id=? AND memory_id=?")) {
                    ps.setString(1, revisionId); ps.setString(2, memoryId);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) revision = mapMemoryRevision(rs); }
                }
                if (revision == null) throw new IllegalArgumentException("memory revision not found: " + revisionId);
                MemoryUnit current;
                try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM memories WHERE id=?")) {
                    ps.setString(1, memoryId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("memory not found: " + memoryId);
                        current = mapMemoryUnit(rs);
                    }
                }
                insertMemoryRevision(connection, current, current.sourceRunId());
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE memories SET content=?,tags=?,layer=?,memory_type=?,confidence=?,updated_at=? WHERE id=?")) {
                    ps.setString(1, revision.content()); ps.setString(2, revision.tags());
                    ps.setString(3, revision.layer()); ps.setString(4, revision.memoryType());
                    ps.setDouble(5, revision.confidence()); ps.setString(6, Instant.now().toString());
                    ps.setString(7, memoryId); ps.executeUpdate();
                }
                connection.commit();
                return findMemoryUnit(memoryId).orElseThrow();
            } catch (Exception e) { rollback(connection); throw e; }
        } catch (Exception e) {
            throw e instanceof SQLException sql ? failure("restore memory revision", sql)
                    : e instanceof IllegalArgumentException argument ? argument
                    : new IllegalStateException("failed to restore memory revision", e);
        }
    }

    public MemoryUnit mergeMemories(String targetId, List<String> sourceIds) {
        List<String> sources = sourceIds == null ? List.of() : sourceIds.stream()
                .filter(value -> value != null && !value.isBlank() && !value.equals(targetId))
                .distinct().limit(20).toList();
        if (sources.isEmpty()) throw new IllegalArgumentException("sourceIds must contain another memory");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                MemoryUnit target = findMemoryUnitById(connection, targetId)
                        .orElseThrow(() -> new IllegalArgumentException("memory not found: " + targetId));
                java.util.LinkedHashSet<String> contents = new java.util.LinkedHashSet<>();
                contents.add(target.content());
                java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
                addTags(tags, target.tags());
                for (String sourceId : sources) {
                    MemoryUnit source = findMemoryUnitById(connection, sourceId)
                            .orElseThrow(() -> new IllegalArgumentException("memory not found: " + sourceId));
                    if (!target.projectKey().equals(source.projectKey())) {
                        throw new IllegalArgumentException("memories from different projects cannot be merged");
                    }
                    contents.add(source.content());
                    addTags(tags, source.tags());
                }
                String content = String.join("\n\n", contents);
                if (content.length() > 32_000) throw new IllegalArgumentException("merged memory exceeds 32000 characters");
                insertMemoryRevision(connection, target, target.sourceRunId());
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE memories SET content=?,tags=?,origin='manual',updated_at=? WHERE id=?")) {
                    update.setString(1, content); update.setString(2, String.join(",", tags));
                    update.setString(3, Instant.now().toString()); update.setString(4, targetId);
                    update.executeUpdate();
                }
                for (String sourceId : sources) {
                    try (PreparedStatement revisions = connection.prepareStatement(
                            "DELETE FROM memory_revisions WHERE memory_id=?")) {
                        revisions.setString(1, sourceId); revisions.executeUpdate();
                    }
                    try (PreparedStatement memory = connection.prepareStatement("DELETE FROM memories WHERE id=?")) {
                        memory.setString(1, sourceId); memory.executeUpdate();
                    }
                }
                connection.commit();
                return findMemoryUnit(targetId).orElseThrow();
            } catch (Exception e) { rollback(connection); throw e; }
        } catch (Exception e) {
            throw e instanceof SQLException sql ? failure("merge memories", sql)
                    : e instanceof IllegalArgumentException argument ? argument
                    : new IllegalStateException("failed to merge memories", e);
        }
    }

    private Optional<MemoryUnit> findMemoryUnitById(Connection connection, String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM memories WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapMemoryUnit(rs)) : Optional.empty();
            }
        }
    }

    private static void addTags(java.util.Set<String> values, String tags) {
        if (tags == null || tags.isBlank()) return;
        for (String value : tags.split(",")) if (!value.isBlank()) values.add(value.trim());
    }

    private static String checksum(String value) {
        return Integer.toUnsignedString((value == null ? "" : value).hashCode(), 36);
    }

    private static String excerpt(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.of(mapper.readValue(json, String[].class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private void insertMemoryRevision(Connection connection, MemoryUnit value, String sourceRunId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO memory_revisions(id,memory_id,content,tags,layer,memory_type,confidence," +
                        "replaced_at,source_run_id) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id("memory_revision")); ps.setString(2, value.id());
            ps.setString(3, value.content()); ps.setString(4, value.tags());
            ps.setString(5, value.layer()); ps.setString(6, value.memoryType());
            ps.setDouble(7, value.confidence()); ps.setString(8, Instant.now().toString());
            ps.setString(9, sourceRunId); ps.executeUpdate();
        }
    }

    private void insertMemorySource(Connection connection, String memoryId, String sourceType, String sourceId,
                                    String sourceRevision, String excerpt, List<String> sourceMessageIds,
                                    Long sourceStartSequence, Long sourceEndSequence) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO memory_sources(id,memory_id,source_type,source_id,source_revision,excerpt,created_at," +
                        "source_message_ids_json,source_start_sequence,source_end_sequence) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id("memory_source"));
            ps.setString(2, memoryId);
            ps.setString(3, sourceType == null || sourceType.isBlank() ? "manual" : sourceType);
            ps.setString(4, sourceId);
            ps.setString(5, sourceRevision == null || sourceRevision.isBlank() ? "1" : sourceRevision);
            ps.setString(6, excerpt == null ? "" : excerpt);
            ps.setString(7, Instant.now().toString());
            try {
                ps.setString(8, mapper.writeValueAsString(
                        sourceMessageIds == null ? List.of() : sourceMessageIds));
            } catch (Exception e) {
                throw new SQLException("failed to serialize memory source message ids", e);
            }
            if (sourceStartSequence == null) ps.setNull(9, java.sql.Types.BIGINT);
            else ps.setLong(9, sourceStartSequence);
            if (sourceEndSequence == null) ps.setNull(10, java.sql.Types.BIGINT);
            else ps.setLong(10, sourceEndSequence);
            ps.executeUpdate();
        }
    }

    private void recordMemoryConflict(Connection connection, String projectKey, String memoryId,
                                      String conflictingMemoryId, String reason) throws SQLException {
        try (PreparedStatement existing = connection.prepareStatement(
                "SELECT 1 FROM memory_conflicts WHERE project_key=? AND memory_id=? " +
                        "AND conflicting_memory_id=? AND status='OPEN'")) {
            existing.setString(1, projectKey);
            existing.setString(2, memoryId);
            existing.setString(3, conflictingMemoryId);
            try (ResultSet rs = existing.executeQuery()) {
                if (rs.next()) return;
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO memory_conflicts(id,project_key,memory_id,conflicting_memory_id,reason,status,created_at) " +
                        "VALUES(?,?,?,?,?,'OPEN',?)")) {
            ps.setString(1, id("memory_conflict"));
            ps.setString(2, projectKey);
            ps.setString(3, memoryId);
            ps.setString(4, conflictingMemoryId);
            ps.setString(5, reason);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    public List<ArtifactRecord> artifacts(String projectKey, int limit) {
        List<ArtifactRecord> values = new ArrayList<>();
        String sql = "SELECT a.* FROM artifacts a JOIN runs r ON r.id=a.run_id " +
                "JOIN sessions s ON s.id=r.session_id WHERE s.project_key=? " +
                "ORDER BY a.created_at DESC LIMIT ?";
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, normalizeProjectKey(projectKey));
            ps.setInt(2, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(mapArtifact(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list project artifacts", e); }
    }

    public boolean deleteArtifact(String artifactId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM artifacts WHERE id=?")) {
            ps.setString(1, artifactId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { throw failure("delete artifact", e); }
    }

    public List<ArtifactRecord> deleteArtifacts(List<String> artifactIds) {
        List<String> ids = normalizedDeleteIds(artifactIds, "artifact");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                Map<String, ArtifactRecord> found = new LinkedHashMap<>();
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT * FROM artifacts WHERE id IN (" + placeholders(ids.size()) + ")")) {
                    bindStrings(ps, ids);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ArtifactRecord artifact = mapArtifact(rs);
                            found.put(artifact.id(), artifact);
                        }
                    }
                }
                requireAllIds(ids, found.keySet(), "artifact");
                deleteRows(connection, "artifacts", "id", ids);
                connection.commit();
                return ids.stream().map(found::get).toList();
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("batch delete artifacts", e);
        }
    }

    public List<String> deleteRuns(List<String> runIds) {
        List<String> ids = normalizedDeleteIds(runIds, "run");
        List<String> attachmentPaths = new ArrayList<>();
        List<String> removableWorkspaceIds = new ArrayList<>();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                Map<String, RunStatus> statuses = new LinkedHashMap<>();
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT id,status FROM runs WHERE id IN (" + placeholders(ids.size()) + ")")) {
                    bindStrings(ps, ids);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) statuses.put(rs.getString("id"), RunStatus.valueOf(rs.getString("status")));
                    }
                }
                requireAllIds(ids, statuses.keySet(), "run");
                List<String> active = ids.stream().filter(id -> !statuses.get(id).terminal()).toList();
                if (!active.isEmpty()) {
                    throw new IllegalStateException("only terminal runs can be deleted: " + String.join(", ", active));
                }
                List<String> activeRelated = activeDelegationRuns(connection, ids);
                if (!activeRelated.isEmpty()) {
                    throw new IllegalStateException("runs with active delegated relatives cannot be deleted: "
                            + String.join(", ", activeRelated));
                }

                String in = placeholders(ids.size());
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT relative_path FROM input_attachments WHERE run_id IN (" + in + ") "
                                + "OR message_id IN (SELECT id FROM messages WHERE run_id IN (" + in + "))")) {
                    int next = bindStrings(ps, ids);
                    bindStrings(ps, ids, next);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) attachmentPaths.add(rs.getString(1));
                    }
                }

                deleteDelegationsForRuns(connection, ids);
                deleteRows(connection, "collaboration_task_runs", "run_id", ids);
                updateRunReferencesToNull(connection, "collaboration_triggers", "created_run_id", ids);
                deleteRows(connection, "evaluation_trials", "run_id", ids);
                deleteRows(connection, "evaluation_baselines", "source_run_id", ids);
                updateRunReferencesToNull(connection, "scheduled_tasks", "last_run_id", ids);
                deleteRows(connection, "notification_outbox", "run_id", ids);
                updateRunReferencesToNull(connection, "memories", "source_run_id", ids);
                updateRunReferencesToNull(connection, "memory_revisions", "source_run_id", ids);
                updateRunReferencesToNull(connection, "plans", "run_id", ids);
                updateRunReferencesToNull(connection, "plan_steps", "run_id", ids);
                updateRunReferencesToNull(connection, "async_jobs", "run_id", ids);
                deleteRows(connection, "agent_feedback", "run_id", ids);
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM input_attachments WHERE run_id IN (" + in + ") "
                                + "OR message_id IN (SELECT id FROM messages WHERE run_id IN (" + in + "))")) {
                    int next = bindStrings(ps, ids);
                    bindStrings(ps, ids, next);
                    ps.executeUpdate();
                }
                for (String table : List.of("model_usage", "model_attempts", "memory_usage_feedback",
                        "memory_extractions", "run_collaboration_policies", "approvals", "run_events",
                        "artifacts")) {
                    deleteRows(connection, table, "run_id", ids);
                }
                deleteRows(connection, "tool_calls", "run_id", ids);
                deleteRows(connection, "messages", "run_id", ids);
                List<String> affectedExpertThreadIds = new ArrayList<>();
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT DISTINCT thread_id FROM collaboration_expert_thread_runs WHERE run_id IN (" + in + ")")) {
                    bindStrings(ps, ids);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) affectedExpertThreadIds.add(rs.getString(1));
                    }
                }
                deleteRows(connection, "collaboration_expert_thread_runs", "run_id", ids);
                deleteRows(connection, "runs", "id", ids);
                for (String threadId : affectedExpertThreadIds) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE collaboration_expert_threads SET latest_run_id=" +
                                    "(SELECT run_id FROM collaboration_expert_thread_runs WHERE thread_id=? " +
                                    "ORDER BY ordinal DESC LIMIT 1), digest_json='{}', updated_at=? WHERE id=?")) {
                        ps.setString(1, threadId);
                        ps.setString(2, Instant.now().toString());
                        ps.setString(3, threadId);
                        ps.executeUpdate();
                    }
                }

                for (String id : ids) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "SELECT 1 FROM runs WHERE COALESCE(workspace_owner_run_id,id)=? LIMIT 1")) {
                        ps.setString(1, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) removableWorkspaceIds.add(id);
                        }
                    }
                }
                connection.commit();
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("batch delete runs", e);
        }

        for (String runId : ids) deleteTree(artifactRoot, artifactRoot.resolve(runId).normalize());
        for (String runId : removableWorkspaceIds) deleteTree(workspaceRoot, workspaceRoot.resolve(runId).normalize());
        for (String relativePath : attachmentPaths) {
            if (relativePath != null && !relativePath.isBlank()) {
                deleteTree(attachmentRoot, attachmentRoot.resolve(relativePath).normalize());
            }
        }
        return ids;
    }

    public long countRuns(RunStatus status) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM runs WHERE status=?")) {
            statement.setString(1, status.name());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0; }
        } catch (SQLException e) {
            throw failure("count runs", e);
        }
    }

    public long countPendingApprovals() {
        return countByStatus("approvals", ApprovalStatus.PENDING.name());
    }

    public long countPendingMemoryExtractions() {
        return countByStatus("memory_extractions", "PENDING");
    }

    private long countByStatus(String table, String status) {
        if (!Set.of("approvals", "memory_extractions").contains(table)) {
            throw new IllegalArgumentException("unsupported status counter");
        }
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE status=?")) {
            statement.setString(1, status);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0; }
        } catch (SQLException e) {
            throw failure("count " + table, e);
        }
    }

    private boolean hasActiveRun(String sessionId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM runs WHERE session_id=? AND status NOT IN (?,?,?) LIMIT 1")) {
            ps.setString(1, sessionId);
            ps.setString(2, RunStatus.COMPLETED.name());
            ps.setString(3, RunStatus.FAILED.name());
            ps.setString(4, RunStatus.CANCELED.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw failure("check active run", e);
        }
    }

    private Optional<SessionGroupRecord> findSessionGroup(String groupId) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM session_groups WHERE id=?")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapSessionGroup(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find session group", e);
        }
    }

    private String normalizeGroupId(String groupId) {
        if (groupId == null || groupId.isBlank()) return null;
        String normalized = groupId.trim();
        if (findSessionGroup(normalized).isEmpty()) {
            throw new IllegalArgumentException("session group not found: " + normalized);
        }
        return normalized;
    }

    private static String normalizeGroupName(String name) {
        return requireText(name, "name", 60);
    }

    private static boolean sessionExists(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM sessions WHERE id=?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean isInternalSession(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT is_internal FROM sessions WHERE id=?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) != 0;
            }
        }
    }

    private static List<String> normalizedDeleteIds(List<String> values, String entity) {
        if (values == null) throw new IllegalArgumentException(entity + " ids are required");
        List<String> ids = values.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) throw new IllegalArgumentException(entity + " ids are required");
        if (ids.size() > 100) throw new IllegalArgumentException("at most 100 " + entity + " ids can be deleted");
        return ids;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static int bindStrings(PreparedStatement statement, List<String> values) throws SQLException {
        return bindStrings(statement, values, 1);
    }

    private static int bindStrings(PreparedStatement statement, List<String> values, int start) throws SQLException {
        int index = start;
        for (String value : values) statement.setString(index++, value);
        return index;
    }

    private static void requireAllRows(Connection connection, String table, List<String> ids, String entity)
            throws SQLException {
        Set<String> found = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM " + table + " WHERE id IN (" + placeholders(ids.size()) + ")")) {
            bindStrings(ps, ids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) found.add(rs.getString(1));
            }
        }
        requireAllIds(ids, found, entity);
    }

    private static void requireAllIds(List<String> ids, Set<String> found, String entity) {
        List<String> missing = ids.stream().filter(id -> !found.contains(id)).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException(entity + " not found: " + String.join(", ", missing));
    }

    private static void deleteRows(Connection connection, String table, String column, List<String> ids)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + column + " IN (" + placeholders(ids.size()) + ")")) {
            bindStrings(ps, ids);
            ps.executeUpdate();
        }
    }

    private static void updateRunReferencesToNull(Connection connection, String table, String column,
                                                   List<String> runIds) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE " + table + " SET " + column + "=NULL WHERE " + column + " IN ("
                        + placeholders(runIds.size()) + ")")) {
            bindStrings(ps, runIds);
            ps.executeUpdate();
        }
    }

    private static void deleteDelegationsForRuns(Connection connection, List<String> runIds) throws SQLException {
        String predicate = "delegation_id IN (SELECT id FROM run_delegations WHERE parent_run_id IN ("
                + placeholders(runIds.size()) + ") OR child_run_id IN (" + placeholders(runIds.size()) + "))";
        for (String table : List.of("run_delegation_dependencies", "run_delegation_resources")) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + table + " WHERE " + predicate)) {
                int next = bindStrings(ps, runIds);
                bindStrings(ps, runIds, next);
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM run_delegations WHERE parent_run_id IN (" + placeholders(runIds.size())
                        + ") OR child_run_id IN (" + placeholders(runIds.size()) + ")")) {
            int next = bindStrings(ps, runIds);
            bindStrings(ps, runIds, next);
            ps.executeUpdate();
        }
    }

    private static List<String> activeDelegationRuns(Connection connection, List<String> runIds)
            throws SQLException {
        String sql = "WITH RECURSIVE related(id) AS ("
                + "SELECT id FROM runs WHERE id IN (" + placeholders(runIds.size()) + ") "
                + "UNION SELECT delegation.child_run_id FROM run_delegations delegation "
                + "JOIN related ON delegation.parent_run_id=related.id "
                + "UNION SELECT delegation.parent_run_id FROM run_delegations delegation "
                + "JOIN related ON delegation.child_run_id=related.id) "
                + "SELECT run.id FROM runs run JOIN related ON related.id=run.id "
                + "WHERE run.status NOT IN ('COMPLETED','FAILED','CANCELED') ORDER BY run.created_at";
        List<String> active = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindStrings(ps, runIds);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) active.add(rs.getString(1));
            }
        }
        return active;
    }

    private static void deleteBySessionRuns(Connection connection, String table, String sessionId)
            throws SQLException {
        deleteBySessionRuns(connection, table, sessionId, "run_id");
    }

    private static void deleteBySessionRuns(Connection connection, String table, String sessionId,
                                            String runColumn) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + runColumn + " IN (SELECT id FROM runs WHERE session_id=?)")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        }
    }

    private static void deletePlansForSession(Connection connection, String sessionId) throws SQLException {
        String predicate = "plan_id IN (SELECT id FROM plans WHERE session_id=? " +
                "OR run_id IN (SELECT id FROM runs WHERE session_id=?))";
        for (String table : List.of("plan_events", "plan_revisions", "validation_checks",
                "async_jobs", "plan_edges", "plan_steps")) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + table + " WHERE " + predicate)) {
                ps.setString(1, sessionId);
                ps.setString(2, sessionId);
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM plans WHERE session_id=? OR run_id IN (SELECT id FROM runs WHERE session_id=?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, sessionId);
            ps.executeUpdate();
        }
    }

    private static List<String> collectDelegatedSessions(Connection connection, String rootSessionId)
            throws SQLException {
        List<String> sessions = new ArrayList<>();
        sessions.add(rootSessionId);
        for (int index = 0; index < sessions.size(); index++) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT DISTINCT child_session_id FROM run_delegations " +
                            "WHERE parent_run_id IN (SELECT id FROM runs WHERE session_id=?)")) {
                ps.setString(1, sessions.get(index));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String child = rs.getString(1);
                        if (!sessions.contains(child)) sessions.add(child);
                    }
                }
            }
        }
        return sessions;
    }

    private static void rejectActiveRuns(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement active = connection.prepareStatement(
                "SELECT COUNT(*) FROM runs WHERE session_id=? AND status IN (?,?,?,?,?,?)")) {
            active.setString(1, sessionId);
            active.setString(2, RunStatus.QUEUED.name());
            active.setString(3, RunStatus.RUNNING.name());
            active.setString(4, RunStatus.WAITING_MODEL.name());
            active.setString(5, RunStatus.WAITING_TOOL.name());
            active.setString(6, RunStatus.WAITING_APPROVAL.name());
            active.setString(7, RunStatus.WAITING_AGENT.name());
            try (ResultSet rs = active.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    throw new IllegalStateException("Cannot delete a session with an active run");
                }
            }
        }
    }

    private static List<String> runIds(Connection connection, String sessionId) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement runs = connection.prepareStatement("SELECT id FROM runs WHERE session_id=?")) {
            runs.setString(1, sessionId);
            try (ResultSet rs = runs.executeQuery()) {
                while (rs.next()) values.add(rs.getString(1));
            }
        }
        return values;
    }

    private Optional<RunRecord> findRun(Connection connection, String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM runs WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRun(rs)) : Optional.empty();
            }
        }
    }

    private Optional<CollaborationPolicy> collaborationPolicy(Connection connection, String runId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM run_collaboration_policies WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapCollaborationPolicy(rs)) : Optional.empty();
            }
        }
    }

    /** Lists files produced in a Run's effective workspace without exposing host paths. */
    public List<WorkspaceFile> workspaceFiles(String runId, int requestedLimit) {
        String owner = workspaceOwnerRunId(runId);
        Path root = workspaceRoot.resolve(owner).normalize();
        if (!root.startsWith(workspaceRoot) || !Files.isDirectory(root)) return List.of();
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> workspaceFile(runId, owner, root, path))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(WorkspaceFile::modifiedAt).reversed()
                            .thenComparing(WorkspaceFile::path))
                    .limit(limit)
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list Run workspace files", e);
        }
    }

    private static WorkspaceFile workspaceFile(String runId, String owner, Path root, Path file) {
        try {
            Path relative = root.relativize(file).normalize();
            if (relative.isAbsolute() || relative.startsWith("..")) return null;
            return new WorkspaceFile(runId, owner, relative.toString().replace('\\', '/'),
                    Files.size(file), Files.getLastModifiedTime(file).toInstant());
        } catch (Exception ignored) {
            return null;
        }
    }

    private void closePendingApprovals(Connection connection, String runId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE approvals SET status=?,resolved_at=? WHERE run_id=? AND status=?")) {
            ps.setString(1, ApprovalStatus.DENIED.name());
            ps.setString(2, Instant.now().toString());
            ps.setString(3, runId);
            ps.setString(4, ApprovalStatus.PENDING.name());
            ps.executeUpdate();
        }
    }

    private Optional<CollaborationPolicy> collaborationPolicyForTree(Connection connection, String runId)
            throws SQLException {
        String current = runId;
        for (int i = 0; i < 8 && current != null && !current.isBlank(); i++) {
            Optional<CollaborationPolicy> policy = collaborationPolicy(connection, current);
            if (policy.isPresent()) return policy;
            current = parentRunId(connection, current).orElse(null);
        }
        return Optional.empty();
    }

    private boolean canClaimCollaborationRun(Connection connection, RunRecord candidate) throws SQLException {
        Optional<CollaborationPolicy> policy = collaborationPolicyForTree(connection, candidate.id());
        if (policy.isEmpty() || !policy.get().enabled() || policy.get().maxConcurrentAgentRuns() <= 0
                || policy.get().runId().equals(candidate.id())) {
            return true;
        }
        return activeDelegatedRunCount(connection, policy.get().runId())
                < policy.get().maxConcurrentAgentRuns();
    }

    private int activeDelegatedRunCount(Connection connection, String rootRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "WITH RECURSIVE tree(run_id) AS (" +
                        "SELECT child_run_id FROM run_delegations WHERE parent_run_id=? " +
                        "UNION SELECT delegation.child_run_id FROM run_delegations delegation " +
                        "JOIN tree ON delegation.parent_run_id=tree.run_id) " +
                        "SELECT COUNT(*) FROM runs run JOIN tree ON tree.run_id=run.id " +
                        "WHERE run.status NOT IN ('QUEUED','COMPLETED','FAILED','CANCELED')")) {
            ps.setString(1, rootRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private Optional<String> parentRunId(Connection connection, String childRunId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT parent_run_id FROM run_delegations WHERE child_run_id=?")) {
            ps.setString(1, childRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private String rootRunId(Connection connection, String runId) throws SQLException {
        String current = runId;
        for (int i = 0; i < 16; i++) {
            Optional<String> parent = parentRunId(connection, current);
            if (parent.isEmpty() || parent.get().isBlank()) return current;
            current = parent.get();
        }
        return current;
    }

    private Optional<SessionRecord> findSession(Connection connection, String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM sessions WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapSession(rs)) : Optional.empty();
            }
        }
    }

    private Optional<RunDelegationRecord> findDelegationByTool(Connection connection, String toolCallId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM run_delegations WHERE parent_tool_call_id=?")) {
            ps.setString(1, toolCallId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapDelegation(rs)) : Optional.empty();
            }
        }
    }

    private static int delegationDepth(Connection connection, String runId) throws SQLException {
        int depth = 0;
        String current = runId;
        while (depth < 16) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT parent_run_id FROM run_delegations WHERE child_run_id=?")) {
                ps.setString(1, current);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return depth;
                    current = rs.getString(1);
                    depth++;
                }
            }
        }
        return depth;
    }

    private void cleanupRunFiles(List<String> runIds) {
        for (String runId : runIds) {
            deleteTree(workspaceRoot, workspaceRoot.resolve(runId).normalize());
            deleteTree(artifactRoot, artifactRoot.resolve(runId).normalize());
        }
    }

    private static void deleteTree(Path root, Path target) {
        if (!target.startsWith(root) || !Files.exists(target)) return;
        try (var paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private void recoverInterruptedRuns() throws SQLException {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE runs SET status=?, queued_at=?, version=version+1 WHERE status IN (?,?,?)")) {
            ps.setString(1, RunStatus.QUEUED.name());
            ps.setString(2, Instant.now().toString());
            ps.setString(3, RunStatus.RUNNING.name());
            ps.setString(4, RunStatus.WAITING_MODEL.name());
            ps.setString(5, RunStatus.WAITING_TOOL.name());
            ps.executeUpdate();
        }
    }

    private List<String> resolveDelegationDependencies(Connection connection, String parentRunId,
                                                       List<String> dependencyRefs) throws SQLException {
        if (dependencyRefs == null || dependencyRefs.isEmpty()) return List.of();
        List<String> resolved = new ArrayList<>();
        for (String raw : dependencyRefs.stream().distinct().limit(50).toList()) {
            String reference = raw == null ? "" : raw.trim();
            if (reference.isBlank()) continue;
            List<String> matches = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id FROM run_delegations WHERE parent_run_id=? " +
                            "AND (id=? OR child_run_id=? OR plan_step_id=? OR agent_name=?) ORDER BY created_at")) {
                ps.setString(1, parentRunId);
                ps.setString(2, reference);
                ps.setString(3, reference);
                ps.setString(4, reference);
                ps.setString(5, reference);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) matches.add(rs.getString(1));
                }
            }
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("delegation dependency not found for this parent: " + reference);
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException("delegation dependency is ambiguous; use delegation_id or child_run_id: "
                        + reference);
            }
            if (!resolved.contains(matches.get(0))) resolved.add(matches.get(0));
        }
        return List.copyOf(resolved);
    }

    private static String resolveDelegatedWorkspaceOwner(Connection connection, String parentRunId,
                                                         String workspaceRef, String parentWorkspaceOwner,
                                                         String childRunId) throws SQLException {
        if (workspaceRef == null || workspaceRef.isBlank()) return parentWorkspaceOwner;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(r.workspace_owner_run_id,r.id) FROM run_delegations d " +
                        "JOIN runs r ON r.id=d.child_run_id WHERE d.parent_run_id=? AND d.workspace_ref=? " +
                        "ORDER BY d.created_at LIMIT 1")) {
            ps.setString(1, parentRunId);
            ps.setString(2, workspaceRef);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : childRunId;
            }
        }
    }

    private static String effectiveDelegationWorkspaceRef(String workspaceRef, String parentWorkspaceOwner) {
        if (workspaceRef == null || workspaceRef.isBlank()) return null;
        if (parentWorkspaceOwner != null && parentWorkspaceOwner.startsWith("collaboration_")) {
            String normalized = workspaceRef.replace('\\', '/');
            if (normalized.equals(parentWorkspaceOwner)
                    || List.of(normalized.split("/")).contains(parentWorkspaceOwner)) {
                return null;
            }
        }
        return workspaceRef;
    }

    private static void insertDelegationDependencies(Connection connection, String delegationId,
                                                     List<String> dependencyIds, Instant now) throws SQLException {
        if (dependencyIds.isEmpty()) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO run_delegation_dependencies(delegation_id,depends_on_delegation_id,created_at) " +
                        "VALUES(?,?,?)")) {
            for (String dependencyId : dependencyIds) {
                ps.setString(1, delegationId);
                ps.setString(2, dependencyId);
                ps.setString(3, now.toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void insertDelegationResources(Connection connection, String delegationId,
                                                  List<String> resources, String mode, Instant now)
            throws SQLException {
        if (resources.isEmpty()) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO run_delegation_resources(delegation_id,resource_key,access_mode,created_at) " +
                        "VALUES(?,?,?,?)")) {
            for (String resource : resources) {
                ps.setString(1, delegationId);
                ps.setString(2, resource);
                ps.setString(3, mode);
                ps.setString(4, now.toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void finalizeDelegationGraph(Connection connection, String childRunId, RunStatus status, String error)
            throws SQLException {
        RunDelegationRecord delegation = findDelegationByChild(connection, childRunId).orElse(null);
        if (delegation == null) return;
        String now = Instant.now().toString();
        String failureClass = delegationFailureClass(status, error);
        String resultJson = terminalDelegationResult(connection, delegation, status, error, failureClass);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE run_delegations SET status=?,result_json=?,failure_class=?,blocked_reason=NULL," +
                        "completed_at=? WHERE id=?")) {
            ps.setString(1, status.name());
            ps.setString(2, resultJson);
            ps.setString(3, failureClass);
            ps.setString(4, now);
            ps.setString(5, delegation.id());
            ps.executeUpdate();
        }
        requeueWaitingParent(connection, delegation.parentRunId(), childRunId);
        advanceDependentDelegations(connection, delegation.id());
    }

    private void advanceDependentDelegations(Connection connection, String completedDelegationId)
            throws SQLException {
        List<String> downstream = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT d.id FROM run_delegations d JOIN run_delegation_dependencies dep " +
                        "ON dep.delegation_id=d.id WHERE dep.depends_on_delegation_id=? AND d.status='BLOCKED'")) {
            ps.setString(1, completedDelegationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) downstream.add(rs.getString(1));
            }
        }
        for (String downstreamId : downstream) {
            RunDelegationRecord dependent = findDelegation(connection, downstreamId).orElse(null);
            if (dependent == null || !dependenciesTerminal(connection, downstreamId)) continue;
            boolean failedUpstream = hasFailedDependency(connection, downstreamId);
            if (!failedUpstream || "DEGRADE".equals(dependent.failurePolicy())) {
                appendDependencyContext(connection, dependent);
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE run_delegations SET status='QUEUED',blocked_reason=NULL WHERE id=? AND status='BLOCKED'")) {
                    ps.setString(1, downstreamId);
                    if (ps.executeUpdate() == 1) {
                        insertEvent(connection, dependent.childRunId(),
                                failedUpstream ? "agent.dependency_degraded" : "agent.dependencies_satisfied",
                                failedUpstream
                                        ? "{\"policy\":\"DEGRADE\"}"
                                        : "{\"status\":\"QUEUED\"}");
                    }
                }
            } else if ("REQUIRE_HUMAN".equals(dependent.failurePolicy())) {
                appendDependencyContext(connection, dependent);
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE run_delegations SET status='WAITING_HUMAN'," +
                                "blocked_reason='upstream delegation failed; human decision required' " +
                                "WHERE id=? AND status='BLOCKED'")) {
                    ps.setString(1, downstreamId);
                    if (ps.executeUpdate() == 1) {
                        insertEvent(connection, dependent.childRunId(), "agent.waiting_human",
                                "{\"reason\":\"upstream_failed\"}");
                    }
                }
            } else {
                cancelDependentDelegation(connection, dependent, "blocked by failed upstream delegation");
            }
        }
    }

    private void appendDependencyContext(Connection connection, RunDelegationRecord dependent)
            throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT upstream.id,upstream.agent_name,upstream.status,upstream.result_json " +
                        "FROM run_delegation_dependencies dep JOIN run_delegations upstream " +
                        "ON upstream.id=dep.depends_on_delegation_id WHERE dep.delegation_id=? " +
                        "ORDER BY dep.created_at")) {
            ps.setString(1, dependent.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("delegation_id", rs.getString("id"));
                    item.put("agent_name", rs.getString("agent_name"));
                    item.put("status", rs.getString("status"));
                    item.put("result_envelope", readJson(rs.getString("result_json")));
                    results.add(item);
                }
            }
        }
        String json;
        try {
            json = mapper.writeValueAsString(results);
        } catch (Exception e) {
            json = "[]";
        }
        String bounded = boundedText(json, 16_000);
        insertMessage(connection, dependent.childSessionId(), dependent.childRunId(), "user",
                "Upstream dependency results are now available. Use these persisted result envelopes as input; "
                        + "do not repeat completed upstream work.\n\n" + bounded,
                null, null, null, false);
        insertEvent(connection, dependent.childRunId(), "agent.dependencies_attached",
                "{\"count\":" + results.size() + "}");
    }

    private void cancelDependentDelegation(Connection connection, RunDelegationRecord delegation, String reason)
            throws SQLException {
        try (PreparedStatement run = connection.prepareStatement(
                "UPDATE runs SET status='CANCELED',error=?,finished_at=?,version=version+1 " +
                        "WHERE id=? AND status NOT IN ('COMPLETED','FAILED','CANCELED')")) {
            run.setString(1, reason);
            run.setString(2, Instant.now().toString());
            run.setString(3, delegation.childRunId());
            if (run.executeUpdate() == 1) {
                insertEvent(connection, delegation.childRunId(), "run.canceled",
                        "{\"reason\":\"" + escape(reason) + "\"}");
                finalizeDelegationGraph(connection, delegation.childRunId(), RunStatus.CANCELED, reason);
            }
        }
    }

    private static boolean dependenciesTerminal(Connection connection, String delegationId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM run_delegation_dependencies dep " +
                        "JOIN run_delegations upstream ON upstream.id=dep.depends_on_delegation_id " +
                        "WHERE dep.delegation_id=? AND upstream.status NOT IN ('COMPLETED','FAILED','CANCELED')")) {
            ps.setString(1, delegationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 0;
            }
        }
    }

    private static boolean hasFailedDependency(Connection connection, String delegationId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM run_delegation_dependencies dep " +
                        "JOIN run_delegations upstream ON upstream.id=dep.depends_on_delegation_id " +
                        "WHERE dep.delegation_id=? AND upstream.status IN ('FAILED','CANCELED') LIMIT 1")) {
            ps.setString(1, delegationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void requeueWaitingParent(Connection connection, String parentRunId, String childRunId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE runs SET status=?,queued_at=?,version=version+1 WHERE id=? AND status=?")) {
            ps.setString(1, RunStatus.QUEUED.name());
            ps.setString(2, Instant.now().toString());
            ps.setString(3, parentRunId);
            ps.setString(4, RunStatus.WAITING_AGENT.name());
            if (ps.executeUpdate() == 1) {
                insertEvent(connection, parentRunId, "run.queued",
                        "{\"reason\":\"delegated_agent_terminal\",\"childRunId\":\""
                                + escape(childRunId) + "\"}");
            }
        }
    }

    private String terminalDelegationResult(Connection connection, RunDelegationRecord delegation,
                                            RunStatus status, String error, String failureClass)
            throws SQLException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", 2);
        value.put("delegation_id", delegation.id());
        value.put("child_run_id", delegation.childRunId());
        value.put("status", status.name());
        value.put("failure_class", failureClass);
        value.put("summary", latestAssistantAnswer(connection, delegation.childSessionId()));
        RunEvidence evidence = delegationEvidence(connection, delegation.childRunId());
        value.put("artifacts", evidence.businessArtifacts().stream().map(artifact -> Map.of(
                "id", artifact.id(), "type", artifact.type(), "name", artifact.name(),
                "relative_path", artifact.relativePath(), "sha256", artifact.sha256())).toList());
        ModelTokenUsage usage = modelTokenUsageForRun(connection, delegation.childRunId());
        value.put("usage", Map.of("input_tokens", usage.inputTokens(),
                "output_tokens", usage.outputTokens(), "total_tokens", usage.totalTokens()));
        value.put("files_changed", evidence.filesChanged().stream().map(file -> Map.of(
                "path", file.path(), "tool_call_id", file.toolCallId(), "changed", file.changed())).toList());
        value.put("workspace_mutations", evidence.workspaceMutations().stream().map(mutation -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", mutation.source());
            item.put("tool_call_id", mutation.toolCallId());
            item.put("workspace_changed", mutation.workspaceChanged());
            item.put("ordinal", mutation.ordinal());
            if (mutation.command() != null && !mutation.command().isBlank()) item.put("command", mutation.command());
            return item;
        }).toList());
        value.put("commands_executed", evidence.commandsExecuted().stream().map(command -> Map.of(
                "tool_call_id", command.toolCallId(), "command", command.command(),
                "exit_code", command.exitCode() == null ? "" : command.exitCode(),
                "timed_out", command.timedOut())).toList());
        value.put("tests", evidence.tests().stream().map(test -> Map.of(
                "tool_call_id", test.toolCallId(), "family", test.family().name(),
                "command", test.command(), "status", test.status().name())).toList());
        value.put("findings", List.of());
        value.put("risks", status == RunStatus.COMPLETED ? List.of()
                : List.of(error == null || error.isBlank() ? status.name() : error));
        value.put("unresolved_items", status == RunStatus.COMPLETED ? List.of()
                : List.of(error == null || error.isBlank() ? status.name() : error));
        value.put("evidence", List.of("run_status:" + status.name(), "terminal_event"));
        value.put("confidence", status == RunStatus.COMPLETED ? 0.9 : 0.35);
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"version\":2,\"status\":\"" + status.name()
                    + "\",\"failure_class\":\"" + escape(failureClass) + "\"}";
        }
    }

    private static String latestAssistantAnswer(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT content FROM messages WHERE session_id=? AND role='assistant' " +
                        "AND content<>'' ORDER BY sequence DESC LIMIT 1")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "";
                String value = rs.getString(1);
                return value.length() <= 4_000 ? value : value.substring(0, 4_000)
                        + "\n[child agent result truncated; inspect artifacts or child session]";
            }
        }
    }

    private RunEvidence delegationEvidence(Connection connection, String runId) throws SQLException {
        List<RunEvidenceDecoder.ToolCall> calls = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id,tool_name,arguments,status,result,error,result_metadata_json "
                        + "FROM tool_calls WHERE run_id=? ORDER BY created_at")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    calls.add(new RunEvidenceDecoder.ToolCall(rs.getString("id"), rs.getString("tool_name"),
                            rs.getString("arguments"), ToolCallStatus.valueOf(rs.getString("status")),
                            rs.getString("result"), rs.getString("error"), rs.getString("result_metadata_json")));
                }
            }
        }
        List<ArtifactRecord> artifacts = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id,run_id,type,name,relative_path,size,sha256,created_at "
                        + "FROM artifacts WHERE run_id=? ORDER BY created_at")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    artifacts.add(new ArtifactRecord(rs.getString("id"), rs.getString("run_id"),
                            rs.getString("type"), rs.getString("name"), rs.getString("relative_path"),
                            rs.getLong("size"), rs.getString("sha256"), instant(rs.getString("created_at"))));
                }
            }
        }
        return new RunEvidenceDecoder(mapper).collect(calls, artifacts);
    }

    private JsonNode readJson(String value) {
        try {
            return mapper.readTree(value == null || value.isBlank() ? "{}" : value);
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }


    private String listJson(List<String> values) {
        try {
            return mapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> jsonList(String value) {
        try {
            JsonNode node = mapper.readTree(value == null || value.isBlank() ? "[]" : value);
            if (node == null || !node.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            node.forEach(item -> {
                if (item != null && item.isTextual() && !item.asText().isBlank()) result.add(item.asText().trim());
            });
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String boundedText(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "...";
    }

    private static String delegationFailureClass(RunStatus status, String error) {
        if (status == RunStatus.COMPLETED) return "";
        if (status == RunStatus.CANCELED) return "CANCELED";
        String lower = error == null ? "" : error.toLowerCase();
        if (lower.contains("timeout")) return "RETRYABLE_INFRA";
        if (lower.contains("budget")) return "BUDGET_EXHAUSTED";
        if (lower.contains("approval")) return "APPROVAL_DENIED";
        if (lower.contains("model")) return "MODEL";
        if (lower.contains("tool")) return "TOOL";
        return "FAILED";
    }

    private Optional<RunDelegationRecord> findDelegation(Connection connection, String delegationId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM run_delegations WHERE id=?")) {
            ps.setString(1, delegationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapDelegation(rs)) : Optional.empty();
            }
        }
    }

    private Optional<RunDelegationRecord> findDelegationByChild(Connection connection, String childRunId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM run_delegations WHERE child_run_id=?")) {
            ps.setString(1, childRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapDelegation(rs)) : Optional.empty();
            }
        }
    }

    private ModelTokenUsage modelTokenUsageForRun(Connection connection, String runId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(CASE WHEN input_tokens>0 THEN input_tokens " +
                        "ELSE estimated_input_tokens END),0),COALESCE(SUM(output_tokens),0) " +
                        "FROM model_usage WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new ModelTokenUsage(rs.getInt(1), rs.getInt(2)) : new ModelTokenUsage(0, 0);
            }
        }
    }

    private boolean updateRun(String runId, RunStatus status, Integer currentStep, String error, boolean terminal) {
        String sql = "UPDATE runs SET status=?, current_step=COALESCE(?,current_step), error=?, " +
                "queued_at=" + (status == RunStatus.QUEUED ? "?" : "queued_at") + ", " +
                "finished_at=" + (terminal ? "?" : "finished_at") + ", version=version+1 WHERE id=? " +
                "AND status NOT IN ('COMPLETED','FAILED','CANCELED')";
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            int index = 1;
            ps.setString(index++, status.name());
            if (currentStep == null) ps.setNull(index++, java.sql.Types.INTEGER); else ps.setInt(index++, currentStep);
            ps.setString(index++, error);
            if (status == RunStatus.QUEUED) ps.setString(index++, Instant.now().toString());
            if (terminal) ps.setString(index++, Instant.now().toString());
            ps.setString(index, runId);
            boolean changed = ps.executeUpdate() > 0;
            if (!changed) {
                connection.rollback();
                return false;
            }
            String eventType = terminal
                    ? "run." + status.name().toLowerCase()
                    : status == RunStatus.QUEUED ? "run.queued" : "run.status_changed";
            String eventData = error == null
                    ? "{\"status\":\"" + status.name() + "\"}"
                    : "{\"status\":\"" + status.name() + "\",\"error\":\"" + escape(error) + "\"}";
            insertEvent(connection, runId, eventType, eventData);
            if (terminal) finalizeDelegationGraph(connection, runId, status, error);
            connection.commit();
            return true;
        } catch (SQLException e) {
            throw failure("update run", e);
        }
    }

    private void updateTool(String id, ToolCallStatus status, String result, String error, boolean finished) {
        updateTool(id, status, result, error, "{}", finished);
    }

    private void updateTool(String id, ToolCallStatus status, String result, String error,
                            String metadataJson, boolean finished) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE tool_calls SET status=?, result=?, error=?, result_metadata_json=?, finished_at=? WHERE id=?")) {
            ps.setString(1, status.name());
            ps.setString(2, result);
            ps.setString(3, error);
            ps.setString(4, metadataJson == null ? "{}" : metadataJson);
            ps.setString(5, finished ? Instant.now().toString() : null);
            ps.setString(6, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("update tool", e);
        }
    }

    private MessageRecord insertMessage(Connection connection, String sessionId, String runId,
                                        String role, String content, String reasoningContent, String toolCallId,
                                        String toolCallsJson, boolean archived) throws SQLException {
        long sequence = nextSequence(connection, "messages", "session_id", sessionId);
        String id = id("msg");
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO messages(id,session_id,run_id,role,content,reasoning_content,tool_call_id,tool_calls_json,archived,sequence,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, sessionId);
            ps.setString(3, runId);
            ps.setString(4, role);
            ps.setString(5, content);
            ps.setString(6, reasoningContent);
            ps.setString(7, toolCallId);
            ps.setString(8, toolCallsJson);
            ps.setInt(9, archived ? 1 : 0);
            ps.setLong(10, sequence);
            ps.setString(11, now.toString());
            ps.executeUpdate();
        }
        return new MessageRecord(id, sessionId, runId, role, content, reasoningContent, toolCallId, toolCallsJson,
                archived, sequence, now);
    }

    private static void attachInputs(Connection connection, String sessionId, String runId,
                                     String messageId, List<String> attachmentIds) throws SQLException {
        if (attachmentIds == null || attachmentIds.isEmpty()) return;
        List<String> unique = attachmentIds.stream().filter(value -> value != null && !value.isBlank())
                .distinct().toList();
        if (unique.size() > 8) throw new IllegalArgumentException("at most 8 attachments are allowed per run");
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE input_attachments SET run_id=?,message_id=? " +
                        "WHERE id=? AND session_id=? AND run_id IS NULL")) {
            for (String attachmentId : unique) {
                ps.setString(1, runId);
                ps.setString(2, messageId);
                ps.setString(3, attachmentId);
                ps.setString(4, sessionId);
                if (ps.executeUpdate() != 1) {
                    throw new IllegalArgumentException("attachment is missing, already used, or belongs to another session");
                }
            }
        }
    }

    private RunEventRecord insertEvent(Connection connection, String runId, String type, String data) throws SQLException {
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO run_events(run_id,event_type,event_data,sequence,created_at) " +
                        "SELECT ?,?,?,COALESCE(MAX(sequence),0)+1,? FROM run_events WHERE run_id=?",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, runId);
            ps.setString(2, type);
            ps.setString(3, data);
            ps.setString(4, now.toString());
            ps.setString(5, runId);
            ps.executeUpdate();
            long sequence;
            try (PreparedStatement sequenceQuery = connection.prepareStatement(
                    "SELECT sequence FROM run_events WHERE id=last_insert_rowid()");
                 ResultSet sequenceResult = sequenceQuery.executeQuery()) {
                sequence = sequenceResult.next() ? sequenceResult.getLong(1) : 0;
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0;
                return new RunEventRecord(id, runId, type, data, sequence, now);
            }
        }
    }

    private long nextSequence(Connection connection, String table, String column, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(MAX(sequence),0)+1 FROM " + table + " WHERE " + column + "=?")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 1;
            }
        }
    }

    private void touchSession(Connection connection, String sessionId, Instant now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE sessions SET updated_at=? WHERE id=?")) {
            ps.setString(1, now.toString());
            ps.setString(2, sessionId);
            ps.executeUpdate();
        }
    }

    private Connection open() throws SQLException {
        return connections.open();
    }

    private static boolean runHasStatus(Connection connection, String runId, RunStatus status) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM runs WHERE id=? AND status=?")) {
            ps.setString(1, runId);
            ps.setString(2, status.name());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static SessionRecord mapSession(ResultSet rs) throws SQLException {
        return new SessionRecord(rs.getString("id"), rs.getString("title"), rs.getString("project_key"),
                rs.getString("group_id"), rs.getString("status"),
                instant(rs.getString("created_at")), instant(rs.getString("updated_at")));
    }

    private static SessionGroupRecord mapSessionGroup(ResultSet rs) throws SQLException {
        return new SessionGroupRecord(rs.getString("id"), rs.getString("name"),
                instant(rs.getString("created_at")), instant(rs.getString("updated_at")));
    }

    private static RunRecord mapRun(ResultSet rs) throws SQLException {
        return new RunRecord(rs.getString("id"), rs.getString("session_id"),
                RunStatus.valueOf(rs.getString("status")), rs.getString("input"), rs.getInt("current_step"),
                rs.getString("error"), rs.getString("thinking_mode"), rs.getString("reasoning_effort"),
                rs.getString("execution_shell"), rs.getInt("priority"),
                rs.getString("model_profile_id"), rs.getString("agent_profile_id"),
                rs.getInt("retry_count"),
                instant(rs.getString("created_at")), instant(rs.getString("started_at")),
                instant(rs.getString("finished_at")), rs.getLong("version"));
    }

    private static MessageRecord mapMessage(ResultSet rs) throws SQLException {
        return new MessageRecord(rs.getString("id"), rs.getString("session_id"), rs.getString("run_id"),
                rs.getString("role"), rs.getString("content"), rs.getString("reasoning_content"), rs.getString("tool_call_id"),
                rs.getString("tool_calls_json"), rs.getInt("archived") != 0, rs.getLong("sequence"),
                instant(rs.getString("created_at")));
    }

    private static RunEventRecord mapEvent(ResultSet rs) throws SQLException {
        return new RunEventRecord(rs.getLong("id"), rs.getString("run_id"), rs.getString("event_type"),
                rs.getString("event_data"), rs.getLong("sequence"), instant(rs.getString("created_at")));
    }

    private static ToolCallRecord mapToolCall(ResultSet rs) throws SQLException {
        String metadata = rs.getString("result_metadata_json");
        return new ToolCallRecord(rs.getString("id"), rs.getString("run_id"), rs.getString("provider_call_id"),
                rs.getString("tool_name"), rs.getString("arguments"), ToolCallStatus.valueOf(rs.getString("status")),
                rs.getString("result"), rs.getString("error"), rs.getString("idempotency_key"),
                rs.getInt("retry_count"), instant(rs.getString("created_at")), instant(rs.getString("finished_at")),
                metadata == null ? "{}" : metadata, rs.getString("wait_kind"), rs.getString("wait_ref"),
                instant(rs.getString("waiting_since")));
    }

    private static RunDelegationRecord mapDelegation(ResultSet rs) throws SQLException {
        return new RunDelegationRecord(rs.getString("id"), rs.getString("parent_run_id"),
                rs.getString("parent_tool_call_id"), rs.getString("child_session_id"),
                rs.getString("child_run_id"), rs.getString("agent_profile_id"),
                rs.getString("agent_name"), rs.getString("task"), rs.getString("plan_id"),
                rs.getString("plan_step_id"), rs.getString("envelope_json"), rs.getString("result_json"),
                rs.getString("status"), rs.getString("failure_class"), rs.getString("failure_policy"),
                rs.getString("blocked_reason"), rs.getString("workspace_ref"),
                instant(rs.getString("completed_at")), instant(rs.getString("created_at")));
    }

    private static CollaborationPolicy mapCollaborationPolicy(ResultSet rs) throws SQLException {
        return new CollaborationPolicy(rs.getString("run_id"), rs.getInt("enabled") != 0,
                rs.getString("complexity"), rs.getString("risk"),
                rs.getString("allowed_agent_profile_ids_json"), rs.getInt("max_experts"),
                rs.getInt("max_depth"), rs.getInt("max_child_runs"),
                rs.getInt("max_concurrent_agent_runs"),
                rs.getLong("max_estimated_tokens"), rs.getDouble("max_estimated_cost"),
                rs.getInt("allow_expert_delegation") != 0, rs.getInt("require_reviewer") != 0,
                rs.getInt("require_runner") != 0, instant(rs.getString("created_at")));
    }

    private static InputAttachmentRecord mapInputAttachment(ResultSet rs) throws SQLException {
        return new InputAttachmentRecord(rs.getString("id"), rs.getString("session_id"),
                rs.getString("run_id"), rs.getString("message_id"), rs.getString("name"),
                rs.getString("mime_type"), rs.getString("relative_path"), rs.getLong("size"),
                rs.getString("sha256"), instant(rs.getString("created_at")));
    }

    public record ToolCallDraft(String providerCallId, String toolName,
                                String arguments, String idempotencyKey, ToolEffect effect) { }

    public record DelegationOptions(List<String> dependencies, List<String> readSet, List<String> writeSet,
                                    String failurePolicy, String workspaceRef) {
        public static DelegationOptions defaults() {
            return new DelegationOptions(List.of(), List.of(), List.of(), "BLOCK_GRAPH", null);
        }

        DelegationOptions normalized() {
            List<String> normalizedDependencies = dependencies == null ? List.of() : dependencies.stream()
                    .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().limit(50).toList();
            String policy = failurePolicy == null || failurePolicy.isBlank()
                    ? "BLOCK_GRAPH" : failurePolicy.trim().toUpperCase();
            if (!Set.of("BLOCK_GRAPH", "DEGRADE", "REQUIRE_HUMAN").contains(policy)) {
                throw new IllegalArgumentException(
                        "failure_policy must be BLOCK_GRAPH, DEGRADE, or REQUIRE_HUMAN");
            }
            String workspace = nullableText(workspaceRef);
            if (workspace != null && workspace.length() > 500) {
                throw new IllegalArgumentException("workspace_ref is too long");
            }
            return new DelegationOptions(normalizedDependencies, normalizeResourceList(readSet),
                    normalizeResourceList(writeSet), policy, workspace);
        }
    }

    private static List<String> normalizeResourceList(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().replace('\\', '/').toLowerCase())
                .distinct().limit(100).toList();
    }


    public record ModelTokenUsage(int inputTokens, int outputTokens) {
        public int totalTokens() { return inputTokens + outputTokens; }
    }

    public record AgentFeedback(String id, String projectKey, String agentProfileId, String planId, String stepId,
                                String runId, String status, String validationStatus, double score,
                                String failureClass, double evidenceQuality, Instant createdAt) { }

    public record MemoryUnit(String id, String projectKey, String memoryKey, String content, String tags,
                             String layer, String memoryType, double confidence, String origin,
                             String sourceSessionId, String sourceRunId, String embeddingJson,
                             Instant createdAt, Instant updatedAt, Instant lastAccessedAt, int accessCount,
                             boolean pinned, boolean enabled, Instant confirmedAt,
                             String structuredPayload, String status, String sourceType, String sourceId,
                             String sourceRevision, Instant validFrom, Instant validTo, String supersedesId,
                             String checksum, String scopeType, String scopeAgentProfileId,
                             String scopeWorkspaceOwnerRunId, String scopeTaskType) { }

    public record MemoryScope(String scopeType, String agentProfileId,
                              String workspaceOwnerRunId, String taskType) {
        public static MemoryScope project() {
            return new MemoryScope("PROJECT", null, null, null);
        }
    }

    public record MemoryWikiPage(String id, String projectKey, String memoryKey, String title, String content,
                                  String tags, String layer, String memoryType, double confidence, String origin,
                                  String status, boolean pinned, boolean enabled, Instant confirmedAt, Instant updatedAt,
                                  List<MemoryWikiLink> outgoingLinks, List<MemoryWikiLink> incomingLinks) { }

    public record MemoryWikiLink(String id, String memoryKey, String title, String relation) { }

    public record MemoryRevision(String id, String memoryId, String content, String tags, String layer,
                                  String memoryType, double confidence, Instant replacedAt,
                                  String sourceRunId) { }
    public record MemorySource(String id, String memoryId, String sourceType, String sourceId,
                               String sourceRevision, String excerpt, List<String> sourceMessageIds,
                               Long sourceStartSequence, Long sourceEndSequence, Instant createdAt) { }
    public record MemoryConflict(String id, String projectKey, String memoryId, String conflictingMemoryId,
                                 String reason, String status, Instant createdAt, Instant resolvedAt) { }
    public record MemoryExtractionMessage(String id, long sequence, String role,
                                          String content, String toolCallId) { }

    public record ApprovalPolicy(String id, String scope, String sessionId, String projectKey,
                                 String toolName, String argumentsSha256, Instant createdAt) { }

    public record KnowledgeFeedback(String id, String projectKey, String documentName, int chunk,
                                    boolean helpful, String note, Instant createdAt) { }

    public record SessionSearchMessage(String id, String sessionId, String sessionTitle, String projectKey,
                                       String runId, String role, String content, long sequence,
                                       Instant createdAt, Instant sessionUpdatedAt) { }

    private Optional<ApprovalRecord> findApproval(String column, String value) {
        if (!column.equals("id") && !column.equals("tool_call_id")) {
            throw new IllegalArgumentException("Unsupported approval lookup");
        }
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM approvals WHERE " + column + "=?")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapApproval(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find approval", e);
        }
    }

    private static ApprovalRecord mapApproval(ResultSet rs) throws SQLException {
        return new ApprovalRecord(rs.getString("id"), rs.getString("run_id"), rs.getString("tool_call_id"),
                ApprovalStatus.valueOf(rs.getString("status")), rs.getString("reason"),
                instant(rs.getString("created_at")), instant(rs.getString("resolved_at")));
    }

    private static ArtifactRecord mapArtifact(ResultSet rs) throws SQLException {
        return new ArtifactRecord(rs.getString("id"), rs.getString("run_id"), rs.getString("type"),
                rs.getString("name"), rs.getString("relative_path"), rs.getLong("size"),
                rs.getString("sha256"), instant(rs.getString("created_at")));
    }

    private static MemoryRecord mapMemory(ResultSet rs) throws SQLException {
        return new MemoryRecord(rs.getString("id"), rs.getString("project_key"), rs.getString("memory_key"),
                rs.getString("content"), rs.getString("tags"), instant(rs.getString("created_at")),
                instant(rs.getString("updated_at")));
    }

    private static List<MemoryWikiPage> wikiPages(List<MemoryUnit> all, List<MemoryUnit> selected) {
        Map<String, MemoryUnit> byKey = new HashMap<>();
        for (MemoryUnit memory : all) byKey.put(memory.memoryKey().toLowerCase(), memory);
        Map<String, List<MemoryWikiLink>> outgoing = new HashMap<>();
        Map<String, List<MemoryWikiLink>> incoming = new HashMap<>();
        for (MemoryUnit memory : all) {
            for (MemoryWikiLink link : wikiLinks(memory, all, byKey)) {
                outgoing.computeIfAbsent(memory.id(), ignored -> new ArrayList<>()).add(link);
                incoming.computeIfAbsent(link.id(), ignored -> new ArrayList<>())
                        .add(new MemoryWikiLink(memory.id(), memory.memoryKey(), wikiTitle(memory), "referenced-by"));
            }
        }
        return selected.stream().map(memory -> new MemoryWikiPage(
                memory.id(), memory.projectKey(), memory.memoryKey(), wikiTitle(memory), memory.content(), memory.tags(),
                memory.layer(), memory.memoryType(), memory.confidence(), memory.origin(), memory.status(), memory.pinned(),
                memory.enabled(), memory.confirmedAt(), memory.updatedAt(),
                List.copyOf(outgoing.getOrDefault(memory.id(), List.of())),
                List.copyOf(incoming.getOrDefault(memory.id(), List.of())))).toList();
    }

    private static List<MemoryWikiLink> wikiLinks(MemoryUnit source, List<MemoryUnit> all,
                                                   Map<String, MemoryUnit> byKey) {
        Map<String, MemoryWikiLink> links = new LinkedHashMap<>();
        Matcher matcher = WIKI_LINK.matcher(source.content());
        while (matcher.find()) {
            MemoryUnit target = byKey.get(matcher.group(1).toLowerCase());
            if (target != null && !target.id().equals(source.id())) {
                links.put(target.id(), new MemoryWikiLink(target.id(), target.memoryKey(), wikiTitle(target), "explicit"));
            }
        }
        Set<String> sourceTags = wikiTags(source.tags());
        if (!sourceTags.isEmpty()) {
            for (MemoryUnit target : all) {
                if (target.id().equals(source.id()) || links.containsKey(target.id())) continue;
                Set<String> sharedTags = wikiTags(target.tags());
                sharedTags.retainAll(sourceTags);
                if (!sharedTags.isEmpty()) {
                    links.put(target.id(), new MemoryWikiLink(target.id(), target.memoryKey(), wikiTitle(target),
                            "tag:" + sharedTags.iterator().next()));
                }
            }
        }
        return links.values().stream().limit(12).toList();
    }

    private static String wikiSearchText(MemoryUnit memory) {
        return (memory.memoryKey() + " " + memory.content() + " " + memory.tags() + " " + memory.memoryType())
                .toLowerCase();
    }

    private static Set<String> wikiTags(String tags) {
        Set<String> values = new HashSet<>();
        if (tags == null || tags.isBlank()) return values;
        for (String tag : tags.split(",")) {
            String value = tag.trim().toLowerCase();
            if (!value.isBlank()) values.add(value);
        }
        return values;
    }

    private static String wikiTitle(MemoryUnit memory) {
        String content = WIKI_LINK.matcher(memory.content()).replaceAll("")
                .replaceAll("\\s+", " ").trim();
        if (content.isBlank()) content = memory.tags() == null ? "" : memory.tags().replace(',', ' ');
        int sentence = firstSentenceEnd(content);
        String title = content.substring(0, Math.min(content.length(), sentence)).trim();
        if (title.length() > 48) {
            int boundary = title.lastIndexOf(' ', 48);
            title = title.substring(0, boundary > 20 ? boundary : 48).trim() + "…";
        }
        return title.isBlank() ? "未命名记忆" : title;
    }

    private static int firstSentenceEnd(String content) {
        for (int index = 0; index < content.length(); index++) {
            char value = content.charAt(index);
            if (value == '。' || value == '！' || value == '？' || value == '.' || value == '!' || value == '?'
                    || value == ';' || value == '；' || value == '\n') return index + 1;
        }
        return content.length();
    }

    private static MemoryUnit mapMemoryUnit(ResultSet rs) throws SQLException {
        String lastAccessed = rs.getString("last_accessed_at");
        String confirmedAt = rs.getString("confirmed_at");
        return new MemoryUnit(rs.getString("id"), rs.getString("project_key"), rs.getString("memory_key"),
                rs.getString("content"), rs.getString("tags"), rs.getString("layer"),
                rs.getString("memory_type"), rs.getDouble("confidence"), rs.getString("origin"),
                rs.getString("source_session_id"), rs.getString("source_run_id"),
                rs.getString("embedding_json"), Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                lastAccessed == null || lastAccessed.isBlank() ? null : Instant.parse(lastAccessed),
                rs.getInt("access_count"), rs.getInt("pinned") != 0, rs.getInt("enabled") != 0,
                confirmedAt == null || confirmedAt.isBlank() ? null : Instant.parse(confirmedAt),
                rs.getString("structured_payload"), rs.getString("status"), rs.getString("source_type"),
                rs.getString("source_id"), rs.getString("source_revision"), instant(rs.getString("valid_from")),
                instant(rs.getString("valid_to")), rs.getString("supersedes_id"), rs.getString("checksum"),
                rs.getString("scope_type"), rs.getString("scope_agent_profile_id"),
                rs.getString("scope_workspace_owner_run_id"), rs.getString("scope_task_type"));
    }

    private static ApprovalPolicy mapApprovalPolicy(ResultSet rs) throws SQLException {
        return new ApprovalPolicy(rs.getString("id"), rs.getString("scope"), rs.getString("session_id"),
                rs.getString("project_key"), rs.getString("tool_name"),
                rs.getString("arguments_sha256"), instant(rs.getString("created_at")));
    }

    private static MemoryRevision mapMemoryRevision(ResultSet rs) throws SQLException {
        return new MemoryRevision(rs.getString("id"), rs.getString("memory_id"), rs.getString("content"),
                rs.getString("tags"), rs.getString("layer"), rs.getString("memory_type"),
                rs.getDouble("confidence"), instant(rs.getString("replaced_at")),
                rs.getString("source_run_id"));
    }

    private static String normalizeProjectKey(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim();
        if (!normalized.matches("[a-zA-Z0-9_.-]{1,80}")) {
            throw new IllegalArgumentException("projectKey must match [a-zA-Z0-9_.-]{1,80}");
        }
        return normalized;
    }

    private static MemoryScope defaultMemoryScope(MemoryScope source, String layer, String memoryType) {
        MemoryScope available = source == null ? MemoryScope.project() : source;
        String normalizedLayer = layer == null ? "L1" : layer.trim().toUpperCase();
        String normalizedType = memoryType == null ? "FACT" : memoryType.trim().toUpperCase();
        String scopeType;
        if (("L1".equals(normalizedLayer) || "EPISODIC".equals(normalizedType))
                && available.workspaceOwnerRunId() != null) {
            scopeType = "WORKSPACE";
        } else if (Set.of("PROCEDURAL", "LESSON").contains(normalizedType)
                && available.agentProfileId() != null) {
            scopeType = "AGENT";
        } else if (Set.of("PROCEDURAL", "LESSON").contains(normalizedType)
                && available.taskType() != null && !"CHAT".equals(available.taskType())) {
            scopeType = "TASK_TYPE";
        } else {
            scopeType = "PROJECT";
        }
        return new MemoryScope(scopeType, available.agentProfileId(), available.workspaceOwnerRunId(),
                available.taskType());
    }

    private static MemoryScope normalizeMemoryScope(MemoryScope scope) {
        MemoryScope value = scope == null ? MemoryScope.project() : scope;
        String type = value.scopeType() == null ? "PROJECT" : value.scopeType().trim().toUpperCase();
        if (!Set.of("PROJECT", "AGENT", "WORKSPACE", "TASK_TYPE").contains(type)) type = "PROJECT";
        String agent = nullableText(value.agentProfileId());
        String workspace = nullableText(value.workspaceOwnerRunId());
        String taskType = nullableText(value.taskType());
        if (("AGENT".equals(type) && agent == null)
                || ("WORKSPACE".equals(type) && workspace == null)
                || ("TASK_TYPE".equals(type) && taskType == null)) type = "PROJECT";
        return new MemoryScope(type, agent, workspace, taskType == null ? null : taskType.toUpperCase());
    }

    private String memoryScopePayload(MemoryScope scope) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scopeVersion", 1);
        payload.put("scopeType", scope.scopeType());
        if (scope.agentProfileId() != null) payload.put("agentProfileId", scope.agentProfileId());
        if (scope.workspaceOwnerRunId() != null) payload.put("workspaceOwnerRunId", scope.workspaceOwnerRunId());
        if (scope.taskType() != null) payload.put("taskType", scope.taskType());
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode memory scope", e);
        }
    }

    private static String normalizeThinkingMode(String value) {
        String normalized = value == null || value.isBlank() ? "auto" : value.trim().toLowerCase();
        if (!normalized.equals("auto") && !normalized.equals("enabled") && !normalized.equals("disabled")) {
            throw new IllegalArgumentException("thinkingMode must be auto, enabled, or disabled");
        }
        return normalized;
    }

    private static String normalizeReasoningEffort(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (!normalized.isBlank() && !normalized.equals("low")
                && !normalized.equals("high") && !normalized.equals("max")) {
            throw new IllegalArgumentException("reasoningEffort must be low, high, or max");
        }
        return normalized;
    }

    private static String normalizeExecutionShell(String value) {
        return com.paicli.platform.common.CommandShell.parse(value).value();
    }

    private static String normalizeEnum(String value, Set<String> allowed, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("unsupported value: " + value);
        }
        return normalized;
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }

    private static String nullableText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static void rollback(Connection connection) {
        try { connection.rollback(); } catch (SQLException ignored) { }
    }

    private static IllegalStateException failure(String action, SQLException e) {
        return new IllegalStateException("SQLite failed to " + action + ": " + e.getMessage(), e);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    public record CollaborationPolicy(String runId, boolean enabled, String complexity, String risk,
                                      String allowedAgentProfileIdsJson, int maxExperts, int maxDepth,
                                      int maxChildRuns, int maxConcurrentAgentRuns, long maxEstimatedTokens,
                                      double maxEstimatedCost,
                                      boolean allowExpertDelegation, boolean requireReviewer,
                                      boolean requireRunner, Instant createdAt) { }
    public record WorkspaceFile(String runId, String workspaceOwnerRunId, String path,
                                long size, Instant modifiedAt) { }
}
