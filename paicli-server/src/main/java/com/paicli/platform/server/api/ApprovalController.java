package com.paicli.platform.server.api;

import com.paicli.platform.server.approval.ApprovalService;
import com.paicli.platform.server.domain.ApprovalRecord;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.paicli.platform.server.store.SqliteRuntimeStore;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/approvals")
public class ApprovalController {
    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public List<ApprovalRecord> pending(@RequestParam(required = false) String runId,
                                        @RequestParam(required = false) String projectKey) {
        if (runId != null && !runId.isBlank()) return approvalService.pendingForRunTree(runId);
        return projectKey == null || projectKey.isBlank()
                ? approvalService.pending() : approvalService.pendingForProject(projectKey);
    }

    @PostMapping("/{approvalId}")
    public ApprovalRecord resolve(@PathVariable String approvalId,
                                  @Valid @RequestBody ApiDtos.ResolveApprovalRequest request) {
        return approvalService.resolve(approvalId, request.decision(), request.rememberScope());
    }

    @GetMapping("/policies")
    public List<SqliteRuntimeStore.ApprovalPolicy> policies(
            @RequestParam(defaultValue = "default") String projectKey) {
        return approvalService.policies(projectKey);
    }

    @DeleteMapping("/policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePolicy(@PathVariable String policyId) {
        if (!approvalService.deletePolicy(policyId)) throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "approval policy not found");
    }

    @PostMapping("/policies/batch-delete")
    @Operation(summary = "Permanently delete persisted approval policies in one transaction",
            description = "Deletes up to 100 exact-argument approval policies. Missing ids roll back the complete batch.")
    public Map<String, Object> batchDeletePolicies(
            @Valid @RequestBody ApiDtos.BatchDeleteRequest request) {
        List<String> deleted = approvalService.deletePolicies(request.ids());
        return Map.of("deletedIds", deleted, "deletedCount", deleted.size());
    }
}
