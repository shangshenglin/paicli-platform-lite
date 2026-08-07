package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.ArtifactRecord;
import com.paicli.platform.server.domain.InputAttachmentRecord;
import com.paicli.platform.server.domain.SessionRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API orchestration for the PRD Analysis business agent. Controllers never touch
 * SQLite directly; they go through this service (and the durable coordinator for
 * stage advancement).
 */
@Service
public class PrdAnalysisService {
    private final PrdAnalysisStore store;
    private final SqliteRuntimeStore runtime;
    private final PrdAnalysisCoordinator coordinator;
    private final PrdAnalysisSkillCatalog skills;
    private final PrdAnalysisMetrics metrics;
    private final ObjectMapper mapper;

    public PrdAnalysisService(PrdAnalysisStore store, SqliteRuntimeStore runtime,
                              PrdAnalysisCoordinator coordinator, PrdAnalysisSkillCatalog skills,
                              PrdAnalysisMetrics metrics, ObjectMapper mapper) {
        this.store = store;
        this.runtime = runtime;
        this.coordinator = coordinator;
        this.skills = skills;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    public Map<String, Object> createTask(String sessionId, String projectKey, String title,
                                          String prdAttachmentId, String sourceContractAttachmentId,
                                          List<String> supportingAttachmentIds, Integer maxParallelism) {
        SessionRecord session = runtime.findSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
        String resolvedProject = projectKey == null || projectKey.isBlank() ? session.projectKey() : projectKey.trim();
        InputAttachmentRecord prd = runtime.findStagedAttachment(sessionId, prdAttachmentId)
                .orElseThrow(() -> new IllegalArgumentException("PRD attachment is not staged in this session"));
        if (!com.paicli.platform.server.artifact.DocumentAttachmentService.isDocument(prd)) {
            throw new IllegalArgumentException("PRD attachment must be a document");
        }
        PrdAnalysisStore.PrdTask task = store.createTask(resolvedProject, title, "USER",
                maxParallelism == null ? 4 : maxParallelism, sessionId);
        PrdAnalysisStore.PrdSource prdSource = store.insertSource(task.id(), prd.id(), "PRD",
                prd.name(), prd.sha256(), "PENDING", null);
        String contractSourceId = null;
        if (sourceContractAttachmentId != null && !sourceContractAttachmentId.isBlank()) {
            InputAttachmentRecord contract = runtime.findStagedAttachment(sessionId, sourceContractAttachmentId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "source contract attachment is not staged in this session"));
            if (!com.paicli.platform.server.artifact.DocumentAttachmentService.isDocument(contract)) {
                throw new IllegalArgumentException("source contract attachment must be a document");
            }
            contractSourceId = store.insertSource(task.id(), contract.id(), "SOURCE_CONTRACT",
                    contract.name(), contract.sha256(), "PENDING", null).id();
        }
        List<String> supporting = supportingAttachmentIds == null ? List.of() : supportingAttachmentIds;
        for (String attachmentId : supporting) {
            if (attachmentId == null || attachmentId.isBlank()) continue;
            InputAttachmentRecord attachment = runtime.findStagedAttachment(sessionId, attachmentId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "supporting attachment is not staged in this session: " + attachmentId));
            if (!com.paicli.platform.server.artifact.DocumentAttachmentService.isDocument(attachment)) {
                throw new IllegalArgumentException("supporting attachment must be a document");
            }
            store.insertSource(task.id(), attachment.id(), "SUPPORTING",
                    attachment.name(), attachment.sha256(), "PENDING", null);
        }
        store.updateTaskSourceLinks(task.id(), prdSource.id(), contractSourceId);
        return detail(task.id());
    }

    public Map<String, Object> start(String taskId) {
        PrdAnalysisStore.PrdTask task = store.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("PRD task not found: " + taskId));
        if (!"DRAFT".equals(task.status())) {
            throw new IllegalStateException("only DRAFT tasks can be started");
        }
        skills.ensureProfiles(task.projectKey());
        store.updateTaskStatus(taskId, "INGESTING", null);
        if (metrics != null) metrics.taskStarted();
        coordinator.advance(taskId);
        return detail(taskId);
    }

    public Map<String, Object> cancel(String taskId) {
        PrdAnalysisStore.PrdTask task = store.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("PRD task not found: " + taskId));
        if (List.of("COMPLETED", "FAILED", "CANCELED").contains(task.status())) {
            throw new IllegalStateException("task is already terminal");
        }
        for (PrdAnalysisStore.PrdRunBinding binding : store.runBindings(taskId)) {
            try {
                runtime.cancelRun(binding.runId());
            } catch (Exception ignored) { }
        }
        store.updateTaskStatus(taskId, "CANCELED", "canceled by user");
        return detail(taskId);
    }

    public Map<String, Object> retry(String taskId) {
        PrdAnalysisStore.PrdTask task = store.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("PRD task not found: " + taskId));
        if (!"FAILED".equals(task.status())) {
            throw new IllegalStateException("only failed tasks can be retried");
        }
        String stage = task.currentStage();
        if ("MAPPING".equals(stage) || "RECONCILING".equals(stage)) {
            coordinator.retryStage(taskId, "MAPPING".equals(stage) ? "MAP" : "RECONCILE");
        } else {
            store.reopenTask(taskId);
        }
        coordinator.advance(taskId);
        return detail(taskId);
    }

    public Map<String, Object> retryNode(String taskId, String nodeId) {
        coordinator.retryNode(taskId, nodeId);
        return detail(taskId);
    }

    public int answer(String taskId, List<PrdAnalysisStore.QuestionAnswer> answers) {
        PrdAnalysisStore.PrdTask task = store.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("PRD task not found: " + taskId));
        if (!"WAITING_USER".equals(task.status())) {
            throw new IllegalStateException("answers are only accepted while waiting for the user");
        }
        int updated = store.answerQuestions(taskId, answers == null ? List.of() : answers);
        coordinator.advance(taskId);
        return updated;
    }

    public Map<String, Object> detail(String taskId) {
        PrdAnalysisStore.PrdTask task = store.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("PRD task not found: " + taskId));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("task", taskView(task));
        value.put("sources", store.sources(taskId).stream().map(this::sourceView).toList());
        List<PrdAnalysisStore.PrdNode> nodes = store.nodes(taskId);
        value.put("nodes", nodes.stream().map(this::nodeView).toList());
        value.put("nodeStats", nodeStats(nodes));
        value.put("findings", store.findings(taskId, null, null, "ACTIVE", 0, 500)
                .stream().map(this::findingView).toList());
        value.put("questions", store.questions(taskId, null, null, 500)
                .stream().map(this::questionView).toList());
        value.put("checks", store.checks(taskId));
        value.put("runs", store.runBindings(taskId).stream().map(this::runBindingView).toList());
        value.put("artifacts", store.artifactsForTask(taskId));
        return value;
    }

    public List<Map<String, Object>> list(String projectKey, String status, int limit) {
        return store.tasks(projectKey, status, limit).stream().map(this::taskView).toList();
    }

    public Map<String, Object> taskView(PrdAnalysisStore.PrdTask task) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", task.id());
        value.put("projectKey", task.projectKey());
        value.put("title", task.title());
        value.put("status", task.status());
        value.put("stage", task.currentStage());
        value.put("maxParallelism", task.maxParallelism());
        value.put("reconcileIteration", task.reconcileIteration());
        value.put("glossary", task.glossaryJson());
        value.put("createdBy", task.createdBy());
        value.put("createdAt", task.createdAt());
        value.put("updatedAt", task.updatedAt());
        value.put("completedAt", task.completedAt());
        value.put("lastError", task.lastError());
        List<PrdAnalysisStore.PrdNode> nodes = store.nodes(task.id());
        long completed = nodes.stream().filter(node -> "COMPLETED".equals(node.status())).count();
        value.put("nodesCompleted", completed);
        value.put("nodesTotal", nodes.size());
        value.put("blockingQuestions", store.countOpenBlocking(task.id()));
        return value;
    }

    private Map<String, Object> sourceView(PrdAnalysisStore.PrdSource source) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", source.id());
        value.put("sourceType", source.sourceType());
        value.put("fileName", source.fileName());
        value.put("contentHash", source.contentHash());
        value.put("extractionStatus", source.extractionStatus());
        value.put("chunkCount", store.chunks(source.id(), 0, 1_000).size());
        value.put("createdAt", source.createdAt());
        return value;
    }

    private Map<String, Object> nodeView(PrdAnalysisStore.PrdNode node) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", node.id());
        value.put("clientKey", node.clientKey());
        value.put("title", node.title());
        value.put("summary", node.summary());
        value.put("sourceId", node.sourceId());
        value.put("startChunkOrdinal", node.startChunkOrdinal());
        value.put("endChunkOrdinal", node.endChunkOrdinal());
        value.put("status", node.status());
        value.put("domainTags", node.domainTagsJson());
        value.put("updatedAt", node.updatedAt());
        return value;
    }

    private Map<String, Long> nodeStats(List<PrdAnalysisStore.PrdNode> nodes) {
        Map<String, Long> stats = new LinkedHashMap<>();
        for (PrdAnalysisStore.PrdNode node : nodes) {
            stats.merge(node.status(), 1L, Long::sum);
        }
        return stats;
    }

    private Map<String, Object> findingView(PrdAnalysisStore.PrdFinding finding) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", finding.id());
        value.put("nodeId", finding.nodeId());
        value.put("type", finding.findingType());
        value.put("name", finding.name());
        value.put("summary", finding.summary());
        value.put("payload", finding.payloadJson());
        value.put("status", finding.status());
        value.put("severity", finding.severity());
        value.put("evidence", store.evidenceForFinding(finding.id()).stream().map(evidence -> {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("chunkId", evidence.chunkId());
            ev.put("start", evidence.localStartOffset());
            ev.put("end", evidence.localEndOffset());
            return ev;
        }).toList());
        return value;
    }

    private Map<String, Object> questionView(PrdAnalysisStore.PrdQuestion question) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", question.id());
        value.put("category", question.category());
        value.put("severity", question.severity());
        value.put("question", question.question());
        value.put("context", question.context());
        value.put("status", question.status());
        value.put("answer", question.answer());
        value.put("resolution", question.resolution());
        return value;
    }

    private Map<String, Object> runBindingView(PrdAnalysisStore.PrdRunBinding binding) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", binding.id());
        value.put("purpose", binding.purpose());
        value.put("nodeId", binding.nodeId());
        value.put("runId", binding.runId());
        value.put("attempt", binding.attempt());
        value.put("status", binding.status());
        value.put("submitted", binding.submissionToolCallId() != null);
        value.put("createdAt", binding.createdAt());
        value.put("updatedAt", binding.updatedAt());
        return value;
    }

    public List<Map<String, Object>> nodes(String taskId) {
        return store.nodes(taskId).stream().map(this::nodeView).toList();
    }

    public List<Map<String, Object>> findings(String taskId, String type, String nodeId, String status,
                                              int offset, int limit) {
        return store.findings(taskId, type, nodeId, status, offset, limit)
                .stream().map(this::findingView).toList();
    }

    public List<PrdAnalysisStore.PrdCheck> checks(String taskId) {
        return store.checks(taskId);
    }

    public List<Map<String, Object>> questions(String taskId, String status, String severity, int limit) {
        return store.questions(taskId, status, severity, limit).stream().map(this::questionView).toList();
    }

    public List<ArtifactRecord> artifacts(String taskId) {
        return store.artifactsForTask(taskId);
    }
}
