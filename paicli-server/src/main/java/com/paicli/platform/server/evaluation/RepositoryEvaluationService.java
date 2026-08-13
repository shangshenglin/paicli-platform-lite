package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.SandboxDriver;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.store.EvaluationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RepositoryEvaluationService {
    private static final String WORKSPACE_DIRECTORY = "workspace";
    private static final String HIDDEN_DIRECTORY = "hidden";
    private static final String GRADER_DIRECTORY = ".paicli-evaluation/grader";
    private static final int PATCH_PREVIEW_LIMIT = 32_000;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final SqliteRuntimeStore runtime;
    private final SandboxDriver sandbox;
    private final ObjectMapper mapper;
    private final Path fixtureRoot;
    private final Path workspaceRoot;

    public RepositoryEvaluationService(SqliteRuntimeStore runtime, SandboxDriver sandbox,
                                       ObjectMapper mapper, PlatformProperties properties) {
        this.runtime = runtime;
        this.sandbox = sandbox;
        this.mapper = mapper;
        this.fixtureRoot = properties.dataDir().resolve("evaluation-fixtures").toAbsolutePath().normalize();
        this.workspaceRoot = properties.workspaceRoot().toAbsolutePath().normalize();
    }

    public FixtureInspection inspectFixture(String fixtureRef) {
        String normalized = RepositoryEvaluationSpec.fixtureRef(fixtureRef);
        Map<String, FileState> files = files(fixture(normalized));
        long bytes = files.values().stream().mapToLong(FileState::size).sum();
        return new FixtureInspection(normalized, directoryDigest(files), files.size(), bytes);
    }

    public PreparedRepositoryCase prepare(EvaluationStore.EvaluationCase evaluationCase,
                                          String workspaceOwner) {
        if (!"REPOSITORY".equals(evaluationCase.caseType())) {
            throw new IllegalArgumentException("repository preparation requires a REPOSITORY case");
        }
        String fixtureRef = RepositoryEvaluationSpec.fixtureRef(evaluationCase.fixtureRef());
        String expectedDigest = RepositoryEvaluationSpec.fixtureSha256(evaluationCase.fixtureSha256());
        RepositoryEvaluationSpec.GraderSpec grader = RepositoryEvaluationSpec.grader(
                mapper, evaluationCase.graderSpecJson());
        RepositoryEvaluationSpec.PatchPolicy patchPolicy = RepositoryEvaluationSpec.patchPolicy(
                mapper, evaluationCase.patchPolicyJson());
        FixtureInspection inspection = inspectFixture(fixtureRef);
        if (!expectedDigest.equals(inspection.sha256())) {
            throw new IllegalStateException("fixture digest mismatch for " + fixtureRef
                    + ": expected " + expectedDigest + " but was " + inspection.sha256());
        }
        Path target = workspace(workspaceOwner);
        if (Files.exists(target)) {
            throw new IllegalStateException("evaluation workspace already exists: " + workspaceOwner);
        }
        copyTree(fixture(fixtureRef).resolve(WORKSPACE_DIRECTORY), target);
        Map<String, Object> snapshot = RepositoryEvaluationSpec.snapshot(
                fixtureRef, inspection.sha256(), grader, patchPolicy);
        try {
            return new PreparedRepositoryCase(workspaceOwner, mapper.writeValueAsString(snapshot), inspection);
        } catch (Exception e) {
            throw new IllegalStateException("failed to persist repository case snapshot", e);
        }
    }

    public RepositoryGrade grade(EvaluationStore.EvaluationTrial trial, RunRecord run) {
        Snapshot snapshot = snapshot(trial.caseSnapshotJson());
        Path agentWorkspace = workspace(runtime.workspaceOwnerRunId(run.id()));
        Path sourceWorkspace = fixture(snapshot.fixtureRef()).resolve(WORKSPACE_DIRECTORY);
        FixtureInspection inspection;
        try {
            inspection = inspectFixture(snapshot.fixtureRef());
        } catch (RuntimeException e) {
            return RepositoryGrade.integrityFailure(snapshot.fixtureSha256(), e.getMessage());
        }
        if (!snapshot.fixtureSha256().equals(inspection.sha256())) {
            return RepositoryGrade.integrityFailure(snapshot.fixtureSha256(),
                    "fixture digest drifted before grading: expected " + snapshot.fixtureSha256()
                            + " but was " + inspection.sha256());
        }
        Map<String, FileState> before = files(sourceWorkspace);
        Map<String, FileState> after;
        try {
            boolean graderWasPrepared = runtime.toolCallsForRun(run.id()).stream()
                    .anyMatch(tool -> "evaluation_grader_prepare".equals(tool.toolName()));
            after = graderWasPrepared
                    ? files(agentWorkspace, Set.of(GRADER_DIRECTORY))
                    : files(agentWorkspace);
        } catch (RuntimeException e) {
            return RepositoryGrade.integrityFailure(snapshot.fixtureSha256(), e.getMessage());
        }
        Patch patch = patch(before, after, snapshot.patchPolicy());
        if (!patch.integrityPassed()) {
            return RepositoryGrade.integrityFailure(snapshot.fixtureSha256(), patch.error())
                    .withPatch(patch);
        }

        Path graderWorkspace = agentWorkspace.resolve(GRADER_DIRECTORY).normalize();
        if (!graderWorkspace.startsWith(agentWorkspace)) {
            return RepositoryGrade.integrityFailure(snapshot.fixtureSha256(), "grader workspace escapes run root")
                    .withPatch(patch);
        }
        try {
            prepareGraderWorkspace(trial, run, snapshot, patch, agentWorkspace,
                    sourceWorkspace, graderWorkspace);
            CommandGrade failToPass = executeGrader(trial, run, "fail_to_pass",
                    snapshot.grader().failToPassCommand(), snapshot.grader());
            CommandGrade passToPass = failToPass.passed()
                    ? executeGrader(trial, run, "pass_to_pass",
                    snapshot.grader().passToPassCommand(), snapshot.grader())
                    : CommandGrade.notRun("FAIL_TO_PASS did not pass");
            boolean resolved = failToPass.passed() && passToPass.passed();
            return new RepositoryGrade(resolved, true, snapshot.fixtureSha256(), patch.patchSha256(),
                    patch.changedFiles(), patch.changedBytes(), patch.preview(), "",
                    failToPass, passToPass, Instant.now().toString());
        } catch (Exception e) {
            return new RepositoryGrade(false, true, snapshot.fixtureSha256(), patch.patchSha256(),
                    patch.changedFiles(), patch.changedBytes(), patch.preview(), "grader failed: " + e.getMessage(),
                    CommandGrade.notRun(e.getMessage()), CommandGrade.notRun(e.getMessage()),
                    Instant.now().toString());
        } finally {
            sandbox.release(run.id());
        }
    }

    private void prepareGraderWorkspace(EvaluationStore.EvaluationTrial trial, RunRecord run,
                                        Snapshot snapshot, Patch patch, Path agentWorkspace,
                                        Path sourceWorkspace, Path graderWorkspace) {
        String idempotencyKey = trial.id() + ":repository-grader:prepare";
        String argumentsJson = write(Map.of(
                "fixtureSha256", snapshot.fixtureSha256(),
                "patchSha256", patch.patchSha256(),
                "graderDirectory", GRADER_DIRECTORY));
        var persisted = runtime.createToolCall(run.id(), "evaluation-prepare",
                "evaluation_grader_prepare", argumentsJson, idempotencyKey);
        if (persisted.status() == ToolCallStatus.COMPLETED && Files.isDirectory(graderWorkspace)) return;
        runtime.markToolRunning(persisted.id());
        try {
            deleteTree(agentWorkspace, graderWorkspace);
            copyTree(sourceWorkspace, graderWorkspace);
            applyPatch(agentWorkspace, graderWorkspace, patch);
            injectHiddenFiles(snapshot.fixtureRef(), graderWorkspace, snapshot.grader().hiddenFiles());
            runtime.completeTool(persisted.id(), write(Map.of(
                    "fixtureSha256", snapshot.fixtureSha256(),
                    "patchSha256", patch.patchSha256(),
                    "prepared", true)));
        } catch (RuntimeException e) {
            runtime.failTool(persisted.id(), e.getMessage());
            throw e;
        }
    }

    private CommandGrade executeGrader(EvaluationStore.EvaluationTrial trial, RunRecord run, String phase,
                                       String command, RepositoryEvaluationSpec.GraderSpec spec) {
        String idempotencyKey = trial.id() + ":repository-grader:" + phase;
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("command", command);
        arguments.put("cwd", GRADER_DIRECTORY);
        arguments.put("shell", spec.shell());
        arguments.put("timeoutSeconds", spec.timeoutSeconds());
        arguments.put("maxOutputBytes", 1_048_576);
        String argumentsJson = write(arguments);
        var persisted = runtime.createToolCall(run.id(), "evaluation-" + phase,
                "evaluation_grader", argumentsJson, idempotencyKey);
        if (persisted.status() == ToolCallStatus.COMPLETED && persisted.result() != null) {
            return readCommandGrade(persisted.result());
        }
        if (persisted.status() == ToolCallStatus.FAILED) {
            return new CommandGrade(false, null, false, 0, "", persisted.error(), true);
        }
        runtime.markToolRunning(persisted.id());
        ToolResult result = sandbox.execute(new ToolRequest(persisted.id(), run.id(),
                "execute_command", arguments, idempotencyKey));
        Integer exitCode = number(result.metadata().get("exitCode"));
        boolean timedOut = Boolean.TRUE.equals(result.metadata().get("timedOut"));
        boolean passed = result.success() && !timedOut && Integer.valueOf(0).equals(exitCode);
        CommandGrade grade = new CommandGrade(passed, exitCode, timedOut, result.durationMs(),
                bounded(result.content(), 50_000), result.error() == null ? "" : result.error(), true);
        String serialized = write(grade);
        if (result.success()) runtime.completeTool(persisted.id(), serialized);
        else runtime.failTool(persisted.id(), grade.error());
        return grade;
    }

    private CommandGrade readCommandGrade(String json) {
        try {
            return mapper.readValue(json, CommandGrade.class);
        } catch (Exception e) {
            return new CommandGrade(false, null, false, 0, "", "invalid persisted grader result", true);
        }
    }

    private Snapshot snapshot(String json) {
        try {
            Map<String, Object> value = mapper.readValue(json, MAP);
            if (!"REPOSITORY".equals(value.get("caseType"))) {
                throw new IllegalArgumentException("trial has no repository case snapshot");
            }
            String fixtureRef = RepositoryEvaluationSpec.fixtureRef(String.valueOf(value.get("fixtureRef")));
            String fixtureSha = RepositoryEvaluationSpec.fixtureSha256(String.valueOf(value.get("fixtureSha256")));
            String graderJson = mapper.writeValueAsString(value.get("grader"));
            String policyJson = mapper.writeValueAsString(value.get("patchPolicy"));
            return new Snapshot(fixtureRef, fixtureSha,
                    RepositoryEvaluationSpec.grader(mapper, graderJson),
                    RepositoryEvaluationSpec.patchPolicy(mapper, policyJson));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid repository case snapshot", e);
        }
    }

    private Patch patch(Map<String, FileState> before, Map<String, FileState> after,
                        RepositoryEvaluationSpec.PatchPolicy policy) {
        Set<String> paths = new LinkedHashSet<>();
        paths.addAll(before.keySet());
        paths.addAll(after.keySet());
        List<FileChange> changes = new ArrayList<>();
        long changedBytes = 0;
        for (String path : paths.stream().sorted().toList()) {
            FileState previous = before.get(path);
            FileState current = after.get(path);
            if (previous != null && current != null && previous.sha256().equals(current.sha256())) continue;
            String operation = previous == null ? "ADD" : current == null ? "DELETE" : "MODIFY";
            long bytes = Math.max(previous == null ? 0 : previous.size(), current == null ? 0 : current.size());
            changedBytes += bytes;
            changes.add(new FileChange(path, operation,
                    previous == null ? "" : previous.sha256(), current == null ? "" : current.sha256(), bytes));
        }
        if (changes.size() > policy.maxChangedFiles()) {
            return Patch.failed(changes, changedBytes, "changed file limit exceeded: " + changes.size()
                    + " / " + policy.maxChangedFiles());
        }
        if (changedBytes > policy.maxPatchBytes()) {
            return Patch.failed(changes, changedBytes, "patch byte limit exceeded: " + changedBytes
                    + " / " + policy.maxPatchBytes());
        }
        List<PathMatcher> forbidden = policy.forbiddenPaths().stream()
                .map(pattern -> workspaceRoot.getFileSystem().getPathMatcher("glob:" + pattern.replace('\\', '/')))
                .toList();
        for (FileChange change : changes) {
            Path relative = Path.of(change.path().replace('/', java.io.File.separatorChar));
            if (change.path().equals(".paicli-evaluation")
                    || change.path().startsWith(".paicli-evaluation/")
                    || change.path().equals(".git") || change.path().startsWith(".git/")
                    || forbidden.stream().anyMatch(matcher -> matcher.matches(relative))) {
                return Patch.failed(changes, changedBytes, "forbidden path changed: " + change.path());
            }
        }
        String digest = digestChanges(changes);
        return new Patch(true, "", digest, changes, changedBytes, preview(changes));
    }

    private void applyPatch(Path agentWorkspace, Path graderWorkspace, Patch patch) {
        for (FileChange change : patch.changes()) {
            Path target = safeRelative(graderWorkspace, change.path());
            if (change.operation().equals("DELETE")) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException e) {
                    throw new IllegalStateException("failed to delete patch path: " + change.path(), e);
                }
                continue;
            }
            Path source = safeRelative(agentWorkspace, change.path());
            try {
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException e) {
                throw new IllegalStateException("failed to apply patch path: " + change.path(), e);
            }
        }
    }

    private void injectHiddenFiles(String fixtureRef, Path graderWorkspace,
                                   List<RepositoryEvaluationSpec.HiddenFile> hiddenFiles) {
        Path hiddenRoot = fixture(fixtureRef).resolve(HIDDEN_DIRECTORY).normalize();
        for (RepositoryEvaluationSpec.HiddenFile hidden : hiddenFiles) {
            Path source = safeRelative(hiddenRoot, hidden.source());
            Path target = safeRelative(graderWorkspace, hidden.target());
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("hidden test file is missing: " + hidden.source());
            }
            try {
                Path hiddenReal = hiddenRoot.toRealPath();
                Path sourceReal = source.toRealPath();
                if (!sourceReal.startsWith(hiddenReal)) {
                    throw new IllegalStateException("hidden test escapes fixture: " + hidden.source());
                }
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.copy(sourceReal, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("failed to inject hidden test: " + hidden.target(), e);
            }
        }
    }

    private Path fixture(String fixtureRef) {
        Path value = fixtureRoot.resolve(fixtureRef).normalize();
        if (!value.startsWith(fixtureRoot)) throw new IllegalArgumentException("fixture escapes evaluation-fixtures");
        return value;
    }

    private Path workspace(String owner) {
        Path value = workspaceRoot.resolve(owner).normalize();
        if (!value.startsWith(workspaceRoot)) throw new IllegalArgumentException("workspace escapes workspace root");
        return value;
    }

    private static Path safeRelative(Path root, String relative) {
        Path value = root.resolve(relative).normalize();
        if (!value.startsWith(root)) throw new IllegalArgumentException("path escapes root: " + relative);
        return value;
    }

    private static Map<String, FileState> files(Path root) {
        return files(root, Set.of());
    }

    private static Map<String, FileState> files(Path root, Set<String> excludedPrefixes) {
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("fixture workspace is missing: " + root);
        Map<String, FileState> result = new LinkedHashMap<>();
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted().toList()) {
                if (path.equals(root)) continue;
                String relative = normalized(root.relativize(path));
                if (excludedPrefixes.stream().anyMatch(prefix -> relative.equals(prefix)
                        || relative.startsWith(prefix + "/"))) continue;
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException("symbolic links are not allowed in evaluation workspaces: " + relative);
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] bytes = Files.readAllBytes(path);
                    result.put(relative, new FileState(sha256(bytes), bytes.length));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to inspect evaluation workspace", e);
        }
        return Map.copyOf(result);
    }

    private static void copyTree(Path source, Path target) {
        if (!Files.isDirectory(source)) throw new IllegalArgumentException("source directory is missing: " + source);
        try {
            Files.walkFileTree(source, new FileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(dir)) throw new IOException("symbolic links are not allowed: " + dir);
                    Files.createDirectories(target.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) throw new IOException("symbolic links are not allowed: " + file);
                    Files.copy(file, target.resolve(source.relativize(file)),
                            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException { throw exc; }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("failed to copy evaluation fixture", e);
        }
    }

    private static void deleteTree(Path root, Path target) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)
                || normalizedTarget.equals(normalizedRoot)
                || !normalizedTarget.endsWith(Path.of(".paicli-evaluation", "grader"))) {
            throw new IllegalArgumentException("refusing to remove unsafe grader directory");
        }
        if (!Files.exists(normalizedTarget)) return;
        try (var stream = Files.walk(normalizedTarget)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to reset grader workspace", e);
        }
    }

    private static String directoryDigest(Map<String, FileState> files) {
        MessageDigest digest = digest();
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.getValue().sha256().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String digestChanges(List<FileChange> changes) {
        MessageDigest digest = digest();
        for (FileChange change : changes) {
            digest.update((change.operation() + "\0" + change.path() + "\0" + change.beforeSha256()
                    + "\0" + change.afterSha256() + "\0").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String preview(List<FileChange> changes) {
        String value = changes.stream().map(change -> change.operation() + " " + change.path()
                + " " + change.beforeSha256() + " -> " + change.afterSha256())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return bounded(value, PATCH_PREVIEW_LIMIT);
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize repository evaluation data", e);
        }
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "\n[truncated]";
    }

    private static Integer number(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private record Snapshot(String fixtureRef, String fixtureSha256,
                            RepositoryEvaluationSpec.GraderSpec grader,
                            RepositoryEvaluationSpec.PatchPolicy patchPolicy) { }

    private record FileState(String sha256, long size) { }

    private record FileChange(String path, String operation, String beforeSha256,
                              String afterSha256, long bytes) { }

    private record Patch(boolean integrityPassed, String error, String patchSha256,
                         List<FileChange> changes, long changedBytes, String preview) {
        static Patch failed(List<FileChange> changes, long bytes, String error) {
            return new Patch(false, error, digestChanges(changes), List.copyOf(changes), bytes,
                    RepositoryEvaluationService.preview(changes));
        }
        List<String> changedFiles() { return changes.stream().map(FileChange::path).toList(); }
    }

    public record FixtureInspection(String fixtureRef, String sha256, int fileCount, long bytes) { }

    public record PreparedRepositoryCase(String workspaceOwner, String caseSnapshotJson,
                                         FixtureInspection fixture) { }

    public record CommandGrade(boolean passed, Integer exitCode, boolean timedOut, long durationMs,
                               String output, String error, boolean executed) {
        static CommandGrade notRun(String reason) {
            return new CommandGrade(false, null, false, 0, "", reason == null ? "" : reason, false);
        }
    }

    public record RepositoryGrade(boolean resolved, boolean integrityPassed, String fixtureSha256,
                                  String patchSha256, List<String> changedFiles, long changedBytes,
                                  String patchPreview, String error, CommandGrade failToPass,
                                  CommandGrade passToPass, String gradedAt) {
        static RepositoryGrade integrityFailure(String fixtureSha256, String error) {
            return new RepositoryGrade(false, false, fixtureSha256, "", List.of(), 0, "", error,
                    CommandGrade.notRun(error), CommandGrade.notRun(error), Instant.now().toString());
        }
        RepositoryGrade withPatch(Patch patch) {
            return new RepositoryGrade(resolved, integrityPassed, fixtureSha256, patch.patchSha256(),
                    patch.changedFiles(), patch.changedBytes(), patch.preview(), error,
                    failToPass, passToPass, gradedAt);
        }
    }
}
