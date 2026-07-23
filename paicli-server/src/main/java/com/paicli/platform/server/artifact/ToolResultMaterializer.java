package com.paicli.platform.server.artifact;

import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.domain.ArtifactRecord;
import org.springframework.stereotype.Component;

@Component
public class ToolResultMaterializer {
    private static final int PREVIEW_CHARS = 1_000;
    private final ArtifactStore artifactStore;
    private final int inlineLimit;

    public ToolResultMaterializer(ArtifactStore artifactStore, ModelProperties properties) {
        this.artifactStore = artifactStore;
        this.inlineLimit = properties.toolResultInlineChars();
    }

    public MaterializedResult materialize(String runId, String toolName, String content) {
        String normalized = content == null ? "" : content;
        if (normalized.length() <= inlineLimit) {
            return new MaterializedResult(normalized, null);
        }
        ArtifactRecord artifact = artifactStore.saveText(runId, "tool_result", toolName, normalized);
        String preview = normalized.substring(0, Math.min(PREVIEW_CHARS, normalized.length()));
        String placeholder = "[Tool result externalized]\n"
                + "artifact_id=" + artifact.id() + "\n"
                + "size_bytes=" + artifact.size() + "\n"
                + "sha256=" + artifact.sha256() + "\n"
                + "preview:\n" + preview + "\n"
                + "Use read_artifact to retrieve another range.";
        return new MaterializedResult(placeholder, artifact);
    }

    public record MaterializedResult(String modelContent, ArtifactRecord artifact) {
    }
}

