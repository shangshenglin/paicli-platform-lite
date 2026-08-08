package com.paicli.platform.server.collaboration;

import com.paicli.platform.server.store.CollaborationStore;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Lightweight ExpertThread: a logical, durable continuation of one expert (agent profile) inside
 * one root collaboration task. A terminal Run is never resurrected; the next Run is a brand-new
 * immutable execution attempt that is attached to the same thread, and the only cross-Run context
 * it receives is the compact thread digest built by {@link ExpertThreadDigestBuilder}.
 */
@Service
public class ExpertThreadService {
    private final CollaborationStore collaboration;
    private final ExpertThreadDigestBuilder digestBuilder;

    public ExpertThreadService(CollaborationStore collaboration, ExpertThreadDigestBuilder digestBuilder) {
        this.collaboration = collaboration;
        this.digestBuilder = digestBuilder;
    }

    /** Idempotent: root_task_id + agent_profile_id + thread_role uniquely identify the thread. */
    public CollaborationStore.ExpertThread getOrCreate(String rootTaskId, String agentProfileId, String role) {
        return collaboration.getOrCreateExpertThread(rootTaskId, agentProfileId, role);
    }

    public void attachRun(String threadId, String runId) {
        collaboration.attachExpertThreadRun(threadId, runId);
    }

    public Optional<CollaborationStore.ExpertThread> findByRun(String runId) {
        return collaboration.expertThreadForRun(runId);
    }

    /** Rebuilds the digest from auditable state (task tree, terminal runs, manifests, artifacts). */
    public CollaborationStore.ExpertThread refreshDigest(String threadId) {
        String digest = digestBuilder.build(threadId);
        collaboration.updateExpertThreadDigest(threadId, digest);
        return collaboration.expertThread(threadId)
                .orElseThrow(() -> new IllegalStateException("expert thread not found: " + threadId));
    }
}
