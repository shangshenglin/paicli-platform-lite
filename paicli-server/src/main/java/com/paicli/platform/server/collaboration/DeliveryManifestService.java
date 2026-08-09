package com.paicli.platform.server.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.AcceptedSnapshotRecord;
import com.paicli.platform.server.domain.DeliveryRecord;
import com.paicli.platform.server.store.CollaborationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PR7: durable delivery manifests per stage (changed files, artifacts, test
 * evidence, criteria evidence, content hash) and an immutable snapshot when the
 * human accepts the whole task, so "what was accepted" never drifts from the
 * workspace later.
 */
@Service
public class DeliveryManifestService {
    private final CollaborationStore collaboration;
    private final SqliteRuntimeStore store;
    private final ObjectMapper mapper;
    private final com.paicli.platform.server.agent.RunEvidenceCollector evidenceCollector;

    public DeliveryManifestService(CollaborationStore collaboration, SqliteRuntimeStore store, ObjectMapper mapper) {
        this(collaboration, store, mapper,
                new com.paicli.platform.server.agent.RunEvidenceCollector(store, mapper));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DeliveryManifestService(CollaborationStore collaboration, SqliteRuntimeStore store, ObjectMapper mapper,
                                   com.paicli.platform.server.agent.RunEvidenceCollector evidenceCollector) {
        this.collaboration = collaboration;
        this.store = store;
        this.mapper = mapper;
        this.evidenceCollector = evidenceCollector;
    }

    /**
     * Records a stage delivery from the unified Run evidence collector so the
     * manifest always reflects real changed files / commands / tests / artifacts.
     */
    public DeliveryRecord recordStageDelivery(String taskId, int stage, String runId) {
        com.paicli.platform.server.agent.RunEvidence evidence = evidenceCollector.collect(runId);
        List<String> changedFiles = evidence.changedFilePaths();
        List<String> artifacts = evidence.businessArtifacts().stream()
                .map(com.paicli.platform.server.agent.ArtifactEvidence::relativePath).toList();
        List<String> testEvidence = evidence.tests().stream()
                .map(test -> test.family().name() + "=" + test.status().name()).toList();
        List<Map<String, Object>> workspaceMutations = evidence.workspaceMutations().stream()
                .map(mutation -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("source", mutation.source());
                    value.put("toolCallId", mutation.toolCallId());
                    value.put("workspaceChanged", mutation.workspaceChanged());
                    value.put("ordinal", mutation.ordinal());
                    if (mutation.command() != null && !mutation.command().isBlank()) {
                        value.put("command", mutation.command());
                    }
                    return value;
                }).toList();
        Map<String, Object> criteria = new LinkedHashMap<>();
        evidence.latestTestStatusByFamily().forEach((family, status) ->
                criteria.put(family.name(), status.name()));
        return recordStageDelivery(taskId, stage, runId, changedFiles, artifacts, testEvidence,
                workspaceMutations, criteria);
    }

    public DeliveryRecord recordStageDelivery(String taskId, int stage, String runId,
                                              List<String> changedFiles, List<String> artifacts,
                                              List<String> testEvidence, Map<String, Object> criteriaEvidence) {
        return recordStageDelivery(taskId, stage, runId, changedFiles, artifacts, testEvidence,
                List.of(), criteriaEvidence);
    }

    private DeliveryRecord recordStageDelivery(String taskId, int stage, String runId,
                                               List<String> changedFiles, List<String> artifacts,
                                               List<String> testEvidence,
                                               List<Map<String, Object>> workspaceMutations,
                                               Map<String, Object> criteriaEvidence) {
        int attempt = store.deliveriesForTask(taskId).stream()
                .filter(delivery -> delivery.stage() == stage)
                .map(DeliveryRecord::attempt).max(Comparator.naturalOrder()).orElse(0) + 1;
        String contentHash = sha256(List.of(
                String.join("\n", changedFiles == null ? List.of() : changedFiles),
                String.join("\n", artifacts == null ? List.of() : artifacts),
                String.join("\n", testEvidence == null ? List.of() : testEvidence),
                write(workspaceMutations == null ? List.of() : workspaceMutations)));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("taskId", taskId);
        manifest.put("stage", stage);
        manifest.put("attempt", attempt);
        manifest.put("runId", runId);
        manifest.put("changedFiles", changedFiles == null ? List.of() : changedFiles);
        manifest.put("artifacts", artifacts == null ? List.of() : artifacts);
        manifest.put("testEvidence", testEvidence == null ? List.of() : testEvidence);
        manifest.put("workspaceMutations", workspaceMutations == null ? List.of() : workspaceMutations);
        manifest.put("criteriaEvidence", criteriaEvidence == null ? Map.of() : criteriaEvidence);
        manifest.put("knownLimitations", List.of());
        manifest.put("contentHash", contentHash);
        manifest.put("createdAt", Instant.now().toString());
        return store.saveDelivery(taskId, stage, attempt, runId, write(manifest), contentHash, "DELIVERED");
    }

    /** Immutable snapshot created when the human ACCEPTs the task. */
    public AcceptedSnapshotRecord accept(String taskId, String conclusionContent) {
        CollaborationStore.CollaborationTask task = collaboration.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("collaboration task not found: " + taskId));
        List<CollaborationStore.CollaborationTask> stages = collaboration.descendantTasks(taskId);
        List<CollaborationStore.CollaborationComment> comments = collaboration.comments(taskId);
        List<CollaborationStore.TaskRun> runs = collaboration.taskTreeRuns(taskId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("task_id", task.id());
        snapshot.put("title", task.title());
        snapshot.put("status", task.status());
        snapshot.put("accepted_at", Instant.now().toString());
        snapshot.put("conclusion", conclusionContent == null ? "" : conclusionContent);
        snapshot.put("comments", comments.stream().map(comment -> Map.of(
                "id", comment.id(), "author", comment.authorType(), "conclusion", comment.conclusion(),
                "at", comment.createdAt().toString())).toList());
        snapshot.put("stages", stages.stream().map(stage -> Map.of(
                "id", stage.id(), "stage", stage.stage(), "title", stage.title(), "status", stage.status())).toList());
        List<String> deliveryTaskIds = new java.util.ArrayList<>();
        deliveryTaskIds.add(task.id());
        stages.forEach(stage -> deliveryTaskIds.add(stage.id()));
        List<Map<String, Object>> deliveries = new java.util.ArrayList<>();
        for (String deliveryTaskId : deliveryTaskIds) {
            store.deliveriesForTask(deliveryTaskId).forEach(delivery -> deliveries.add(Map.of(
                    "task_id", deliveryTaskId, "run_id", delivery.runId(), "stage", delivery.stage(),
                    "attempt", delivery.attempt(), "status", delivery.status(),
                    "content_hash", delivery.contentHash(), "at", delivery.createdAt().toString())));
        }
        snapshot.put("deliveries", List.copyOf(deliveries));
        snapshot.put("run_ids", runs.stream().map(CollaborationStore.TaskRun::runId).distinct().toList());
        snapshot.put("agent_profile_ids", runs.stream().map(CollaborationStore.TaskRun::agentProfileId)
                .filter(value -> value != null && !value.isBlank()).distinct().toList());
        return store.saveAcceptedSnapshot(taskId, write(snapshot));
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize delivery data", e);
        }
    }

    private static String sha256(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) digest.update(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }
}
