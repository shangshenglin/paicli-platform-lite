package com.paicli.platform.server.agent;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PR6: worktree-style conflict detection over parallel child deliveries. Each
 * child reports the files it changed; overlapping paths between different
 * children are flagged as conflicts that must be resolved before a Leader can
 * declare the merged delivery final.
 */
@Service
public class WorkspaceMergeService {

    /** Returns the set of paths written by more than one child. */
    public Set<String> detectConflicts(List<ChildChanges> children) {
        Map<String, List<String>> writersByPath = new LinkedHashMap<>();
        for (ChildChanges child : children) {
            for (String path : child.changedFiles()) {
                writersByPath.computeIfAbsent(path, ignored -> new ArrayList<>()).add(child.childRunId());
            }
        }
        Set<String> conflicts = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : writersByPath.entrySet()) {
            if (entry.getValue().size() > 1) conflicts.add(entry.getKey());
        }
        return conflicts;
    }

    public boolean hasConflicts(List<ChildChanges> children) {
        return !detectConflicts(children).isEmpty();
    }

    public record ChildChanges(String childRunId, List<String> changedFiles) {
        /** Builds a child change set from the unified Run evidence collector. */
        public static ChildChanges of(String childRunId, RunEvidence evidence) {
            return new ChildChanges(childRunId, evidence.changedFilePaths());
        }
    }
}
