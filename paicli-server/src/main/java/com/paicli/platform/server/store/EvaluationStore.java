package com.paicli.platform.server.store;

import com.paicli.platform.server.config.PlatformProperties;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EvaluationStore {
    private final SqliteConnectionFactory connections;

    public EvaluationStore(PlatformProperties properties) {
        this.connections = new SqliteConnectionFactory(
                properties.dataDir().resolve("paicli.db").toAbsolutePath().normalize());
    }

    public List<EvaluationSuite> suites(String projectKey) {
        List<EvaluationSuite> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM evaluation_suites WHERE project_key=? ORDER BY updated_at DESC")) {
            ps.setString(1, project(projectKey));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(suite(rs)); }
            return values;
        } catch (SQLException e) { throw failure("list evaluation suites", e); }
    }

    public Optional<EvaluationSuite> suite(String id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM evaluation_suites WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(suite(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find evaluation suite", e); }
    }

    public EvaluationSuite saveSuite(String id, String projectKey, String name, String description,
                                     int defaultTrials, int passThreshold) {
        Instant now = Instant.now();
        String resolvedId = blank(id) ? id("eval-suite") : id;
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(blank(id)
                ? "INSERT INTO evaluation_suites(id,project_key,name,description,default_trials,pass_threshold,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)"
                : "UPDATE evaluation_suites SET project_key=?,name=?,description=?,default_trials=?,pass_threshold=?,updated_at=? WHERE id=?")) {
            if (blank(id)) {
                ps.setString(1, resolvedId); ps.setString(2, project(projectKey));
                ps.setString(3, text(name, "name", 120)); ps.setString(4, optional(description, 1000));
                ps.setInt(5, range(defaultTrials, 1, 10, "defaultTrials"));
                ps.setInt(6, range(passThreshold, 1, 100, "passThreshold"));
                ps.setString(7, now.toString()); ps.setString(8, now.toString());
            } else {
                ps.setString(1, project(projectKey)); ps.setString(2, text(name, "name", 120));
                ps.setString(3, optional(description, 1000));
                ps.setInt(4, range(defaultTrials, 1, 10, "defaultTrials"));
                ps.setInt(5, range(passThreshold, 1, 100, "passThreshold"));
                ps.setString(6, now.toString()); ps.setString(7, resolvedId);
            }
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("evaluation suite not found: " + id);
            return suite(resolvedId).orElseThrow();
        } catch (SQLException e) { throw failure("save evaluation suite", e); }
    }

    public boolean deleteSuite(String id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM evaluation_suites WHERE id=?")) {
            ps.setString(1, id); return ps.executeUpdate() > 0;
        } catch (SQLException e) { throw failure("delete evaluation suite", e); }
    }

    public List<EvaluationCase> cases(String suiteId) {
        List<EvaluationCase> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM evaluation_cases WHERE suite_id=? ORDER BY name")) {
            ps.setString(1, suiteId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(evaluationCase(rs)); }
            return values;
        } catch (SQLException e) { throw failure("list evaluation cases", e); }
    }

    public Optional<EvaluationCase> evaluationCase(String id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM evaluation_cases WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(evaluationCase(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find evaluation case", e); }
    }

    public EvaluationCase saveCase(String id, String suiteId, String name, String prompt,
                                   String requiredToolsJson, String forbiddenToolsJson,
                                   String requiredResponseJson, String forbiddenResponseJson,
                                   int maxToolCalls, int maxTokens, long maxDurationMs, boolean enabled) {
        if (suite(suiteId).isEmpty()) throw new IllegalArgumentException("evaluation suite not found: " + suiteId);
        Instant now = Instant.now();
        String resolvedId = blank(id) ? id("eval-case") : id;
        String sql = blank(id)
                ? "INSERT INTO evaluation_cases(id,suite_id,name,prompt,required_tools_json,forbidden_tools_json,required_response_json,forbidden_response_json,max_tool_calls,max_tokens,max_duration_ms,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                : "UPDATE evaluation_cases SET suite_id=?,name=?,prompt=?,required_tools_json=?,forbidden_tools_json=?,required_response_json=?,forbidden_response_json=?,max_tool_calls=?,max_tokens=?,max_duration_ms=?,enabled=?,updated_at=? WHERE id=?";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (blank(id)) ps.setString(i++, resolvedId);
            ps.setString(i++, suiteId); ps.setString(i++, text(name, "name", 120));
            ps.setString(i++, text(prompt, "prompt", 32_000));
            ps.setString(i++, json(requiredToolsJson)); ps.setString(i++, json(forbiddenToolsJson));
            ps.setString(i++, json(requiredResponseJson)); ps.setString(i++, json(forbiddenResponseJson));
            ps.setInt(i++, Math.max(0, maxToolCalls)); ps.setInt(i++, Math.max(0, maxTokens));
            ps.setLong(i++, Math.max(0, maxDurationMs)); ps.setInt(i++, enabled ? 1 : 0);
            if (blank(id)) ps.setString(i++, now.toString());
            ps.setString(i++, now.toString());
            if (!blank(id)) ps.setString(i, resolvedId);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("evaluation case not found: " + id);
            return evaluationCase(resolvedId).orElseThrow();
        } catch (SQLException e) { throw failure("save evaluation case", e); }
    }

    public boolean deleteCase(String id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM evaluation_cases WHERE id=?")) {
            ps.setString(1, id); return ps.executeUpdate() > 0;
        } catch (SQLException e) { throw failure("delete evaluation case", e); }
    }

    public EvaluationExecution createExecution(EvaluationSuite suite, String modelProfileId,
                                               int trialCount, int passThreshold) {
        String id = id("eval-exec"); Instant now = Instant.now();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO evaluation_executions(id,suite_id,project_key,status,model_profile_id,trial_count,pass_threshold,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, suite.id()); ps.setString(3, suite.projectKey());
            ps.setString(4, "RUNNING"); ps.setString(5, blank(modelProfileId) ? null : modelProfileId);
            ps.setInt(6, range(trialCount, 1, 10, "trialCount"));
            ps.setInt(7, range(passThreshold, 1, 100, "passThreshold")); ps.setString(8, now.toString());
            ps.executeUpdate(); return execution(id).orElseThrow();
        } catch (SQLException e) { throw failure("create evaluation execution", e); }
    }

    public List<EvaluationExecution> executions(String suiteId, int limit) {
        List<EvaluationExecution> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM evaluation_executions WHERE suite_id=? ORDER BY created_at DESC LIMIT ?")) {
            ps.setString(1, suiteId); ps.setInt(2, Math.max(1, Math.min(limit, 100)));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(execution(rs)); }
            return values;
        } catch (SQLException e) { throw failure("list evaluation executions", e); }
    }

    public Optional<EvaluationExecution> execution(String id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM evaluation_executions WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(execution(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find evaluation execution", e); }
    }

    public EvaluationTrial addTrial(String executionId, String caseId, int ordinal,
                                    String sessionId, String runId) {
        String id = id("eval-trial"); Instant now = Instant.now();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO evaluation_trials(id,execution_id,case_id,ordinal,session_id,run_id,status,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, executionId); ps.setString(3, caseId);
            ps.setInt(4, ordinal); ps.setString(5, sessionId); ps.setString(6, runId);
            ps.setString(7, "RUNNING"); ps.setString(8, now.toString()); ps.executeUpdate();
            return trial(id).orElseThrow();
        } catch (SQLException e) { throw failure("create evaluation trial", e); }
    }

    public List<EvaluationTrial> trials(String executionId) {
        List<EvaluationTrial> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM evaluation_trials WHERE execution_id=? ORDER BY case_id,ordinal")) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(trial(rs)); }
            return values;
        } catch (SQLException e) { throw failure("list evaluation trials", e); }
    }

    public Optional<EvaluationTrial> trial(String id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM evaluation_trials WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(trial(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find evaluation trial", e); }
    }

    public void completeTrial(String id, String status, int score, boolean passed, String detailsJson) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE evaluation_trials SET status=?,score=?,passed=?,details_json=?,completed_at=? WHERE id=?")) {
            ps.setString(1, status); ps.setInt(2, score); ps.setInt(3, passed ? 1 : 0);
            ps.setString(4, detailsJson); ps.setString(5, Instant.now().toString()); ps.setString(6, id);
            ps.executeUpdate();
        } catch (SQLException e) { throw failure("complete evaluation trial", e); }
    }

    public void completeExecution(String id, String status, double averageScore, boolean passed) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE evaluation_executions SET status=?,average_score=?,passed=?,completed_at=? WHERE id=?")) {
            ps.setString(1, status); ps.setDouble(2, averageScore); ps.setInt(3, passed ? 1 : 0);
            ps.setString(4, Instant.now().toString()); ps.setString(5, id); ps.executeUpdate();
        } catch (SQLException e) { throw failure("complete evaluation execution", e); }
    }

    public Optional<EvaluationBaseline> baseline(String caseId) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM evaluation_baselines WHERE case_id=?")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(baseline(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find evaluation baseline", e); }
    }

    public EvaluationBaseline saveBaseline(String caseId, String sourceRunId, String response,
                                           String toolNamesJson, int tokens, long durationMs) {
        Instant now = Instant.now();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO evaluation_baselines(case_id,source_run_id,response,tool_names_json,tokens,duration_ms,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(case_id) DO UPDATE SET source_run_id=excluded.source_run_id," +
                        "response=excluded.response,tool_names_json=excluded.tool_names_json,tokens=excluded.tokens," +
                        "duration_ms=excluded.duration_ms,updated_at=excluded.updated_at")) {
            ps.setString(1, caseId); ps.setString(2, sourceRunId); ps.setString(3, response == null ? "" : response);
            ps.setString(4, toolNamesJson); ps.setInt(5, Math.max(0, tokens)); ps.setLong(6, Math.max(0, durationMs));
            ps.setString(7, now.toString()); ps.setString(8, now.toString()); ps.executeUpdate();
            return baseline(caseId).orElseThrow();
        } catch (SQLException e) { throw failure("save evaluation baseline", e); }
    }

    private Connection open() throws SQLException { return connections.open(); }
    private static String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", ""); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String project(String value) {
        String normalized = blank(value) ? "default" : value.trim();
        if (!normalized.matches("[a-zA-Z0-9_.-]{1,80}")) throw new IllegalArgumentException("invalid projectKey");
        return normalized;
    }
    private static String text(String value, String name, int limit) {
        if (blank(value)) throw new IllegalArgumentException(name + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > limit) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }
    private static String optional(String value, int limit) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > limit) throw new IllegalArgumentException("value is too long");
        return normalized;
    }
    private static String json(String value) { return blank(value) ? "[]" : value; }
    private static int range(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        return value;
    }
    private static Instant instant(String value) { return blank(value) ? null : Instant.parse(value); }
    private static IllegalStateException failure(String action, SQLException e) { return new IllegalStateException(action + " failed", e); }

    private static EvaluationSuite suite(ResultSet rs) throws SQLException {
        return new EvaluationSuite(rs.getString("id"), rs.getString("project_key"), rs.getString("name"),
                rs.getString("description"), rs.getInt("default_trials"), rs.getInt("pass_threshold"),
                instant(rs.getString("created_at")), instant(rs.getString("updated_at")));
    }
    private static EvaluationCase evaluationCase(ResultSet rs) throws SQLException {
        return new EvaluationCase(rs.getString("id"), rs.getString("suite_id"), rs.getString("name"),
                rs.getString("prompt"), rs.getString("required_tools_json"), rs.getString("forbidden_tools_json"),
                rs.getString("required_response_json"), rs.getString("forbidden_response_json"),
                rs.getInt("max_tool_calls"), rs.getInt("max_tokens"), rs.getLong("max_duration_ms"),
                rs.getInt("enabled") != 0, instant(rs.getString("created_at")), instant(rs.getString("updated_at")));
    }
    private static EvaluationExecution execution(ResultSet rs) throws SQLException {
        Double score = rs.getObject("average_score") == null ? null : rs.getDouble("average_score");
        Boolean passed = rs.getObject("passed") == null ? null : rs.getInt("passed") != 0;
        return new EvaluationExecution(rs.getString("id"), rs.getString("suite_id"), rs.getString("project_key"),
                rs.getString("status"), rs.getString("model_profile_id"), rs.getInt("trial_count"),
                rs.getInt("pass_threshold"), score, passed, instant(rs.getString("created_at")),
                instant(rs.getString("completed_at")));
    }
    private static EvaluationTrial trial(ResultSet rs) throws SQLException {
        Integer score = rs.getObject("score") == null ? null : rs.getInt("score");
        Boolean passed = rs.getObject("passed") == null ? null : rs.getInt("passed") != 0;
        return new EvaluationTrial(rs.getString("id"), rs.getString("execution_id"), rs.getString("case_id"),
                rs.getInt("ordinal"), rs.getString("session_id"), rs.getString("run_id"), rs.getString("status"),
                score, passed, rs.getString("details_json"), instant(rs.getString("created_at")),
                instant(rs.getString("completed_at")));
    }
    private static EvaluationBaseline baseline(ResultSet rs) throws SQLException {
        return new EvaluationBaseline(rs.getString("case_id"), rs.getString("source_run_id"),
                rs.getString("response"), rs.getString("tool_names_json"), rs.getInt("tokens"),
                rs.getLong("duration_ms"), instant(rs.getString("created_at")), instant(rs.getString("updated_at")));
    }

    public record EvaluationSuite(String id, String projectKey, String name, String description,
                                  int defaultTrials, int passThreshold, Instant createdAt, Instant updatedAt) { }
    public record EvaluationCase(String id, String suiteId, String name, String prompt,
                                 String requiredToolsJson, String forbiddenToolsJson,
                                 String requiredResponseJson, String forbiddenResponseJson,
                                 int maxToolCalls, int maxTokens, long maxDurationMs, boolean enabled,
                                 Instant createdAt, Instant updatedAt) { }
    public record EvaluationExecution(String id, String suiteId, String projectKey, String status,
                                      String modelProfileId, int trialCount, int passThreshold,
                                      Double averageScore, Boolean passed, Instant createdAt, Instant completedAt) { }
    public record EvaluationTrial(String id, String executionId, String caseId, int ordinal,
                                  String sessionId, String runId, String status, Integer score,
                                  Boolean passed, String detailsJson, Instant createdAt, Instant completedAt) { }
    public record EvaluationBaseline(String caseId, String sourceRunId, String response,
                                     String toolNamesJson, int tokens, long durationMs,
                                     Instant createdAt, Instant updatedAt) { }
}
