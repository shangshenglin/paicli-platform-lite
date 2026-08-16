package com.paicli.platform.server.knowledge;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Runs the same labelled retrieval cases through every supported ablation strategy. */
@Service
public class RetrievalEvaluationService {
    private static final int MAX_CASES = 200;
    private final KnowledgeService knowledge;

    public RetrievalEvaluationService(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    public EvaluationReport evaluate(EvaluationRequest request) {
        if (request == null || request.projectKey() == null || request.projectKey().isBlank()) {
            throw new IllegalArgumentException("projectKey must not be blank");
        }
        if (request.cases() != null && request.cases().size() > MAX_CASES) {
            throw new IllegalArgumentException("retrieval evaluation supports at most " + MAX_CASES + " cases");
        }
        List<EvaluationCase> cases = request.cases() == null ? List.of() : List.copyOf(request.cases());
        if (cases.isEmpty()) throw new IllegalArgumentException("at least one retrieval case is required");
        Map<String, RetrievalMetrics> ablations = new LinkedHashMap<>();
        for (KnowledgeService.RetrievalStrategy strategy : KnowledgeService.RetrievalStrategy.values()) {
            ablations.put(strategy.label(), evaluateStrategy(request.projectKey(), cases, strategy));
        }
        return new EvaluationReport(request.projectKey(), cases.size(), Map.copyOf(ablations));
    }

    private RetrievalMetrics evaluateStrategy(String projectKey, List<EvaluationCase> cases,
                                               KnowledgeService.RetrievalStrategy strategy) {
        double recall5 = 0, recall10 = 0, reciprocalRank = 0, ndcg = 0;
        long relevantRetrieved = 0, citationsReturned = 0, groundedCases = 0, answerCases = 0;
        List<CaseResult> results = new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            EvaluationCase testCase = cases.get(index);
            if (testCase.query() == null || testCase.query().isBlank()) {
                throw new IllegalArgumentException("case query must not be blank");
            }
            Set<String> relevant = normalized(testCase.relevantCitations());
            if (relevant.isEmpty()) throw new IllegalArgumentException("case relevantCitations must not be empty");
            List<KnowledgeService.SearchHit> hits = knowledge.searchForEvaluation(projectKey,
                    testCase.query(), 10, strategy);
            List<String> retrieved = hits.stream().map(hit -> citationKey(hit.citation())).toList();
            double caseRecall5 = recallAt(retrieved, relevant, 5);
            double caseRecall10 = recallAt(retrieved, relevant, 10);
            double caseMrr = reciprocalRank(retrieved, relevant);
            double caseNdcg = ndcgAt(retrieved, relevant, 10);
            List<String> topFive = retrieved.stream().limit(5).toList();
            long topFiveRelevant = topFive.stream().filter(relevant::contains).count();
            relevantRetrieved += topFiveRelevant;
            citationsReturned += topFive.size();
            Set<String> answers = normalized(testCase.answerCitations());
            boolean grounded = !answers.isEmpty() && relevant.containsAll(answers)
                    && new LinkedHashSet<>(topFive).containsAll(answers);
            if (!answers.isEmpty()) {
                answerCases++;
                if (grounded) groundedCases++;
            }
            recall5 += caseRecall5;
            recall10 += caseRecall10;
            reciprocalRank += caseMrr;
            ndcg += caseNdcg;
            results.add(new CaseResult(testCase.id() == null || testCase.id().isBlank()
                    ? "case-" + (index + 1) : testCase.id(), caseRecall5, caseRecall10, caseMrr,
                    caseNdcg, grounded, hits.stream().map(KnowledgeService.SearchHit::citation).toList()));
        }
        int count = cases.size();
        return new RetrievalMetrics(recall5 / count, recall10 / count, reciprocalRank / count, ndcg / count,
                citationsReturned == 0 ? 0 : (double) relevantRetrieved / citationsReturned,
                answerCases == 0 ? 0 : (double) groundedCases / answerCases, List.copyOf(results));
    }

    private static double recallAt(List<String> retrieved, Set<String> relevant, int k) {
        long hits = retrieved.stream().limit(k).distinct().filter(relevant::contains).count();
        return (double) hits / relevant.size();
    }

    private static double reciprocalRank(List<String> retrieved, Set<String> relevant) {
        for (int i = 0; i < retrieved.size(); i++) if (relevant.contains(retrieved.get(i))) return 1.0 / (i + 1);
        return 0;
    }

    private static double ndcgAt(List<String> retrieved, Set<String> relevant, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(k, retrieved.size()); i++) {
            if (relevant.contains(retrieved.get(i))) dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        double ideal = 0;
        for (int i = 0; i < Math.min(k, relevant.size()); i++) ideal += 1.0 / (Math.log(i + 2) / Math.log(2));
        return ideal == 0 ? 0 : dcg / ideal;
    }

    private static Set<String> normalized(List<String> citations) {
        Set<String> result = new LinkedHashSet<>();
        if (citations != null) citations.stream().filter(value -> value != null && !value.isBlank())
                .map(RetrievalEvaluationService::citationKey).forEach(result::add);
        return Set.copyOf(result);
    }

    static String citationKey(String citation) {
        String value = citation == null ? "" : citation.trim().toLowerCase(Locale.ROOT);
        int range = value.indexOf(':');
        if (range >= 0) value = value.substring(0, range);
        int version = value.indexOf('@');
        if (version >= 0) value = value.substring(0, version);
        return value;
    }

    public record EvaluationRequest(String projectKey, List<EvaluationCase> cases) { }
    public record EvaluationCase(String id, String query, List<String> relevantCitations,
                                 List<String> answerCitations) { }
    public record EvaluationReport(String projectKey, int caseCount,
                                   Map<String, RetrievalMetrics> ablations) { }
    public record RetrievalMetrics(double recallAt5, double recallAt10, double mrr, double ndcgAt10,
                                   double citationHitRate, double answerGroundedRate,
                                   List<CaseResult> cases) { }
    public record CaseResult(String id, double recallAt5, double recallAt10, double reciprocalRank,
                             double ndcgAt10, boolean answerGrounded, List<String> retrievedCitations) { }
}
