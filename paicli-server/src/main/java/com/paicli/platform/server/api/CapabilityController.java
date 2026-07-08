package com.paicli.platform.server.api;

import com.paicli.platform.server.config.MemoryProperties;
import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.config.RagProperties;
import com.paicli.platform.server.config.WebProperties;
import com.paicli.platform.server.knowledge.KnowledgeService;
import com.paicli.platform.server.mcp.McpToolProvider;
import com.paicli.platform.server.skill.SkillService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/capabilities")
public class CapabilityController {
    private final SkillService skills;
    private final KnowledgeService knowledge;
    private final McpToolProvider mcp;
    private final RagProperties rag;
    private final MemoryProperties memory;
    private final ModelProperties model;
    private final WebProperties web;

    public CapabilityController(SkillService skills, KnowledgeService knowledge, McpToolProvider mcp,
                                RagProperties rag, MemoryProperties memory, ModelProperties model,
                                WebProperties web) {
        this.skills = skills;
        this.knowledge = knowledge;
        this.mcp = mcp;
        this.rag = rag;
        this.memory = memory;
        this.model = model;
        this.web = web;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String projectKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skills", Map.of("count", skills.list(projectKey).size(),
                "loading", "index-then-load", "resources", true));
        result.put("rag", Map.of("documents", knowledge.list(projectKey).size(),
                "embeddingProvider", rag.embeddingProvider(),
                "semanticEmbedding", !rag.embeddingProvider().equals("local")
                        && !rag.embeddingProvider().equals("local-hash"),
                "automaticRetrieval", rag.autoRetrieve(),
                "scannedPdfOcr", rag.pdfOcrEnabled(),
                "pdfOcrMaxPages", rag.pdfOcrMaxPages(),
                "retrieval", "BM25 + vector + RRF + dedup"));
        result.put("memory", Map.of("automaticExtraction", memory.autoExtract(),
                "layers", "L1/L2/L3", "retrievalTopK", memory.retrievalTopK(),
                "revisionHistory", true));
        result.put("web", Map.of("enabled", web.enabled(), "ssrfProtection", true));
        result.put("mcp", Map.of("servers", mcp.statuses().size(),
                "ready", mcp.statuses().stream().filter(McpToolProvider.ServerStatus::ready).count()));
        result.put("model", Map.of("provider", model.provider(), "model", model.model(),
                "retryAttempts", model.maxAttempts(), "fallbackConfigured", !model.fallbackModel().isBlank(),
                "maxRunSteps", model.maxRunSteps(), "maxRunTokens", model.maxRunTokens()));
        result.put("multimodal", Map.of("images", true, "documents", true,
                "scannedPdfVisualFallback", true,
                "supportedImageTypes", "PNG/JPEG/GIF"));
        return result;
    }
}
