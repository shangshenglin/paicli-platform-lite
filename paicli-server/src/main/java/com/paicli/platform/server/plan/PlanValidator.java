package com.paicli.platform.server.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class PlanValidator {
    private static final int MAX_FIELD = 4_000;

    private final SqliteRuntimeStore runtime;
    private final ObjectMapper mapper;

    public PlanValidator(SqliteRuntimeStore runtime) {
        this(runtime, new ObjectMapper());
    }

    @Autowired
    public PlanValidator(SqliteRuntimeStore runtime, ObjectMapper mapper) {
        this.runtime = runtime;
        this.mapper = mapper;
    }

    public ValidationResult validate(PlanStore.PlanStep step, RunRecord run) {
        List<String> criteria = criteria(step.doneCriteriaJson());
        if (criteria.isEmpty()) criteria = List.of("run_status:COMPLETED");
        Optional<MessageRecord> finalAnswer = finalAssistantMessage(run);
        String answer = finalAnswer.map(MessageRecord::content).orElse("");
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (String criterion : criteria) {
            RuleResult result = evaluate(criterion, run, answer);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("criterion", criterion);
            row.put("passed", result.passed());
            row.put("actual", result.actual());
            results.add(row);
            if (!result.passed()) failures.add(result.actual());
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("runId", run.id());
        evidence.put("runStatus", run.status().name());
        evidence.put("finalMessageId", finalAnswer.map(MessageRecord::id).orElse(""));
        evidence.put("finalAnswerPresent", !answer.isBlank());
        evidence.put("criteria", results);
        String evidenceJson = toJson(evidence);
        String actual = failures.isEmpty() ? "All done criteria passed" : String.join("; ", failures);
        return new ValidationResult(failures.isEmpty(), limit(actual), limit(evidenceJson),
                failures.isEmpty() ? null : limit("Validation failed: " + String.join("; ", failures)));
    }

    private RuleResult evaluate(String rawCriterion, RunRecord run, String answer) {
        String criterion = rawCriterion == null ? "" : rawCriterion.trim();
        if (criterion.isBlank()) return new RuleResult(true, "blank criterion ignored");
        String lower = criterion.toLowerCase(Locale.ROOT);
        if (lower.startsWith("run_status:")) {
            String expected = criterion.substring("run_status:".length()).trim();
            boolean passed = run.status().name().equalsIgnoreCase(expected);
            return new RuleResult(passed, "run status is " + run.status().name() + ", expected " + expected);
        }
        if (lower.startsWith("answer_contains:")) {
            String expected = criterion.substring("answer_contains:".length()).trim();
            boolean passed = !expected.isBlank() && contains(answer, expected);
            return new RuleResult(passed, passed ? "final answer contains required text"
                    : "final answer does not contain required text: " + expected);
        }
        if (lower.startsWith("contains:")) {
            String expected = criterion.substring("contains:".length()).trim();
            boolean passed = !expected.isBlank() && contains(answer, expected);
            return new RuleResult(passed, passed ? "final answer contains required text"
                    : "final answer does not contain required text: " + expected);
        }
        if (lower.startsWith("answer_not_contains:")) {
            String forbidden = criterion.substring("answer_not_contains:".length()).trim();
            boolean passed = forbidden.isBlank() || !contains(answer, forbidden);
            return new RuleResult(passed, passed ? "final answer avoids forbidden text"
                    : "final answer contains forbidden text: " + forbidden);
        }
        if (lower.startsWith("not_contains:")) {
            String forbidden = criterion.substring("not_contains:".length()).trim();
            boolean passed = forbidden.isBlank() || !contains(answer, forbidden);
            return new RuleResult(passed, passed ? "final answer avoids forbidden text"
                    : "final answer contains forbidden text: " + forbidden);
        }
        if (answer.isBlank()) {
            return new RuleResult(false, "final answer is missing for criterion: " + criterion);
        }
        boolean passed = hasMeaningfulOverlap(criterion, answer);
        return new RuleResult(passed, passed ? "final answer includes criterion evidence"
                : "final answer lacks evidence for criterion: " + criterion);
    }

    private Optional<MessageRecord> finalAssistantMessage(RunRecord run) {
        return runtime.messages(run.sessionId()).stream()
                .filter(message -> run.id().equals(message.runId()))
                .filter(message -> "assistant".equals(message.role()))
                .filter(message -> message.content() != null && !message.content().isBlank())
                .reduce((first, second) -> second);
    }

    private List<String> criteria(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return List.of();
        try {
            return mapper.readValue(rawJson, new TypeReference<List<String>>() { }).stream()
                    .map(value -> value == null ? "" : value.trim())
                    .filter(value -> !value.isBlank())
                    .toList();
        } catch (Exception ignored) {
            return List.of(rawJson.trim());
        }
    }

    private static boolean contains(String text, String expected) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private static boolean hasMeaningfulOverlap(String criterion, String answer) {
        String normalizedAnswer = answer.toLowerCase(Locale.ROOT);
        for (String token : criterion.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            if (token.length() >= 3 && normalizedAnswer.contains(token)) return true;
        }
        return criterion.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                && answer.contains(criterion);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private static String limit(String value) {
        if (value == null) return "";
        return value.length() > MAX_FIELD ? value.substring(0, MAX_FIELD) : value;
    }

    public record ValidationResult(boolean passed, String actual, String evidence, String error) { }
    private record RuleResult(boolean passed, String actual) { }
}
