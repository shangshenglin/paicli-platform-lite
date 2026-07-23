package com.paicli.platform.server.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class PlanValidator {
    private static final int MAX_FIELD = 4_000;
    private static final long MAX_TEXT_FILE_BYTES = 2_000_000L;

    private final SqliteRuntimeStore runtime;
    private final ObjectMapper mapper;
    private final Path workspaceRoot;

    public PlanValidator(SqliteRuntimeStore runtime) {
        this(runtime, new ObjectMapper(), Path.of("."));
    }

    public PlanValidator(SqliteRuntimeStore runtime, ObjectMapper mapper) {
        this(runtime, mapper, Path.of("."));
    }

    @Autowired
    public PlanValidator(SqliteRuntimeStore runtime, ObjectMapper mapper, PlatformProperties properties) {
        this(runtime, mapper, properties.workspaceRoot());
    }

    PlanValidator(SqliteRuntimeStore runtime, ObjectMapper mapper, Path workspaceRoot) {
        this.runtime = runtime;
        this.mapper = mapper;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    public ValidationResult validate(PlanStore.PlanStep step, RunRecord run) {
        List<String> criteria = criteria(step.doneCriteriaJson());
        if (criteria.isEmpty()) criteria = List.of("run_status:COMPLETED");
        Optional<MessageRecord> finalAnswer = finalAssistantMessage(run);
        String answer = finalAnswer.map(MessageRecord::content).orElse("");
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (String criterion : criteria) {
            Instant started = Instant.now();
            RuleResult result = evaluate(criterion, run, answer);
            Instant finished = Instant.now();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("validatorType", result.validatorType());
            row.put("criterion", criterion);
            row.put("expected", result.expected());
            row.put("actual", result.actual());
            row.put("status", result.passed() ? "PASSED" : "FAILED");
            row.put("artifactRefs", List.of());
            row.put("logRefs", List.of());
            row.put("sourceRefs", result.sourceRefs());
            row.put("startedAt", started.toString());
            row.put("finishedAt", finished.toString());
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
        if (criterion.isBlank()) return passed("LLM_RUBRIC", "", "blank criterion ignored");
        String lower = criterion.toLowerCase(Locale.ROOT);
        if (lower.startsWith("run_status:")) {
            String expected = criterion.substring("run_status:".length()).trim();
            boolean passed = run.status().name().equalsIgnoreCase(expected);
            return passed ? passed("RUN_STATUS", expected, "run status is " + run.status().name())
                    : failed("RUN_STATUS", expected, "run status is " + run.status().name()
                    + ", expected " + expected);
        }
        if (lower.startsWith("answer_contains:")) {
            String expected = criterion.substring("answer_contains:".length()).trim();
            boolean passed = !expected.isBlank() && contains(answer, expected);
            return passed ? passed("ANSWER_CONTAINS", expected, "final answer contains required text")
                    : failed("ANSWER_CONTAINS", expected,
                    "final answer does not contain required text: " + expected);
        }
        if (lower.startsWith("contains:")) {
            String expected = criterion.substring("contains:".length()).trim();
            boolean passed = !expected.isBlank() && contains(answer, expected);
            return passed ? passed("ANSWER_CONTAINS", expected, "final answer contains required text")
                    : failed("ANSWER_CONTAINS", expected,
                    "final answer does not contain required text: " + expected);
        }
        if (lower.startsWith("answer_not_contains:")) {
            String forbidden = criterion.substring("answer_not_contains:".length()).trim();
            boolean passed = forbidden.isBlank() || !contains(answer, forbidden);
            return passed ? passed("ANSWER_NOT_CONTAINS", forbidden, "final answer avoids forbidden text")
                    : failed("ANSWER_NOT_CONTAINS", forbidden,
                    "final answer contains forbidden text: " + forbidden);
        }
        if (lower.startsWith("not_contains:")) {
            String forbidden = criterion.substring("not_contains:".length()).trim();
            boolean passed = forbidden.isBlank() || !contains(answer, forbidden);
            return passed ? passed("ANSWER_NOT_CONTAINS", forbidden, "final answer avoids forbidden text")
                    : failed("ANSWER_NOT_CONTAINS", forbidden,
                    "final answer contains forbidden text: " + forbidden);
        }
        if (lower.startsWith("file_exists:")) {
            return validateFileExists(criterion.substring("file_exists:".length()).trim(), true);
        }
        if (lower.startsWith("file_not_exists:")) {
            return validateFileExists(criterion.substring("file_not_exists:".length()).trim(), false);
        }
        if (lower.startsWith("file_absent:")) {
            return validateFileExists(criterion.substring("file_absent:".length()).trim(), false);
        }
        if (lower.startsWith("file_contains:")) {
            return validateFileContains(criterion.substring("file_contains:".length()).trim());
        }
        if (lower.startsWith("test_report:")) {
            return validateTestReport(criterion.substring("test_report:".length()).trim());
        }
        if (answer.isBlank()) {
            return failed("LLM_RUBRIC", criterion, "final answer is missing for criterion: " + criterion);
        }
        boolean passed = hasMeaningfulOverlap(criterion, answer);
        return passed ? passed("LLM_RUBRIC", criterion, "final answer includes criterion evidence")
                : failed("LLM_RUBRIC", criterion, "final answer lacks evidence for criterion: " + criterion);
    }

    private RuleResult validateFileExists(String rawPath, boolean shouldExist) {
        PathResolution path = resolveWorkspacePath(rawPath);
        if (!path.allowed()) return failed("FILE_EXISTS", rawPath, path.message(), List.of());
        boolean exists = Files.exists(path.path());
        boolean passed = shouldExist ? exists : !exists;
        String expected = shouldExist ? "file exists: " + rawPath : "file is absent: " + rawPath;
        String actual = exists ? "file exists: " + rawPath : "file is absent: " + rawPath;
        return passed ? passed("FILE_EXISTS", expected, actual, List.of(rawPath))
                : failed("FILE_EXISTS", expected, actual, List.of(rawPath));
    }

    private RuleResult validateFileContains(String rawSpec) {
        String[] parts = rawSpec.split("::", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return failed("FILE_CONTAINS", rawSpec, "expected format file_contains:path::text");
        }
        PathResolution path = resolveWorkspacePath(parts[0].trim());
        if (!path.allowed()) return failed("FILE_CONTAINS", rawSpec, path.message(), List.of());
        if (!Files.isRegularFile(path.path())) {
            return failed("FILE_CONTAINS", rawSpec, "file is missing: " + parts[0].trim(),
                    List.of(parts[0].trim()));
        }
        try {
            if (Files.size(path.path()) > MAX_TEXT_FILE_BYTES) {
                return failed("FILE_CONTAINS", rawSpec, "file is too large to validate inline: " + parts[0].trim(),
                        List.of(parts[0].trim()));
            }
            String content = Files.readString(path.path(), StandardCharsets.UTF_8);
            boolean passed = contains(content, parts[1].trim());
            return passed ? passed("FILE_CONTAINS", rawSpec, "file contains required text", List.of(parts[0].trim()))
                    : failed("FILE_CONTAINS", rawSpec,
                    "file does not contain required text: " + parts[1].trim(), List.of(parts[0].trim()));
        } catch (Exception e) {
            return failed("FILE_CONTAINS", rawSpec, "failed to read file: " + e.getMessage(),
                    List.of(parts[0].trim()));
        }
    }

    private RuleResult validateTestReport(String rawPath) {
        PathResolution path = resolveWorkspacePath(rawPath);
        if (!path.allowed()) return failed("TEST_REPORT", rawPath, path.message(), List.of());
        if (!Files.isRegularFile(path.path())) {
            return failed("TEST_REPORT", rawPath, "test report is missing: " + rawPath, List.of(rawPath));
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Element root = factory.newDocumentBuilder().parse(path.path().toFile()).getDocumentElement();
            Totals totals = "testsuite".equals(root.getTagName())
                    ? totals(root)
                    : totals(root.getElementsByTagName("testsuite"));
            boolean passed = totals.tests() > 0 && totals.failures() == 0 && totals.errors() == 0;
            String actual = "tests=" + totals.tests() + ", failures=" + totals.failures()
                    + ", errors=" + totals.errors() + ", skipped=" + totals.skipped();
            return passed ? passed("TEST_REPORT", rawPath, actual, List.of(rawPath))
                    : failed("TEST_REPORT", rawPath, actual, List.of(rawPath));
        } catch (Exception e) {
            return failed("TEST_REPORT", rawPath, "failed to parse test report: " + e.getMessage(),
                    List.of(rawPath));
        }
    }

    private Optional<MessageRecord> finalAssistantMessage(RunRecord run) {
        return runtime.messages(run.sessionId()).stream()
                .filter(message -> run.id().equals(message.runId()))
                .filter(message -> "assistant".equals(message.role()))
                .filter(message -> message.content() != null && !message.content().isBlank())
                .reduce((first, second) -> second);
    }

    private PathResolution resolveWorkspacePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return new PathResolution(null, false, "workspace path is blank");
        }
        Path relative = Path.of(rawPath.trim());
        if (relative.isAbsolute()) {
            return new PathResolution(null, false, "absolute paths are not allowed: " + rawPath);
        }
        Path resolved = workspaceRoot.resolve(relative).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            return new PathResolution(null, false, "path escapes workspace root: " + rawPath);
        }
        return new PathResolution(resolved, true, "");
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

    private static Totals totals(NodeList suites) {
        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        for (int i = 0; i < suites.getLength(); i++) {
            if (suites.item(i) instanceof Element suite) {
                Totals one = totals(suite);
                tests += one.tests();
                failures += one.failures();
                errors += one.errors();
                skipped += one.skipped();
            }
        }
        return new Totals(tests, failures, errors, skipped);
    }

    private static Totals totals(Element suite) {
        return new Totals(intAttr(suite, "tests"), intAttr(suite, "failures"),
                intAttr(suite, "errors"), intAttr(suite, "skipped"));
    }

    private static int intAttr(Element element, String name) {
        String value = element.getAttribute(name);
        if (value == null || value.isBlank()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static RuleResult passed(String type, String expected, String actual) {
        return passed(type, expected, actual, List.of());
    }

    private static RuleResult passed(String type, String expected, String actual, List<String> sourceRefs) {
        return new RuleResult(type, true, limit(expected), limit(actual), List.copyOf(sourceRefs));
    }

    private static RuleResult failed(String type, String expected, String actual) {
        return failed(type, expected, actual, List.of());
    }

    private static RuleResult failed(String type, String expected, String actual, List<String> sourceRefs) {
        return new RuleResult(type, false, limit(expected), limit(actual), List.copyOf(sourceRefs));
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
    private record RuleResult(String validatorType, boolean passed, String expected, String actual,
                              List<String> sourceRefs) { }
    private record PathResolution(Path path, boolean allowed, String message) { }
    private record Totals(int tests, int failures, int errors, int skipped) { }
}
