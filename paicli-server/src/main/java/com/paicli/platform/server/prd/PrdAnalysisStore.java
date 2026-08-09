package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.SqliteConnectionFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Durable store for the PRD Analysis business agent. All PRD state is kept in
 * SQLite so the worker can recover deterministically from the database without
 * depending on in-memory phases or terminal run events.
 */
@Repository
public class PrdAnalysisStore {
    private static final Set<String> TASK_STATUSES = Set.of(
            "DRAFT", "INGESTING", "MAPPING", "ANALYZING", "RECONCILING", "VERIFYING",
            "WAITING_USER", "PACKAGING", "COMPLETED", "FAILED", "CANCELED");
    private static final Set<String> SOURCE_TYPES = Set.of("PRD", "SOURCE_CONTRACT", "SUPPORTING");
    private static final Set<String> NODE_STATUSES = Set.of("PENDING", "READY", "RUNNING", "COMPLETED", "FAILED");
    private static final Set<String> FINDING_TYPES = Set.of(
            "ENTITY", "BUSINESS_RULE", "FLOW", "STATE_TRANSITION", "FIELD_MAPPING",
            "CONDITION", "CONSTRAINT", "ASSUMPTION");
    private static final Set<String> FINDING_STATUSES = Set.of("ACTIVE", "MERGED", "REJECTED", "SUPERSEDED");
    private static final Set<String> DEPENDENCY_TYPES = Set.of("SEQUENCE", "DATA", "RULE", "REFERENCE");
    private static final Set<String> QUESTION_SEVERITIES = Set.of("BLOCKING", "WARNING", "INFO");
    private static final Set<String> FINDING_SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");
    private static final Set<String> QUESTION_STATUSES = Set.of("OPEN", "ANSWERED", "RESOLVED", "DISMISSED");
    private static final Set<String> RUN_PURPOSES = Set.of("MAP", "NODE_ANALYSIS", "RECONCILE");
    private static final int MAX_NODES = 50;
    private static final int MAX_PARALLELISM = 8;
    private static final int MAX_PAYLOAD_JSON = 256_000;
    private final SqliteConnectionFactory connections;
    private final ObjectMapper mapper;

    public PrdAnalysisStore(PlatformProperties properties, ObjectMapper mapper) {
        this.connections = new SqliteConnectionFactory(
                properties.dataDir().resolve("paicli.db").toAbsolutePath().normalize());
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // Tasks
    // ------------------------------------------------------------------

    public PrdTask createTask(String projectKey, String title, String createdBy, int maxParallelism, String sessionId) {
        String id = id("prd");
        Instant now = Instant.now();
        int parallelism = Math.max(1, Math.min(maxParallelism <= 0 ? 4 : maxParallelism, MAX_PARALLELISM));
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO prd_analysis_tasks(id,project_key,title,status,current_stage,max_parallelism," +
                        "session_id,created_by,created_at,updated_at,version) VALUES(?,?,?,?,?,?,?,?,?,?,0)")) {
            ps.setString(1, id);
            ps.setString(2, project(projectKey));
            ps.setString(3, text(title, "title", 240));
            ps.setString(4, "DRAFT");
            ps.setString(5, "DRAFT");
            ps.setInt(6, parallelism);
            ps.setString(7, nullable(sessionId));
            ps.setString(8, blank(createdBy) ? "USER" : createdBy.trim().toUpperCase());
            ps.setString(9, now.toString());
            ps.setString(10, now.toString());
            ps.executeUpdate();
            return task(id).orElseThrow();
        } catch (SQLException e) { throw failure("create prd task", e); }
    }

    public List<PrdTask> tasks(String projectKey, String requestedStatus, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        String sql = "SELECT * FROM prd_analysis_tasks WHERE project_key=?"
                + (blank(requestedStatus) ? "" : " AND status=?")
                + " ORDER BY updated_at DESC LIMIT ?";
        List<PrdTask> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            int index = 1;
            ps.setString(index++, project(projectKey));
            if (!blank(requestedStatus)) ps.setString(index++, requestedStatus.trim().toUpperCase());
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(task(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd tasks", e); }
    }

    public Optional<PrdTask> task(String id) {
        if (blank(id)) return Optional.empty();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_tasks WHERE id=?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(task(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find prd task", e); }
    }

    public Optional<PrdTask> taskForRun(String runId) {
        if (blank(runId)) return Optional.empty();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT t.* FROM prd_analysis_tasks t JOIN prd_analysis_runs r ON r.task_id=t.id "
                        + "WHERE r.run_id=?")) {
            ps.setString(1, runId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(task(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find prd task for run", e); }
    }

    public Optional<PrdTask> taskForSession(String sessionId) {
        if (blank(sessionId)) return Optional.empty();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT t.* FROM prd_analysis_tasks t JOIN prd_analysis_runs r ON r.task_id=t.id "
                        + "JOIN runs ru ON ru.id=r.run_id WHERE ru.session_id=?")) {
            ps.setString(1, sessionId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(task(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find prd task for session", e); }
    }

    /** Optimistic stage transition; only succeeds if the task is in the expected stage. */
    public boolean transitionStage(String taskId, String expectedStage, String nextStage) {
        String expected = expectedStage == null ? null : expectedStage.trim().toUpperCase();
        String next = normalizeStage(nextStage);
        Instant now = Instant.now();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_tasks SET current_stage=?,updated_at=?,version=version+1 "
                        + "WHERE id=? AND current_stage=? AND status NOT IN ('COMPLETED','FAILED','CANCELED')")) {
            ps.setString(1, next);
            ps.setString(2, now.toString());
            ps.setString(3, taskId);
            ps.setString(4, expected == null ? next : expected);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { throw failure("transition prd task stage", e); }
    }

    /** Set the business status. */
    public boolean updateTaskStatus(String taskId, String status, String lastError) {
        String normalized = normalizeTaskStatus(status);
        Instant now = Instant.now();
        String sql = "UPDATE prd_analysis_tasks SET status=?,"
                + "current_stage=CASE WHEN ? IN ('FAILED','CANCELED') THEN current_stage ELSE ? END,"
                + "updated_at=?,last_error=?,version=version+1 "
                + (normalized.equals("COMPLETED") ? ",completed_at=? " : " ")
                + "WHERE id=? AND status NOT IN ('COMPLETED','FAILED','CANCELED')";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            int index = 1;
            ps.setString(index++, normalized);
            ps.setString(index++, normalized);
            ps.setString(index++, normalized);
            ps.setString(index++, now.toString());
            ps.setString(index++, nullable(lastError));
            if (normalized.equals("COMPLETED")) ps.setString(index++, now.toString());
            ps.setString(index++, taskId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { throw failure("update prd task status", e); }
    }

    /** Reopens a failed task to its preserved stage so the user can retry it. */
    public boolean reopenTask(String taskId) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_tasks SET status=current_stage,last_error=NULL,updated_at=?,version=version+1 "
                        + "WHERE id=? AND status='FAILED'")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, taskId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { throw failure("reopen prd task", e); }
    }

    public void updateTaskSourceLinks(String taskId, String prdSourceId, String contractSourceId) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_tasks SET prd_source_id=?,source_contract_source_id=?,updated_at=? WHERE id=?")) {
            ps.setString(1, nullable(prdSourceId));
            ps.setString(2, nullable(contractSourceId));
            ps.setString(3, Instant.now().toString());
            ps.setString(4, taskId);
            ps.executeUpdate();
        } catch (SQLException e) { throw failure("update prd task source links", e); }
    }

    public boolean markTaskFailed(String taskId, String error) {
        return updateTaskStatus(taskId, "FAILED", error);
    }

    public boolean saveGlossary(String taskId, String glossaryJson) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_tasks SET glossary_json=?,updated_at=? WHERE id=?")) {
            ps.setString(1, json(glossaryJson));
            ps.setString(2, Instant.now().toString());
            ps.setString(3, taskId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { throw failure("save prd glossary", e); }
    }

    public boolean incrementReconcileIteration(String taskId) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_tasks SET reconcile_iteration=reconcile_iteration+1,updated_at=? WHERE id=?")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, taskId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { throw failure("increment prd reconcile iteration", e); }
    }

    public boolean claimTask(String taskId, String owner, Instant expiresAt) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_tasks SET claim_owner=?,claim_expires_at=?,updated_at=? "
                        + "WHERE id=? AND (claim_owner IS NULL OR claim_owner=? OR claim_expires_at IS NULL "
                        + "OR claim_expires_at<?)")) {
            String now = Instant.now().toString();
            ps.setString(1, owner);
            ps.setString(2, expiresAt.toString());
            ps.setString(3, now);
            ps.setString(4, taskId);
            ps.setString(5, owner);
            ps.setString(6, now);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { throw failure("claim prd task", e); }
    }

    public boolean releaseClaim(String taskId, String owner) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_tasks SET claim_owner=NULL,claim_expires_at=NULL WHERE id=? AND claim_owner=?")) {
            ps.setString(1, taskId);
            ps.setString(2, owner);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { throw failure("release prd task claim", e); }
    }

    public List<PrdTask> claimActiveTasks(String owner, Instant now, Instant leaseExpiry, int limit) {
        String select = "SELECT * FROM prd_analysis_tasks WHERE status NOT IN ('DRAFT','COMPLETED','FAILED','CANCELED') "
                + "AND (claim_owner IS NULL OR claim_expires_at IS NULL OR claim_expires_at<?) "
                + "ORDER BY updated_at ASC LIMIT ?";
        List<PrdTask> tasks = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(select)) {
            ps.setString(1, now.toString());
            ps.setInt(2, Math.max(1, Math.min(limit, 20)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tasks.add(task(rs));
            }
        } catch (SQLException e) { throw failure("claim active prd tasks", e); }
        List<PrdTask> claimed = new ArrayList<>();
        for (PrdTask value : tasks) {
            if (claimTask(value.id(), owner, leaseExpiry)) claimed.add(task(value.id()).orElse(value));
        }
        return claimed;
    }

    // ------------------------------------------------------------------
    // Sources + chunks
    // ------------------------------------------------------------------

    public PrdSource insertSource(String taskId, String attachmentId, String sourceType, String fileName,
                                  String contentHash, String extractionStatus, String textArtifactId) {
        String id = id("prdsrc");
        Instant now = Instant.now();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO prd_analysis_sources(id,task_id,attachment_id,source_type,file_name,content_hash," +
                        "extraction_status,text_artifact_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, taskId);
            ps.setString(3, attachmentId);
            ps.setString(4, normalizeSourceType(sourceType));
            ps.setString(5, value(fileName, 512));
            ps.setString(6, value(contentHash, 128));
            ps.setString(7, extractionStatus == null || extractionStatus.isBlank() ? "PENDING" : extractionStatus.trim().toUpperCase());
            ps.setString(8, nullable(textArtifactId));
            ps.setString(9, now.toString());
            ps.executeUpdate();
            return new PrdSource(id, taskId, attachmentId, normalizeSourceType(sourceType),
                    fileName, contentHash, "", textArtifactId, now);
        } catch (SQLException e) { throw failure("insert prd source", e); }
    }

    public List<PrdSource> sources(String taskId) {
        List<PrdSource> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_sources WHERE task_id=? ORDER BY created_at")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(source(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd sources", e); }
    }

    public Optional<PrdSource> source(String id) {
        if (blank(id)) return Optional.empty();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_sources WHERE id=?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(source(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find prd source", e); }
    }

    public void markSourceExtracted(String sourceId, String extractionStatus, String textArtifactId) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_sources SET extraction_status=?,text_artifact_id=? WHERE id=?")) {
            ps.setString(1, extractionStatus == null || extractionStatus.isBlank()
                    ? "COMPLETED" : extractionStatus.trim().toUpperCase());
            ps.setString(2, nullable(textArtifactId));
            ps.setString(3, sourceId);
            ps.executeUpdate();
        } catch (SQLException e) { throw failure("mark prd source extracted", e); }
    }

    public void insertChunks(String sourceId, List<ChunkDraft> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                insertChunksWithinTransaction(c, sourceId, chunks);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) { throw failure("insert prd chunks", e); }
    }

    /** Replaces an extracted source snapshot and its terminal extraction state atomically. */
    public void replaceChunksAndMarkExtracted(String sourceId, List<ChunkDraft> chunks,
                                              String extractionStatus, String textArtifactId) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement delete = c.prepareStatement(
                        "DELETE FROM prd_analysis_source_chunks WHERE source_id=?")) {
                    delete.setString(1, sourceId);
                    delete.executeUpdate();
                }
                insertChunksWithinTransaction(c, sourceId, chunks);
                try (PreparedStatement update = c.prepareStatement(
                        "UPDATE prd_analysis_sources SET extraction_status=?,text_artifact_id=? WHERE id=?")) {
                    update.setString(1, extractionStatus == null || extractionStatus.isBlank()
                            ? "COMPLETED" : extractionStatus.trim().toUpperCase());
                    update.setString(2, nullable(textArtifactId));
                    update.setString(3, sourceId);
                    update.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) { throw failure("replace prd source chunks", e); }
    }

    private void insertChunksWithinTransaction(Connection c, String sourceId, List<ChunkDraft> chunks)
            throws SQLException {
        if (chunks == null || chunks.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO prd_analysis_source_chunks(id,source_id,ordinal,heading,start_offset," +
                        "end_offset,text,content_hash) VALUES(?,?,?,?,?,?,?,?)")) {
            for (ChunkDraft draft : chunks) {
                ps.setString(1, id("prdchunk"));
                ps.setString(2, sourceId);
                ps.setInt(3, draft.ordinal());
                ps.setString(4, nullable(draft.heading()));
                ps.setInt(5, draft.startOffset());
                ps.setInt(6, draft.endOffset());
                ps.setString(7, draft.text());
                ps.setString(8, draft.contentHash());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public List<PrdChunk> chunks(String sourceId, int offset, int limit) {
        int resolvedOffset = Math.max(0, offset);
        int resolvedLimit = Math.max(1, Math.min(limit, 100));
        List<PrdChunk> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_source_chunks WHERE source_id=? ORDER BY ordinal LIMIT ? OFFSET ?")) {
            ps.setString(1, sourceId);
            ps.setInt(2, resolvedLimit);
            ps.setInt(3, resolvedOffset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(chunk(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd chunks", e); }
    }

    /** Internal full-snapshot reader. Model-facing tools remain paged through {@link #chunks}. */
    public List<PrdChunk> allChunks(String sourceId) {
        List<PrdChunk> values = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<PrdChunk> page = chunks(sourceId, offset, 100);
            values.addAll(page);
            if (page.size() < 100) return values;
            offset += page.size();
        }
    }

    public Optional<PrdChunk> chunk(String id) {
        if (blank(id)) return Optional.empty();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_source_chunks WHERE id=?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(chunk(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find prd chunk", e); }
    }

    public List<PrdChunk> chunksForRange(String sourceId, int startOrdinal, int endOrdinal) {
        List<PrdChunk> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_source_chunks WHERE source_id=? AND ordinal>=? AND ordinal<=? ORDER BY ordinal")) {
            ps.setString(1, sourceId);
            ps.setInt(2, startOrdinal);
            ps.setInt(3, endOrdinal);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(chunk(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd chunk range", e); }
    }

    // ------------------------------------------------------------------
    // Nodes + dependencies
    // ------------------------------------------------------------------

    public List<PrdNode> nodes(String taskId) {
        List<PrdNode> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_nodes WHERE task_id=? ORDER BY created_at,client_key")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(node(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd nodes", e); }
    }

    public Optional<PrdNode> node(String id) {
        if (blank(id)) return Optional.empty();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_nodes WHERE id=?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(node(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find prd node", e); }
    }

    public boolean updateNodeStatus(String nodeId, String status) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_nodes SET status=?,updated_at=? WHERE id=?")) {
            ps.setString(1, normalizeNodeStatus(status));
            ps.setString(2, Instant.now().toString());
            ps.setString(3, nodeId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { throw failure("update prd node status", e); }
    }

    public List<PrdDependency> dependencies(String taskId) {
        List<PrdDependency> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_node_dependencies WHERE task_id=?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(dependency(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd dependencies", e); }
    }

    public List<PrdDependency> incomingDependencies(String taskId, String nodeId) {
        List<PrdDependency> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_node_dependencies WHERE task_id=? AND to_node_id=?")) {
            ps.setString(1, taskId);
            ps.setString(2, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(dependency(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd incoming dependencies", e); }
    }

    public boolean nodeReady(String taskId, String nodeId) {
        List<PrdDependency> incoming = incomingDependencies(taskId, nodeId);
        if (incoming.isEmpty()) return true;
        for (PrdDependency dependency : incoming) {
            PrdNode from = node(dependency.fromNodeId()).orElse(null);
            if (from == null || !"COMPLETED".equals(from.status())) return false;
        }
        return true;
    }

    public long countNodesByStatus(String taskId, String status) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM prd_analysis_nodes WHERE task_id=? AND status=?")) {
            ps.setString(1, taskId);
            ps.setString(2, normalizeNodeStatus(status));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) { throw failure("count prd nodes", e); }
    }

    // ------------------------------------------------------------------
    // Findings + evidence
    // ------------------------------------------------------------------

    public List<PrdFinding> findings(String taskId, String type, String nodeId, String status, int offset, int limit) {
        int resolvedLimit = Math.max(1, Math.min(limit, 500));
        StringBuilder sql = new StringBuilder("SELECT * FROM prd_analysis_findings WHERE task_id=? ");
        List<Object> args = new ArrayList<>();
        args.add(taskId);
        if (!blank(type)) { sql.append("AND finding_type=? "); args.add(type.trim().toUpperCase()); }
        if (!blank(nodeId)) { sql.append("AND node_id=? "); args.add(nodeId.trim()); }
        if (!blank(status)) { sql.append("AND status=? "); args.add(status.trim().toUpperCase()); }
        sql.append("ORDER BY created_at LIMIT ? OFFSET ?");
        args.add(resolvedLimit);
        args.add(Math.max(0, offset));
        List<PrdFinding> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object value = args.get(i);
                if (value instanceof Integer number) ps.setInt(i + 1, number);
                else ps.setString(i + 1, String.valueOf(value));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(finding(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd findings", e); }
    }

    public Optional<PrdFinding> finding(String id) {
        if (blank(id)) return Optional.empty();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_findings WHERE id=?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(finding(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find prd finding", e); }
    }

    public List<PrdFinding> findingsForNode(String nodeId) {
        List<PrdFinding> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_findings WHERE node_id=? AND status='ACTIVE' ORDER BY finding_type,name")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(finding(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd findings for node", e); }
    }

    public Map<String, Long> findingCounts(String taskId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT finding_type,COUNT(*) FROM prd_analysis_findings WHERE task_id=? AND status='ACTIVE' "
                        + "GROUP BY finding_type")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) counts.put(rs.getString(1), rs.getLong(2));
            }
            return counts;
        } catch (SQLException e) { throw failure("count prd findings", e); }
    }

    public long countFindings(String taskId, String status) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM prd_analysis_findings WHERE task_id=? "
                        + (blank(status) ? "" : " AND status=?"))) {
            ps.setString(1, taskId);
            if (!blank(status)) ps.setString(2, status.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) { throw failure("count prd findings", e); }
    }

    public List<PrdEvidence> evidenceForFinding(String findingId) {
        return evidenceByColumn("finding_id", findingId);
    }

    public List<PrdEvidence> evidenceForQuestion(String questionId) {
        return evidenceByColumn("question_id", questionId);
    }

    private List<PrdEvidence> evidenceByColumn(String column, String value) {
        List<PrdEvidence> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_evidence WHERE " + column + "=? ORDER BY created_at")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(evidence(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd evidence", e); }
    }

    // ------------------------------------------------------------------
    // Questions
    // ------------------------------------------------------------------

    public List<PrdQuestion> questions(String taskId, String status, String severity, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        StringBuilder sql = new StringBuilder("SELECT * FROM prd_analysis_questions WHERE task_id=? ");
        List<Object> args = new ArrayList<>();
        args.add(taskId);
        if (!blank(status)) { sql.append("AND status=? "); args.add(status.trim().toUpperCase()); }
        if (!blank(severity)) { sql.append("AND severity=? "); args.add(severity.trim().toUpperCase()); }
        sql.append("ORDER BY CASE severity WHEN 'BLOCKING' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,created_at LIMIT ?");
        args.add(limit);
        List<PrdQuestion> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object value = args.get(i);
                if (value instanceof Integer number) ps.setInt(i + 1, number);
                else ps.setString(i + 1, String.valueOf(value));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(question(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd questions", e); }
    }

    public Optional<PrdQuestion> question(String id) {
        if (blank(id)) return Optional.empty();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_questions WHERE id=?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(question(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find prd question", e); }
    }

    public long countOpenBlocking(String taskId) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM prd_analysis_questions WHERE task_id=? AND severity='BLOCKING' "
                        + "AND status='OPEN'")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) { throw failure("count open blocking prd questions", e); }
    }

    /** Counts both unanswered and user-answered blocking questions until reconciliation resolves them. */
    public long countUnresolvedBlocking(String taskId) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM prd_analysis_questions WHERE task_id=? AND severity='BLOCKING' "
                        + "AND status IN ('OPEN','ANSWERED')")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) { throw failure("count unresolved blocking prd questions", e); }
    }

    public List<PrdQuestion> openBlockingQuestions(String taskId) {
        List<PrdQuestion> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_questions WHERE task_id=? AND severity='BLOCKING' "
                        + "AND status IN ('OPEN','ANSWERED') ORDER BY created_at")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(question(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list open blocking prd questions", e); }
    }

    /** Answers must belong to the task; only OPEN/ANSWERED questions can be answered. */
    public int answerQuestions(String taskId, List<QuestionAnswer> answers) {
        if (answers == null || answers.isEmpty()) return 0;
        Instant now = Instant.now();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            int updated = 0;
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE prd_analysis_questions SET answer=?,status='ANSWERED',answered_at=?,updated_at=? "
                                + "WHERE id=? AND task_id=? AND status IN ('OPEN','ANSWERED')")) {
                    for (QuestionAnswer answer : answers) {
                        if (blank(answer.questionId())) throw new IllegalArgumentException("questionId is required");
                        PrdQuestion question = question(answer.questionId())
                                .orElseThrow(() -> new IllegalArgumentException("question not found: "
                                        + answer.questionId()));
                        if (!question.taskId().equals(taskId)) {
                            throw new IllegalArgumentException("question does not belong to task " + taskId);
                        }
                        ps.setString(1, text(answer.answer(), "answer", 8_000));
                        ps.setString(2, now.toString());
                        ps.setString(3, now.toString());
                        ps.setString(4, answer.questionId().trim());
                        ps.setString(5, taskId);
                        updated += ps.executeUpdate();
                    }
                }
                c.commit();
                return updated;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) { throw failure("answer prd questions", e); }
    }

    /** Inserts a standalone question (used by the deterministic validator for ambiguous findings). */
    public PrdQuestion insertQuestion(String taskId, String category, String severity, String question, String context) {
        String id = id("prdq");
        Instant now = Instant.now();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO prd_analysis_questions(id,task_id,category,severity,question,context,status," +
                        "created_at) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, taskId);
            ps.setString(3, value(category, 120));
            ps.setString(4, normalizeSeverity(severity));
            ps.setString(5, text(question, "question", 4_000));
            ps.setString(6, value(context, 4_000));
            ps.setString(7, "OPEN");
            ps.setString(8, now.toString());
            ps.executeUpdate();
            return new PrdQuestion(id, taskId, category, severity, question, context,
                    "OPEN", null, null, now, null, null);
        } catch (SQLException e) { throw failure("insert prd question", e); }
    }

    public List<com.paicli.platform.server.domain.ArtifactRecord> artifactsForTask(String taskId) {
        List<com.paicli.platform.server.domain.ArtifactRecord> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT a.* FROM artifacts a JOIN prd_analysis_runs r ON r.run_id=a.run_id "
                        + "WHERE r.task_id=? ORDER BY a.created_at")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(new com.paicli.platform.server.domain.ArtifactRecord(
                            rs.getString("id"), rs.getString("run_id"), rs.getString("type"),
                            rs.getString("name"), rs.getString("relative_path"), rs.getLong("size"),
                            rs.getString("sha256"), instant(rs.getString("created_at"))));
                }
            }
            return values;
        } catch (SQLException e) { throw failure("list prd task artifacts", e); }
    }

    // ------------------------------------------------------------------
    // Checks
    // ------------------------------------------------------------------

    public void replaceChecks(String taskId, List<CheckDraft> checks) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement delete = c.prepareStatement(
                        "DELETE FROM prd_analysis_checks WHERE task_id=?")) {
                    delete.setString(1, taskId);
                    delete.executeUpdate();
                }
                if (checks != null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO prd_analysis_checks(id,task_id,check_type,severity,status,subject_type," +
                                    "subject_id,message,expected_json,actual_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                        for (CheckDraft check : checks) {
                            ps.setString(1, id("prdchk"));
                            ps.setString(2, taskId);
                            ps.setString(3, check.checkType());
                            ps.setString(4, check.severity());
                            ps.setString(5, check.status());
                            ps.setString(6, nullable(check.subjectType()));
                            ps.setString(7, nullable(check.subjectId()));
                            ps.setString(8, value(check.message(), 8_000));
                            ps.setString(9, nullable(check.expectedJson()));
                            ps.setString(10, nullable(check.actualJson()));
                            ps.setString(11, Instant.now().toString());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) { throw failure("replace prd checks", e); }
    }

    public List<PrdCheck> checks(String taskId) {
        List<PrdCheck> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_checks WHERE task_id=? ORDER BY created_at,check_type")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(check(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd checks", e); }
    }

    // ------------------------------------------------------------------
    // Run bindings
    // ------------------------------------------------------------------

    public PrdRunBinding createRunBinding(String taskId, String purpose, String nodeId, String runId, int attempt) {
        String id = id("prdrun");
        Instant now = Instant.now();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO prd_analysis_runs(id,task_id,purpose,node_id,run_id,attempt,status,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, taskId);
            ps.setString(3, normalizePurpose(purpose));
            ps.setString(4, nullable(nodeId));
            ps.setString(5, runId);
            ps.setInt(6, Math.max(0, attempt));
            ps.setString(7, "CREATED");
            ps.setString(8, now.toString());
            ps.setString(9, now.toString());
            ps.executeUpdate();
            return findBinding(id).orElseThrow();
        } catch (SQLException e) { throw failure("create prd run binding", e); }
    }

    public Optional<PrdRunBinding> findBinding(String id) {
        if (blank(id)) return Optional.empty();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_runs WHERE id=?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(runBinding(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find prd run binding", e); }
    }

    public Optional<PrdRunBinding> runBindingForRun(String runId) {
        if (blank(runId)) return Optional.empty();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_runs WHERE run_id=?")) {
            ps.setString(1, runId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(runBinding(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find prd run binding for run", e); }
    }

    public Optional<PrdRunBinding> latestRunBinding(String taskId, String purpose, String nodeId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM prd_analysis_runs WHERE task_id=? AND purpose=? ");
        List<Object> args = new ArrayList<>();
        args.add(taskId);
        args.add(normalizePurpose(purpose));
        if (!blank(nodeId)) { sql.append("AND node_id=? "); args.add(nodeId.trim()); }
        sql.append("ORDER BY attempt DESC,created_at DESC LIMIT 1");
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object value = args.get(i);
                if (value instanceof Integer number) ps.setInt(i + 1, number);
                else ps.setString(i + 1, String.valueOf(value));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(runBinding(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find latest prd run binding", e); }
    }

    public List<PrdRunBinding> runBindings(String taskId) {
        List<PrdRunBinding> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM prd_analysis_runs WHERE task_id=? ORDER BY created_at")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(runBinding(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list prd run bindings", e); }
    }

    public boolean updateRunBindingStatus(String bindingId, String status, String resultSummaryJson) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_runs SET status=?,result_summary_json=?,updated_at=? WHERE id=?")) {
            ps.setString(1, status == null || status.isBlank() ? "CREATED" : status.trim().toUpperCase());
            ps.setString(2, nullable(resultSummaryJson));
            ps.setString(3, Instant.now().toString());
            ps.setString(4, bindingId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { throw failure("update prd run binding status", e); }
    }

    // ------------------------------------------------------------------
    // Structured submissions (coarse-grained, transactional, idempotent)
    // ------------------------------------------------------------------

    /** Idempotent submit for the Mapper run. Returns the stored result on retry. */
    public Map<String, Object> submitMap(String taskId, String bindingId, String toolCallId, String payloadJson) {
        PrdRunBinding binding = requireBinding(bindingId);
        requirePurpose(binding, "MAP");
        requireTask(binding, taskId);
        return withSubmission(binding, toolCallId, payloadJson, c -> {
            Map<String, Object> payload = parsePayload(payloadJson);
            List<Map<String, Object>> nodePayloads = list(payload, "nodes");
            List<Map<String, Object>> dependencyPayloads = list(payload, "dependencies");
            List<Map<String, Object>> glossaryPayload = list(payload, "glossary");
            if (nodePayloads.isEmpty()) throw new IllegalArgumentException("prd_submit_map requires at least one node");
            if (nodePayloads.size() > MAX_NODES) {
                throw new IllegalArgumentException("too many nodes (max " + MAX_NODES + ")");
            }
            validateDependencyGraph(nodePayloads, dependencyPayloads);
            List<PrdNode> existing = nodes(taskId);
            if (!existing.isEmpty()) {
                throw new IllegalStateException("nodes already submitted for task " + taskId
                        + "; use retry only for the same submission");
            }
            List<PrdSource> sources = sources(taskId);
            Map<String, PrdSource> sourceById = new LinkedHashMap<>();
            for (PrdSource source : sources) sourceById.put(source.id(), source);
            List<String> clientKeys = new ArrayList<>();
            Map<String, String> clientKeyToId = new LinkedHashMap<>();
            List<PrdNode> createdNodes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO prd_analysis_nodes(id,task_id,client_key,title,summary,source_id," +
                                    "start_chunk_ordinal,end_chunk_ordinal,status,domain_tags_json,created_at,updated_at) "
                                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        for (Map<String, Object> item : nodePayloads) {
                            String clientKey = text(String.valueOf(item.get("clientKey")), "clientKey", 120);
                            if (clientKeys.contains(clientKey)) {
                                throw new IllegalArgumentException("duplicate clientKey: " + clientKey);
                            }
                            clientKeys.add(clientKey);
                            String sourceId = String.valueOf(item.get("sourceId"));
                            PrdSource source = sourceById.get(sourceId);
                            if (source == null) throw new IllegalArgumentException("unknown sourceId: " + sourceId);
                            int start = integer(item.get("startChunkOrdinal"), 0);
                            int end = integer(item.get("endChunkOrdinal"), start);
                            validateChunkRange(sourceId, start, end);
                            String nodeId = id("prdnode");
                            clientKeyToId.put(clientKey, nodeId);
                            ps.setString(1, nodeId);
                            ps.setString(2, taskId);
                            ps.setString(3, clientKey);
                            ps.setString(4, text(String.valueOf(item.get("title")), "node title", 240));
                            ps.setString(5, value(String.valueOf(item.getOrDefault("summary", "")), 4_000));
                            ps.setString(6, sourceId);
                            ps.setInt(7, start);
                            ps.setInt(8, end);
                            ps.setString(9, "PENDING");
                            ps.setString(10, jsonArray(item.get("domainTags")));
                            ps.setString(11, Instant.now().toString());
                            ps.setString(12, Instant.now().toString());
                            ps.addBatch();
                            createdNodes.add(new PrdNode(nodeId, taskId, clientKey,
                                    String.valueOf(item.get("title")), String.valueOf(item.getOrDefault("summary", "")),
                                    sourceId, start, end, "PENDING", jsonArray(item.get("domainTags")),
                                    Instant.now(), Instant.now()));
                        }
                        ps.executeBatch();
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO prd_analysis_node_dependencies(task_id,from_node_id,to_node_id,dependency_type) "
                                    + "VALUES(?,?,?,?)")) {
                        for (Map<String, Object> item : dependencyPayloads) {
                            String from = String.valueOf(item.get("fromClientKey"));
                            String to = String.valueOf(item.get("toClientKey"));
                            String type = normalizeDependencyType(String.valueOf(item.getOrDefault("type", "REFERENCE")));
                            String fromId = clientKeyToId.get(from);
                            String toId = clientKeyToId.get(to);
                            if (fromId == null) throw new IllegalArgumentException("dependency fromClientKey not found: " + from);
                            if (toId == null) throw new IllegalArgumentException("dependency toClientKey not found: " + to);
                            if (fromId.equals(toId)) throw new IllegalArgumentException("self dependency is not allowed");
                            ps.setString(1, taskId);
                            ps.setString(2, fromId);
                            ps.setString(3, toId);
                            ps.setString(4, type);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    if (!glossaryPayload.isEmpty()) {
                        updateGlossaryWithinTransaction(c, taskId, mapper.writeValueAsString(glossaryPayload));
                    }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId);
            result.put("status", "SUBMITTED");
            result.put("nodes", createdNodes.stream().map(this::nodeView).toList());
            result.put("nodeCount", createdNodes.size());
            result.put("dependencies", dependencyPayloads.size());
            return result;
        });
    }

    /** Idempotent submit for a Node Analyst run. Findings + evidence + questions in one transaction. */
    public Map<String, Object> submitNodeAnalysis(String taskId, String bindingId, String nodeId,
                                                  String toolCallId, String payloadJson) {
        PrdRunBinding binding = requireBinding(bindingId);
        requirePurpose(binding, "NODE_ANALYSIS");
        requireTask(binding, taskId);
        if (blank(nodeId) || !nodeId.equals(binding.nodeId())) {
            throw new IllegalArgumentException("this Run is not bound to node " + nodeId);
        }
        PrdNode node = node(nodeId).orElseThrow(() -> new IllegalArgumentException("node not found: " + nodeId));
        return withSubmission(binding, toolCallId, payloadJson, c -> {
            Map<String, Object> payload = parsePayload(payloadJson);
            List<Map<String, Object>> findingPayloads = list(payload, "findings");
            List<Map<String, Object>> questionPayloads = list(payload, "questions");
            String summary = value(String.valueOf(payload.getOrDefault("summary", node.summary())), 8_000);
            List<PrdFinding> createdFindings = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO prd_analysis_findings(id,task_id,node_id,finding_type,name,summary," +
                                    "payload_json,status,severity,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                        for (Map<String, Object> item : findingPayloads) {
                            String findingType = normalizeFindingType(String.valueOf(item.get("type")));
                            String name = text(String.valueOf(item.get("name")), "finding name", 240);
                            String findingSummary = value(String.valueOf(item.getOrDefault("summary", "")), 8_000);
                            String severity = normalizeFindingSeverity(String.valueOf(item.getOrDefault("severity", "MEDIUM")));
                            String findingId = id("prdfnd");
                            ps.setString(1, findingId);
                            ps.setString(2, taskId);
                            ps.setString(3, nodeId);
                            ps.setString(4, findingType);
                            ps.setString(5, name);
                            ps.setString(6, findingSummary);
                            ps.setString(7, jsonOf(item.get("payload")));
                            ps.setString(8, "ACTIVE");
                            ps.setString(9, severity);
                            ps.setString(10, Instant.now().toString());
                            ps.setString(11, Instant.now().toString());
                            ps.addBatch();
                            createdFindings.add(new PrdFinding(findingId, taskId, nodeId, findingType, name,
                                    findingSummary, jsonOf(item.get("payload")), "ACTIVE", null, severity,
                                    Instant.now(), Instant.now()));
                        }
                        ps.executeBatch();
            }
            insertEvidenceBatch(c, taskId, createdFindings, findingPayloads);
            List<PrdQuestion> createdQuestions = insertQuestionsWithinTransaction(c, taskId, nodeId, questionPayloads);
            updateNodeWithinTransaction(c, nodeId, "COMPLETED", summary);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId);
            result.put("nodeId", nodeId);
            result.put("status", "SUBMITTED");
            result.put("findings", createdFindings.size());
            result.put("questions", createdQuestions.size());
            result.put("findingIds", createdFindings.stream().map(PrdFinding::id).toList());
            return result;
        });
    }

    /** Idempotent submit for the Reconciler run. */
    public Map<String, Object> submitReconciliation(String taskId, String bindingId,
                                                    String toolCallId, String payloadJson) {
        PrdRunBinding binding = requireBinding(bindingId);
        requirePurpose(binding, "RECONCILE");
        requireTask(binding, taskId);
        return withSubmission(binding, toolCallId, payloadJson, c -> {
            Map<String, Object> payload = parsePayload(payloadJson);
            List<Map<String, Object>> mergeActions = list(payload, "mergeActions");
            List<Map<String, Object>> statusActions = list(payload, "statusActions");
            List<Map<String, Object>> newQuestions = list(payload, "newQuestions");
            List<String> resolvedQuestionIds = stringList(payload.get("resolvedQuestionIds"));
            String summary = value(String.valueOf(payload.getOrDefault("summary", "")), 8_000);
            try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE prd_analysis_findings SET status='MERGED',merged_into_id=?,updated_at=? WHERE id=? AND task_id=?")) {
                        for (Map<String, Object> action : mergeActions) {
                            String canonical = String.valueOf(action.get("canonicalFindingId"));
                            PrdFinding canonicalFinding = requireFindingWithinTask(c, taskId, canonical);
                            List<String> sourceIds = stringList(action.get("sourceFindingIds"));
                            if (sourceIds.isEmpty()) {
                                throw new IllegalArgumentException("merge action requires sourceFindingIds");
                            }
                            for (String sourceId : sourceIds) {
                                PrdFinding source = requireFindingWithinTask(c, taskId, sourceId);
                                if (source.id().equals(canonicalFinding.id())) {
                                    throw new IllegalArgumentException("cannot merge finding into itself: " + sourceId);
                                }
                                ps.setString(1, canonicalFinding.id());
                                ps.setString(2, Instant.now().toString());
                                ps.setString(3, source.id());
                                ps.setString(4, taskId);
                                ps.addBatch();
                            }
                        }
                        ps.executeBatch();
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE prd_analysis_findings SET status=?,updated_at=? WHERE id=? AND task_id=?")) {
                        for (Map<String, Object> action : statusActions) {
                            String findingId = String.valueOf(action.get("findingId"));
                            String status = normalizeFindingStatus(String.valueOf(action.get("status")));
                            requireFindingWithinTask(c, taskId, findingId);
                            ps.setString(1, status);
                            ps.setString(2, Instant.now().toString());
                            ps.setString(3, findingId);
                            ps.setString(4, taskId);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    List<PrdQuestion> createdQuestions = insertQuestionsWithinTransaction(c, taskId, null, newQuestions);
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE prd_analysis_questions SET status='RESOLVED',resolution=?,resolved_at=?,updated_at=? "
                                    + "WHERE id=? AND task_id=? AND (status IN ('ANSWERED','RESOLVED') OR severity<>'BLOCKING')")) {
                        for (String questionId : resolvedQuestionIds) {
                            PrdQuestion question = requireQuestionWithinTask(c, taskId, questionId);
                            if ("BLOCKING".equals(question.severity()) && !"ANSWERED".equals(question.status())) {
                                throw new IllegalArgumentException("blocking question must be answered before resolution: "
                                        + questionId);
                            }
                            ps.setString(1, summary.isBlank() ? "resolved during reconciliation" : summary);
                            ps.setString(2, Instant.now().toString());
                            ps.setString(3, Instant.now().toString());
                            ps.setString(4, questionId);
                            ps.setString(5, taskId);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId);
            result.put("status", "SUBMITTED");
            result.put("merges", mergeActions.size());
            result.put("statusActions", statusActions.size());
            result.put("newQuestions", createdQuestions.size());
            result.put("resolvedQuestions", resolvedQuestionIds.size());
            return result;
        });
    }

    // ------------------------------------------------------------------
    // Transaction helpers for submissions
    // ------------------------------------------------------------------

    private Map<String, Object> withSubmission(PrdRunBinding binding, String toolCallId,
                                               String payloadJson, ThrowingConnectionSupplier<Map<String, Object>> action) {
        if (payloadJson == null || payloadJson.isBlank()) throw new IllegalArgumentException("payload is required");
        if (payloadJson.length() > MAX_PAYLOAD_JSON) {
            throw new IllegalArgumentException("payload is too large");
        }
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                // SQLite has database-level write locking. This no-op update obtains that
                // lock before observing the marker, so concurrent retries serialize.
                try (PreparedStatement lock = c.prepareStatement(
                        "UPDATE prd_analysis_runs SET updated_at=updated_at WHERE id=?")) {
                    lock.setString(1, binding.id());
                    if (lock.executeUpdate() != 1) throw new IllegalArgumentException("PRD Run binding not found");
                }
                try (PreparedStatement existing = c.prepareStatement(
                        "SELECT submission_tool_call_id,submission_result_json FROM prd_analysis_runs WHERE id=?")) {
                    existing.setString(1, binding.id());
                    try (ResultSet rs = existing.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("PRD Run binding not found");
                        String priorToolCallId = rs.getString(1);
                        if (!blank(priorToolCallId)) {
                            if (!priorToolCallId.equals(toolCallId)) {
                                throw new IllegalStateException("this PRD Run has already submitted with a different tool call");
                            }
                            Map<String, Object> stored = storedSubmission(rs.getString(2));
                            c.commit();
                            return stored;
                        }
                    }
                }
                Map<String, Object> result = action.get(c);
                persistSubmissionWithinTransaction(c, binding.id(), toolCallId, payloadJson, writeJson(result));
                c.commit();
                return result;
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            } catch (Exception e) {
                c.rollback();
                throw new IllegalStateException("PRD submission failed", e);
            }
        } catch (SQLException e) { throw failure("persist prd submission", e); }
    }

    private void persistSubmissionWithinTransaction(Connection c, String bindingId, String toolCallId,
                                                     String payloadJson, String resultJson) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_runs SET submission_tool_call_id=?,submission_payload_json=?,submission_result_json=?,submitted_at=?,updated_at=? WHERE id=?")) {
            ps.setString(1, toolCallId);
            ps.setString(2, payloadJson);
            ps.setString(3, resultJson);
            ps.setString(4, Instant.now().toString());
            ps.setString(5, Instant.now().toString());
            ps.setString(6, bindingId);
            ps.executeUpdate();
        }
    }

    private Map<String, Object> storedSubmission(PrdRunBinding binding) {
        return storedSubmission(binding.submissionResultJson());
    }

    private Map<String, Object> storedSubmission(String resultJson) {
        try {
            return mapper.readValue(resultJson, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException("stored submission result is corrupt", e);
        }
    }

    private void insertEvidenceBatch(Connection c, String taskId, List<PrdFinding> findings,
                                     List<Map<String, Object>> findingPayloads) throws SQLException {
        if (findingPayloads.size() != findings.size()) {
            throw new IllegalStateException("internal evidence mismatch");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO prd_analysis_evidence(id,finding_id,question_id,source_id,chunk_id," +
                        "local_start_offset,local_end_offset,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
            for (int i = 0; i < findingPayloads.size(); i++) {
                Map<String, Object> item = findingPayloads.get(i);
                PrdFinding finding = findings.get(i);
                List<Map<String, Object>> evidence = listValue(item.get("evidence"));
                for (Map<String, Object> ev : evidence) {
                    String chunkId = String.valueOf(ev.get("chunkId"));
                    int start = integer(ev.get("start"), 0);
                    int end = integer(ev.get("end"), start);
                    PrdChunk chunk = chunk(chunkId).orElseThrow(() ->
                            new IllegalArgumentException("evidence chunk not found: " + chunkId));
                    PrdSource chunkSource = source(chunk.sourceId())
                            .orElseThrow(() -> new IllegalArgumentException("evidence source not found"));
                    if (!chunkSource.taskId().equals(taskId)) {
                        throw new IllegalArgumentException("evidence chunk belongs to another task");
                    }
                    if (start < 0 || end < start || end > chunk.text().length()) {
                        throw new IllegalArgumentException("evidence offset out of range for chunk " + chunkId);
                    }
                    ps.setString(1, id("prdev"));
                    ps.setString(2, finding.id());
                    ps.setNull(3, java.sql.Types.VARCHAR);
                    ps.setString(4, chunk.sourceId());
                    ps.setString(5, chunkId);
                    ps.setInt(6, start);
                    ps.setInt(7, end);
                    ps.setString(8, Instant.now().toString());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private List<PrdQuestion> insertQuestionsWithinTransaction(Connection c, String taskId, String nodeId,
                                                               List<Map<String, Object>> questionPayloads) throws SQLException {
        List<PrdQuestion> created = new ArrayList<>();
        if (questionPayloads == null || questionPayloads.isEmpty()) return created;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO prd_analysis_questions(id,task_id,category,severity,question,context,status," +
                        "created_at) VALUES(?,?,?,?,?,?,?,?)")) {
            for (Map<String, Object> item : questionPayloads) {
                String questionId = id("prdq");
                String severity = normalizeSeverity(String.valueOf(item.getOrDefault("severity", "WARNING")));
                String category = value(String.valueOf(item.getOrDefault("category", "")), 120);
                String questionText = text(String.valueOf(item.get("question")), "question", 4_000);
                String context = value(String.valueOf(item.getOrDefault("context", "")), 4_000);
                ps.setString(1, questionId);
                ps.setString(2, taskId);
                ps.setString(3, category);
                ps.setString(4, severity);
                ps.setString(5, questionText);
                ps.setString(6, context);
                ps.setString(7, "OPEN");
                ps.setString(8, Instant.now().toString());
                ps.addBatch();
                created.add(new PrdQuestion(questionId, taskId, category, severity, questionText, context,
                        "OPEN", null, null, Instant.now(), null, null));
            }
            ps.executeBatch();
        }
        return created;
    }

    private void updateNodeWithinTransaction(Connection c, String nodeId, String status, String summary)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_nodes SET status=?,summary=?,updated_at=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setString(2, summary);
            ps.setString(3, Instant.now().toString());
            ps.setString(4, nodeId);
            ps.executeUpdate();
        }
    }

    private void updateGlossaryWithinTransaction(Connection c, String taskId, String glossaryJson)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE prd_analysis_tasks SET glossary_json=?,updated_at=? WHERE id=?")) {
            ps.setString(1, glossaryJson);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, taskId);
            ps.executeUpdate();
        }
    }

    private PrdFinding requireFindingWithinTask(Connection c, String taskId, String findingId) {
        return finding(findingId).filter(value -> value.taskId().equals(taskId))
                .orElseThrow(() -> new IllegalArgumentException("finding not found in task: " + findingId));
    }

    private PrdQuestion requireQuestionWithinTask(Connection c, String taskId, String questionId) {
        return question(questionId).filter(value -> value.taskId().equals(taskId))
                .orElseThrow(() -> new IllegalArgumentException("question not found in task: " + questionId));
    }

    private PrdRunBinding requireBinding(String bindingId) {
        return findBinding(bindingId).orElseThrow(() -> new IllegalArgumentException("PRD run binding not found"));
    }

    private static void requirePurpose(PrdRunBinding binding, String purpose) {
        if (!purpose.equals(binding.purpose())) {
            throw new IllegalArgumentException("this Run is not a " + purpose + " Run");
        }
    }

    private static void requireTask(PrdRunBinding binding, String taskId) {
        if (!binding.taskId().equals(taskId)) {
            throw new IllegalArgumentException("PRD run is bound to another task");
        }
    }

    private void validateChunkRange(String sourceId, int start, int end) {
        if (start < 0 || end < start) throw new IllegalArgumentException("invalid chunk range");
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM prd_analysis_source_chunks WHERE source_id=? AND ordinal>=? AND ordinal<=?")) {
            ps.setString(1, sourceId);
            ps.setInt(2, start);
            ps.setInt(3, end);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getLong(1) == 0) {
                    throw new IllegalArgumentException("chunk range [" + start + "," + end + "] is empty for source");
                }
            }
        } catch (SQLException e) { throw failure("validate prd chunk range", e); }
    }

    private static void validateDependencyGraph(List<Map<String, Object>> nodes,
                                                List<Map<String, Object>> dependencies) {
        Set<String> keys = new HashSet<>();
        Map<String, List<String>> edges = new HashMap<>();
        for (Map<String, Object> node : nodes) {
            String key = text(String.valueOf(node.get("clientKey")), "clientKey", 120);
            if (!keys.add(key)) throw new IllegalArgumentException("duplicate clientKey: " + key);
            edges.put(key, new ArrayList<>());
        }
        for (Map<String, Object> dependency : dependencies) {
            String from = String.valueOf(dependency.get("fromClientKey"));
            String to = String.valueOf(dependency.get("toClientKey"));
            if (!keys.contains(from)) throw new IllegalArgumentException("dependency fromClientKey not found: " + from);
            if (!keys.contains(to)) throw new IllegalArgumentException("dependency toClientKey not found: " + to);
            if (from.equals(to)) throw new IllegalArgumentException("self dependency is not allowed");
            edges.get(from).add(to);
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String key : keys) {
            if (hasDependencyCycle(key, edges, visiting, visited)) {
                throw new IllegalArgumentException("dependency graph contains a cycle");
            }
        }
    }

    private static boolean hasDependencyCycle(String key, Map<String, List<String>> edges,
                                              Set<String> visiting, Set<String> visited) {
        if (visited.contains(key)) return false;
        if (!visiting.add(key)) return true;
        for (String next : edges.getOrDefault(key, List.of())) {
            if (hasDependencyCycle(next, edges, visiting, visited)) return true;
        }
        visiting.remove(key);
        visited.add(key);
        return false;
    }

    // ------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------

    private Map<String, Object> parsePayload(String payloadJson) {
        try {
            Map<String, Object> value = mapper.readValue(payloadJson, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            return value == null ? new LinkedHashMap<>() : value;
        } catch (Exception e) {
            throw new IllegalArgumentException("payload is not valid JSON: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> payload, String key) {
        return listValue(payload.get(key));
    }

    private static List<Map<String, Object>> listValue(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> raw)) throw new IllegalArgumentException("expected a JSON array");
        List<Map<String, Object>> values = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("expected a JSON object");
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((k, v) -> normalized.put(String.valueOf(k), v));
            values.add(normalized);
        }
        return values;
    }

    private static List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> raw)) throw new IllegalArgumentException("expected a JSON array");
        List<String> values = new ArrayList<>();
        for (Object item : raw) values.add(String.valueOf(item).trim());
        return values;
    }

    private String jsonOf(Object value) {
        try {
            return value == null ? "{}" : mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid payload value");
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize submission result", e);
        }
    }

    private static String jsonArray(Object value) {
        if (value == null) return "[]";
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : list) {
                if (out.length() > 1) out.append(",");
                out.append("\"").append(String.valueOf(item).replace("\"", "\\\"")).append("\"");
            }
            return out.append("]").toString();
        }
        return "[]";
    }

    private Map<String, Object> nodeView(PrdNode node) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", node.id());
        value.put("clientKey", node.clientKey());
        value.put("title", node.title());
        value.put("summary", node.summary());
        value.put("sourceId", node.sourceId());
        value.put("startChunkOrdinal", node.startChunkOrdinal());
        value.put("endChunkOrdinal", node.endChunkOrdinal());
        value.put("domainTags", node.domainTagsJson());
        return value;
    }

    // ------------------------------------------------------------------
    // Row mappers
    // ------------------------------------------------------------------

    private static PrdTask task(ResultSet rs) throws SQLException {
        return new PrdTask(rs.getString("id"), rs.getString("project_key"), rs.getString("title"),
                rs.getString("status"), rs.getString("current_stage"), rs.getString("prd_source_id"),
                rs.getString("source_contract_source_id"), rs.getInt("max_parallelism"),
                rs.getInt("reconcile_iteration"), rs.getString("glossary_json"),
                rs.getString("created_by"), instant(rs.getString("created_at")),
                instant(rs.getString("updated_at")), instant(rs.getString("completed_at")),
                rs.getString("last_error"), rs.getLong("version"), rs.getString("session_id"));
    }

    private static PrdSource source(ResultSet rs) throws SQLException {
        return new PrdSource(rs.getString("id"), rs.getString("task_id"), rs.getString("attachment_id"),
                rs.getString("source_type"), rs.getString("file_name"), rs.getString("content_hash"),
                rs.getString("extraction_status"), rs.getString("text_artifact_id"),
                instant(rs.getString("created_at")));
    }

    private static PrdChunk chunk(ResultSet rs) throws SQLException {
        return new PrdChunk(rs.getString("id"), rs.getString("source_id"), rs.getInt("ordinal"),
                rs.getString("heading"), rs.getInt("start_offset"), rs.getInt("end_offset"),
                rs.getString("text"), rs.getString("content_hash"));
    }

    private static PrdNode node(ResultSet rs) throws SQLException {
        return new PrdNode(rs.getString("id"), rs.getString("task_id"), rs.getString("client_key"),
                rs.getString("title"), rs.getString("summary"), rs.getString("source_id"),
                rs.getInt("start_chunk_ordinal"), rs.getInt("end_chunk_ordinal"), rs.getString("status"),
                rs.getString("domain_tags_json"), instant(rs.getString("created_at")),
                instant(rs.getString("updated_at")));
    }

    private static PrdDependency dependency(ResultSet rs) throws SQLException {
        return new PrdDependency(rs.getString("task_id"), rs.getString("from_node_id"),
                rs.getString("to_node_id"), rs.getString("dependency_type"));
    }

    private static PrdFinding finding(ResultSet rs) throws SQLException {
        return new PrdFinding(rs.getString("id"), rs.getString("task_id"), rs.getString("node_id"),
                rs.getString("finding_type"), rs.getString("name"), rs.getString("summary"),
                rs.getString("payload_json"), rs.getString("status"), rs.getString("merged_into_id"),
                rs.getString("severity"), instant(rs.getString("created_at")), instant(rs.getString("updated_at")));
    }

    private static PrdEvidence evidence(ResultSet rs) throws SQLException {
        return new PrdEvidence(rs.getString("id"), rs.getString("finding_id"), rs.getString("question_id"),
                rs.getString("source_id"), rs.getString("chunk_id"), rs.getInt("local_start_offset"),
                rs.getInt("local_end_offset"), instant(rs.getString("created_at")));
    }

    private static PrdQuestion question(ResultSet rs) throws SQLException {
        return new PrdQuestion(rs.getString("id"), rs.getString("task_id"), rs.getString("category"),
                rs.getString("severity"), rs.getString("question"), rs.getString("context"),
                rs.getString("status"), rs.getString("answer"), rs.getString("resolution"),
                instant(rs.getString("created_at")), instant(rs.getString("answered_at")),
                instant(rs.getString("resolved_at")));
    }

    private static PrdCheck check(ResultSet rs) throws SQLException {
        return new PrdCheck(rs.getString("id"), rs.getString("task_id"), rs.getString("check_type"),
                rs.getString("severity"), rs.getString("status"), rs.getString("subject_type"),
                rs.getString("subject_id"), rs.getString("message"), rs.getString("expected_json"),
                rs.getString("actual_json"), instant(rs.getString("created_at")));
    }

    private static PrdRunBinding runBinding(ResultSet rs) throws SQLException {
        return new PrdRunBinding(rs.getString("id"), rs.getString("task_id"), rs.getString("purpose"),
                rs.getString("node_id"), rs.getString("run_id"), rs.getInt("attempt"), rs.getString("status"),
                rs.getString("result_summary_json"), rs.getString("submission_tool_call_id"),
                rs.getString("submission_payload_json"), rs.getString("submission_result_json"),
                instant(rs.getString("submitted_at")), instant(rs.getString("created_at")),
                instant(rs.getString("updated_at")));
    }

    // ------------------------------------------------------------------
    // Normalization helpers
    // ------------------------------------------------------------------

    private static String normalizeTaskStatus(String value) {
        String normalized = blank(value) ? "DRAFT" : value.trim().toUpperCase();
        if (!TASK_STATUSES.contains(normalized)) throw new IllegalArgumentException("unsupported task status: " + value);
        return normalized;
    }

    private static String normalizeStage(String value) {
        String normalized = blank(value) ? "DRAFT" : value.trim().toUpperCase();
        if (!TASK_STATUSES.contains(normalized)) throw new IllegalArgumentException("unsupported stage: " + value);
        return normalized;
    }

    private static String normalizeSourceType(String value) {
        String normalized = blank(value) ? "SUPPORTING" : value.trim().toUpperCase();
        if (!SOURCE_TYPES.contains(normalized)) throw new IllegalArgumentException("unsupported source type: " + value);
        return normalized;
    }

    private static String normalizeNodeStatus(String value) {
        String normalized = blank(value) ? "PENDING" : value.trim().toUpperCase();
        if (!NODE_STATUSES.contains(normalized)) throw new IllegalArgumentException("unsupported node status: " + value);
        return normalized;
    }

    private static String normalizeFindingType(String value) {
        String normalized = blank(value) ? "" : value.trim().toUpperCase();
        if (!FINDING_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported finding type: " + value);
        }
        return normalized;
    }

    private static String normalizeFindingStatus(String value) {
        String normalized = blank(value) ? "ACTIVE" : value.trim().toUpperCase();
        if (!FINDING_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported finding status: " + value);
        }
        return normalized;
    }

    private static String normalizeDependencyType(String value) {
        String normalized = blank(value) ? "REFERENCE" : value.trim().toUpperCase();
        if (!DEPENDENCY_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported dependency type: " + value);
        }
        return normalized;
    }

    private static String normalizeSeverity(String value) {
        String normalized = blank(value) ? "WARNING" : value.trim().toUpperCase();
        if (!QUESTION_SEVERITIES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported severity: " + value);
        }
        return normalized;
    }

    private static String normalizeFindingSeverity(String value) {
        String normalized = blank(value) ? "MEDIUM" : value.trim().toUpperCase();
        if (!FINDING_SEVERITIES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported finding severity: " + value);
        }
        return normalized;
    }

    private static String normalizePurpose(String value) {
        String normalized = blank(value) ? "" : value.trim().toUpperCase();
        if (!RUN_PURPOSES.contains(normalized)) throw new IllegalArgumentException("unsupported purpose: " + value);
        return normalized;
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Connection open() throws SQLException { return connections.open(); }

    private static String actor(String value) { return blank(value) ? "SYSTEM" : value.trim().toUpperCase(); }
    private static String project(String value) { return blank(value) ? "default" : value.trim(); }
    private static String text(String value, String name, int max) {
        String normalized = value(value, max);
        if (normalized.isBlank()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
    private static String value(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("value is too long");
        return normalized;
    }
    private static String json(String value) {
        String normalized = blank(value) ? "{}" : value.trim();
        if (normalized.length() > 64_000) throw new IllegalArgumentException("json is too long");
        return normalized;
    }
    private static String nullable(String value) { return blank(value) ? null : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    private static Instant instant(String value) { return blank(value) ? null : Instant.parse(value); }
    private static IllegalStateException failure(String action, SQLException error) {
        return new IllegalStateException("SQLite failed to " + action + ": " + error.getMessage(), error);
    }

    // ------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------

    public record PrdTask(String id, String projectKey, String title, String status, String currentStage,
                          String prdSourceId, String sourceContractSourceId, int maxParallelism,
                          int reconcileIteration, String glossaryJson, String createdBy,
                          Instant createdAt, Instant updatedAt, Instant completedAt,
                          String lastError, long version, String sessionId) { }

    public record PrdSource(String id, String taskId, String attachmentId, String sourceType,
                            String fileName, String contentHash, String extractionStatus,
                            String textArtifactId, Instant createdAt) { }

    public record PrdChunk(String id, String sourceId, int ordinal, String heading,
                           int startOffset, int endOffset, String text, String contentHash) { }

    public record PrdNode(String id, String taskId, String clientKey, String title, String summary,
                          String sourceId, int startChunkOrdinal, int endChunkOrdinal, String status,
                          String domainTagsJson, Instant createdAt, Instant updatedAt) { }

    public record PrdDependency(String taskId, String fromNodeId, String toNodeId, String dependencyType) { }

    public record PrdFinding(String id, String taskId, String nodeId, String findingType, String name,
                             String summary, String payloadJson, String status, String mergedIntoId,
                             String severity, Instant createdAt, Instant updatedAt) { }

    public record PrdEvidence(String id, String findingId, String questionId, String sourceId,
                              String chunkId, int localStartOffset, int localEndOffset, Instant createdAt) { }

    public record PrdQuestion(String id, String taskId, String category, String severity, String question,
                              String context, String status, String answer, String resolution,
                              Instant createdAt, Instant answeredAt, Instant resolvedAt) { }

    public record PrdCheck(String id, String taskId, String checkType, String severity, String status,
                           String subjectType, String subjectId, String message, String expectedJson,
                           String actualJson, Instant createdAt) { }

    public record PrdRunBinding(String id, String taskId, String purpose, String nodeId, String runId,
                                int attempt, String status, String resultSummaryJson,
                                String submissionToolCallId, String submissionPayloadJson,
                                String submissionResultJson, Instant submittedAt,
                                Instant createdAt, Instant updatedAt) { }

    public record ChunkDraft(int ordinal, String heading, int startOffset, int endOffset,
                             String text, String contentHash) { }

    public record QuestionAnswer(String questionId, String answer) { }

    public record CheckDraft(String checkType, String severity, String status, String subjectType,
                             String subjectId, String message, String expectedJson, String actualJson) { }

    @FunctionalInterface
    private interface ThrowingConnectionSupplier<T> {
        T get(Connection connection) throws Exception;
    }
}
