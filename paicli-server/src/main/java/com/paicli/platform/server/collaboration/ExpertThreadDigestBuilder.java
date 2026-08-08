package com.paicli.platform.server.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.CollaborationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ExpertThread resume digest: a compact, auditable summary of one expert's logical thread within
 * a root collaboration task. It is the ONLY context a follow-up Run receives about previous Runs
 * of the same thread; full old conversations, tool results and artifact bodies are never included.
 * Sources are the collaboration task tree, run terminal status, final assistant summary, workspace
 * changed files and artifact metadata (no parallel artifact scanning).
 */
@Service
public class ExpertThreadDigestBuilder {
    private static final int SUMMARY_CHARS = 900;
    private static final int MAX_CHANGED_FILES = 100;
    private static final int MAX_ARTIFACT_REFS = 50;
    private final CollaborationStore collaboration;
    private final SqliteRuntimeStore store;
    private final ObjectMapper mapper;

    public ExpertThreadDigestBuilder(CollaborationStore collaboration, SqliteRuntimeStore store,
                                     ObjectMapper mapper) {
        this.collaboration = collaboration;
        this.store = store;
        this.mapper = mapper;
    }

    public String build(String threadId) {
        CollaborationStore.ExpertThread thread = collaboration.expertThread(threadId)
                .orElseThrow(() -> new IllegalArgumentException("expert thread not found: " + threadId));
        CollaborationStore.CollaborationTask root = collaboration.task(thread.rootTaskId())
                .orElse(null);
        List<CollaborationStore.ExpertThreadRun> threadRuns = collaboration.expertThreadRuns(threadId);
        List<CollaborationStore.CollaborationTask> stages = root == null
                ? List.of() : collaboration.descendantTasks(root.id());

        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("thread_id", thread.id());
        digest.put("root_task_id", thread.rootTaskId());
        digest.put("agent_profile_id", thread.agentProfileId());
        digest.put("thread_role", thread.threadRole());
        digest.put("objective", objective(root, thread));
        digest.put("latest_run", latestRun(thread, threadRuns));
        digest.put("completed_work", completedWork(stages));
        digest.put("remaining_work", remainingWork(stages));
        digest.put("blockers", stages.stream().filter(stage -> "BLOCKED".equals(stage.status()))
                .map(CollaborationStore.CollaborationTask::id).toList());
        digest.put("changed_files", changedFiles(threadRuns));
        digest.put("artifact_refs", artifactRefs(threadRuns));
        digest.put("test_summary", testSummary(threadRuns));
        latestHumanInstruction(root).ifPresent(value -> digest.put("latest_human_instruction", value));
        return write(digest);
    }

    private Map<String, Object> latestRun(CollaborationStore.ExpertThread thread,
                                          List<CollaborationStore.ExpertThreadRun> threadRuns) {
        String latestRunId = thread.latestRunId();
        if (latestRunId == null) return Map.of();
        Optional<com.paicli.platform.server.domain.RunRecord> run = store.findRun(latestRunId);
        if (run.isEmpty()) return Map.of("run_id", latestRunId);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("run_id", run.get().id());
        value.put("status", run.get().status().name());
        value.put("summary", runSummary(latestRunId));
        if (run.get().finishedAt() != null) value.put("finished_at", run.get().finishedAt().toString());
        return value;
    }

    private String runSummary(String runId) {
        return store.messagesForRun(runId).stream()
                .filter(message -> "assistant".equals(message.role()))
                .max(Comparator.comparingLong(com.paicli.platform.server.domain.MessageRecord::sequence))
                .map(message -> truncate(message.content()))
                .orElse("");
    }

    private List<String> completedWork(List<CollaborationStore.CollaborationTask> stages) {
        return stages.stream().filter(stage -> List.of("IN_REVIEW", "DONE").contains(stage.status()))
                .map(CollaborationStore.CollaborationTask::title).toList();
    }

    private List<String> remainingWork(List<CollaborationStore.CollaborationTask> stages) {
        return stages.stream().filter(stage -> List.of("BACKLOG", "TODO", "IN_PROGRESS").contains(stage.status()))
                .map(CollaborationStore.CollaborationTask::title).toList();
    }

    private List<String> changedFiles(List<CollaborationStore.ExpertThreadRun> threadRuns) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (CollaborationStore.ExpertThreadRun link : threadRuns) {
            if (files.size() >= MAX_CHANGED_FILES) break;
            store.workspaceFiles(link.runId(), 200).stream()
                    .map(SqliteRuntimeStore.WorkspaceFile::path)
                    .forEach(path -> { if (files.size() < MAX_CHANGED_FILES) files.add(path); });
        }
        return new ArrayList<>(files);
    }

    private List<Map<String, String>> artifactRefs(List<CollaborationStore.ExpertThreadRun> threadRuns) {
        LinkedHashSet<Map<String, String>> refs = new LinkedHashSet<>();
        for (CollaborationStore.ExpertThreadRun link : threadRuns) {
            if (refs.size() >= MAX_ARTIFACT_REFS) break;
            for (com.paicli.platform.server.domain.ArtifactRecord artifact : store.artifactsForRun(link.runId())) {
                if (refs.size() >= MAX_ARTIFACT_REFS) break;
                Map<String, String> ref = new LinkedHashMap<>();
                ref.put("id", artifact.id());
                ref.put("type", artifact.type());
                ref.put("name", artifact.name());
                refs.add(ref);
            }
        }
        return new ArrayList<>(refs);
    }

    private Map<String, Object> testSummary(List<CollaborationStore.ExpertThreadRun> threadRuns) {
        Map<String, Object> value = new LinkedHashMap<>();
        List<Map<String, String>> reports = new ArrayList<>();
        for (CollaborationStore.ExpertThreadRun link : threadRuns) {
            for (com.paicli.platform.server.domain.ArtifactRecord artifact : store.artifactsForRun(link.runId())) {
                String type = artifact.type() == null ? "" : artifact.type().toLowerCase();
                String name = artifact.name() == null ? "" : artifact.name().toLowerCase();
                if (type.contains("test") || type.contains("report") || name.contains("test")
                        || name.contains("report")) {
                    if (reports.size() >= 20) break;
                    Map<String, String> ref = new LinkedHashMap<>();
                    ref.put("id", artifact.id());
                    ref.put("type", artifact.type());
                    ref.put("name", artifact.name());
                    reports.add(ref);
                }
            }
        }
        value.put("reports", reports);
        return value;
    }

    private Optional<String> latestHumanInstruction(CollaborationStore.CollaborationTask root) {
        if (root == null) return Optional.empty();
        return collaboration.comments(root.id()).stream()
                .filter(comment -> "USER".equals(comment.authorType()) && comment.content() != null
                        && !comment.content().isBlank())
                .max(Comparator.comparing(CollaborationStore.CollaborationComment::createdAt))
                .map(comment -> truncate(comment.content()));
    }

    private String objective(CollaborationStore.CollaborationTask root, CollaborationStore.ExpertThread thread) {
        if (root == null) return "Expert thread " + thread.threadRole() + " of agent " + thread.agentProfileId();
        String criteria = root.acceptanceCriteria() == null ? "" : root.acceptanceCriteria().trim();
        if (criteria.isBlank()) return "Expert " + thread.agentProfileId() + " ??????" + root.title() + "???????";
        return "Expert " + thread.agentProfileId() + " ??????" + root.title() + "?????????????"
                + truncate(criteria);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= SUMMARY_CHARS) return value == null ? "" : value;
        return value.substring(0, SUMMARY_CHARS) + "?";
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("failed to serialize expert thread digest", e); }
    }
}
