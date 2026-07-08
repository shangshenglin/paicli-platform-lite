package com.paicli.platform.server.api;

import com.paicli.platform.server.approval.ApprovalService;
import com.paicli.platform.server.domain.ApprovalRecord;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@RestController
@RequestMapping("/v1/approvals")
public class ApprovalController {
    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public List<ApprovalRecord> pending() {
        return approvalService.pending();
    }

    @PostMapping("/{approvalId}")
    public ApprovalRecord resolve(@PathVariable String approvalId,
                                  @Valid @RequestBody ApiDtos.ResolveApprovalRequest request) {
        return approvalService.resolve(approvalId, request.decision());
    }
}
