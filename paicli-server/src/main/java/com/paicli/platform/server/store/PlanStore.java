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
public class PlanStore {
    private final SqliteConnectionFactory connections;

    public PlanStore(PlatformProperties properties) {
        this.connections = new SqliteConnectionFactory(
                properties.dataDir().resolve("paicli.db").toAbsolutePath().normalize());
    }

    public Plan savePlan(String sessionId, String runId, String projectKey, String objective, String summary,
                         String source, String rawPlanJson, String validationErrorsJson,
                         List<StepDraft> steps, List<EdgeDraft> edges) {
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("plan must contain at least one step");
        String planId = id("plan");
        String now = Instant.now().toString();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO plans(id,session_id,run_id,project_key,objective,summary,status,version,source," +
                                "raw_plan_json,validation_errors_json,created_at,updated_at) " +
                                "VALUES(?,?,?,?,?,?,'WAITING_APPROVAL',1,?,?,?,?,?)")) {
                    ps.setString(1, planId);
                    ps.setString(2, nullable(sessionId));
                    ps.setString(3, nullable(runId));
                    ps.setString(4, project(projectKey));
                    ps.setString(5, text(objective, "objective", 8_000));
                    ps.setString(6, value(summary, 2_000));
                    ps.setString(7, value(source, 40).isBlank() ? "MANUAL" : source.trim().toUpperCase());
                    ps.setString(8, json(rawPlanJson, 128_000));
                    ps.setString(9, listJson(validationErrorsJson));
                    ps.setString(10, now);
                    ps.setString(11, now);
                    ps.executeUpdate();
                }
                insertSteps(c, planId, steps, now);
                insertEdges(c, planId, edges, now);
                insertValidationChecks(c, planId, steps, now);
                appendEvent(c, planId, null, "plan.created", "{\"steps\":" + steps.size() + "}", now);
                c.commit();
                return findPlan(planId).orElseThrow();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("save plan", e);
        }
    }

    public Optional<Plan> findPlan(String id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("SELECT * FROM plans WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(plan(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find plan", e);
        }
    }

    public List<Plan> plans(String projectKey, int limit) {
        List<Plan> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM plans WHERE project_key=? ORDER BY updated_at DESC LIMIT ?")) {
            ps.setString(1, project(projectKey));
            ps.setInt(2, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(plan(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list plans", e);
        }
    }

    public List<Plan> plansForSession(String sessionId, int limit) {
        List<Plan> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT p.* FROM plans p " +
                        "LEFT JOIN plan_steps ps ON ps.plan_id=p.id " +
                        "WHERE p.session_id=? " +
                        "OR p.run_id IN (SELECT id FROM runs WHERE session_id=?) " +
                        "OR ps.run_id IN (SELECT id FROM runs WHERE session_id=?) " +
                        "ORDER BY p.updated_at DESC LIMIT ?")) {
            String id = nullable(sessionId);
            ps.setString(1, id);
            ps.setString(2, id);
            ps.setString(3, id);
            ps.setInt(4, Math.max(1, Math.min(limit, 20)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(plan(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list session plans", e);
        }
    }

    public List<PlanStep> steps(String planId) {
        List<PlanStep> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM plan_steps WHERE plan_id=? ORDER BY ordinal")) {
            ps.setString(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(step(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list plan steps", e);
        }
    }

    public List<PlanEdge> edges(String planId) {
        List<PlanEdge> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM plan_edges WHERE plan_id=? ORDER BY from_step_id,to_step_id")) {
            ps.setString(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(edge(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list plan edges", e);
        }
    }

    public List<PlanEvent> events(String planId, long after, int limit) {
        List<PlanEvent> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM plan_events WHERE plan_id=? AND id>? ORDER BY id LIMIT ?")) {
            ps.setString(1, planId);
            ps.setLong(2, Math.max(0, after));
            ps.setInt(3, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(event(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list plan events", e);
        }
    }

    public List<Plan> activePlans(int limit) {
        List<Plan> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM plans WHERE status='ACTIVE' ORDER BY updated_at LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 100)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(plan(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list active plans", e);
        }
    }

    public List<PlanStep> readySteps(String planId, int limit) {
        List<PlanStep> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM plan_steps WHERE plan_id=? AND status='READY' ORDER BY ordinal LIMIT ?")) {
            ps.setString(1, planId);
            ps.setInt(2, Math.max(1, Math.min(limit, 20)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(step(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list ready plan steps", e);
        }
    }

    public Optional<PlanStep> findStepByRun(String runId) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM plan_steps WHERE run_id=? ORDER BY updated_at DESC LIMIT 1")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(step(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find plan step by run", e);
        }
    }

    public Plan activate(String planId, String eventType) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                Plan plan = findPlan(c, planId).orElseThrow(() -> new IllegalArgumentException("plan not found"));
                if (!List.of("DRAFT", "WAITING_APPROVAL").contains(plan.status())) {
                    throw new IllegalStateException("only draft or waiting approval plans can be started");
                }
                String now = Instant.now().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plans SET status='ACTIVE',started_at=COALESCE(started_at,?),updated_at=? WHERE id=?")) {
                    ps.setString(1, now);
                    ps.setString(2, now);
                    ps.setString(3, planId);
                    ps.executeUpdate();
                }
                markReadySteps(c, planId, now);
                appendEvent(c, planId, null, eventType, "{}", now);
                c.commit();
                return findPlan(planId).orElseThrow();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("activate plan", e);
        }
    }

    /** Activates an automatic orchestration plan and binds its root Run atomically. */
    public Plan activateAndBindFirstStep(String planId, String runId) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                Plan plan = findPlan(c, planId).orElseThrow(() -> new IllegalArgumentException("plan not found"));
                if (!List.of("DRAFT", "WAITING_APPROVAL").contains(plan.status())) {
                    throw new IllegalStateException("only draft or waiting approval plans can be started");
                }
                String now = Instant.now().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plans SET status='ACTIVE',started_at=COALESCE(started_at,?),updated_at=? WHERE id=?")) {
                    ps.setString(1, now);
                    ps.setString(2, now);
                    ps.setString(3, planId);
                    ps.executeUpdate();
                }
                markReadySteps(c, planId, now);
                PlanStep root;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM plan_steps WHERE plan_id=? AND status='READY' ORDER BY ordinal LIMIT 1")) {
                    ps.setString(1, planId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalStateException("automatic plan has no ready root step");
                        root = step(rs);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plan_steps SET status='RUNNING',run_id=?,started_at=COALESCE(started_at,?),updated_at=? " +
                                "WHERE id=? AND status='READY'")) {
                    ps.setString(1, runId);
                    ps.setString(2, now);
                    ps.setString(3, now);
                    ps.setString(4, root.id());
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("automatic plan root is not ready");
                }
                appendEvent(c, planId, null, "plan.started", "{}", now);
                appendEvent(c, planId, root.id(), "plan_step.run_bound", "{\"runId\":\"" + escape(runId) + "\"}", now);
                c.commit();
                return findPlan(planId).orElseThrow();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("activate and bind automatic plan", e);
        }
    }

    public Plan cancel(String planId) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                Plan plan = findPlan(c, planId).orElseThrow(() -> new IllegalArgumentException("plan not found"));
                if (List.of("COMPLETED", "FAILED", "CANCELED").contains(plan.status())) return plan;
                String now = Instant.now().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plans SET status='CANCELED',completed_at=?,updated_at=? WHERE id=?")) {
                    ps.setString(1, now);
                    ps.setString(2, now);
                    ps.setString(3, planId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plan_steps SET status='CANCELED',updated_at=? " +
                                "WHERE plan_id=? AND status IN ('PENDING','READY','RUNNING','WAITING_APPROVAL','WAITING_JOB')")) {
                    ps.setString(1, now);
                    ps.setString(2, planId);
                    ps.executeUpdate();
                }
                appendEvent(c, planId, null, "plan.canceled", "{}", now);
                c.commit();
                return findPlan(planId).orElseThrow();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("cancel plan", e);
        }
    }

    public Optional<PlanStep> claimReadyStep(String stepId) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                PlanStep current = findStep(c, stepId).orElseThrow(() ->
                        new IllegalArgumentException("plan step not found"));
                String now = Instant.now().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plan_steps SET status='RUNNING',started_at=COALESCE(started_at,?),updated_at=? " +
                                "WHERE id=? AND status='READY'")) {
                    ps.setString(1, now);
                    ps.setString(2, now);
                    ps.setString(3, stepId);
                    if (ps.executeUpdate() != 1) {
                        rollback(c);
                        return Optional.empty();
                    }
                }
                appendEvent(c, current.planId(), stepId, "plan_step.claimed", "{}", now);
                c.commit();
                return findStep(stepId);
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("claim plan step", e);
        }
    }

    public PlanStep bindStepRun(String stepId, String runId, boolean asyncJob) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                PlanStep current = findStep(c, stepId).orElseThrow(() ->
                        new IllegalArgumentException("plan step not found"));
                String now = Instant.now().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plan_steps SET status=?,run_id=?,updated_at=? WHERE id=? " +
                                "AND status IN ('RUNNING','WAITING_JOB')")) {
                    ps.setString(1, asyncJob ? "WAITING_JOB" : "RUNNING");
                    ps.setString(2, runId);
                    ps.setString(3, now);
                    ps.setString(4, stepId);
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("plan step is not claimed");
                }
                appendEvent(c, current.planId(), stepId, asyncJob ? "plan_step.job_bound" : "plan_step.run_bound",
                        "{\"runId\":\"" + escape(runId) + "\"}", now);
                c.commit();
                return findStep(stepId).orElseThrow();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("bind plan step run", e);
        }
    }

    public void markStepWaitingApproval(String stepId) {
        setStepRuntimeStatus(stepId, "WAITING_APPROVAL", null, "plan_step.waiting_approval");
    }

    public void markStepRunningAgain(String stepId) {
        setStepRuntimeStatus(stepId, "RUNNING", null, "plan_step.resumed");
    }

    public void completeStep(String stepId, String resultSummary) {
        setStepTerminal(stepId, "COMPLETED", resultSummary, null, "plan_step.completed");
    }

    public void failStep(String stepId, String reason) {
        setStepTerminal(stepId, "FAILED", null, reason, "plan_step.failed");
    }

    public void cancelStep(String stepId, String reason) {
        setStepTerminal(stepId, "CANCELED", null, reason, "plan_step.canceled");
    }

    public void releaseStep(String stepId, String reason) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                PlanStep current = findStep(c, stepId).orElseThrow(() ->
                        new IllegalArgumentException("plan step not found"));
                String now = Instant.now().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plan_steps SET status='READY',failure_reason=?,updated_at=? " +
                                "WHERE id=? AND status='RUNNING' AND run_id IS NULL")) {
                    ps.setString(1, nullable(reason));
                    ps.setString(2, now);
                    ps.setString(3, stepId);
                    ps.executeUpdate();
                }
                appendEvent(c, current.planId(), stepId, "plan_step.released",
                        "{\"reason\":\"" + escape(reason) + "\"}", now);
                c.commit();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("release plan step", e);
        }
    }

    public void refreshReadySteps(String planId) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                String now = Instant.now().toString();
                markReadySteps(c, planId, now);
                finishPlanIfTerminal(c, planId, now);
                c.commit();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("refresh ready plan steps", e);
        }
    }

    public void failPlan(String planId, String reason) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                String now = Instant.now().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plans SET status='FAILED',failure_reason=?,completed_at=?,updated_at=? " +
                                "WHERE id=? AND status NOT IN ('COMPLETED','FAILED','CANCELED')")) {
                    ps.setString(1, value(reason, 1_000));
                    ps.setString(2, now);
                    ps.setString(3, now);
                    ps.setString(4, planId);
                    ps.executeUpdate();
                }
                appendEvent(c, planId, null, "plan.failed", "{\"reason\":\"" + escape(reason) + "\"}", now);
                c.commit();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("fail plan", e);
        }
    }

    public Plan replacePlan(String planId, String reason, String rawPlanJson, String summary,
                            List<StepDraft> steps, List<EdgeDraft> edges) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                Plan plan = findPlan(c, planId).orElseThrow(() -> new IllegalArgumentException("plan not found"));
                if ("ACTIVE".equals(plan.status())) {
                    throw new IllegalStateException("active plan replan is not implemented in this phase");
                }
                int nextVersion = plan.version() + 1;
                String now = Instant.now().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO plan_revisions(id,plan_id,version,reason,raw_plan_json,created_at) VALUES(?,?,?,?,?,?)")) {
                    ps.setString(1, id("planrev"));
                    ps.setString(2, planId);
                    ps.setInt(3, nextVersion);
                    ps.setString(4, value(reason, 1_000));
                    ps.setString(5, json(rawPlanJson, 128_000));
                    ps.setString(6, now);
                    ps.executeUpdate();
                }
                deletePlanChildren(c, planId);
                insertSteps(c, planId, steps, now);
                insertEdges(c, planId, edges, now);
                insertValidationChecks(c, planId, steps, now);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plans SET summary=?,status='WAITING_APPROVAL',version=?,raw_plan_json=?," +
                                "validation_errors_json='[]',updated_at=?,failure_reason=NULL WHERE id=?")) {
                    ps.setString(1, value(summary, 2_000));
                    ps.setInt(2, nextVersion);
                    ps.setString(3, json(rawPlanJson, 128_000));
                    ps.setString(4, now);
                    ps.setString(5, planId);
                    ps.executeUpdate();
                }
                appendEvent(c, planId, null, "plan.replanned", "{\"version\":" + nextVersion + "}", now);
                c.commit();
                return findPlan(planId).orElseThrow();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("replace plan", e);
        }
    }

    public PlanStep retryStep(String stepId) {
        return updateStepStatus(stepId, "PENDING", null, "plan_step.retry");
    }

    public PlanStep skipStep(String stepId, String reason) {
        return updateStepStatus(stepId, "SKIPPED", reason, "plan_step.skipped");
    }

    public AsyncJob createAsyncJob(String planId, String stepId, String runId, String projectKey,
                                   String kind, String payloadJson, String idempotencyKey) {
        Optional<AsyncJob> existing = findAsyncJobByKey(idempotencyKey);
        if (existing.isPresent()) return existing.get();
        String jobId = id("job");
        String now = Instant.now().toString();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO async_jobs(id,plan_id,step_id,run_id,project_key,kind,status,idempotency_key," +
                        "payload_json,result_json,log,error,attempts,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,'{}','{}','',NULL,0,?,?)")) {
            ps.setString(1, jobId);
            ps.setString(2, nullable(planId));
            ps.setString(3, nullable(stepId));
            ps.setString(4, nullable(runId));
            ps.setString(5, project(projectKey));
            ps.setString(6, text(kind == null ? "GENERIC" : kind.toUpperCase(), "kind", 80));
            ps.setString(7, "QUEUED");
            ps.setString(8, text(idempotencyKey, "idempotencyKey", 300));
            ps.setString(9, now);
            ps.setString(10, now);
            ps.executeUpdate();
            if (payloadJson != null && !payloadJson.isBlank()) {
                try (PreparedStatement update = c.prepareStatement(
                        "UPDATE async_jobs SET payload_json=? WHERE id=?")) {
                    update.setString(1, json(payloadJson, 128_000));
                    update.setString(2, jobId);
                    update.executeUpdate();
                }
            }
            return findAsyncJob(jobId).orElseThrow();
        } catch (SQLException e) {
            throw failure("create async job", e);
        }
    }

    public Optional<AsyncJob> findAsyncJob(String id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("SELECT * FROM async_jobs WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(job(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find async job", e);
        }
    }

    public Optional<AsyncJob> findAsyncJobByKey(String idempotencyKey) {
        if (blank(idempotencyKey)) return Optional.empty();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM async_jobs WHERE idempotency_key=?")) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(job(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("find async job by key", e);
        }
    }

    public List<AsyncJob> asyncJobs(String planId, int limit) {
        List<AsyncJob> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM async_jobs WHERE plan_id=? ORDER BY updated_at DESC LIMIT ?")) {
            ps.setString(1, planId);
            ps.setInt(2, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(job(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list async jobs", e);
        }
    }

    public AsyncJob bindAsyncJobRun(String jobId, String runId) {
        String now = Instant.now().toString();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE async_jobs SET run_id=?,status='RUNNING',started_at=COALESCE(started_at,?),updated_at=? " +
                        "WHERE id=? AND status IN ('QUEUED','RUNNING')")) {
            ps.setString(1, runId);
            ps.setString(2, now);
            ps.setString(3, now);
            ps.setString(4, jobId);
            ps.executeUpdate();
            return findAsyncJob(jobId).orElseThrow();
        } catch (SQLException e) {
            throw failure("bind async job run", e);
        }
    }

    public AsyncJob completeAsyncJob(String jobId, String resultJson, String log) {
        return finishAsyncJob(jobId, "COMPLETED", resultJson, log, null);
    }

    public AsyncJob failAsyncJob(String jobId, String error, String log) {
        return finishAsyncJob(jobId, "FAILED", "{}", log, error);
    }

    public AsyncJob cancelAsyncJob(String jobId) {
        return finishAsyncJob(jobId, "CANCELED", "{}", "", "job canceled");
    }

    public List<ValidationCheck> validationChecks(String planId, int limit) {
        List<ValidationCheck> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM validation_checks WHERE plan_id=? ORDER BY created_at, id LIMIT ?")) {
            ps.setString(1, planId);
            ps.setInt(2, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(check(rs));
            }
            return values;
        } catch (SQLException e) {
            throw failure("list validation checks", e);
        }
    }

    public void passStepChecks(String stepId, String evidence) {
        finishStepChecks(stepId, "PASSED", "", evidence);
    }

    public void failStepChecks(String stepId, String error, String evidence) {
        finishStepChecks(stepId, "FAILED", error, evidence);
    }

    private PlanStep updateStepStatus(String stepId, String status, String reason, String eventType) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                PlanStep current = findStep(c, stepId).orElseThrow(() -> new IllegalArgumentException("plan step not found"));
                String now = Instant.now().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plan_steps SET status=?,failure_reason=?,updated_at=?,completed_at=CASE WHEN ? IN " +
                                "('COMPLETED','FAILED','SKIPPED','CANCELED') THEN ? ELSE completed_at END WHERE id=?")) {
                    ps.setString(1, status);
                    ps.setString(2, nullable(reason));
                    ps.setString(3, now);
                    ps.setString(4, status);
                    ps.setString(5, now);
                    ps.setString(6, stepId);
                    ps.executeUpdate();
                }
                appendEvent(c, current.planId(), stepId, eventType, "{\"status\":\"" + status + "\"}", now);
                c.commit();
                return findStep(stepId).orElseThrow();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("update plan step", e);
        }
    }

    private void setStepRuntimeStatus(String stepId, String status, String reason, String eventType) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                PlanStep current = findStep(c, stepId).orElseThrow(() ->
                        new IllegalArgumentException("plan step not found"));
                String now = Instant.now().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plan_steps SET status=?,failure_reason=?,updated_at=? WHERE id=? " +
                                "AND status IN ('RUNNING','WAITING_APPROVAL','WAITING_JOB')")) {
                    ps.setString(1, status);
                    ps.setString(2, nullable(reason));
                    ps.setString(3, now);
                    ps.setString(4, stepId);
                    ps.executeUpdate();
                }
                appendEvent(c, current.planId(), stepId, eventType, "{\"status\":\"" + status + "\"}", now);
                c.commit();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("update plan step runtime status", e);
        }
    }

    private void setStepTerminal(String stepId, String status, String resultSummary, String reason, String eventType) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                PlanStep current = findStep(c, stepId).orElseThrow(() ->
                        new IllegalArgumentException("plan step not found"));
                String now = Instant.now().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE plan_steps SET status=?,result_summary=?,failure_reason=?,completed_at=?," +
                                "updated_at=? WHERE id=? AND status NOT IN ('COMPLETED','FAILED','SKIPPED','CANCELED')")) {
                    ps.setString(1, status);
                    ps.setString(2, nullable(resultSummary));
                    ps.setString(3, nullable(reason));
                    ps.setString(4, now);
                    ps.setString(5, now);
                    ps.setString(6, stepId);
                    ps.executeUpdate();
                }
                if ("COMPLETED".equals(status)) {
                    finishStepChecks(c, stepId, "PASSED", "", "step completed", now);
                } else {
                    finishStepChecks(c, stepId, "FAILED", reason == null ? status : reason,
                            "step did not complete", now);
                }
                appendEvent(c, current.planId(), stepId, eventType, "{\"status\":\"" + status + "\"}", now);
                markReadySteps(c, current.planId(), now);
                finishPlanIfTerminal(c, current.planId(), now);
                c.commit();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("finish plan step", e);
        }
    }

    private AsyncJob finishAsyncJob(String jobId, String status, String resultJson, String log, String error) {
        String now = Instant.now().toString();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE async_jobs SET status=?,result_json=?,log=CASE WHEN ?='' THEN log ELSE ? END," +
                        "error=?,completed_at=CASE WHEN ? IN ('COMPLETED','FAILED','CANCELED') THEN ? ELSE completed_at END," +
                        "updated_at=? WHERE id=? AND status NOT IN ('COMPLETED','FAILED','CANCELED')")) {
            ps.setString(1, status);
            ps.setString(2, json(resultJson, 128_000));
            ps.setString(3, log == null ? "" : log);
            ps.setString(4, value(log, 32_000));
            ps.setString(5, nullable(error));
            ps.setString(6, status);
            ps.setString(7, now);
            ps.setString(8, now);
            ps.setString(9, jobId);
            ps.executeUpdate();
            return findAsyncJob(jobId).orElseThrow();
        } catch (SQLException e) {
            throw failure("finish async job", e);
        }
    }

    private void finishStepChecks(String stepId, String status, String error, String evidence) {
        try (Connection c = open()) {
            finishStepChecks(c, stepId, status, error, evidence, Instant.now().toString());
        } catch (SQLException e) {
            throw failure("finish validation checks", e);
        }
    }

    private void finishStepChecks(Connection c, String stepId, String status, String error, String evidence, String now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE validation_checks SET status=?,actual=?,evidence=?,error=?,completed_at=?,updated_at=? " +
                        "WHERE step_id=? AND status='PENDING'")) {
            ps.setString(1, status);
            ps.setString(2, status);
            ps.setString(3, value(evidence, 4_000));
            ps.setString(4, nullable(error));
            ps.setString(5, now);
            ps.setString(6, now);
            ps.setString(7, stepId);
            ps.executeUpdate();
        }
    }

    public Optional<PlanStep> findStep(String stepId) {
        try (Connection c = open()) {
            return findStep(c, stepId);
        } catch (SQLException e) {
            throw failure("find plan step", e);
        }
    }

    private void insertSteps(Connection c, String planId, List<StepDraft> steps, String now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO plan_steps(id,plan_id,client_id,ordinal,title,description,type,status,execution_mode," +
                        "done_criteria_json,run_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'PENDING',?,?,NULL,?,?)")) {
            for (StepDraft step : steps) {
                ps.setString(1, step.id());
                ps.setString(2, planId);
                ps.setString(3, step.clientId());
                ps.setInt(4, step.ordinal());
                ps.setString(5, step.title());
                ps.setString(6, step.description());
                ps.setString(7, step.type());
                ps.setString(8, step.executionMode());
                ps.setString(9, step.doneCriteriaJson());
                ps.setString(10, now);
                ps.setString(11, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertValidationChecks(Connection c, String planId, List<StepDraft> steps, String now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO validation_checks(id,plan_id,step_id,name,kind,status,expected,actual,evidence,error," +
                        "created_at,updated_at) VALUES(?,?,?,?,?,'PENDING',?,'','',NULL,?,?)")) {
            for (StepDraft step : steps) {
                ps.setString(1, id("vcheck"));
                ps.setString(2, planId);
                ps.setString(3, step.id());
                ps.setString(4, value(step.title(), 160));
                ps.setString(5, "DONE_CRITERIA");
                ps.setString(6, json(step.doneCriteriaJson(), 16_000));
                ps.setString(7, now);
                ps.setString(8, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertEdges(Connection c, String planId, List<EdgeDraft> edges, String now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO plan_edges(plan_id,from_step_id,to_step_id,created_at) VALUES(?,?,?,?)")) {
            for (EdgeDraft edge : edges == null ? List.<EdgeDraft>of() : edges) {
                ps.setString(1, planId);
                ps.setString(2, edge.fromStepId());
                ps.setString(3, edge.toStepId());
                ps.setString(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void deletePlanChildren(Connection c, String planId) throws SQLException {
        for (String table : List.of("async_jobs", "validation_checks", "plan_edges", "plan_steps")) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE plan_id=?")) {
                ps.setString(1, planId);
                ps.executeUpdate();
            }
        }
    }

    private void markReadySteps(Connection c, String planId, String now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE plan_steps SET status='READY',updated_at=? WHERE plan_id=? AND status='PENDING' " +
                        "AND NOT EXISTS (SELECT 1 FROM plan_edges e JOIN plan_steps dep ON dep.id=e.from_step_id " +
                        "WHERE e.plan_id=plan_steps.plan_id AND e.to_step_id=plan_steps.id " +
                        "AND dep.status<>'COMPLETED')")) {
            ps.setString(1, now);
            ps.setString(2, planId);
            ps.executeUpdate();
        }
    }

    private void finishPlanIfTerminal(Connection c, String planId, String now) throws SQLException {
        try (PreparedStatement failed = c.prepareStatement(
                "SELECT failure_reason FROM plan_steps WHERE plan_id=? AND status='FAILED' ORDER BY updated_at DESC LIMIT 1")) {
            failed.setString(1, planId);
            try (ResultSet rs = failed.executeQuery()) {
                if (rs.next()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE plans SET status='FAILED',failure_reason=?,completed_at=?,updated_at=? " +
                                    "WHERE id=? AND status='ACTIVE'")) {
                        ps.setString(1, rs.getString(1));
                        ps.setString(2, now);
                        ps.setString(3, now);
                        ps.setString(4, planId);
                        if (ps.executeUpdate() > 0) {
                            appendEvent(c, planId, null, "plan.failed", "{}", now);
                        }
                    }
                    return;
                }
            }
        }
        try (PreparedStatement open = c.prepareStatement(
                "SELECT COUNT(*) FROM plan_steps WHERE plan_id=? " +
                        "AND status NOT IN ('COMPLETED','SKIPPED','CANCELED')")) {
            open.setString(1, planId);
            try (ResultSet rs = open.executeQuery()) {
                if (rs.next() && rs.getLong(1) == 0) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE plans SET status='COMPLETED',completed_at=?,updated_at=? " +
                                    "WHERE id=? AND status='ACTIVE'")) {
                        ps.setString(1, now);
                        ps.setString(2, now);
                        ps.setString(3, planId);
                        if (ps.executeUpdate() > 0) {
                            appendEvent(c, planId, null, "plan.completed", "{}", now);
                        }
                    }
                }
            }
        }
    }

    private void appendEvent(Connection c, String planId, String stepId, String type, String data, String now)
            throws SQLException {
        long sequence = 1;
        try (PreparedStatement next = c.prepareStatement(
                "SELECT COALESCE(MAX(sequence),0)+1 FROM plan_events WHERE plan_id=?")) {
            next.setString(1, planId);
            try (ResultSet rs = next.executeQuery()) {
                if (rs.next()) sequence = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO plan_events(plan_id,step_id,event_type,event_data,sequence,created_at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, planId);
            ps.setString(2, stepId);
            ps.setString(3, type);
            ps.setString(4, data == null ? "{}" : data);
            ps.setLong(5, sequence);
            ps.setString(6, now);
            ps.executeUpdate();
        }
    }

    private Optional<Plan> findPlan(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM plans WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(plan(rs)) : Optional.empty();
            }
        }
    }

    private Optional<PlanStep> findStep(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM plan_steps WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(step(rs)) : Optional.empty();
            }
        }
    }

    private Connection open() throws SQLException { return connections.open(); }
    private static Plan plan(ResultSet r) throws SQLException {
        return new Plan(r.getString("id"), r.getString("session_id"), r.getString("run_id"),
                r.getString("project_key"), r.getString("objective"), r.getString("summary"),
                r.getString("status"), r.getInt("version"), r.getString("source"),
                r.getString("raw_plan_json"), r.getString("validation_errors_json"),
                instant(r.getString("created_at")), instant(r.getString("updated_at")),
                instant(r.getString("started_at")), instant(r.getString("completed_at")),
                r.getString("failure_reason"));
    }
    private static PlanStep step(ResultSet r) throws SQLException {
        return new PlanStep(r.getString("id"), r.getString("plan_id"), r.getString("client_id"),
                r.getInt("ordinal"), r.getString("title"), r.getString("description"),
                r.getString("type"), r.getString("status"), r.getString("execution_mode"),
                r.getString("done_criteria_json"), r.getString("run_id"), r.getString("result_summary"),
                r.getString("failure_reason"), instant(r.getString("started_at")),
                instant(r.getString("completed_at")), instant(r.getString("created_at")),
                instant(r.getString("updated_at")));
    }
    private static PlanEdge edge(ResultSet r) throws SQLException {
        return new PlanEdge(r.getString("plan_id"), r.getString("from_step_id"),
                r.getString("to_step_id"), instant(r.getString("created_at")));
    }
    private static PlanEvent event(ResultSet r) throws SQLException {
        return new PlanEvent(r.getLong("id"), r.getString("plan_id"), r.getString("step_id"),
                r.getString("event_type"), r.getString("event_data"), r.getLong("sequence"),
                instant(r.getString("created_at")));
    }
    private static AsyncJob job(ResultSet r) throws SQLException {
        return new AsyncJob(r.getString("id"), r.getString("plan_id"), r.getString("step_id"),
                r.getString("run_id"), r.getString("project_key"), r.getString("kind"),
                r.getString("status"), r.getString("idempotency_key"), r.getString("payload_json"),
                r.getString("result_json"), r.getString("log"), r.getString("error"),
                r.getInt("attempts"), instant(r.getString("created_at")), instant(r.getString("updated_at")),
                instant(r.getString("started_at")), instant(r.getString("completed_at")));
    }
    private static ValidationCheck check(ResultSet r) throws SQLException {
        return new ValidationCheck(r.getString("id"), r.getString("plan_id"), r.getString("step_id"),
                r.getString("name"), r.getString("kind"), r.getString("status"),
                r.getString("expected"), r.getString("actual"), r.getString("evidence"),
                r.getString("error"), instant(r.getString("created_at")), instant(r.getString("updated_at")),
                instant(r.getString("completed_at")));
    }
    private static String project(String v) {
        String x = blank(v) ? "default" : v.trim();
        if (!x.matches("[a-zA-Z0-9_.-]{1,80}")) throw new IllegalArgumentException("invalid projectKey");
        return x;
    }
    private static String text(String v, String name, int max) {
        if (blank(v)) throw new IllegalArgumentException(name + " must not be blank");
        return value(v, max);
    }
    private static String value(String v, int max) {
        String x = v == null ? "" : v.trim();
        if (x.length() > max) throw new IllegalArgumentException("value is too long");
        return x;
    }
    private static String json(String v, int max) {
        String x = blank(v) ? "{}" : v.trim();
        if (x.length() > max) throw new IllegalArgumentException("json is too long");
        return x;
    }
    private static String listJson(String v) {
        String x = blank(v) ? "[]" : v.trim();
        if (x.length() > 16_000) throw new IllegalArgumentException("json is too long");
        return x;
    }
    private static String nullable(String v) { return blank(v) ? null : v.trim(); }
    private static boolean blank(String v) { return v == null || v.isBlank(); }
    private static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private static String id(String p) { return p + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
    private static Instant instant(String v) { return blank(v) ? null : Instant.parse(v); }
    private static void rollback(Connection c) { try { c.rollback(); } catch (Exception ignored) { } }
    private static IllegalStateException failure(String action, SQLException e) {
        return new IllegalStateException("SQLite failed to " + action + ": " + e.getMessage(), e);
    }

    public record Plan(String id, String sessionId, String runId, String projectKey, String objective,
                       String summary, String status, int version, String source, String rawPlanJson,
                       String validationErrorsJson, Instant createdAt, Instant updatedAt,
                       Instant startedAt, Instant completedAt, String failureReason) { }
    public record PlanStep(String id, String planId, String clientId, int ordinal, String title,
                           String description, String type, String status, String executionMode,
                           String doneCriteriaJson, String runId, String resultSummary, String failureReason,
                           Instant startedAt, Instant completedAt, Instant createdAt, Instant updatedAt) { }
    public record PlanEdge(String planId, String fromStepId, String toStepId, Instant createdAt) { }
    public record PlanEvent(long id, String planId, String stepId, String type, String data,
                            long sequence, Instant createdAt) { }
    public record AsyncJob(String id, String planId, String stepId, String runId, String projectKey,
                           String kind, String status, String idempotencyKey, String payloadJson,
                           String resultJson, String log, String error, int attempts, Instant createdAt,
                           Instant updatedAt, Instant startedAt, Instant completedAt) { }
    public record ValidationCheck(String id, String planId, String stepId, String name, String kind,
                                  String status, String expected, String actual, String evidence,
                                  String error, Instant createdAt, Instant updatedAt, Instant completedAt) { }
    public record StepDraft(String id, String clientId, int ordinal, String title, String description,
                            String type, String executionMode, String doneCriteriaJson) { }
    public record EdgeDraft(String fromStepId, String toStepId) { }
}
