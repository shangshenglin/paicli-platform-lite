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
        store.markRunStatus(run.id(), com.paicli.platform.common.RunStatus.WAITING_APPROVAL);
        store.appendEvent(run.id(), "approval.requested", json(approval));
        auditService.record("approval.requested", run.id(), call.id(), Map.of(
                "approvalId", approval.id(), "tool", call.toolName(), "arguments", call.arguments()));
        return approval;
    }

    public synchronized ApprovalRecord resolve(String approvalId, ApprovalStatus decision) {
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

    public ApprovalStatus statusForTool(String toolCallId) {
        return store.findApprovalByToolCall(toolCallId)
                .map(ApprovalRecord::status)
                .orElse(null);
    }

    private static String json(ApprovalRecord approval) {
        return "{\"approvalId\":\"" + approval.id() + "\",\"toolCallId\":\""
                + approval.toolCallId() + "\",\"status\":\"" + approval.status() + "\"}";
    }
}
