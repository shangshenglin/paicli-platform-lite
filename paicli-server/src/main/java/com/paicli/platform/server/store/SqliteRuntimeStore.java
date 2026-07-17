package com.paicli.platform.server.store;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.common.ToolEffect;
import com.paicli.platform.common.ApprovalStatus;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.domain.ApprovalRecord;
import com.paicli.platform.server.domain.ArtifactRecord;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.InputAttachmentRecord;
import com.paicli.platform.server.domain.MemoryRecord;
import com.paicli.platform.server.domain.RunEventRecord;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.SessionRecord;
import com.paicli.platform.server.domain.SessionGroupRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class SqliteRuntimeStore {
    private final Path databasePath;
    private final Path workspaceRoot;
    private final Path artifactRoot;
    private final Path attachmentRoot;
    private final SqliteConnectionFactory connections;

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
            SqliteSchemaMigrator.ensureColumn(connection, "runs", "queued_at", "TEXT");
            SqliteSchemaMigrator.ensureColumn(connection, "runs", "priority", "INTEGER NOT NULL DEFAULT 0");
            SqliteSchemaMigrator.ensureColumn(connection, "runs", "model_profile_id", "TEXT");
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
            statement.execute("CREATE INDEX IF NOT EXISTS idx_memories_project ON memories(project_key, updated_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_revisions (" +
                    "id TEXT PRIMARY KEY, memory_id TEXT NOT NULL, content TEXT NOT NULL, tags TEXT NOT NULL, " +
                    "layer TEXT NOT NULL, memory_type TEXT NOT NULL, confidence REAL NOT NULL, " +
                    "replaced_at TEXT NOT NULL, source_run_id TEXT, FOREIGN KEY(memory_id) REFERENCES memories(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_memory_revisions_memory " +
                    "ON memory_revisions(memory_id, replaced_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS memory_extractions (" +
                    "run_id TEXT PRIMARY KEY, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, " +
                    "error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runs(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_memory_extractions_status " +
                    "ON memory_extractions(status, updated_at)");
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
                    "task TEXT NOT NULL, created_at TEXT NOT NULL, " +
                    "FOREIGN KEY(parent_run_id) REFERENCES runs(id), " +
                    "FOREIGN KEY(parent_tool_call_id) REFERENCES tool_calls(id), " +
                    "FOREIGN KEY(child_session_id) REFERENCES sessions(id), " +
                    "FOREIGN KEY(child_run_id) REFERENCES runs(id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_delegations_parent ON run_delegations(parent_run_id, created_at)");
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
                    "enabled INTEGER NOT NULL DEFAULT 1, next_run_at TEXT, last_run_at TEXT, last_run_id TEXT, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(project_key,name))");
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
            statement.execute("CREATE TABLE IF NOT EXISTS evaluation_executions (" +
                    "id TEXT PRIMARY KEY, suite_id TEXT NOT NULL, project_key TEXT NOT NULL, status TEXT NOT NULL, " +
                    "model_profile_id TEXT, trial_count INTEGER NOT NULL, pass_threshold INTEGER NOT NULL, " +
                    "average_score REAL, passed INTEGER, created_at TEXT NOT NULL, completed_at TEXT, " +
                    "FOREIGN KEY(suite_id) REFERENCES evaluation_suites(id))");
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
            statement.execute("CREATE TABLE IF NOT EXISTS evaluation_baselines (" +
                    "case_id TEXT PRIMARY KEY, source_run_id TEXT NOT NULL, response TEXT NOT NULL, " +
                    "tool_names_json TEXT NOT NULL, tokens INTEGER NOT NULL, duration_ms INTEGER NOT NULL, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, " +
                    "FOREIGN KEY(case_id) REFERENCES evaluation_cases(id) ON DELETE CASCADE)");
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
            SqliteSchemaMigrator.recordAppliedVersions(connection);
            statement.execute("UPDATE memory_extractions SET status='PENDING', updated_at='" +
                    Instant.now() + "' WHERE status='RUNNING'");
        }
        recoverInterruptedRuns();
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
                    deleteBySessionRuns(connection, "memory_extractions", currentSession);
                    deleteBySessionRuns(connection, "approvals", currentSession);
                    deleteBySessionRuns(connection, "tool_calls", currentSession);
                    deleteBySessionRuns(connection, "run_events", currentSession);
                    deleteBySessionRuns(connection, "artifacts", currentSession);
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
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO runs(id,session_id,status,input,current_step,thinking_mode," +
                                "reasoning_effort,priority,model_profile_id,retry_count,created_at,queued_at,version) " +
                                "VALUES(?,?,?,?,0,?,?,?,?,?,?,?,0)")) {
                    ps.setString(1, runId);
                    ps.setString(2, sessionId);
                    ps.setString(3, RunStatus.QUEUED.name());
                    ps.setString(4, input.trim());
                    ps.setString(5, resolvedThinking);
                    ps.setString(6, resolvedEffort);
                    ps.setInt(7, Math.max(-10, Math.min(priority, 10)));
                    ps.setString(8, modelProfileId == null || modelProfileId.isBlank() ? null : modelProfileId);
                    ps.setInt(9, Math.max(0, retryCount));
                    ps.setString(10, now.toString());
                    ps.setString(11, now.toString());
                    ps.executeUpdate();
                }
                MessageRecord userMessage = insertMessage(connection, sessionId, runId, "user", input.trim(),
                        null, null, null, false);
                attachInputs(connection, sessionId, runId, userMessage.id(), attachmentIds);
                insertEvent(connection, runId, "run.queued", "{\"runId\":\"" + runId + "\"}");
                touchSession(connection, sessionId, now);
                connection.commit();
                return new RunRecord(runId, sessionId, RunStatus.QUEUED, input.trim(), 0,
                        null, resolvedThinking, resolvedEffort, Math.max(-10, Math.min(priority, 10)),
                        modelProfileId, Math.max(0, retryCount), now, null, null, 0);
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
                RunRecord selected = null;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT r.* FROM runs r JOIN sessions s ON s.id=r.session_id WHERE r.status=? " +
                                "AND (SELECT COUNT(*) FROM runs active JOIN sessions owner ON owner.id=active.session_id " +
                                "WHERE owner.project_key=s.project_key AND active.status NOT IN ('QUEUED','COMPLETED','FAILED','CANCELED')) " +
                                "< COALESCE((SELECT max_concurrent_runs FROM budget_policies b " +
                                "WHERE b.project_key=s.project_key),4) " +
                                "ORDER BY r.priority DESC, queued_at, r.created_at LIMIT 1")) {
                    ps.setString(1, RunStatus.QUEUED.name());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) selected = mapRun(rs);
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
        String name = requireText(agentName, "agentName", 80);
        String input = requireText(task, "task", 32_000);
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
                Instant now = Instant.now();
                String childSessionId = id("session");
                String childRunId = id("run");
                try (PreparedStatement session = connection.prepareStatement(
                        "INSERT INTO sessions(id,title,project_key,group_id,status,is_internal,created_at,updated_at) " +
                                "VALUES(?,?,?,?,?,?,?,?)")) {
                    session.setString(1, childSessionId);
                    session.setString(2, "Agent: " + name);
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
                                "created_at,queued_at,version) VALUES(?,?,?,?,0,?,?,?,?,0)")) {
                    run.setString(1, childRunId);
                    run.setString(2, childSessionId);
                    run.setString(3, RunStatus.QUEUED.name());
                    run.setString(4, input);
                    run.setString(5, parent.thinkingMode());
                    run.setString(6, parent.reasoningEffort());
                    run.setString(7, now.toString());
                    run.setString(8, now.toString());
                    run.executeUpdate();
                }
                insertMessage(connection, childSessionId, childRunId, "user", input,
                        null, null, null, false);
                insertEvent(connection, childRunId, "run.queued", "{\"delegatedBy\":\"" + parentRunId + "\"}");
                String delegationId = id("delegation");
                try (PreparedStatement delegation = connection.prepareStatement(
                        "INSERT INTO run_delegations(id,parent_run_id,parent_tool_call_id,child_session_id," +
                                "child_run_id,agent_name,task,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
                    delegation.setString(1, delegationId);
                    delegation.setString(2, parentRunId);
                    delegation.setString(3, parentToolCallId);
                    delegation.setString(4, childSessionId);
                    delegation.setString(5, childRunId);
                    delegation.setString(6, name);
                    delegation.setString(7, input);
                    delegation.setString(8, now.toString());
                    delegation.executeUpdate();
                }
                insertEvent(connection, parentRunId, "agent.delegated", "{\"childRunId\":\""
                        + childRunId + "\",\"agentName\":\"" + escape(name) + "\"}");
                connection.commit();
                return new RunDelegationRecord(delegationId, parentRunId, parentToolCallId,
                        childSessionId, childRunId, name, input, now);
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
        String project = normalizeProjectKey(projectKey);
        String key = requireText(memoryKey, "memoryKey", 120);
        String value = requireText(content, "content", 32_000);
        String normalizedTags = tags == null ? "" : tags.trim();
        String normalizedLayer = Set.of("L1", "L2", "L3").contains(layer) ? layer : "L1";
        String normalizedType = memoryType == null || memoryType.isBlank() ? "FACT" : memoryType.trim().toUpperCase();
        double normalizedConfidence = Math.max(0, Math.min(1, confidence));
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                MemoryUnit existing = findMemoryUnit(connection, project, key).orElse(null);
                if (existing != null && !existing.content().equals(value)) {
                    try (PreparedStatement revision = connection.prepareStatement(
                            "INSERT INTO memory_revisions(id,memory_id,content,tags,layer,memory_type,confidence," +
                                    "replaced_at,source_run_id) VALUES(?,?,?,?,?,?,?,?,?)")) {
                        revision.setString(1, id("memory_revision"));
                        revision.setString(2, existing.id());
                        revision.setString(3, existing.content());
                        revision.setString(4, existing.tags());
                        revision.setString(5, existing.layer());
                        revision.setString(6, existing.memoryType());
                        revision.setDouble(7, existing.confidence());
                        revision.setString(8, now.toString());
                        revision.setString(9, runId);
                        revision.executeUpdate();
                    }
                }
                String memoryId = existing == null ? id("memory") : existing.id();
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO memories(id,project_key,memory_key,content,tags,created_at,updated_at," +
                                "layer,memory_type,confidence,origin,source_session_id,source_run_id,embedding_json) " +
                                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(project_key,memory_key) DO UPDATE SET " +
                                "content=excluded.content,tags=excluded.tags,updated_at=excluded.updated_at," +
                                "layer=excluded.layer,memory_type=excluded.memory_type,confidence=excluded.confidence," +
                                "origin='automatic',source_session_id=excluded.source_session_id," +
                                "source_run_id=excluded.source_run_id,embedding_json=excluded.embedding_json")) {
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
                    ps.executeUpdate();
                }
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
                "SELECT * FROM memories WHERE project_key=? AND enabled=1 " +
                        "ORDER BY pinned DESC, updated_at DESC LIMIT ?")) {
            ps.setString(1, normalizeProjectKey(projectKey));
            ps.setInt(2, Math.max(1, Math.min(limit, 500)));
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
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO memory_extractions(run_id,status,attempts,error,created_at,updated_at) " +
                        "VALUES(?,'PENDING',0,NULL,?,?)")) {
            ps.setString(1, runId);
            ps.setString(2, now);
            ps.setString(3, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("enqueue memory extraction", e);
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
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
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

    public boolean commitToolOutcome(String sessionId, String runId, ToolCallRecord call,
                                     boolean success, String modelContent, String error,
                                     String eventJson, int currentStep) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (!runHasStatus(connection, runId, RunStatus.WAITING_TOOL)) {
                    connection.rollback();
                    return false;
                }
                ToolCallStatus toolStatus = success ? ToolCallStatus.COMPLETED : ToolCallStatus.FAILED;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE tool_calls SET status=?,result=?,error=?,finished_at=? " +
                                "WHERE id=? AND status=?")) {
                    ps.setString(1, toolStatus.name());
                    ps.setString(2, success ? modelContent : null);
                    ps.setString(3, success ? null : error);
                    ps.setString(4, Instant.now().toString());
                    ps.setString(5, call.id());
                    ps.setString(6, ToolCallStatus.RUNNING.name());
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
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO model_usage(run_id,provider,estimated_input_tokens,input_tokens," +
                        "output_tokens,cached_input_tokens,model_name,duration_ms,retry_count,local_model,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
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
            ps.setString(11, Instant.now().toString());
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
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM((CASE WHEN input_tokens>0 THEN input_tokens " +
                        "ELSE estimated_input_tokens END)+output_tokens),0) FROM model_usage WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) {
            throw failure("read model usage", e);
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
            if (changed) insertEvent(connection, runId, "run.canceled", "{}");
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
                "SELECT COUNT(*) FROM runs WHERE session_id=? AND status IN (?,?,?,?,?)")) {
            active.setString(1, sessionId);
            active.setString(2, RunStatus.QUEUED.name());
            active.setString(3, RunStatus.RUNNING.name());
            active.setString(4, RunStatus.WAITING_MODEL.name());
            active.setString(5, RunStatus.WAITING_TOOL.name());
            active.setString(6, RunStatus.WAITING_APPROVAL.name());
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
            connection.commit();
            return true;
        } catch (SQLException e) {
            throw failure("update run", e);
        }
    }

    private void updateTool(String id, ToolCallStatus status, String result, String error, boolean finished) {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE tool_calls SET status=?, result=?, error=?, finished_at=? WHERE id=?")) {
            ps.setString(1, status.name());
            ps.setString(2, result);
            ps.setString(3, error);
            ps.setString(4, finished ? Instant.now().toString() : null);
            ps.setString(5, id);
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
                rs.getInt("priority"), rs.getString("model_profile_id"), rs.getInt("retry_count"),
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
        return new ToolCallRecord(rs.getString("id"), rs.getString("run_id"), rs.getString("provider_call_id"),
                rs.getString("tool_name"), rs.getString("arguments"), ToolCallStatus.valueOf(rs.getString("status")),
                rs.getString("result"), rs.getString("error"), rs.getString("idempotency_key"),
                rs.getInt("retry_count"), instant(rs.getString("created_at")), instant(rs.getString("finished_at")));
    }

    private static RunDelegationRecord mapDelegation(ResultSet rs) throws SQLException {
        return new RunDelegationRecord(rs.getString("id"), rs.getString("parent_run_id"),
                rs.getString("parent_tool_call_id"), rs.getString("child_session_id"),
                rs.getString("child_run_id"), rs.getString("agent_name"), rs.getString("task"),
                instant(rs.getString("created_at")));
    }

    private static InputAttachmentRecord mapInputAttachment(ResultSet rs) throws SQLException {
        return new InputAttachmentRecord(rs.getString("id"), rs.getString("session_id"),
                rs.getString("run_id"), rs.getString("message_id"), rs.getString("name"),
                rs.getString("mime_type"), rs.getString("relative_path"), rs.getLong("size"),
                rs.getString("sha256"), instant(rs.getString("created_at")));
    }

    public record ToolCallDraft(String providerCallId, String toolName,
                                String arguments, String idempotencyKey, ToolEffect effect) { }

    public record MemoryUnit(String id, String projectKey, String memoryKey, String content, String tags,
                             String layer, String memoryType, double confidence, String origin,
                             String sourceSessionId, String sourceRunId, String embeddingJson,
                             Instant createdAt, Instant updatedAt, Instant lastAccessedAt, int accessCount,
                             boolean pinned, boolean enabled, Instant confirmedAt) { }

    public record MemoryRevision(String id, String memoryId, String content, String tags, String layer,
                                 String memoryType, double confidence, Instant replacedAt,
                                 String sourceRunId) { }

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
                confirmedAt == null || confirmedAt.isBlank() ? null : Instant.parse(confirmedAt));
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

    private static String normalizeThinkingMode(String value) {
        String normalized = value == null || value.isBlank() ? "auto" : value.trim().toLowerCase();
        if (!normalized.equals("auto") && !normalized.equals("enabled") && !normalized.equals("disabled")) {
            throw new IllegalArgumentException("thinkingMode must be auto, enabled, or disabled");
        }
        return normalized;
    }

    private static String normalizeReasoningEffort(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (!normalized.isBlank() && !normalized.equals("high") && !normalized.equals("max")) {
            throw new IllegalArgumentException("reasoningEffort must be high or max");
        }
        return normalized;
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(name + " is too long");
        return normalized;
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
}
