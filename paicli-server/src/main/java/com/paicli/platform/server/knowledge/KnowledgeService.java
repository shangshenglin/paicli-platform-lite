package com.paicli.platform.server.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.io.AtomicFileWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.Base64;

@Service
public class KnowledgeService {
    private static final Pattern SAFE = Pattern.compile("[a-zA-Z0-9_.-]{1,120}");
    private static final int MAX_DOCUMENT_CHARS = 2_000_000;
    private static final int MAX_TOTAL_CHARS = 16_000_000;
    private static final int MAX_FILES = 500;
    private static final int INDEX_VERSION = 2;
    private static final int MAX_INDEX_CHUNKS = 600;
    private final Path dataRoot;
    private final ObjectMapper mapper;
    private final KnowledgeEmbeddingService embeddings;
    private final StructuredDocumentChunker chunker;

    @Autowired
    public KnowledgeService(PlatformProperties properties, ObjectMapper mapper,
                            KnowledgeEmbeddingService embeddings, StructuredDocumentChunker chunker) {
        dataRoot = properties.dataDir().toAbsolutePath().normalize();
        this.mapper = mapper;
        this.embeddings = embeddings;
        this.chunker = chunker;
    }

    public KnowledgeService(PlatformProperties properties) {
        this.dataRoot = properties.dataDir().toAbsolutePath().normalize();
        this.mapper = new ObjectMapper();
        this.embeddings = new KnowledgeEmbeddingService(
                new com.paicli.platform.server.config.RagProperties("local", "", "", "", 25 * 1024 * 1024),
                this.mapper);
        this.chunker = new StructuredDocumentChunker();
    }

    public KnowledgeDocument upsert(String projectKey, String name, String content) {
        String value = content == null ? "" : content.trim();
        if (value.isBlank()) throw new IllegalArgumentException("content must not be blank");
        if (value.length() > MAX_DOCUMENT_CHARS) throw new IllegalArgumentException("document is too large");
        Path file = documentPath(projectKey, name);
        try {
            Files.createDirectories(file.getParent());
            AtomicFileWriter.write(file, value.getBytes(StandardCharsets.UTF_8));
            writeIndex(file, value);
            return describe(file);
        } catch (Exception e) {
            throw new IllegalStateException("failed to store knowledge document", e);
        }
    }

    public KnowledgeDocument upload(String projectKey, MultipartFile file, DocumentTextExtractor extractor) {
        DocumentTextExtractor.ExtractedDocument extracted = extractor.extract(file);
        return upsertExtracted(projectKey, extracted);
    }

    public KnowledgeDocument upsertExtracted(String projectKey, DocumentTextExtractor.ExtractedDocument extracted) {
        String storedName = storedName(extracted.name());
        return upsert(projectKey, storedName, "Source: " + extracted.name() + "\nContent-Type: "
                + (extracted.contentType() == null ? "application/octet-stream" : extracted.contentType())
                + "\n\n" + extracted.text());
    }

    public static String storedName(String originalName) {
        return DocumentTextExtractor.safeName(originalName) + ".extracted.txt";
    }

    public List<KnowledgeDocument> list(String projectKey) {
        Path root = knowledgeRoot(projectKey);
        if (!Files.isDirectory(root)) return List.of();
        try (var files = Files.list(root)) {
            return files.filter(Files::isRegularFile).sorted()
                    .limit(MAX_FILES).map(this::describe).toList();
        } catch (Exception e) {
            throw new IllegalStateException("failed to list knowledge documents", e);
        }
    }

    public boolean delete(String projectKey, String name) {
        try {
            Path document = documentPath(projectKey, name);
            Files.deleteIfExists(indexPath(document));
            return Files.deleteIfExists(document);
        } catch (Exception e) {
            throw new IllegalStateException("failed to delete knowledge document", e);
        }
    }

    public List<SearchHit> search(String projectKey, String query, int requestedLimit) {
        return searchInternal(projectKey, query, requestedLimit, Set.of(), false);
    }

    public List<SearchHit> searchAttached(String projectKey, List<String> documentNames,
                                          String query, int requestedLimit) {
        Set<String> allowed = documentNames == null ? Set.of() : documentNames.stream()
                .filter(name -> name != null && !name.isBlank()).collect(java.util.stream.Collectors.toSet());
        if (allowed.isEmpty()) return List.of();
        return searchInternal(projectKey, query, requestedLimit, allowed, true);
    }

    private List<SearchHit> searchInternal(String projectKey, String query, int requestedLimit,
                                           Set<String> allowedDocuments, boolean fallbackSampling) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) throw new IllegalArgumentException("query must not be blank");
        int limit = Math.max(1, Math.min(requestedLimit, 10));
        Set<String> terms = terms(normalized);
        float[] queryVector = embeddings.semanticEnabled() ? embeddings.embed(normalized) : new float[0];
        List<Candidate> candidates = new ArrayList<>();
        int consumed = 0;
        for (KnowledgeDocument document : list(projectKey).stream()
                .filter(document -> allowedDocuments.isEmpty() || allowedDocuments.contains(document.name())).toList()) {
            if (consumed >= MAX_TOTAL_CHARS) break;
            try {
                IndexedDocument indexed = readOrCreateIndex(document.path());
                for (int index = 0; index < indexed.chunks().size(); index++) {
                    IndexedChunk chunk = indexed.chunks().get(index);
                    consumed += chunk.text().length();
                    double similarity = embeddings.semanticEnabled() ? cosine(queryVector, chunk.embedding()) : 0;
                    List<String> tokens = tokens((chunk.heading() == null ? "" : chunk.heading() + " ") + chunk.text());
                    Map<String, Integer> frequencies = new HashMap<>();
                    for (String token : tokens) frequencies.merge(token, 1, Integer::sum);
                    candidates.add(new Candidate(candidates.size(), document.name(), index + 1,
                            chunk.start(), chunk.end(), chunk.heading(), chunk.kind(), chunk.text(),
                            frequencies, Math.max(1, tokens.size()), similarity, 0));
                }
            } catch (Exception e) {
                throw new IllegalStateException("failed to search " + document.name(), e);
            }
        }
        if (candidates.isEmpty()) return List.of();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (String term : terms) {
            int matches = 0;
            for (Candidate candidate : candidates) if (candidate.frequencies().containsKey(term)) matches++;
            documentFrequency.put(term, matches);
        }
        double averageLength = candidates.stream().mapToInt(Candidate::length).average().orElse(1);
        List<Candidate> scored = candidates.stream().map(candidate -> candidate.withLexical(
                bm25(candidate, terms, documentFrequency, candidates.size(), averageLength)
                        + phraseBoost(candidate, normalized))).toList();

        Map<Integer, Double> fused = new HashMap<>();
        rank(scored.stream().filter(candidate -> candidate.lexical() > 0)
                .sorted(Comparator.comparingDouble(Candidate::lexical).reversed()).toList(), fused, 1.0);
        if (embeddings.semanticEnabled()) {
            rank(scored.stream().filter(candidate -> candidate.similarity() >= 0.05)
                    .sorted(Comparator.comparingDouble(Candidate::similarity).reversed()).toList(), fused, 1.15);
        }

        List<Candidate> ranked = scored.stream().filter(candidate -> fused.containsKey(candidate.id()))
                .sorted(Comparator.<Candidate>comparingDouble(candidate ->
                                fused.get(candidate.id()) + candidate.lexical() * 0.01
                                        + Math.max(0, candidate.similarity()) * 0.02)
                .reversed().thenComparing(Candidate::document).thenComparingInt(Candidate::chunk))
                .toList();
        if (ranked.isEmpty() && fallbackSampling) ranked = sampleAcrossDocuments(scored, limit);
        List<SearchHit> hits = new ArrayList<>();
        Map<String, Integer> perDocument = new HashMap<>();
        for (Candidate candidate : ranked) {
            if (perDocument.getOrDefault(candidate.document(), 0) >= 3 || overlapsExisting(hits, candidate)) continue;
            double finalScore = fused.getOrDefault(candidate.id(), 0.0) + candidate.lexical() * 0.01
                    + Math.max(0, candidate.similarity()) * 0.02;
            hits.add(new SearchHit(candidate.document(), candidate.chunk(), candidate.start(), candidate.end(),
                    finalScore, candidate.similarity(), candidate.heading(), candidate.kind(), candidate.text()));
            perDocument.merge(candidate.document(), 1, Integer::sum);
            if (hits.size() >= limit) break;
        }
        return List.copyOf(hits);
    }

    private static List<Candidate> sampleAcrossDocuments(List<Candidate> candidates, int limit) {
        List<String> documents = candidates.stream().map(Candidate::document).distinct().toList();
        if (documents.isEmpty()) return List.of();
        int quota = Math.max(1, (int) Math.ceil((double) limit / documents.size()));
        List<Candidate> sampled = new ArrayList<>();
        for (String document : documents) {
            List<Candidate> values = candidates.stream().filter(value -> value.document().equals(document)).toList();
            int count = Math.min(quota, values.size());
            for (int index = 0; index < count; index++) {
                int at = count == 1 ? 0 : (int) Math.round((double) index * (values.size() - 1) / (count - 1));
                sampled.add(values.get(at));
            }
        }
        return sampled.stream().limit(limit).toList();
    }

    private static double bm25(Candidate candidate, Set<String> queryTerms, Map<String, Integer> df,
                               int corpusSize, double averageLength) {
        double score = 0;
        double k1 = 1.2;
        double b = 0.75;
        for (String term : queryTerms) {
            int frequency = candidate.frequencies().getOrDefault(term, 0);
            if (frequency == 0) continue;
            int documents = df.getOrDefault(term, 0);
            double idf = Math.log(1 + (corpusSize - documents + 0.5) / (documents + 0.5));
            double denominator = frequency + k1 * (1 - b + b * candidate.length() / averageLength);
            score += idf * frequency * (k1 + 1) / denominator;
        }
        return score;
    }

    private static double phraseBoost(Candidate candidate, String query) {
        String text = (candidate.heading() + " " + candidate.text()).toLowerCase(Locale.ROOT);
        if (text.contains(query)) return 3.0;
        return candidate.heading() != null && candidate.heading().toLowerCase(Locale.ROOT).contains(query) ? 2.0 : 0;
    }

    private static void rank(List<Candidate> values, Map<Integer, Double> fused, double weight) {
        int max = Math.min(values.size(), 60);
        for (int i = 0; i < max; i++) fused.merge(values.get(i).id(), weight / (60.0 + i + 1), Double::sum);
    }

    private static boolean overlapsExisting(List<SearchHit> hits, Candidate candidate) {
        for (SearchHit hit : hits) {
            if (!hit.document().equals(candidate.document())) continue;
            int overlap = Math.max(0, Math.min(hit.endChar(), candidate.end())
                    - Math.max(hit.startChar(), candidate.start()));
            int shorter = Math.max(1, Math.min(hit.endChar() - hit.startChar(), candidate.end() - candidate.start()));
            if ((double) overlap / shorter >= 0.75) return true;
        }
        return false;
    }

    private void writeIndex(Path document, String content) throws Exception {
        List<StructuredDocumentChunker.Chunk> chunks = chunker.chunk(content).stream()
                .limit(MAX_INDEX_CHUNKS).toList();
        List<String> inputs = chunks.stream().map(chunk ->
                (chunk.heading().isBlank() ? "" : chunk.heading() + "\n") + chunk.text()).toList();
        List<float[]> vectors = embeddings.embedAll(inputs);
        List<IndexedChunk> indexed = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            StructuredDocumentChunker.Chunk chunk = chunks.get(index);
            indexed.add(new IndexedChunk(chunk.start(), chunk.end(), chunk.heading(), chunk.kind(), chunk.text(),
                    vectors.get(index)));
        }
        Path index = indexPath(document);
        AtomicFileWriter.write(index, mapper.writeValueAsBytes(new IndexedDocument(INDEX_VERSION, embeddings.provider(),
                Files.getLastModifiedTime(document).toMillis(), indexed)));
    }

    private IndexedDocument readOrCreateIndex(Path document) throws Exception {
        Path index = indexPath(document);
        if (Files.isRegularFile(index)) {
            IndexedDocument value = mapper.readValue(index.toFile(), IndexedDocument.class);
            if (value.version() == INDEX_VERSION && value.provider().equals(embeddings.provider())
                    && value.sourceModified() == Files.getLastModifiedTime(document).toMillis()) return value;
        }
        String content = Files.readString(document, StandardCharsets.UTF_8);
        if (content.length() > MAX_DOCUMENT_CHARS) content = content.substring(0, MAX_DOCUMENT_CHARS);
        writeIndex(document, content);
        return mapper.readValue(index.toFile(), IndexedDocument.class);
    }

    private Path indexPath(Path document) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(document.getFileName().toString().getBytes(StandardCharsets.UTF_8));
        return document.getParent().resolve(".index").resolve(encoded + ".json").normalize();
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0;
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    private static double score(String text, String query, Set<String> terms) {
        String lower = text.toLowerCase(Locale.ROOT);
        double score = lower.contains(query) ? 8.0 : 0.0;
        for (String term : terms) {
            int from = 0;
            int occurrences = 0;
            while ((from = lower.indexOf(term, from)) >= 0 && occurrences < 8) {
                occurrences++;
                from += Math.max(1, term.length());
            }
            score += occurrences * (term.length() > 2 ? 2.0 : 1.0);
        }
        return score;
    }

    private static Set<String> terms(String query) {
        return new HashSet<>(tokens(query));
    }

    private static List<String> tokens(String value) {
        List<String> result = new ArrayList<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (token.isBlank()) continue;
            result.add(token);
            if (containsHan(token) && token.length() > 2) {
                for (int i = 0; i < token.length() - 1; i++) result.add(token.substring(i, i + 2));
            }
        }
        return result;
    }

    private static boolean containsHan(String value) {
        return value.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private Path knowledgeRoot(String projectKey) {
        String key = projectKey == null ? "" : projectKey.trim();
        if (!SAFE.matcher(key).matches()) throw new IllegalArgumentException("invalid project key");
        Path projects = dataRoot.resolve("projects").normalize();
        Path root = projects.resolve(key).resolve("knowledge").normalize();
        if (!root.startsWith(projects)) throw new IllegalArgumentException("invalid project key");
        return root;
    }

    private Path documentPath(String projectKey, String name) {
        String requested = name == null ? "" : name.trim();
        String normalized = DocumentTextExtractor.safeName(requested);
        if (!normalized.equals(requested)) throw new IllegalArgumentException("invalid document name");
        Path root = knowledgeRoot(projectKey);
        Path file = root.resolve(normalized).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("invalid document name");
        return file;
    }

    private KnowledgeDocument describe(Path file) {
        try {
            return new KnowledgeDocument(file.getFileName().toString(), Files.size(file),
                    Files.getLastModifiedTime(file).toInstant(), file);
        } catch (Exception e) {
            throw new IllegalStateException("failed to inspect knowledge document", e);
        }
    }

    private record IndexedDocument(int version, String provider, long sourceModified, List<IndexedChunk> chunks) { }
    private record IndexedChunk(int start, int end, String heading, String kind,
                                String text, float[] embedding) { }
    private record Candidate(int id, String document, int chunk, int start, int end,
                             String heading, String kind, String text, Map<String, Integer> frequencies,
                             int length, double similarity, double lexical) {
        Candidate withLexical(double value) {
            return new Candidate(id, document, chunk, start, end, heading == null ? "" : heading,
                    kind == null ? "paragraph" : kind, text, frequencies, length, similarity, value);
        }
    }
    public record KnowledgeDocument(String name, long size, Instant updatedAt, @JsonIgnore Path path) { }
    public record SearchHit(String document, int chunk, int startChar, int endChar,
                            double score, double vectorSimilarity, String heading, String kind, String content) { }
}
