package com.paicli.platform.server.domain;

import java.time.Instant;

/**
 * Durable, structured reflection produced only when a Run needs repair.
 * Deliberately contains no model reasoning chain: only the auditable failure
 * class, diagnosis, decision, plan patch, evidence references and next action.
 */
public record ReflectionRecord(
        String id,
        String runId,
        String failureClass,
        String diagnosis,
        String decision,
        String planPatchJson,
        String evidenceRefsJson,
        String nextAction,
        Instant createdAt
) {
}
