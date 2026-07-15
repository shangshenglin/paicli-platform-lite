package com.paicli.platform.server.approval;

import com.paicli.platform.common.ApprovalStatus;
import com.paicli.platform.server.audit.AuditService;
import com.paicli.platform.server.domain.ApprovalRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ToolRouter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class ApprovalService {
    private final SqliteRuntimeStore store;
    private final AuditService auditService;
    private final ToolRouter toolRouter;

    public ApprovalService(SqliteRuntimeStore store, AuditService auditService, ToolRouter toolRouter) {
        this.store = store;
        this.auditService = auditService;
        this.toolRouter = toolRouter;
    }

    public boolean requiresApproval(String toolName) {
        return toolRouter.requiresApproval(toolName);
    }

    public synchronized ApprovalRecord request(RunRecord run, ToolCallRecord call) {
        String reason = "Tool '" + call.toolName() + "' requires explicit approval before execution";
        ApprovalRecord approval = store.createApproval(run.id(), call.id(), reason);
        var session = store.findSession(run.sessionId()).orElseThrow();
        var policy = store.matchingApprovalPolicy(session.id(), session.projectKey(), call.toolName(),
                sha256(call.arguments()));
        if (policy.isPresent()) {
            ApprovalRecord approved = store.resolveApproval(approval.id(), ApprovalStatus.APPROVED);
            store.appendEvent(run.id(), "approval.policy_matched", json(approved));
            auditService.record("approval.policy_matched", run.id(), call.id(), Map.of(
                    "approvalId", approved.id(), "policyId", policy.get().id(), "scope", policy.get().scope()));
            store.requeueRun(run.id(), run.currentStep());
            return approved;
        }
        store.markRunStatus(run.id(), com.paicli.platform.common.RunStatus.WAITING_APPROVAL);
        store.appendEvent(run.id(), "approval.requested", json(approval));
        auditService.record("approval.requested", run.id(), call.id(), Map.of(
                "approvalId", approval.id(), "tool", call.toolName(), "arguments", call.arguments()));
        return approval;
    }

    public synchronized ApprovalRecord resolve(String approvalId, ApprovalStatus decision) {
        return resolve(approvalId, decision, null);
    }

    public synchronized ApprovalRecord resolve(String approvalId, ApprovalStatus decision, String rememberScope) {
        String normalizedScope = rememberScope == null ? "" : rememberScope.trim().toUpperCase();
        if (decision == ApprovalStatus.APPROVED && !normalizedScope.isBlank()
                && !java.util.Set.of("SESSION", "PROJECT").contains(normalizedScope)) {
            throw new IllegalArgumentException("rememberScope must be SESSION or PROJECT");
        }
        ApprovalRecord current = store.findApproval(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("approval not found: " + approvalId));
        if (current.status() != ApprovalStatus.PENDING) {
            if (current.status() == decision) return current;
            throw new IllegalStateException("approval is already " + current.status());
        }
        RunRecord currentRun = store.findRun(current.runId())
                .orElseThrow(() -> new IllegalStateException("run not found: " + current.runId()));
        if (currentRun.status().terminal()) {
            throw new IllegalStateException("run is already " + currentRun.status());
        }
        ApprovalRecord approval = store.resolveApproval(approvalId, decision);
        RunRecord run = store.findRun(approval.runId())
                .orElseThrow(() -> new IllegalStateException("run not found: " + approval.runId()));
        if (decision == ApprovalStatus.APPROVED && !normalizedScope.isBlank()) {
            ToolCallRecord call = store.findToolCall(approval.toolCallId()).orElseThrow();
            var session = store.findSession(run.sessionId()).orElseThrow();
            store.createApprovalPolicy(normalizedScope, session.id(), session.projectKey(),
                    call.toolName(), sha256(call.arguments()));
        }
        store.appendEvent(run.id(), "approval.resolved", json(approval));
        auditService.record("approval.resolved", run.id(), approval.toolCallId(), Map.of(
                "approvalId", approval.id(), "decision", decision));
        if (decision == ApprovalStatus.APPROVED) {
            store.requeueRun(run.id(), run.currentStep());
        } else {
            store.failTool(approval.toolCallId(), "Tool call denied by user");
            store.failRun(run.id(), "Tool call denied by user");
            toolRouter.release(run.id());
        }
        return approval;
    }

    public List<ApprovalRecord> pending() {
        return store.pendingApprovals();
    }

    public List<SqliteRuntimeStore.ApprovalPolicy> policies(String projectKey) {
        return store.approvalPolicies(projectKey);
    }

    public boolean deletePolicy(String policyId) {
        return store.deleteApprovalPolicy(policyId);
    }

    public ApprovalStatus statusForTool(String toolCallId) {
        return store.findApprovalByToolCall(toolCallId)
                .map(ApprovalRecord::status)
                .orElse(null);
    }

    private static String json(ApprovalRecord approval) {
        return "{\"approvalId\":\"" + approval.id() + "\",\"toolCallId\":\""
                + approval.toolCallId() + "\",\"status\":\"" + approval.status() + "\"}";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
