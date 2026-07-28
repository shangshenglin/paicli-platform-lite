package com.paicli.platform.server.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

public final class TaskTitle {
    private static final int MAX_CODE_POINTS = 36;
    private static final Set<String> GENERIC_SESSION_TITLES = Set.of(
            "新对话", "未命名对话", "new session", "new conversation", "untitled");
    private static final Pattern LEADING_MARKUP = Pattern.compile(
            "^(?:#{1,6}\\s*|[-*+]\\s+|\\d+[.)、]\\s*)+");
    private static final Pattern TASK_PREFIX = Pattern.compile(
            "^(?:(?:用户|协作|计划)?(?:任务|目标|请求|需求)|用户目标|任务目标)\\s*[:：]\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern POLITE_PREFIX = Pattern.compile(
            "^(?:请帮我|麻烦帮我|帮我|请)\\s*", Pattern.CASE_INSENSITIVE);

    private TaskTitle() {
    }

    public static boolean isGenericSessionTitle(String title) {
        return title == null || title.isBlank()
                || GENERIC_SESSION_TITLES.contains(title.trim().toLowerCase());
    }

    public static String summarize(String text, String fallback) {
        String candidate = Arrays.stream((text == null ? "" : text).replace('\r', '\n').split("\\n"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(line -> LEADING_MARKUP.matcher(line).replaceFirst("").trim())
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("");
        candidate = TASK_PREFIX.matcher(candidate).replaceFirst("").trim();
        candidate = POLITE_PREFIX.matcher(candidate).replaceFirst("").trim();
        candidate = candidate.replaceAll("[`*_~]+", "").replaceAll("\\s+", " ").trim();
        int sentenceEnd = firstSentenceEnd(candidate);
        if (sentenceEnd > 0) candidate = candidate.substring(0, sentenceEnd).trim();
        if (candidate.isBlank()) candidate = fallback == null || fallback.isBlank() ? "未命名任务" : fallback.trim();
        return truncate(candidate, MAX_CODE_POINTS);
    }

    public static String delegated(String agentName, String task) {
        String name = agentName == null || agentName.isBlank() ? "子专家" : agentName.trim();
        return truncate(name + " · " + summarize(task, "协作任务"), 80);
    }

    private static int firstSentenceEnd(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if ("。！？!?；;".indexOf(current) >= 0 && index >= 7) return index;
        }
        return -1;
    }

    private static String truncate(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        int end = value.offsetByCodePoints(0, maxCodePoints - 1);
        return value.substring(0, end).trim() + "…";
    }
}
