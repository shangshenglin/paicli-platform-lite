package com.paicli.platform.server.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.platform.server.config.PlatformProperties;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PrdAnalysisStore {
    private final SqliteConnectionFactory connections;
    private final ObjectMapper mapper;

    public PrdAnalysisStore(PlatformProperties properties, ObjectMapper mapper) {
        this.connections = new SqliteConnectionFactory(
                properties.dataDir().resolve("paicli.db").toAbsolutePath().normalize());
        this.mapper = mapper;
    }

    public AnalysisJob createJob(String projectKey, String title, String prdText,
                                 String sourceContractJson, String configJson, String artifactDir) {
        String id = "prd_" + UUID.randomUUID();
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO prd_analysis_jobs(id,project_key,title,status,stage,prd_text," +
                            "source_contract_json,config_json,artifact_dir,created_at,updated_at) " +
                            "VALUES(?,?,?,'QUEUED','MAP_PRD',?,?,?,?,?,?)")) {
                statement.setString(1, id);
                statement.setString(2, project(projectKey));
                statement.setString(3, text(title, "title", 240));
                statement.setString(4, text(prdText, "prdText", 2_000_000));
                statement.setString(5, json(sourceContractJson, "{}"));
                statement.setString(6, json(configJson, "{}"));
                statement.setString(7, text(artifactDir, "artifactDir", 500).replace("{jobId}", id));
                statement.setString(8, now.toString());
                statement.setString(9, now.toString());
                statement.executeUpdate();
                appendEvent(connection, id, "analysis.queued", "{\"stage\":\"MAP_PRD\"}");
                connection.commit();
                return findJob(id).orElseThrow();
            } catch (SQLException | RuntimeException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("create PRD analysis job", e);
        }
    }

    public List<AnalysisJob> jobs(String projectKey, int limit) {
        List<AnalysisJob> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM prd_analysis_jobs WHERE project_key=? ORDER BY created_at DESC LIMIT ?")) {
            statement.setString(1, project(projectKey));
            statement.setInt(2, Math.max(1, Math.min(limit, 200)));
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) values.add(job(results));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list PRD analysis jobs", e);
        }
    }

    public Optional<AnalysisJob> findJob(String id) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM prd_analysis_jobs WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(job(results)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find PRD analysis job", e);
        }
    }

    public Optional<AnalysisJob> claimNext(String workerId) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String id = null;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id FROM prd_analysis_jobs WHERE status='QUEUED' ORDER BY updated_at,id LIMIT 1");
                     ResultSet results = select.executeQuery()) {
                    if (results.next()) id = results.getString(1);
                }
                if (id == null) {
                    connection.rollback();
                    return Optional.empty();
                }
                Instant now = Instant.now();
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE prd_analysis_jobs SET status='RUNNING',claimed_by=?,lease_expires_at=?," +
                                "updated_at=? WHERE id=? AND status='QUEUED'")) {
                    update.setString(1, workerId);
                    update.setString(2, now.plusSeconds(300).toString());
                    update.setString(3, now.toString());
                    update.setString(4, id);
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return Optional.empty();
                    }
                }
                appendEvent(connection, id, "analysis.claimed", jsonObject("worker", workerId));
                connection.commit();
                return findJob(id);
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("claim PRD analysis job", e);
        }
    }

    public AnalysisJob transition(String id, String stage, String status, String eventType, String payloadJson) {
        Instant now = Instant.now();
        boolean terminal = List.of("COMPLETED", "FAILED", "CANCELED").contains(status);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE prd_analysis_jobs SET stage=?,status=?,error=NULL,claimed_by=NULL," +
                            "lease_expires_at=NULL,progress_version=progress_version+1,updated_at=?," +
                            "finished_at=? WHERE id=? AND status<>'CANCELED'")) {
                statement.setString(1, stage);
                statement.setString(2, status);
                statement.setString(3, now.toString());
                statement.setString(4, terminal ? now.toString() : null);
                statement.setString(5, id);
                if (statement.executeUpdate() == 0) throw new IllegalStateException("analysis job is canceled or missing");
                appendEvent(connection, id, eventType, json(payloadJson, "{}"));
                connection.commit();
                return findJob(id).orElseThrow();
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("transition PRD analysis job", e);
        }
    }

    public AnalysisJob fail(String id, String error) {
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE prd_analysis_jobs SET status='FAILED',error=?,claimed_by=NULL,lease_expires_at=NULL," +
                            "updated_at=?,finished_at=? WHERE id=? AND status NOT IN ('COMPLETED','CANCELED')")) {
                statement.setString(1, value(error, 8_000));
                statement.setString(2, now.toString());
                statement.setString(3, now.toString());
                statement.setString(4, id);
                int updated = statement.executeUpdate();
                if (updated > 0) {
                    appendEvent(connection, id, "analysis.failed", jsonObject("error", value(error, 2_000)));
                }
                connection.commit();
                return findJob(id).orElseThrow();
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("fail PRD analysis job", e);
        }
    }

    public AnalysisJob retry(String id) {
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE prd_analysis_jobs SET status='QUEUED',error=NULL,finished_at=NULL,updated_at=? " +
                            "WHERE id=? AND status IN ('FAILED','AWAITING_USER')")) {
                statement.setString(1, now.toString());
                statement.setString(2, id);
                if (statement.executeUpdate() == 0) throw new IllegalStateException("analysis job cannot be retried");
                appendEvent(connection, id, "analysis.requeued", "{}");
                connection.commit();
                return findJob(id).orElseThrow();
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("retry PRD analysis job", e);
        }
    }

    public AnalysisJob cancel(String id) {
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE prd_analysis_jobs SET status='CANCELED',claimed_by=NULL,lease_expires_at=NULL," +
                            "updated_at=?,finished_at=? WHERE id=? AND status NOT IN ('COMPLETED','FAILED','CANCELED')")) {
                statement.setString(1, now.toString());
                statement.setString(2, now.toString());
                statement.setString(3, id);
                if (statement.executeUpdate() == 0) throw new IllegalStateException("analysis job cannot be canceled");
                appendEvent(connection, id, "analysis.canceled", "{}");
                connection.commit();
                return findJob(id).orElseThrow();
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("cancel PRD analysis job", e);
        }
    }

    public void replaceNodes(String jobId, List<NodeDraft> drafts) {
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement clearItems = connection.prepareStatement(
                        "DELETE FROM prd_analysis_items WHERE job_id=?")) {
                    clearItems.setString(1, jobId);
                    clearItems.executeUpdate();
                }
                try (PreparedStatement clearNodes = connection.prepareStatement(
                        "DELETE FROM prd_analysis_nodes WHERE job_id=?")) {
                    clearNodes.setString(1, jobId);
                    clearNodes.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO prd_analysis_nodes(id,job_id,node_key,ordinal,heading,heading_level," +
                                "start_line,end_line,content,dependencies_json,tags_json,status,created_at,updated_at) " +
                                "VALUES(?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?)")) {
                    for (NodeDraft draft : drafts) {
                        insert.setString(1, "prd_node_" + UUID.randomUUID());
                        insert.setString(2, jobId);
                        insert.setString(3, draft.nodeKey());
                        insert.setInt(4, draft.ordinal());
                        insert.setString(5, draft.heading());
                        insert.setInt(6, draft.headingLevel());
                        insert.setInt(7, draft.startLine());
                        insert.setInt(8, draft.endLine());
                        insert.setString(9, draft.content());
                        insert.setString(10, json(draft.dependenciesJson(), "[]"));
                        insert.setString(11, json(draft.tagsJson(), "[]"));
                        insert.setString(12, now.toString());
                        insert.setString(13, now.toString());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                appendEvent(connection, jobId, "map.completed", jsonObject("nodes", drafts.size()));
                connection.commit();
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("replace PRD analysis nodes", e);
        }
    }

    public List<AnalysisNode> nodes(String jobId) {
        List<AnalysisNode> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM prd_analysis_nodes WHERE job_id=? ORDER BY ordinal")) {
            statement.setString(1, jobId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) values.add(node(results));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list PRD analysis nodes", e);
        }
    }

    public List<AnalysisNode> pendingNodes(String jobId) {
        return nodes(jobId).stream().filter(node -> !"COMPLETED".equals(node.status())).toList();
    }

    public AnalysisNode commitNodeAnalysis(String nodeId, String rawAnalysisJson) {
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                AnalysisNode current = findNode(connection, nodeId).orElseThrow(
                        () -> new IllegalArgumentException("analysis node not found"));
                JsonNode root = mapper.readTree(json(rawAnalysisJson, "{}"));
                if (!(root instanceof ObjectNode object)) throw new IllegalArgumentException("node analysis must be an object");
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM prd_analysis_items WHERE node_id=?")) {
                    delete.setString(1, nodeId);
                    delete.executeUpdate();
                }
                Map<String, String> replacements = new LinkedHashMap<>();
                allocateItems(connection, current.jobId(), nodeId, object, "entities", "ENTITY", "E", replacements, now);
                allocateItems(connection, current.jobId(), nodeId, object, "rules", "RULE", "R", replacements, now);
                allocateItems(connection, current.jobId(), nodeId, object, "flows", "FLOW", "F", replacements, now);
                rewriteReferences(object, replacements);
                String normalized = mapper.writeValueAsString(object);
                updateItemPayloads(connection, current.jobId(), object);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE prd_analysis_nodes SET status='COMPLETED',analysis_json=?,error=NULL,updated_at=? WHERE id=?")) {
                    update.setString(1, normalized);
                    update.setString(2, now.toString());
                    update.setString(3, nodeId);
                    update.executeUpdate();
                }
                try (PreparedStatement action = connection.prepareStatement(
                        "UPDATE prd_analysis_actions SET status='COMPLETED',result_json=?,finished_at=? " +
                                "WHERE node_id=? AND status='REQUESTED'")) {
                    action.setString(1, "{\"committed\":true}");
                    action.setString(2, now.toString());
                    action.setString(3, nodeId);
                    action.executeUpdate();
                }
                appendEvent(connection, current.jobId(), "node.completed",
                        "{\"nodeId\":\"" + escape(nodeId) + "\",\"ordinal\":" + current.ordinal() + "}");
                connection.commit();
                return findNode(nodeId).orElseThrow();
            } catch (Exception e) {
                rollback(connection);
                if (e instanceof IllegalArgumentException illegal) throw illegal;
                throw new IllegalStateException("commit PRD node analysis failed", e);
            }
        } catch (SQLException e) {
            throw failure("commit PRD node analysis", e);
        }
    }

    public void failNode(String nodeId, String error) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE prd_analysis_nodes SET status='FAILED',error=?,updated_at=? WHERE id=?")) {
            statement.setString(1, value(error, 4_000));
            statement.setString(2, Instant.now().toString());
            statement.setString(3, nodeId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw failure("fail PRD analysis node", e);
        }
    }

    public AnalysisAction persistNodeAction(String jobId, String nodeId, String providerCallId,
                                            String name, String argumentsJson, String idempotencyKey) {
        String id = "prd_action_" + UUID.randomUUID();
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO prd_analysis_actions(id,job_id,node_id,provider_call_id,name,arguments_json,status," +
                        "idempotency_key,created_at) VALUES(?,?,?,?,?,?,'REQUESTED',?,?) " +
                        "ON CONFLICT(idempotency_key) DO NOTHING")) {
            statement.setString(1, id);
            statement.setString(2, jobId);
            statement.setString(3, nodeId);
            statement.setString(4, value(providerCallId, 240));
            statement.setString(5, text(name, "name", 120));
            statement.setString(6, json(argumentsJson, "{}"));
            statement.setString(7, text(idempotencyKey, "idempotencyKey", 500));
            statement.setString(8, now.toString());
            statement.executeUpdate();
            return findActionByKey(idempotencyKey).orElseThrow();
        } catch (SQLException e) {
            throw failure("persist PRD analysis action", e);
        }
    }

    public Optional<AnalysisAction> pendingNodeAction(String nodeId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM prd_analysis_actions WHERE node_id=? AND status='REQUESTED' ORDER BY created_at LIMIT 1")) {
            statement.setString(1, nodeId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(action(results)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find pending PRD analysis action", e);
        }
    }

    public List<AnalysisAction> actions(String jobId) {
        List<AnalysisAction> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM prd_analysis_actions WHERE job_id=? ORDER BY created_at,id")) {
            statement.setString(1, jobId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) values.add(action(results));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list PRD analysis actions", e);
        }
    }

    public List<AnalysisItem> items(String jobId) {
        List<AnalysisItem> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM prd_analysis_items WHERE job_id=? ORDER BY kind,item_id")) {
            statement.setString(1, jobId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) values.add(new AnalysisItem(results.getString("job_id"),
                        results.getString("item_id"), results.getString("node_id"), results.getString("kind"),
                        results.getString("name"), results.getString("payload_json"),
                        Instant.parse(results.getString("created_at"))));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list PRD analysis items", e);
        }
    }

    public Clarification upsertClarification(String jobId, String source, String severity,
                                             String category, String question, String fingerprint) {
        String id = "prd_question_" + UUID.randomUUID();
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO prd_analysis_clarifications(id,job_id,source,severity,category,question,fingerprint," +
                        "status,created_at) VALUES(?,?,?,?,?,?,?,'OPEN',?) " +
                        "ON CONFLICT(job_id,fingerprint) DO UPDATE SET severity=excluded.severity," +
                        "category=excluded.category,question=excluded.question")) {
            statement.setString(1, id);
            statement.setString(2, jobId);
            statement.setString(3, value(source, 80));
            statement.setString(4, value(severity, 20));
            statement.setString(5, value(category, 80));
            statement.setString(6, text(question, "question", 4_000));
            statement.setString(7, text(fingerprint, "fingerprint", 128));
            statement.setString(8, now.toString());
            statement.executeUpdate();
            return clarifications(jobId).stream().filter(value -> value.fingerprint().equals(fingerprint))
                    .findFirst().orElseThrow();
        } catch (SQLException e) {
            throw failure("upsert PRD clarification", e);
        }
    }

    public List<Clarification> clarifications(String jobId) {
        List<Clarification> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM prd_analysis_clarifications WHERE job_id=? ORDER BY created_at,id")) {
            statement.setString(1, jobId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) values.add(clarification(results));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list PRD clarifications", e);
        }
    }

    public Clarification resolveClarification(String jobId, String questionId, String answer) {
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE prd_analysis_clarifications SET status='RESOLVED',answer=?,resolved_at=? " +
                            "WHERE id=? AND job_id=? AND status='OPEN'")) {
                statement.setString(1, text(answer, "answer", 8_000));
                statement.setString(2, now.toString());
                statement.setString(3, questionId);
                statement.setString(4, jobId);
                if (statement.executeUpdate() == 0) throw new IllegalArgumentException("open clarification not found");
                appendEvent(connection, jobId, "clarification.resolved", jsonObject("questionId", questionId));
                connection.commit();
                return clarifications(jobId).stream().filter(value -> value.id().equals(questionId))
                        .findFirst().orElseThrow();
            } catch (Exception e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("resolve PRD clarification", e);
        }
    }

    public List<AnalysisEvent> events(String jobId, long after, int limit) {
        List<AnalysisEvent> values = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM prd_analysis_events WHERE job_id=? AND sequence>? ORDER BY sequence LIMIT ?")) {
            statement.setString(1, jobId);
            statement.setLong(2, Math.max(0, after));
            statement.setInt(3, Math.max(1, Math.min(limit, 500)));
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) values.add(new AnalysisEvent(results.getString("job_id"),
                        results.getLong("sequence"), results.getString("type"), results.getString("payload_json"),
                        Instant.parse(results.getString("created_at"))));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list PRD analysis events", e);
        }
    }

    private void allocateItems(Connection connection, String jobId, String nodeId, ObjectNode root,
                               String field, String kind, String prefix, Map<String, String> replacements,
                               Instant now) throws Exception {
        JsonNode value = root.path(field);
        if (!value.isArray()) {
            root.set(field, mapper.createArrayNode());
            return;
        }
        int sequence = nextItemSequence(connection, jobId, prefix);
        for (JsonNode element : (ArrayNode) value) {
            if (!(element instanceof ObjectNode item)) continue;
            String localId = item.path("id").asText("").trim();
            String globalId = prefix + String.format("%03d", sequence++);
            if (!localId.isEmpty()) replacements.put(localId, globalId);
            item.put("local_id", localId);
            item.put("id", globalId);
            String name = item.path("name").asText(item.path("title").asText(globalId));
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO prd_analysis_items(job_id,item_id,node_id,kind,name,payload_json,created_at) " +
                            "VALUES(?,?,?,?,?,?,?)")) {
                insert.setString(1, jobId);
                insert.setString(2, globalId);
                insert.setString(3, nodeId);
                insert.setString(4, kind);
                insert.setString(5, value(name, 500));
                insert.setString(6, mapper.writeValueAsString(item));
                insert.setString(7, now.toString());
                insert.executeUpdate();
            }
        }
    }

    private int nextItemSequence(Connection connection, String jobId, String prefix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT item_id FROM prd_analysis_items WHERE job_id=? AND item_id LIKE ? ORDER BY length(item_id) DESC,item_id DESC LIMIT 1")) {
            statement.setString(1, jobId);
            statement.setString(2, prefix + "%");
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) return 1;
                String id = results.getString(1);
                return Integer.parseInt(id.substring(prefix.length())) + 1;
            }
        }
    }

    private void updateItemPayloads(Connection connection, String jobId, ObjectNode root) throws Exception {
        for (String field : List.of("entities", "rules", "flows")) {
            JsonNode values = root.path(field);
            if (!values.isArray()) continue;
            for (JsonNode item : values) {
                String id = item.path("id").asText("");
                if (id.isBlank()) continue;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE prd_analysis_items SET payload_json=?,name=? WHERE job_id=? AND item_id=?")) {
                    update.setString(1, mapper.writeValueAsString(item));
                    update.setString(2, value(item.path("name").asText(item.path("title").asText(id)), 500));
                    update.setString(3, jobId);
                    update.setString(4, id);
                    update.executeUpdate();
                }
            }
        }
    }

    private void rewriteReferences(JsonNode node, Map<String, String> replacements) {
        if (node instanceof ObjectNode object) {
            object.fields().forEachRemaining(entry -> {
                JsonNode child = entry.getValue();
                if (!"local_id".equals(entry.getKey()) && child.isTextual()
                        && replacements.containsKey(child.asText())) {
                    object.put(entry.getKey(), replacements.get(child.asText()));
                } else {
                    rewriteReferences(child, replacements);
                }
            });
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode child = array.get(index);
                if (child.isTextual() && replacements.containsKey(child.asText())) {
                    array.set(index, mapper.getNodeFactory().textNode(replacements.get(child.asText())));
                } else {
                    rewriteReferences(child, replacements);
                }
            }
        }
    }

    private Optional<AnalysisNode> findNode(String id) {
        try (Connection connection = open()) {
            return findNode(connection, id);
        } catch (SQLException e) {
            throw failure("find PRD analysis node", e);
        }
    }

    private Optional<AnalysisNode> findNode(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM prd_analysis_nodes WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(node(results)) : Optional.empty();
            }
        }
    }

    private Optional<AnalysisAction> findActionByKey(String idempotencyKey) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM prd_analysis_actions WHERE idempotency_key=?")) {
            statement.setString(1, idempotencyKey);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(action(results)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find PRD analysis action", e);
        }
    }

    public void recordEvent(String jobId, String type, String payloadJson) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                appendEvent(connection, jobId, type, payloadJson);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("record PRD analysis event", e);
        }
    }

    private void appendEvent(Connection connection, String jobId, String type, String payloadJson) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO prd_analysis_events(job_id,sequence,type,payload_json,created_at) " +
                        "VALUES(?,COALESCE((SELECT MAX(sequence)+1 FROM prd_analysis_events WHERE job_id=?),1),?,?,?)")) {
            statement.setString(1, jobId);
            statement.setString(2, jobId);
            statement.setString(3, type);
            statement.setString(4, json(payloadJson, "{}"));
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private AnalysisJob job(ResultSet results) throws SQLException {
        return new AnalysisJob(results.getString("id"), results.getString("project_key"),
                results.getString("title"), results.getString("status"), results.getString("stage"),
                results.getString("prd_text"), results.getString("source_contract_json"),
                results.getString("config_json"), results.getString("artifact_dir"),
                results.getInt("progress_version"), results.getInt("repair_count"), results.getString("error"),
                instant(results.getString("created_at")), instant(results.getString("updated_at")),
                instant(results.getString("finished_at")));
    }

    private AnalysisNode node(ResultSet results) throws SQLException {
        return new AnalysisNode(results.getString("id"), results.getString("job_id"),
                results.getString("node_key"), results.getInt("ordinal"), results.getString("heading"),
                results.getInt("heading_level"), results.getInt("start_line"), results.getInt("end_line"),
                results.getString("content"), results.getString("dependencies_json"), results.getString("tags_json"),
                results.getString("status"), results.getString("analysis_json"), results.getString("error"),
                instant(results.getString("created_at")), instant(results.getString("updated_at")));
    }

    private Clarification clarification(ResultSet results) throws SQLException {
        return new Clarification(results.getString("id"), results.getString("job_id"), results.getString("source"),
                results.getString("severity"), results.getString("category"), results.getString("question"),
                results.getString("fingerprint"), results.getString("status"), results.getString("answer"),
                instant(results.getString("created_at")), instant(results.getString("resolved_at")));
    }

    private AnalysisAction action(ResultSet results) throws SQLException {
        return new AnalysisAction(results.getString("id"), results.getString("job_id"),
                results.getString("node_id"), results.getString("provider_call_id"),
                results.getString("name"), results.getString("arguments_json"), results.getString("status"),
                results.getString("result_json"), results.getString("error"), results.getString("idempotency_key"),
                instant(results.getString("created_at")), instant(results.getString("finished_at")));
    }

    private Connection open() throws SQLException {
        return connections.open();
    }

    private String jsonObject(String key, Object value) {
        try {
            return mapper.writeValueAsString(Map.of(key, value == null ? "" : value));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String json(String value, String fallback) {
        String resolved = value == null || value.isBlank() ? fallback : value.trim();
        try {
            mapper.readTree(resolved);
            return resolved;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON", e);
        }
    }

    private static String project(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }

    private static String text(String value, String name, int max) {
        String resolved = value == null ? "" : value.trim();
        if (resolved.isEmpty()) throw new IllegalArgumentException(name + " is required");
        if (resolved.length() > max) throw new IllegalArgumentException(name + " is too long");
        return resolved;
    }

    private static String value(String value, int max) {
        String resolved = value == null ? "" : value;
        return resolved.length() <= max ? resolved : resolved.substring(0, max);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static IllegalStateException failure(String action, SQLException error) {
        return new IllegalStateException(action + " failed", error);
    }

    public record AnalysisJob(String id, String projectKey, String title, String status, String stage,
                              String prdText, String sourceContractJson, String configJson, String artifactDir,
                              int progressVersion, int repairCount, String error, Instant createdAt,
                              Instant updatedAt, Instant finishedAt) { }

    public record NodeDraft(String nodeKey, int ordinal, String heading, int headingLevel,
                            int startLine, int endLine, String content,
                            String dependenciesJson, String tagsJson) { }

    public record AnalysisNode(String id, String jobId, String nodeKey, int ordinal, String heading,
                               int headingLevel, int startLine, int endLine, String content,
                               String dependenciesJson, String tagsJson, String status,
                               String analysisJson, String error, Instant createdAt, Instant updatedAt) { }

    public record AnalysisItem(String jobId, String itemId, String nodeId, String kind, String name,
                               String payloadJson, Instant createdAt) { }

    public record AnalysisAction(String id, String jobId, String nodeId, String providerCallId,
                                 String name, String argumentsJson, String status, String resultJson,
                                 String error, String idempotencyKey, Instant createdAt, Instant finishedAt) { }

    public record Clarification(String id, String jobId, String source, String severity, String category,
                                String question, String fingerprint, String status, String answer,
                                Instant createdAt, Instant resolvedAt) { }

    public record AnalysisEvent(String jobId, long sequence, String type, String payloadJson,
                                Instant createdAt) { }
}
