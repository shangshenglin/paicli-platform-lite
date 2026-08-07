package com.paicli.platform.server.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.TaskDigestRecord;
import com.paicli.platform.server.store.CollaborationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PR7: builds and persists a compact task digest so a re-woken Leader does not
 * have to reload the full history. Only the latest revision is injected; old
 * comments, runs and tool results stay behind references.
 */
@Service
public class TaskDigestService {
    private final CollaborationStore collaboration;
    private final SqliteRuntimeStore store;
    private final ObjectMapper mapper;

    public TaskDigestService(CollaborationStore collaboration, SqliteRuntimeStore store, ObjectMapper mapper) {
        this.collaboration = collaboration;
        this.store = store;
        this.mapper = mapper;
    }

    public Optional<TaskDigestRecord> latest(String taskId) {
        return store.latestTaskDigest(taskId);
    }

    public TaskDigestRecord build(String taskId) {
        CollaborationStore.CollaborationTask task = collaboration.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("collaboration task not found: " + taskId));
        List<CollaborationStore.CollaborationTask> stages = collaboration.descendantTasks(taskId);
        List<CollaborationStore.CollaborationComment> comments = collaboration.comments(taskId);
        List<CollaborationStore.CollaborationActivity> recent = collaboration.activities(taskId, 0, 200);
        long maxActivityId = recent.stream().mapToLong(CollaborationStore.CollaborationActivity::id).max().orElse(0L);

        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("task_id", task.id());
        digest.put("title", task.title());
        digest.put("status", task.status());
        digest.put("assignee_type", task.assigneeType());
        digest.put("assignee_id", task.assigneeId());
        digest.put("acceptance_criteria", blank(task.acceptanceCriteria()) ? "" : task.acceptanceCriteria());
        digest.put("stages", stages.stream().map(stage -> Map.of(
                "id", stage.id(), "title", stage.title(), "stage", stage.stage(), "status", stage.status())).toList());
        digest.put("blockers", stages.stream().filter(stage -> "BLOCKED".equals(stage.status()))
                .map(CollaborationStore.CollaborationTask::id).toList());
        comments.stream().filter(comment -> "USER".equals(comment.authorType()))
                .max(Comparator.comparing(CollaborationStore.CollaborationComment::createdAt))
                .ifPresent(comment -> digest.put("latest_human_instruction", comment.content()));
        digest.put("deliveries", store.deliveriesForTask(taskId).stream()
                .map(delivery -> Map.of("stage", delivery.stage(), "attempt", delivery.attempt(),
                        "status", delivery.status(), "content_hash", delivery.contentHash()))
                .toList());
        long lastSeen = store.latestTaskDigest(taskId)
                .map(record -> parseLong(record.lastActivityId())).orElse(0L);
        digest.put("incremental_activity", recent.stream().filter(activity -> activity.id() > lastSeen)
                .limit(60).map(activity -> Map.of(
                        "type", activity.activityType(), "actor", activity.actorType(),
                        "subject", blank(activity.subjectId()) ? "" : activity.subjectId(),
                        "at", activity.createdAt().toString()))
                .toList());
        String json = write(digest);
        return store.saveTaskDigest(taskId, json, String.valueOf(maxActivityId));
    }

    /** Renders the latest digest for injection into a Leader run input. */
    public String prompt(String taskId) {
        TaskDigestRecord record = latest(taskId).orElseGet(() -> build(taskId));
        return "<task_digest>\n" + record.digestJson() + "\n</task_digest>";
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize task digest", e);
        }
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
