package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.PrdAnalysisStore;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PrdNodeMapper {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private final ObjectMapper mapper;

    public PrdNodeMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<PrdAnalysisStore.NodeDraft> map(String markdown) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<Heading> headings = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            Matcher matcher = HEADING.matcher(lines[index]);
            if (matcher.matches()) headings.add(new Heading(index + 1, matcher.group(1).length(), matcher.group(2).trim()));
        }
        if (headings.isEmpty()) {
            return List.of(draft(1, "PRD 全文", 1, 1, lines.length, normalized, List.of(), List.of("root")));
        }

        List<PrdAnalysisStore.NodeDraft> drafts = new ArrayList<>();
        Deque<NodeRef> parents = new ArrayDeque<>();
        for (int index = 0; index < headings.size(); index++) {
            Heading current = headings.get(index);
            int endLine = index + 1 < headings.size() ? headings.get(index + 1).line() - 1 : lines.length;
            String content = String.join("\n", java.util.Arrays.copyOfRange(lines, current.line() - 1, endLine));
            while (!parents.isEmpty() && parents.peekLast().level() >= current.level()) parents.removeLast();
            List<String> dependencies = parents.isEmpty() ? List.of() : List.of(parents.peekLast().key());
            int ordinal = drafts.size() + 1;
            String key = "N" + String.format("%03d", ordinal);
            List<String> tags = tags(current.title(), current.level(), ordinal == 1);
            drafts.add(draft(ordinal, current.title(), current.level(), current.line(), endLine,
                    content, dependencies, tags));
            parents.addLast(new NodeRef(key, current.level()));
        }
        return drafts;
    }

    public Map<String, Integer> dependencyLevels(List<PrdAnalysisStore.AnalysisNode> nodes) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        Map<String, PrdAnalysisStore.AnalysisNode> byKey = new LinkedHashMap<>();
        nodes.forEach(node -> byKey.put(node.nodeKey(), node));
        for (PrdAnalysisStore.AnalysisNode node : nodes) level(node, byKey, levels, new ArrayDeque<>());
        return levels;
    }

    private int level(PrdAnalysisStore.AnalysisNode node,
                      Map<String, PrdAnalysisStore.AnalysisNode> byKey,
                      Map<String, Integer> levels, Deque<String> visiting) {
        Integer existing = levels.get(node.nodeKey());
        if (existing != null) return existing;
        if (visiting.contains(node.nodeKey())) throw new IllegalArgumentException("node dependency cycle");
        visiting.addLast(node.nodeKey());
        int level = 0;
        try {
            for (String key : mapper.readValue(node.dependenciesJson(), String[].class)) {
                PrdAnalysisStore.AnalysisNode dependency = byKey.get(key);
                if (dependency == null) throw new IllegalArgumentException("missing node dependency: " + key);
                level = Math.max(level, level(dependency, byKey, levels, visiting) + 1);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid node dependencies", e);
        } finally {
            visiting.removeLast();
        }
        levels.put(node.nodeKey(), level);
        return level;
    }

    private PrdAnalysisStore.NodeDraft draft(int ordinal, String heading, int level, int start, int end,
                                             String content, List<String> dependencies, List<String> tags) {
        try {
            return new PrdAnalysisStore.NodeDraft("N" + String.format("%03d", ordinal), ordinal, heading,
                    level, start, end, content, mapper.writeValueAsString(dependencies),
                    mapper.writeValueAsString(tags));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> tags(String title, int level, boolean root) {
        String value = title.toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        if (root) tags.add("root");
        if (level <= 2) tags.add("major");
        if (contains(value, "流程", "flow", "journey", "process")) tags.add("flow");
        if (contains(value, "数据", "字段", "接口", "source", "schema", "contract")) tags.add("source");
        if (contains(value, "规则", "约束", "rule", "policy")) tags.add("rule");
        if (contains(value, "术语", "glossary", "definition", "定义")) tags.add("glossary");
        return List.copyOf(tags);
    }

    private static boolean contains(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private record Heading(int line, int level, String title) { }
    private record NodeRef(String key, int level) { }
}
