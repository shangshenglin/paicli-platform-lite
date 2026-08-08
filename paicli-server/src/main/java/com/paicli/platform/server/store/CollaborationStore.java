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
import java.util.Set;
import java.util.UUID;

@Repository
public class CollaborationStore {
    private static final Set<String> STATUSES = Set.of(
            "BACKLOG", "TODO", "IN_PROGRESS", "BLOCKED", "IN_REVIEW", "DONE", "CANCELED");
    private static final Set<String> ASSIGNEE_TYPES = Set.of("HUMAN", "AGENT", "TEAM");
    private static final Set<String> TARGET_TYPES = Set.of("AGENT", "TEAM");
    private final SqliteConnectionFactory connections;

    public CollaborationStore(PlatformProperties properties) {
        this.connections = new SqliteConnectionFactory(
                properties.dataDir().resolve("paicli.db").toAbsolutePath().normalize());
    }

    public List<CollaborationTask> tasks(String projectKey, String requestedStatus, int requestedLimit) {
        String status = normalizeOptionalStatus(requestedStatus);
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        String sql = "SELECT * FROM collaboration_tasks WHERE project_key=? AND (parent_id IS NULL OR parent_id='')"
                + (status == null ? "" : " AND status=?")
                + " ORDER BY CASE status WHEN 'IN_PROGRESS' THEN 0 WHEN 'BLOCKED' THEN 1 "
                + "WHEN 'IN_REVIEW' THEN 2 WHEN 'TODO' THEN 3 WHEN 'BACKLOG' THEN 4 ELSE 5 END,"
                + "priority DESC,updated_at DESC LIMIT ?";
        List<CollaborationTask> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            int index = 1;
            ps.setString(index++, project(projectKey));
            if (status != null) ps.setString(index++, status);
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(task(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list collaboration tasks", e); }
    }

    public List<TaskHistory> taskHistory(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 1_000));
        String sql = "WITH RECURSIVE task_tree(root_id, task_id) AS ("
                + "SELECT id,id FROM collaboration_tasks WHERE parent_id IS NULL OR parent_id='' "
                + "UNION ALL "
                + "SELECT tree.root_id,child.id FROM collaboration_tasks child "
                + "JOIN task_tree tree ON child.parent_id=tree.task_id"
                + ") "
                + "SELECT task.*,"
                + "(SELECT runs.session_id FROM task_tree tree "
                + "JOIN collaboration_task_runs link ON link.task_id=tree.task_id "
                + "JOIN runs ON runs.id=link.run_id WHERE tree.root_id=task.id "
                + "ORDER BY runs.created_at DESC,link.created_at DESC LIMIT 1) AS latest_session_id,"
                + "(SELECT GROUP_CONCAT(DISTINCT runs.session_id) FROM task_tree tree "
                + "JOIN collaboration_task_runs link ON link.task_id=tree.task_id "
                + "JOIN runs ON runs.id=link.run_id WHERE tree.root_id=task.id) AS linked_session_ids,"
                + "(SELECT COUNT(DISTINCT link.run_id) FROM task_tree tree "
                + "JOIN collaboration_task_runs link ON link.task_id=tree.task_id WHERE tree.root_id=task.id) AS run_count "
                + "FROM collaboration_tasks task WHERE task.parent_id IS NULL OR task.parent_id='' "
                + "ORDER BY task.updated_at DESC LIMIT ?";
        List<TaskHistory> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String joined = rs.getString("linked_session_ids");
                    List<String> sessionIds = blank(joined) ? List.of() : List.of(joined.split(","));
                    values.add(new TaskHistory(task(rs), rs.getString("latest_session_id"),
                            sessionIds, rs.getLong("run_count")));
                }
            }
            return values;
        } catch (SQLException e) { throw failure("list collaboration task history", e); }
    }

    public Optional<CollaborationTask> task(String id) {
        if (blank(id)) return Optional.empty();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM collaboration_tasks WHERE id=?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(task(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find collaboration task", e); }
    }

    public List<CollaborationTask> childTasks(String parentId) {
        if (blank(parentId)) return List.of();
        List<CollaborationTask> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM collaboration_tasks WHERE parent_id=? ORDER BY stage,created_at,id")) {
            ps.setString(1, parentId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(task(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list collaboration child tasks", e); }
    }

    /** Returns every staged descendant while keeping the root task as the sole list-level work item. */
    public List<CollaborationTask> descendantTasks(String parentId) {
        List<CollaborationTask> values = new ArrayList<>();
        collectDescendants(parentId, values);
        return values;
    }

    public List<TaskRun> taskTreeRuns(String taskId) {
        List<TaskRun> values = new ArrayList<>(taskRuns(taskId));
        for (CollaborationTask child : descendantTasks(taskId)) values.addAll(taskRuns(child.id()));
        return values.stream().collect(java.util.stream.Collectors.toMap(TaskRun::runId, value -> value,
                (first, ignored) -> first, java.util.LinkedHashMap::new)).values().stream()
                .sorted(java.util.Comparator.comparing(TaskRun::createdAt)).toList();
    }

    private void collectDescendants(String parentId, List<CollaborationTask> values) {
        for (CollaborationTask child : childTasks(parentId)) {
            values.add(child);
            collectDescendants(child.id(), values);
        }
    }

    public CollaborationTask saveTask(String id, String projectKey, String title, String description,
                                      String status, int priority, String assigneeType, String assigneeId,
                                      String acceptanceCriteria, String parentId, int stage,
                                      String latestPlanId, String createdBy) {
        String resolvedId = blank(id) ? id("task") : id.trim();
        String normalizedStatus = normalizeStatus(status);
        String normalizedAssignee = normalizeAssigneeType(assigneeType);
        Instant now = Instant.now();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                boolean exists = exists(c, "collaboration_tasks", resolvedId);
                try (PreparedStatement ps = c.prepareStatement(exists
                        ? "UPDATE collaboration_tasks SET title=?,description=?,status=?,priority=?,"
                        + "assignee_type=?,assignee_id=?,acceptance_criteria=?,parent_id=?,stage=?,"
                        + "latest_plan_id=?,updated_at=? WHERE id=?"
                        : "INSERT INTO collaboration_tasks(id,project_key,title,description,status,priority,"
                        + "assignee_type,assignee_id,acceptance_criteria,parent_id,stage,latest_plan_id,created_by,"
                        + "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    int i = 1;
                    if (!exists) {
                        ps.setString(i++, resolvedId);
                        ps.setString(i++, project(projectKey));
                    }
                    ps.setString(i++, text(title, "title", 180));
                    ps.setString(i++, value(description, 32_000));
                    ps.setString(i++, normalizedStatus);
                    ps.setInt(i++, Math.max(-10, Math.min(priority, 10)));
                    ps.setString(i++, normalizedAssignee);
                    ps.setString(i++, nullable(assigneeId));
                    ps.setString(i++, value(acceptanceCriteria, 16_000));
                    ps.setString(i++, nullable(parentId));
                    ps.setInt(i++, Math.max(0, Math.min(stage, 100)));
                    ps.setString(i++, nullable(latestPlanId));
                    if (exists) {
                        ps.setString(i++, now.toString());
                        ps.setString(i, resolvedId);
                    } else {
                        ps.setString(i++, value(createdBy, 80).isBlank() ? "USER" : value(createdBy, 80));
                        ps.setString(i++, now.toString());
                        ps.setString(i, now.toString());
                    }
                    ps.executeUpdate();
                }
                appendActivity(c, resolvedId, exists ? "TASK_UPDATED" : "TASK_CREATED",
                        "USER", value(createdBy, 80), resolvedId,
                        "{\"status\":\"" + normalizedStatus + "\"}", now);
                if (!blank(parentId) && stage > 0) ensureBarrier(c, parentId, stage, now);
                c.commit();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
            return task(resolvedId).orElseThrow();
        } catch (SQLException e) { throw failure("save collaboration task", e); }
    }

    public CollaborationTask updateStatus(String id, String status, String actorType,
                                          String actorId, String payloadJson) {
        String normalized = normalizeStatus(status);
        Instant now = Instant.now();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE collaboration_tasks SET status=?,updated_at=? WHERE id=?")) {
                ps.setString(1, normalized); ps.setString(2, now.toString()); ps.setString(3, id);
                if (ps.executeUpdate() == 0) throw new IllegalArgumentException("collaboration task not found: " + id);
                appendActivity(c, id, "STATUS_CHANGED", actor(actorType), nullable(actorId), id,
                        json(payloadJson), now);
                c.commit();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
            return task(id).orElseThrow();
        } catch (SQLException e) { throw failure("update collaboration task status", e); }
    }

    public boolean deleteTask(String id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM collaboration_tasks WHERE id=?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { throw failure("delete collaboration task", e); }
    }

    public CollaborationComment addComment(String taskId, String parentCommentId,
                                           String authorType, String authorId, String content,
                                           boolean conclusion, List<MentionTarget> mentions) {
        String commentId = id("comment");
        Instant now = Instant.now();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                if (!exists(c, "collaboration_tasks", taskId)) {
                    throw new IllegalArgumentException("collaboration task not found: " + taskId);
                }
                if (!blank(parentCommentId) && !exists(c, "collaboration_comments", parentCommentId)) {
                    throw new IllegalArgumentException("parent comment not found: " + parentCommentId);
                }
                if (conclusion) {
                    try (PreparedStatement clear = c.prepareStatement(
                            "UPDATE collaboration_comments SET conclusion=0 WHERE task_id=?")) {
                        clear.setString(1, taskId); clear.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO collaboration_comments(id,task_id,parent_comment_id,author_type,author_id,"
                                + "content,resolved,conclusion,created_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, commentId); ps.setString(2, taskId);
                    ps.setString(3, nullable(parentCommentId)); ps.setString(4, actor(authorType));
                    ps.setString(5, nullable(authorId)); ps.setString(6, text(content, "content", 32_000));
                    ps.setInt(7, 0); ps.setInt(8, conclusion ? 1 : 0); ps.setString(9, now.toString());
                    ps.executeUpdate();
                }
                for (MentionTarget mention : mentions == null ? List.<MentionTarget>of() : mentions) {
                    if (mention == null || blank(mention.id())) continue;
                    String type = normalizeTargetType(mention.type());
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT OR IGNORE INTO collaboration_mentions(comment_id,target_type,target_id,created_at) "
                                    + "VALUES(?,?,?,?)")) {
                        ps.setString(1, commentId); ps.setString(2, type); ps.setString(3, mention.id().trim());
                        ps.setString(4, now.toString()); ps.executeUpdate();
                    }
                }
                appendActivity(c, taskId, conclusion ? "CONCLUSION_POSTED" : "COMMENT_POSTED",
                        actor(authorType), nullable(authorId), commentId,
                        "{\"parentCommentId\":" + quoted(parentCommentId) + ",\"mentions\":"
                                + (mentions == null ? 0 : mentions.size()) + "}", now);
                try (PreparedStatement update = c.prepareStatement(
                        "UPDATE collaboration_tasks SET updated_at=? WHERE id=?")) {
                    update.setString(1, now.toString()); update.setString(2, taskId); update.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
            return comment(commentId).orElseThrow();
        } catch (SQLException e) { throw failure("add collaboration comment", e); }
    }

    public Optional<CollaborationComment> comment(String id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM collaboration_comments WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(comment(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find collaboration comment", e); }
    }

    public List<CollaborationComment> comments(String taskId) {
        List<CollaborationComment> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM collaboration_comments WHERE task_id=? ORDER BY created_at,id")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(comment(rs)); }
            return values;
        } catch (SQLException e) { throw failure("list collaboration comments", e); }
    }

    public List<MentionTarget> mentions(String commentId) {
        List<MentionTarget> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT target_type,target_id FROM collaboration_mentions WHERE comment_id=? ORDER BY target_type,target_id")) {
            ps.setString(1, commentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(new MentionTarget(rs.getString(1), rs.getString(2)));
            }
            return values;
        } catch (SQLException e) { throw failure("list collaboration mentions", e); }
    }

    public CollaborationComment setDiscussionState(String id, boolean resolved, boolean conclusion) {
        CollaborationComment current = comment(id).orElseThrow(() ->
                new IllegalArgumentException("collaboration comment not found: " + id));
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                if (conclusion) {
                    try (PreparedStatement clear = c.prepareStatement(
                            "UPDATE collaboration_comments SET conclusion=0 WHERE task_id=?")) {
                        clear.setString(1, current.taskId()); clear.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE collaboration_comments SET resolved=?,conclusion=? WHERE id=?")) {
                    ps.setInt(1, resolved ? 1 : 0); ps.setInt(2, conclusion ? 1 : 0); ps.setString(3, id);
                    ps.executeUpdate();
                }
                appendActivity(c, current.taskId(), resolved ? "DISCUSSION_RESOLVED" : "DISCUSSION_REOPENED",
                        "USER", null, id, "{\"conclusion\":" + conclusion + "}", Instant.now());
                c.commit();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
            return comment(id).orElseThrow();
        } catch (SQLException e) { throw failure("set collaboration discussion state", e); }
    }

    public List<CollaborationActivity> activities(String taskId, long after, int requestedLimit) {
        List<CollaborationActivity> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM collaboration_activities WHERE task_id=? AND id>? ORDER BY id LIMIT ?")) {
            ps.setString(1, taskId); ps.setLong(2, Math.max(0, after));
            ps.setInt(3, Math.max(1, Math.min(requestedLimit, 1_000)));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(activity(rs)); }
            return values;
        } catch (SQLException e) { throw failure("list collaboration activities", e); }
    }

    /**
     * Returns comments recorded on the task and every staged descendant, newest-first,
     * so the root task's collaboration view can surface every sub-agent's final reply.
     */
    public List<CollaborationComment> treeComments(String rootTaskId) {
        List<CollaborationComment> values = new ArrayList<>();
        for (String taskId : taskTreeIds(rootTaskId)) values.addAll(comments(taskId));
        values.sort(java.util.Comparator.comparing(CollaborationComment::createdAt)
                .thenComparing(CollaborationComment::id));
        return values;
    }

    /**
     * Returns the merged activity timeline of the task and every staged descendant
     * (sub-agent responses, stage dispatches, parallel deliveries, barrier completions),
     * ordered by the global event id so the root task view reflects the whole tree.
     */
    public List<CollaborationActivity> treeActivities(String rootTaskId, long after, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 2_000));
        List<CollaborationActivity> values = new ArrayList<>();
        for (String taskId : taskTreeIds(rootTaskId)) values.addAll(activities(taskId, after, limit));
        values.sort(java.util.Comparator.comparingLong(CollaborationActivity::id));
        if (values.size() > limit) values = new ArrayList<>(values.subList(values.size() - limit, values.size()));
        return values;
    }

    private List<String> taskTreeIds(String rootTaskId) {
        List<String> ids = new ArrayList<>();
        ids.add(rootTaskId);
        for (CollaborationTask child : descendantTasks(rootTaskId)) ids.add(child.id());
        return ids;
    }

    public void recordActivity(String taskId, String activityType, String actorType,
                               String actorId, String subjectId, String payloadJson) {
        try (Connection c = open()) {
            appendActivity(c, taskId, value(activityType, 80).toUpperCase(), actor(actorType),
                    nullable(actorId), nullable(subjectId), json(payloadJson), Instant.now());
        } catch (SQLException e) { throw failure("record collaboration activity", e); }
    }

    public Trigger createOrGetTrigger(String taskId, String triggerType, String sourceId,
                                      String targetType, String targetId, String payloadJson,
                                      String idempotencyKey) {
        CollaborationTask task = task(taskId).orElseThrow(() ->
                new IllegalArgumentException("collaboration task not found: " + taskId));
        String triggerId = id("trigger");
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO collaboration_triggers(id,task_id,project_key,trigger_type,source_id,"
                        + "target_type,target_id,payload_json,idempotency_key,status,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,'PENDING',?)")) {
            ps.setString(1, triggerId); ps.setString(2, taskId); ps.setString(3, task.projectKey());
            ps.setString(4, value(triggerType, 40).toUpperCase()); ps.setString(5, nullable(sourceId));
            ps.setString(6, normalizeTargetType(targetType)); ps.setString(7, text(targetId, "targetId", 100));
            ps.setString(8, json(payloadJson)); ps.setString(9, text(idempotencyKey, "idempotencyKey", 240));
            ps.setString(10, Instant.now().toString()); ps.executeUpdate();
        } catch (SQLException e) { throw failure("create collaboration trigger", e); }
        return triggerByKey(idempotencyKey).orElseThrow();
    }

    public Optional<Trigger> trigger(String id) {
        return trigger("SELECT * FROM collaboration_triggers WHERE id=?", id);
    }

    public Optional<Trigger> triggerByKey(String key) {
        return trigger("SELECT * FROM collaboration_triggers WHERE idempotency_key=?", key);
    }

    private Optional<Trigger> trigger(String sql, String value) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(trigger(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find collaboration trigger", e); }
    }

    public Trigger completeTrigger(String id, String runId) {
        Instant now = Instant.now();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                Trigger current;
                try (PreparedStatement find = c.prepareStatement(
                        "SELECT * FROM collaboration_triggers WHERE id=?")) {
                    find.setString(1, id);
                    try (ResultSet rs = find.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("collaboration trigger not found: " + id);
                        current = trigger(rs);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE collaboration_triggers SET status='COMPLETED',created_run_id=?,processed_at=?,error=NULL "
                                + "WHERE id=? AND status='PENDING'")) {
                    ps.setString(1, runId); ps.setString(2, now.toString()); ps.setString(3, id); ps.executeUpdate();
                }
                linkRun(c, current.taskId(), runId, id, "TRIGGERED", now);
                appendActivity(c, current.taskId(), "RUN_TRIGGERED", current.targetType(), current.targetId(),
                        runId, "{\"triggerId\":\"" + id + "\",\"triggerType\":\""
                                + current.triggerType() + "\"}", now);
                c.commit();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
            return trigger(id).orElseThrow();
        } catch (SQLException e) { throw failure("complete collaboration trigger", e); }
    }

    public Trigger failTrigger(String id, String error) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE collaboration_triggers SET status='FAILED',error=?,processed_at=? WHERE id=?")) {
            ps.setString(1, value(error, 2_000)); ps.setString(2, Instant.now().toString()); ps.setString(3, id);
            ps.executeUpdate();
            return trigger(id).orElseThrow();
        } catch (SQLException e) { throw failure("fail collaboration trigger", e); }
    }

    public void linkRun(String taskId, String runId, String triggerId, String relationship) {
        try (Connection c = open()) {
            linkRun(c, taskId, runId, triggerId, relationship, Instant.now());
        } catch (SQLException e) { throw failure("link collaboration run", e); }
    }

    public Optional<CollaborationTask> taskForRun(String runId) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT task.* FROM collaboration_tasks task JOIN collaboration_task_runs link ON link.task_id=task.id "
                        + "WHERE link.run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(task(rs)) : Optional.empty(); }
        } catch (SQLException e) { throw failure("find task for run", e); }
    }

    public Optional<ExpertThread> expertThread(String id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM collaboration_expert_threads WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(expertThread(rs)) : Optional.empty(); }
        } catch (SQLException e) { throw failure("find expert thread", e); }
    }

    /**
     * Idempotently resolves the logical expert thread for a root task + agent + role.
     * The unique key keeps one expert's continuation within one collaboration task, while the
     * same agent participating in another task gets its own thread.
     */
    public ExpertThread getOrCreateExpertThread(String rootTaskId, String agentProfileId, String threadRole) {
        Optional<ExpertThread> existing = expertThreadByKey(rootTaskId, agentProfileId, threadRole);
        if (existing.isPresent()) return existing.get();
        String threadId = "expert_thread_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Instant now = Instant.now();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO collaboration_expert_threads(id,root_task_id,agent_profile_id,thread_role,"
                        + "status,digest_json,latest_run_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            int i = 1;
            ps.setString(i++, threadId);
            ps.setString(i++, rootTaskId);
            ps.setString(i++, agentProfileId);
            ps.setString(i++, blank(threadRole) ? "EXPERT" : threadRole.trim().toUpperCase());
            ps.setString(i++, "ACTIVE");
            ps.setString(i++, "{}");
            ps.setString(i++, null);
            ps.setString(i++, now.toString());
            ps.setString(i, now.toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw failure("create expert thread", e); }
        return expertThreadByKey(rootTaskId, agentProfileId, threadRole)
                .orElseThrow(() -> new IllegalStateException("expert thread was not created"));
    }

    private Optional<ExpertThread> expertThreadByKey(String rootTaskId, String agentProfileId, String threadRole) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM collaboration_expert_threads WHERE root_task_id=? AND agent_profile_id=? AND thread_role=?")) {
            ps.setString(1, rootTaskId);
            ps.setString(2, agentProfileId);
            ps.setString(3, blank(threadRole) ? "EXPERT" : threadRole.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(expertThread(rs)) : Optional.empty(); }
        } catch (SQLException e) { throw failure("find expert thread by key", e); }
    }

    public Optional<ExpertThread> expertThreadForRun(String runId) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT t.* FROM collaboration_expert_threads t "
                        + "JOIN collaboration_expert_thread_runs r ON r.thread_id=t.id WHERE r.run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(expertThread(rs)) : Optional.empty(); }
        } catch (SQLException e) { throw failure("find expert thread for run", e); }
    }

    public List<ExpertThread> expertThreadsForRoot(String rootTaskId) {
        List<ExpertThread> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM collaboration_expert_threads WHERE root_task_id=? ORDER BY updated_at DESC")) {
            ps.setString(1, rootTaskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(expertThread(rs));
            }
            return values;
        } catch (SQLException e) { throw failure("list expert threads for root task", e); }
    }

    public List<ExpertThreadRun> expertThreadRuns(String threadId) {
        List<ExpertThreadRun> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT thread_id,run_id,ordinal,created_at FROM collaboration_expert_thread_runs "
                        + "WHERE thread_id=? ORDER BY ordinal")) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(new ExpertThreadRun(rs.getString("thread_id"), rs.getString("run_id"),
                        rs.getInt("ordinal"), instant(rs.getString("created_at"))));
            }
            return values;
        } catch (SQLException e) { throw failure("list expert thread runs", e); }
    }

    public void attachExpertThreadRun(String threadId, String runId) {
        Instant now = Instant.now();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                int ordinal;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COALESCE(MAX(ordinal),0)+1 FROM collaboration_expert_thread_runs WHERE thread_id=?")) {
                    ps.setString(1, threadId);
                    try (ResultSet rs = ps.executeQuery()) { ordinal = rs.next() ? rs.getInt(1) : 1; }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO collaboration_expert_thread_runs(thread_id,run_id,ordinal,created_at) "
                                + "VALUES(?,?,?,?)")) {
                    ps.setString(1, threadId);
                    ps.setString(2, runId);
                    ps.setInt(3, ordinal);
                    ps.setString(4, now.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE collaboration_expert_threads SET latest_run_id=?,updated_at=? WHERE id=?")) {
                    ps.setString(1, runId);
                    ps.setString(2, now.toString());
                    ps.setString(3, threadId);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) { throw failure("attach expert thread run", e); }
    }

    public void updateExpertThreadDigest(String threadId, String digestJson) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE collaboration_expert_threads SET digest_json=?,updated_at=? WHERE id=?")) {
            ps.setString(1, digestJson == null ? "{}" : digestJson);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, threadId);
            ps.executeUpdate();
        } catch (SQLException e) { throw failure("update expert thread digest", e); }
    }

    private ExpertThread expertThread(ResultSet rs) throws SQLException {
        return new ExpertThread(rs.getString("id"), rs.getString("root_task_id"),
                rs.getString("agent_profile_id"), rs.getString("thread_role"), rs.getString("status"),
                rs.getString("digest_json"), rs.getString("latest_run_id"),
                instant(rs.getString("created_at")), instant(rs.getString("updated_at")));
    }

    public List<TaskRun> taskRuns(String taskId) {
        List<TaskRun> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT link.*,runs.session_id,runs.status,runs.agent_profile_id,runs.model_profile_id,"
                        + "(SELECT name FROM model_profiles WHERE id=runs.model_profile_id) AS model_profile_name,"
                        + "COALESCE((SELECT model_name FROM model_usage WHERE run_id=runs.id "
                        + "ORDER BY created_at DESC,id DESC LIMIT 1),"
                        + "(SELECT model FROM model_profiles WHERE id=runs.model_profile_id),'') AS model_name,"
                        + "runs.created_at AS run_created_at,"
                        + "runs.finished_at FROM collaboration_task_runs link JOIN runs ON runs.id=link.run_id "
                        + "WHERE link.task_id=? ORDER BY link.created_at DESC")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(new TaskRun(rs.getString("task_id"), rs.getString("run_id"),
                        rs.getString("trigger_id"), rs.getString("relationship"), rs.getString("session_id"),
                        rs.getString("status"), rs.getString("agent_profile_id"), rs.getString("model_profile_id"),
                        rs.getString("model_profile_name"), rs.getString("model_name"),
                        instant(rs.getString("run_created_at")), instant(rs.getString("finished_at"))));
            }
            return values;
        } catch (SQLException e) { throw failure("list collaboration task runs", e); }
    }

    public RouteDecision saveRouteDecision(String projectKey, String taskId, String triggerId, String input,
                                           String complexity, String risk, String targetType, String targetId,
                                           String leaderId, String recommendedJson, String reasonsJson,
                                           int estimatedConcurrency) {
        String decisionId = id("route");
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO collaboration_route_decisions(id,project_key,task_id,trigger_id,input,complexity,risk,"
                        + "target_type,target_id,leader_agent_profile_id,recommended_agent_profile_ids_json,"
                        + "reasons_json,estimated_concurrency,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int i = 1;
            ps.setString(i++, decisionId); ps.setString(i++, project(projectKey));
            ps.setString(i++, nullable(taskId)); ps.setString(i++, nullable(triggerId));
            ps.setString(i++, text(input, "input", 32_000)); ps.setString(i++, value(complexity, 20));
            ps.setString(i++, value(risk, 20)); ps.setString(i++, normalizeTargetType(targetType));
            ps.setString(i++, nullable(targetId)); ps.setString(i++, nullable(leaderId));
            ps.setString(i++, json(recommendedJson)); ps.setString(i++, json(reasonsJson));
            ps.setInt(i++, Math.max(1, Math.min(estimatedConcurrency, 20)));
            ps.setString(i, Instant.now().toString()); ps.executeUpdate();
            return routeDecision(decisionId).orElseThrow();
        } catch (SQLException e) { throw failure("save collaboration route decision", e); }
    }

    public Optional<RouteDecision> routeDecision(String id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM collaboration_route_decisions WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(routeDecision(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find collaboration route decision", e); }
    }

    public TeamMetrics teamMetrics(String teamId) {
        long totalTasks = scalar("SELECT COUNT(*) FROM collaboration_tasks WHERE assignee_type='TEAM' AND assignee_id=?", teamId);
        long completedTasks = scalar("SELECT COUNT(*) FROM collaboration_tasks WHERE assignee_type='TEAM' AND assignee_id=? AND status='DONE'", teamId);
        long blockedTasks = scalar("SELECT COUNT(*) FROM collaboration_tasks WHERE assignee_type='TEAM' AND assignee_id=? AND status='BLOCKED'", teamId);
        long totalRuns = scalar("SELECT COUNT(*) FROM collaboration_task_runs link JOIN collaboration_tasks task "
                + "ON task.id=link.task_id WHERE task.assignee_type='TEAM' AND task.assignee_id=?", teamId);
        long successfulRuns = scalar("SELECT COUNT(*) FROM collaboration_task_runs link JOIN collaboration_tasks task "
                + "ON task.id=link.task_id JOIN runs ON runs.id=link.run_id WHERE task.assignee_type='TEAM' "
                + "AND task.assignee_id=? AND runs.status='COMPLETED'", teamId);
        long delegatedRuns = scalar("SELECT COUNT(*) FROM collaboration_task_runs link JOIN collaboration_tasks task "
                + "ON task.id=link.task_id JOIN run_delegations delegation ON delegation.parent_run_id=link.run_id "
                + "WHERE task.assignee_type='TEAM' AND task.assignee_id=?", teamId);
        long humanInterventions = scalar("SELECT COUNT(*) FROM collaboration_activities activity "
                + "JOIN collaboration_tasks task ON task.id=activity.task_id WHERE task.assignee_type='TEAM' "
                + "AND task.assignee_id=? AND (activity.activity_type IN "
                + "('DISCUSSION_RESOLVED','CONCLUSION_POSTED','HUMAN_ACTION') "
                + "OR (activity.activity_type='STATUS_CHANGED' AND activity.payload_json NOT LIKE '%\"action\"%')) "
                + "AND activity.actor_type='USER'", teamId);
        return new TeamMetrics(teamId, totalTasks, completedTasks, blockedTasks, totalRuns, successfulRuns,
                delegatedRuns, humanInterventions, totalTasks == 0 ? 0 : completedTasks * 1.0 / totalTasks,
                totalRuns == 0 ? 0 : successfulRuns * 1.0 / totalRuns);
    }

    public Optional<StageBarrier> evaluateStageBarrier(String parentTaskId, int stage) {
        Instant now = Instant.now();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                ensureBarrier(c, parentTaskId, stage, now);
                int total;
                int open;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*),SUM(CASE WHEN status IN ('IN_REVIEW','DONE','CANCELED') THEN 0 ELSE 1 END) "
                                + "FROM collaboration_tasks WHERE parent_id=? AND stage=?")) {
                    ps.setString(1, parentTaskId); ps.setInt(2, stage);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next(); total = rs.getInt(1); open = rs.getInt(2);
                    }
                }
                if (total > 0 && open == 0) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE collaboration_stage_barriers SET status='COMPLETED',completed_at=? "
                                    + "WHERE parent_task_id=? AND stage=? AND status<>'COMPLETED'")) {
                        ps.setString(1, now.toString()); ps.setString(2, parentTaskId); ps.setInt(3, stage);
                        if (ps.executeUpdate() > 0) {
                            appendActivity(c, parentTaskId, "STAGE_COMPLETED", "SYSTEM", null,
                                    parentTaskId + ":" + stage, "{\"stage\":" + stage + "}", now);
                        }
                    }
                }
                c.commit();
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
            return stageBarrier(parentTaskId, stage);
        } catch (SQLException e) { throw failure("evaluate collaboration stage barrier", e); }
    }

    public Optional<StageBarrier> stageBarrier(String parentTaskId, int stage) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM collaboration_stage_barriers WHERE parent_task_id=? AND stage=?")) {
            ps.setString(1, parentTaskId); ps.setInt(2, stage);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new StageBarrier(rs.getString("parent_task_id"),
                        rs.getInt("stage"), rs.getString("status"), instant(rs.getString("completed_at")),
                        Instant.parse(rs.getString("created_at")))) : Optional.empty();
            }
        } catch (SQLException e) { throw failure("find collaboration stage barrier", e); }
    }

    /**
     * Returns barriers that may have been left waiting by an older lifecycle path.
     * Callers must still evaluate the barrier because the child task state is authoritative.
     */
    public List<StageBarrier> waitingStageBarriers() {
        List<StageBarrier> values = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM collaboration_stage_barriers WHERE status='WAITING' ORDER BY created_at")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(new StageBarrier(rs.getString("parent_task_id"),
                        rs.getInt("stage"), rs.getString("status"), instant(rs.getString("completed_at")),
                        Instant.parse(rs.getString("created_at"))));
            }
            return values;
        } catch (SQLException e) { throw failure("list waiting collaboration stage barriers", e); }
    }

    /** Returns completed barriers whose idempotent Leader wake-up Trigger was never persisted. */
    public List<StageBarrier> completedStageBarriersWithoutTrigger() {
        List<StageBarrier> values = new ArrayList<>();
        String sql = "SELECT barrier.* FROM collaboration_stage_barriers barrier WHERE barrier.status='COMPLETED' "
                + "AND NOT EXISTS (SELECT 1 FROM collaboration_triggers trigger "
                + "WHERE trigger.task_id=barrier.parent_task_id AND trigger.trigger_type='STAGE_BARRIER' "
                + "AND trigger.idempotency_key=('stage:' || barrier.parent_task_id || ':' || barrier.stage)) "
                + "ORDER BY barrier.completed_at,barrier.created_at";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) values.add(new StageBarrier(rs.getString("parent_task_id"),
                    rs.getInt("stage"), rs.getString("status"), instant(rs.getString("completed_at")),
                    Instant.parse(rs.getString("created_at"))));
            return values;
        } catch (SQLException e) { throw failure("list untriggered completed collaboration stage barriers", e); }
    }

    private long scalar(String sql, String value) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        } catch (SQLException e) { throw failure("read collaboration metric", e); }
    }

    private static void linkRun(Connection c, String taskId, String runId, String triggerId,
                                String relationship, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO collaboration_task_runs(task_id,run_id,trigger_id,relationship,created_at) "
                        + "VALUES(?,?,?,?,?)")) {
            ps.setString(1, taskId); ps.setString(2, runId); ps.setString(3, nullable(triggerId));
            ps.setString(4, value(relationship, 40).isBlank() ? "EXECUTION" : value(relationship, 40).toUpperCase());
            ps.setString(5, now.toString()); ps.executeUpdate();
        }
    }

    private static void appendActivity(Connection c, String taskId, String type, String actorType,
                                       String actorId, String subjectId, String payloadJson, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO collaboration_activities(task_id,activity_type,actor_type,actor_id,subject_id,"
                        + "payload_json,created_at) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, taskId); ps.setString(2, type); ps.setString(3, actor(actorType));
            ps.setString(4, nullable(actorId)); ps.setString(5, nullable(subjectId));
            ps.setString(6, json(payloadJson)); ps.setString(7, now.toString()); ps.executeUpdate();
        }
    }

    private static void ensureBarrier(Connection c, String parentId, int stage, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO collaboration_stage_barriers(parent_task_id,stage,status,created_at) "
                        + "VALUES(?,?,'WAITING',?)")) {
            ps.setString(1, parentId); ps.setInt(2, stage); ps.setString(3, now.toString()); ps.executeUpdate();
        }
    }

    private static boolean exists(Connection c, String table, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private CollaborationTask task(ResultSet rs) throws SQLException {
        return new CollaborationTask(rs.getString("id"), rs.getString("project_key"), rs.getString("title"),
                rs.getString("description"), rs.getString("status"), rs.getInt("priority"),
                rs.getString("assignee_type"), rs.getString("assignee_id"),
                rs.getString("acceptance_criteria"), rs.getString("parent_id"), rs.getInt("stage"),
                rs.getString("latest_plan_id"), rs.getString("created_by"),
                Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));
    }

    private CollaborationComment comment(ResultSet rs) throws SQLException {
        return new CollaborationComment(rs.getString("id"), rs.getString("task_id"),
                rs.getString("parent_comment_id"), rs.getString("author_type"), rs.getString("author_id"),
                rs.getString("content"), rs.getInt("resolved") != 0, rs.getInt("conclusion") != 0,
                Instant.parse(rs.getString("created_at")));
    }

    private CollaborationActivity activity(ResultSet rs) throws SQLException {
        return new CollaborationActivity(rs.getLong("id"), rs.getString("task_id"),
                rs.getString("activity_type"), rs.getString("actor_type"), rs.getString("actor_id"),
                rs.getString("subject_id"), rs.getString("payload_json"),
                Instant.parse(rs.getString("created_at")));
    }

    private Trigger trigger(ResultSet rs) throws SQLException {
        return new Trigger(rs.getString("id"), rs.getString("task_id"), rs.getString("project_key"),
                rs.getString("trigger_type"), rs.getString("source_id"), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("payload_json"), rs.getString("idempotency_key"),
                rs.getString("status"), rs.getString("created_run_id"), rs.getString("error"),
                Instant.parse(rs.getString("created_at")), instant(rs.getString("processed_at")));
    }

    private RouteDecision routeDecision(ResultSet rs) throws SQLException {
        return new RouteDecision(rs.getString("id"), rs.getString("project_key"), rs.getString("task_id"),
                rs.getString("trigger_id"), rs.getString("input"), rs.getString("complexity"),
                rs.getString("risk"), rs.getString("target_type"), rs.getString("target_id"),
                rs.getString("leader_agent_profile_id"), rs.getString("recommended_agent_profile_ids_json"),
                rs.getString("reasons_json"), rs.getInt("estimated_concurrency"),
                Instant.parse(rs.getString("created_at")));
    }

    private Connection open() throws SQLException { return connections.open(); }
    private static void rollback(Connection c) { try { c.rollback(); } catch (SQLException ignored) { } }
    private static String normalizeStatus(String value) {
        String normalized = blank(value) ? "TODO" : value.trim().toUpperCase();
        if (!STATUSES.contains(normalized)) throw new IllegalArgumentException("unsupported collaboration task status: " + value);
        return normalized;
    }
    private static String normalizeOptionalStatus(String value) { return blank(value) ? null : normalizeStatus(value); }
    private static String normalizeAssigneeType(String value) {
        String normalized = blank(value) ? "HUMAN" : value.trim().toUpperCase();
        if (!ASSIGNEE_TYPES.contains(normalized)) throw new IllegalArgumentException("unsupported assignee type: " + value);
        return normalized;
    }
    private static String normalizeTargetType(String value) {
        String normalized = blank(value) ? "AGENT" : value.trim().toUpperCase();
        if (!TARGET_TYPES.contains(normalized)) throw new IllegalArgumentException("unsupported trigger target: " + value);
        return normalized;
    }
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
    private static String quoted(String value) { return blank(value) ? "null" : "\"" + value.replace("\"", "\\\"") + "\""; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
    private static Instant instant(String value) { return blank(value) ? null : Instant.parse(value); }
    private static IllegalStateException failure(String action, SQLException error) {
        return new IllegalStateException("SQLite failed to " + action + ": " + error.getMessage(), error);
    }

    public record CollaborationTask(String id, String projectKey, String title, String description,
                                    String status, int priority, String assigneeType, String assigneeId,
                                    String acceptanceCriteria, String parentId, int stage,
                                    String latestPlanId, String createdBy, Instant createdAt, Instant updatedAt) { }
    public record ExpertThread(String id, String rootTaskId, String agentProfileId, String threadRole,
                               String status, String digestJson, String latestRunId,
                               Instant createdAt, Instant updatedAt) { }
    public record ExpertThreadRun(String threadId, String runId, int ordinal, Instant createdAt) { }
    public record CollaborationComment(String id, String taskId, String parentCommentId,
                                       String authorType, String authorId, String content,
                                       boolean resolved, boolean conclusion, Instant createdAt) { }
    public record CollaborationActivity(long id, String taskId, String activityType,
                                        String actorType, String actorId, String subjectId,
                                        String payloadJson, Instant createdAt) { }
    public record MentionTarget(String type, String id) { }
    public record Trigger(String id, String taskId, String projectKey, String triggerType,
                          String sourceId, String targetType, String targetId, String payloadJson,
                          String idempotencyKey, String status, String createdRunId, String error,
                          Instant createdAt, Instant processedAt) { }
    public record TaskRun(String taskId, String runId, String triggerId, String relationship,
                          String sessionId, String status, String agentProfileId, String modelProfileId,
                          String modelProfileName, String modelName,
                          Instant createdAt, Instant finishedAt) { }
    public record TaskHistory(CollaborationTask task, String latestSessionId,
                              List<String> linkedSessionIds, long runCount) { }
    public record RouteDecision(String id, String projectKey, String taskId, String triggerId,
                                String input, String complexity, String risk, String targetType,
                                String targetId, String leaderAgentProfileId,
                                String recommendedAgentProfileIdsJson, String reasonsJson,
                                int estimatedConcurrency, Instant createdAt) { }
    public record TeamMetrics(String teamId, long totalTasks, long completedTasks, long blockedTasks,
                              long totalRuns, long successfulRuns, long delegatedRuns,
                              long humanInterventions, double taskCompletionRate,
                              double runSuccessRate) { }
    public record StageBarrier(String parentTaskId, int stage, String status,
                               Instant completedAt, Instant createdAt) { }
}
