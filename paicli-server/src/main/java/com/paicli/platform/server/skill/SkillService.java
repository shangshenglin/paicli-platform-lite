package com.paicli.platform.server.skill;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.web.NetworkPolicy;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Comparator;

@Service
public class SkillService {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_.-]{1,80}");
    private static final Pattern FIELD = Pattern.compile("(?m)^([a-zA-Z][a-zA-Z0-9_-]*):\\s*(.+?)\\s*$");
    private static final int MAX_SKILL_CHARS = 32_000;
    private static final int MAX_RESOURCE_CHARS = 24_000;
    private static final int MAX_RESOURCE_LIST = 100;
    private static final int MAX_INDEX_CHARS = 8_000;
    private static final int MAX_IMPORT_FILES = 300;
    private static final long MAX_IMPORT_BYTES = 10L * 1024 * 1024;
    private final Path dataRoot;

    public SkillService(PlatformProperties properties) {
        dataRoot = properties.dataDir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(dataRoot.resolve("skills"));
            Files.createDirectories(dataRoot.resolve("projects"));
        } catch (IOException e) {
            throw new IllegalStateException("failed to initialize skill directories", e);
        }
    }

    public List<SkillDescriptor> list(String projectKey) {
        Map<String, SkillDescriptor> skills = new LinkedHashMap<>();
        loadDirectory(dataRoot.resolve("skills"), "global", skills);
        loadDirectory(projectDirectory(projectKey).resolve("skills"), "project", skills);
        return skills.values().stream().filter(SkillDescriptor::enabled)
                .sorted((a, b) -> a.name().compareTo(b.name())).toList();
    }

    public SkillContent load(String projectKey, String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("skill name must match " + NAME.pattern());
        }
        SkillDescriptor descriptor = list(projectKey).stream()
                .filter(skill -> skill.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("skill not found: " + name));
        try {
            String value = Files.readString(descriptor.path(), StandardCharsets.UTF_8);
            if (value.length() > MAX_SKILL_CHARS) {
                value = value.substring(0, MAX_SKILL_CHARS)
                        + "\n\n[Skill truncated by Platform Lite character budget]";
            }
            return new SkillContent(descriptor.name(), descriptor.description(), descriptor.source(), value,
                    resources(descriptor.path().getParent()));
        } catch (Exception e) {
            throw new IllegalStateException("failed to read skill: " + name, e);
        }
    }

    public SkillResource readResource(String projectKey, String name, String relativePath,
                                      int requestedOffset, int requestedLimit) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("resource path is required");
        }
        SkillDescriptor descriptor = list(projectKey).stream()
                .filter(skill -> skill.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("skill not found: " + name));
        Path root = descriptor.path().getParent().toAbsolutePath().normalize();
        Path resource = root.resolve(relativePath.replace('\\', '/')).normalize();
        if (!resource.startsWith(root) || resource.equals(descriptor.path())
                || !Files.isRegularFile(resource) || Files.isSymbolicLink(resource)) {
            throw new IllegalArgumentException("skill resource is unavailable: " + relativePath);
        }
        int offset = Math.max(0, requestedOffset);
        int limit = Math.max(1, Math.min(requestedLimit <= 0 ? 8_000 : requestedLimit, MAX_RESOURCE_CHARS));
        try {
            byte[] bytes = Files.readAllBytes(resource);
            if (looksBinary(bytes)) throw new IllegalArgumentException("binary skill resources cannot be loaded as text");
            String text = new String(bytes, StandardCharsets.UTF_8);
            int start = Math.min(offset, text.length());
            int end = Math.min(text.length(), start + limit);
            return new SkillResource(name, root.relativize(resource).toString().replace('\\', '/'),
                    text.substring(start, end), start, end, text.length(), end < text.length());
        } catch (IOException e) {
            throw new IllegalStateException("failed to read skill resource", e);
        }
    }

    private static List<String> resources(Path root) {
        try (var paths = Files.walk(root, 8)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(root::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(path -> !path.equals("SKILL.md") && !path.startsWith(".git/"))
                    .sorted().limit(MAX_RESOURCE_LIST).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean looksBinary(byte[] bytes) {
        int sample = Math.min(bytes.length, 4096);
        for (int index = 0; index < sample; index++) if (bytes[index] == 0) return true;
        return false;
    }

    public String indexPrompt(String projectKey) {
        return indexPrompt(projectKey, List.of());
    }

    public String indexPrompt(String projectKey, List<String> allowedNames) {
        List<String> allow = allowedNames == null ? List.of() : allowedNames.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        StringBuilder out = new StringBuilder("<available_skills>\n"
                + "Skills are listed in stable name order. Call load_skill with an exact name before following one.\n");
        for (SkillDescriptor skill : list(projectKey)) {
            if (!allow.isEmpty() && !allow.contains(skill.name())) continue;
            String line = "- " + skill.name() + ": " + skill.description() + "\n";
            if (out.length() + line.length() + 20 > MAX_INDEX_CHARS) break;
            out.append(line);
        }
        if (out.indexOf("- ") < 0) return "";
        return out.append("</available_skills>").toString();
    }

    public SkillDescriptor importFromGit(String projectKey, String gitUrl, String requestedName,
                                         String ref, boolean global) {
        GitSource gitSource = normalizeGitSource(gitUrl, ref);
        NetworkPolicy.requirePublicHttpUrl(gitSource.remoteUrl());
        String name = normalizeSkillName(requestedName, gitSource.remoteUrl());
        Path targetRoot = global ? dataRoot.resolve("skills") : projectDirectory(projectKey).resolve("skills");
        Path target = targetRoot.resolve(name).normalize();
        if (!target.startsWith(targetRoot.normalize())) throw new IllegalArgumentException("invalid skill name");
        if (Files.exists(target)) throw new IllegalStateException("skill already exists: " + name);
        Path stagingRoot = dataRoot.resolve(".skill-imports").normalize();
        Path checkout = stagingRoot.resolve(UUID.randomUUID().toString()).normalize();
        try {
            Files.createDirectories(stagingRoot);
            List<String> command = new ArrayList<>(List.of(
                    "git", "-c", "http.version=HTTP/1.1", "clone", "--depth", "1"));
            boolean sparse = requestedName != null && !requestedName.isBlank();
            if (sparse) command.addAll(List.of("--filter=blob:none", "--sparse"));
            if (gitSource.ref() != null) {
                if (!gitSource.ref().matches("[a-zA-Z0-9_./-]{1,120}")) {
                    throw new IllegalArgumentException("invalid git ref");
                }
                command.addAll(List.of("--branch", gitSource.ref()));
            }
            command.addAll(List.of("--", gitSource.remoteUrl(), checkout.toString()));
            runProcess(command, 180, "git clone");
            if (sparse) {
                List<String> sparseCommand = new ArrayList<>(List.of(
                        "git", "-C", checkout.toString(), "sparse-checkout", "set", "--no-cone", "SKILL.md"));
                for (String container : List.of("", "skills", ".github/skills", ".agents/skills", ".claude/skills",
                        "skills/.curated", "skills/.experimental", "skills/.system")) {
                    String prefix = container.isEmpty() ? "" : container + "/";
                    sparseCommand.add(prefix + name + "/**");
                }
                runProcess(sparseCommand, 120, "git sparse checkout");
            }
            Path source = locateSkillRoot(checkout, name);
            validateImport(source);
            String commit = runProcess(List.of("git", "-C", checkout.toString(), "rev-parse", "HEAD"),
                    20, "read skill commit").trim();
            Files.createDirectories(targetRoot);
            copyTree(source, target);
            writeMetadata(target, gitSource.remoteUrl(), gitSource.ref(), commit, global ? "global" : "project",
                    true, false, Instant.now().toString());
            return list(projectKey).stream().filter(skill -> skill.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("imported repository has no valid SKILL.md"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("skill import interrupted", e);
        } catch (Exception e) {
            throw e instanceof IllegalArgumentException argument ? argument
                    : e instanceof IllegalStateException state ? state
                    : new IllegalStateException("failed to import skill", e);
        } finally {
            deleteTree(checkout);
        }
    }

    private static String runProcess(List<String> command, long timeoutSeconds, String operation) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> drainOutput(process.getInputStream()));
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            String text = output.isDone() ? output.getNow("").trim() : "";
            throw new IllegalStateException(operation + " timed out after " + timeoutSeconds + " seconds: "
                    + text);
        }
        String text = output.get(5, TimeUnit.SECONDS).trim();
        if (process.exitValue() != 0) throw new IllegalStateException(operation + " failed: " + text);
        return text;
    }

    private static String drainOutput(InputStream input) {
        try (input; ByteArrayOutputStream kept = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int remaining = 16_000 - kept.size();
                if (remaining > 0) kept.write(buffer, 0, Math.min(read, remaining));
            }
            return kept.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "failed to read process output: " + e.getMessage();
        }
    }

    public boolean delete(String projectKey, String name, boolean global) {
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("invalid skill name");
        Path root = global ? dataRoot.resolve("skills").normalize()
                : projectDirectory(projectKey).resolve("skills").normalize();
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || !Files.exists(target)) return false;
        deleteTree(target);
        return !Files.exists(target);
    }

    public SkillInspection inspectFromGit(String gitUrl,String requestedName,String ref){
        GitSource source=normalizeGitSource(gitUrl,ref);NetworkPolicy.requirePublicHttpUrl(source.remoteUrl());
        String name=normalizeSkillName(requestedName,source.remoteUrl());Path checkout=dataRoot.resolve(".skill-imports").resolve(UUID.randomUUID().toString()).normalize();
        try{Files.createDirectories(checkout.getParent());List<String> command=new ArrayList<>(List.of("git","-c","http.version=HTTP/1.1","clone","--depth","1"));
            if(source.ref()!=null)command.addAll(List.of("--branch",source.ref()));command.addAll(List.of("--",source.remoteUrl(),checkout.toString()));runProcess(command,180,"inspect skill repository");
            Path root=locateSkillRoot(checkout,name);validateImport(root);String header=Files.readString(root.resolve("SKILL.md"),StandardCharsets.UTF_8);
            Map<String,String> fields=frontmatter(header.substring(0,Math.min(header.length(),8_000)));List<String> files;
            try(var paths=Files.walk(root)){files=paths.filter(Files::isRegularFile).map(root::relativize).map(value->value.toString().replace('\\','/')).sorted().limit(MAX_IMPORT_FILES).toList();}
            return new SkillInspection(name,source.remoteUrl(),source.ref()==null?"":source.ref(),fields.getOrDefault("permissions","未声明"),files);
        }catch(Exception e){throw e instanceof RuntimeException runtime?runtime:new IllegalStateException("skill inspection failed",e);}finally{deleteTree(checkout);}
    }

    public List<SkillDescriptor> lifecycle(String projectKey) {
        Map<String, SkillDescriptor> skills = new LinkedHashMap<>();
        loadDirectory(dataRoot.resolve("skills"), "global", skills);
        loadDirectory(projectDirectory(projectKey).resolve("skills"), "project", skills);
        return skills.values().stream().sorted(Comparator.comparing(SkillDescriptor::name)).toList();
    }

    public SkillDescriptor setState(String projectKey, String name, boolean global, boolean enabled, boolean pinned) {
        SkillDescriptor descriptor = lifecycle(projectKey).stream()
                .filter(value -> value.name().equals(name) && value.source().equals(global ? "global" : "project"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("skill not found: " + name));
        Properties metadata = metadata(descriptor.path().getParent());
        writeMetadata(descriptor.path().getParent(), metadata.getProperty("repository", ""),
                metadata.getProperty("ref", ""), metadata.getProperty("commit", ""), descriptor.source(),
                enabled, pinned, metadata.getProperty("installedAt", Instant.now().toString()));
        return lifecycle(projectKey).stream().filter(value -> value.name().equals(name)
                && value.source().equals(descriptor.source())).findFirst().orElseThrow();
    }

    public List<String> fileManifest(String projectKey, String name) {
        SkillDescriptor descriptor = lifecycle(projectKey).stream().filter(value -> value.name().equals(name))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("skill not found: " + name));
        Path root = descriptor.path().getParent();
        try (var paths = Files.walk(root, 8)) {
            return paths.filter(Files::isRegularFile).map(root::relativize)
                    .map(value -> value.toString().replace('\\', '/')).sorted().limit(MAX_IMPORT_FILES).toList();
        } catch (IOException e) { throw new IllegalStateException("failed to list skill files", e); }
    }

    public SkillUpdateStatus checkForUpdate(String projectKey,String name,boolean global){
        SkillDescriptor descriptor=descriptor(projectKey,name,global);if(descriptor.repository().isBlank())
            return new SkillUpdateStatus(name,descriptor.commit(),"",false,"本地 Skill 没有来源仓库");
        try{
            NetworkPolicy.requirePublicHttpUrl(descriptor.repository());
            String ref=descriptor.ref().isBlank()?"HEAD":descriptor.ref();
            String output=runProcess(List.of("git","-c","http.version=HTTP/1.1","ls-remote","--",descriptor.repository(),ref),60,"check skill update");
            String latest=output.isBlank()?"":output.split("\\s+")[0];
            return new SkillUpdateStatus(name,descriptor.commit(),latest,!latest.isBlank()&&!latest.equals(descriptor.commit()),"");
        }catch(Exception e){return new SkillUpdateStatus(name,descriptor.commit(),"",false,e.getMessage());}
    }

    public SkillDescriptor upgrade(String projectKey,String name,boolean global){
        SkillDescriptor descriptor=descriptor(projectKey,name,global);if(descriptor.pinned())throw new IllegalStateException("skill version is pinned");
        if(descriptor.repository().isBlank())throw new IllegalStateException("local skill cannot be upgraded from Git");
        Path target=descriptor.path().getParent();Path backup=rollbackPath(descriptor.source(),name);
        try{deleteTree(backup);Files.createDirectories(backup.getParent());copyTree(target,backup);deleteTree(target);
            return importFromGit(projectKey,descriptor.repository(),name,descriptor.ref(),global);
        }catch(Exception e){if(!Files.exists(target)&&Files.exists(backup)){try{copyTree(backup,target);}catch(Exception ignored){}}
            throw e instanceof RuntimeException runtime?runtime:new IllegalStateException("skill upgrade failed",e);}
    }

    public SkillDescriptor rollback(String projectKey,String name,boolean global){
        SkillDescriptor descriptor=descriptor(projectKey,name,global);Path backup=rollbackPath(descriptor.source(),name);
        if(!Files.isDirectory(backup))throw new IllegalStateException("no rollback version is available");
        Path target=descriptor.path().getParent();try{deleteTree(target);copyTree(backup,target);return descriptor(projectKey,name,global);}
        catch(Exception e){throw new IllegalStateException("skill rollback failed",e);}
    }

    private SkillDescriptor descriptor(String projectKey,String name,boolean global){return lifecycle(projectKey).stream()
            .filter(value->value.name().equals(name)&&value.source().equals(global?"global":"project"))
            .findFirst().orElseThrow(()->new IllegalArgumentException("skill not found: "+name));}
    private Path rollbackPath(String scope,String name){return dataRoot.resolve(".skill-rollbacks").resolve(scope).resolve(name).normalize();}

    private static void writeMetadata(Path root, String repository, String ref, String commit, String scope,
                                      boolean enabled, boolean pinned, String installedAt) {
        Properties values = new Properties(); values.setProperty("repository", repository == null ? "" : repository);
        values.setProperty("ref", ref == null ? "" : ref); values.setProperty("commit", commit == null ? "" : commit);
        values.setProperty("scope", scope); values.setProperty("enabled", Boolean.toString(enabled));
        values.setProperty("pinned", Boolean.toString(pinned)); values.setProperty("installedAt", installedAt);
        try { Files.createDirectories(root); try (var output = Files.newOutputStream(root.resolve(".paicli-skill.properties"))) {
            values.store(output, "PaiCLI Skill lifecycle metadata; contains no secrets");
        }} catch (IOException e) { throw new IllegalStateException("failed to write skill metadata", e); }
    }

    private static Properties metadata(Path root) {
        Properties values = new Properties(); Path file = root.resolve(".paicli-skill.properties");
        if (!Files.isRegularFile(file)) return values;
        try (var input = Files.newInputStream(file)) { values.load(input); } catch (IOException ignored) { }
        return values;
    }

    private static String normalizeSkillName(String requested, String gitUrl) {
        String value = requested == null ? "" : requested.trim();
        if (value.isBlank()) {
            String path = java.net.URI.create(gitUrl).getPath();
            value = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.git$", "");
        }
        if (!NAME.matcher(value).matches()) throw new IllegalArgumentException("invalid skill name");
        return value;
    }

    static GitSource normalizeGitSource(String value, String requestedRef) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("git URL is required");
        java.net.URI uri;
        try {
            uri = java.net.URI.create(value.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid git URL", e);
        }
        String ref = requestedRef == null || requestedRef.isBlank() ? null : requestedRef.trim();
        String host = uri.getHost();
        if (host != null && (host.equalsIgnoreCase("github.com") || host.equalsIgnoreCase("www.github.com"))) {
            List<String> parts = java.util.Arrays.stream(uri.getPath().split("/"))
                    .filter(part -> !part.isBlank()).toList();
            if (parts.size() < 2) throw new IllegalArgumentException("GitHub URL must include owner and repository");
            String owner = parts.get(0);
            String repository = parts.get(1).replaceFirst("(?i)\\.git$", "");
            if (!owner.matches("[a-zA-Z0-9_.-]+") || !repository.matches("[a-zA-Z0-9_.-]+")) {
                throw new IllegalArgumentException("invalid GitHub repository URL");
            }
            if (ref == null && parts.size() >= 4
                    && (parts.get(2).equals("tree") || parts.get(2).equals("blob"))) {
                ref = parts.get(3);
            }
            return new GitSource("https://github.com/" + owner + "/" + repository + ".git", ref);
        }
        return new GitSource(value.trim(), ref);
    }

    static Path locateSkillRoot(Path checkout, String name) throws Exception {
        if (Files.isRegularFile(checkout.resolve("SKILL.md"))) return checkout;
        Path named = checkout.resolve(name);
        if (Files.isRegularFile(named.resolve("SKILL.md"))) return named;

        for (String container : List.of("skills", ".github/skills", ".agents/skills", ".claude/skills",
                "skills/.curated", "skills/.experimental", "skills/.system")) {
            Path candidate = checkout.resolve(container).resolve(name).normalize();
            if (candidate.startsWith(checkout) && Files.isRegularFile(candidate.resolve("SKILL.md"))) {
                return candidate;
            }
        }

        try (var paths = Files.walk(checkout, 8)) {
            List<Path> namedMatches = paths
                    .filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .map(Path::getParent)
                    .filter(path -> path.getFileName().toString().equals(name))
                    .limit(2)
                    .toList();
            if (namedMatches.size() == 1) return namedMatches.get(0);
        }
        try (var paths = Files.walk(checkout, 8)) {
            List<Path> matches = paths.filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .map(Path::getParent).limit(2).toList();
            if (matches.size() == 1) return matches.get(0);
        }
        throw new IllegalArgumentException(
                "repository contains multiple skills; provide the exact skill directory name");
    }

    private static void validateImport(Path source) throws Exception {
        int files = 0;
        long bytes = 0;
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                if (!relative.toString().isEmpty() && relative.getName(0).toString().equals(".git")) continue;
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("skill repository contains symlinks");
                if (!Files.isRegularFile(path)) continue;
                files++;
                bytes += Files.size(path);
                if (files > MAX_IMPORT_FILES || bytes > MAX_IMPORT_BYTES) {
                    throw new IllegalArgumentException("skill exceeds import size budget");
                }
            }
        }
        if (!Files.isRegularFile(source.resolve("SKILL.md"))) throw new IllegalArgumentException("SKILL.md is required");
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path relative = source.relativize(path);
                if (relative.toString().equals(".git") || relative.startsWith(".git")) continue;
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target)) throw new IllegalArgumentException("invalid repository path");
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else if (Files.isRegularFile(path)) Files.copy(path, destination);
            }
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private void loadDirectory(Path root, String source, Map<String, SkillDescriptor> target) {
        Path normalized = root.toAbsolutePath().normalize();
        if (!normalized.startsWith(dataRoot) || !Files.isDirectory(normalized)) return;
        try (var entries = Files.list(normalized)) {
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                String fallbackName = directory.getFileName().toString();
                if (!NAME.matcher(fallbackName).matches()) continue;
                Path file = directory.resolve("SKILL.md").normalize();
                if (!file.startsWith(normalized) || !Files.isRegularFile(file)) continue;
                String header = Files.readString(file, StandardCharsets.UTF_8);
                if (header.length() > 8_000) header = header.substring(0, 8_000);
                Map<String, String> fields = frontmatter(header);
                String name = fields.getOrDefault("name", fallbackName).trim();
                if (!NAME.matcher(name).matches()) continue;
                String description = fields.getOrDefault("description", "").trim();
                if (description.length() > 500) description = description.substring(0, 500);
                Properties metadata = metadata(directory);
                boolean enabled = Boolean.parseBoolean(metadata.getProperty("enabled", "true"));
                target.put(name, new SkillDescriptor(name, description, source,
                        metadata.getProperty("repository", ""), metadata.getProperty("ref", ""),
                        metadata.getProperty("commit", ""), metadata.getProperty("installedAt", ""),
                        enabled, Boolean.parseBoolean(metadata.getProperty("pinned", "false")), file));
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to scan skills under " + normalized, e);
        }
    }

    private Path projectDirectory(String projectKey) {
        String key = projectKey == null ? "" : projectKey.trim();
        if (!NAME.matcher(key).matches()) throw new IllegalArgumentException("invalid project key");
        Path projects = dataRoot.resolve("projects").normalize();
        Path project = projects.resolve(key).normalize();
        if (!project.startsWith(projects)) throw new IllegalArgumentException("invalid project key");
        return project;
    }

    private static Map<String, String> frontmatter(String content) {
        if (!content.startsWith("---")) return Map.of();
        int end = content.indexOf("\n---", 3);
        if (end < 0) return Map.of();
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = FIELD.matcher(content.substring(3, end));
        while (matcher.find()) {
            String value = matcher.group(2).trim();
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            fields.put(matcher.group(1).toLowerCase(), value);
        }
        return fields;
    }

    public record SkillDescriptor(String name, String description, String source, String repository, String ref,
                                  String commit, String installedAt, boolean enabled, boolean pinned,
                                  @JsonIgnore Path path) { }
    public record SkillContent(String name, String description, String source, String content,
                               List<String> resources) { }
    public record SkillResource(String skill, String path, String content, int offset, int end,
                                int totalChars, boolean truncated) { }
    public record SkillUpdateStatus(String name,String currentCommit,String latestCommit,boolean updateAvailable,String error) { }
    public record SkillInspection(String name,String repository,String ref,String permissions,List<String> files) { }
    record GitSource(String remoteUrl, String ref) { }
}
