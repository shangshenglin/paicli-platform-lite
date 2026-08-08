package com.paicli.platform.server.agent;

import java.util.List;
import java.util.Locale;

/**
 * Conservative classifier for Root Run natural-language task requirements. Only
 * high-confidence imperative mutation/test intents are detected; questions,
 * explanations and ambiguous phrasing default to TEXT_ONLY. The classifier is a
 * fallback only — structured sources (DelegationEnvelope / PlanStep / WorkingPlan
 * completion) outrank it and cannot be silently downgraded by the model.
 */
public final class CompletionRequirementClassifier {
    private static final List<String> MUTATION_POSITIVE = List.of(
            "修改", "实现", "修复", "新增", "添加", "删除", "更新", "重构", "替换", "调整",
            "改成", "改为", "编写", "创建", "补丁", "实现功能", "加个字段", "加字段",
            "modify", "implement", "fix", "add", "create", "update", "delete",
            "refactor", "replace", "change", "write", "patch",
            "implement the", "fix the", "add a", "add an", "create a", "update the",
            "change the", "write a", "write the", "make the", "build the");
    private static final List<String> MUTATION_NEGATIVE = List.of(
            "如何修改", "为什么要修改", "解释一下", "解释", "分析", "说明", "告诉我", "介绍",
            "应该怎么", "怎么实现", "为什么", "是什么", "区别", "比较", "总结", "阅读",
            "how to", "why", "explain", "analyze", "analyse", "describe", "summarize",
            "summarise", "compare", "what is", "review", "comment on");
    private static final List<String> TEST_POSITIVE = List.of(
            "运行测试", "跑一下测试", "跑测试", "补单元测试", "确保测试通过", "执行 mvn test",
            "执行 pytest", "让测试通过", "写测试", "添加测试", "测试通过", "run tests",
            "run the tests", "run test", "make tests pass", "ensure tests pass",
            "add unit tests", "add tests", "write tests", "execute tests",
            "run mvn test", "run pytest", "unit tests", "test suite",
            "tests pass", "tests passed", "all tests", "tests green");
    private static final List<String> TEST_NEGATIVE = List.of(
            "测试是什么", "为什么测试失败", "解释 pytest", "解释测试", "what is a test",
            "why did the test fail", "explain the test", "what tests");

    private CompletionRequirementClassifier() { }

    public static Requirements classify(String task) {
        if (task == null || task.isBlank()) return new Requirements(false, false);
        String value = " " + task.trim().toLowerCase(Locale.ROOT) + " ";
        boolean mutation = !containsAny(value, MUTATION_NEGATIVE)
                && containsAny(value, MUTATION_POSITIVE);
        boolean test = !containsAny(value, TEST_NEGATIVE)
                && containsAny(value, TEST_POSITIVE);
        return new Requirements(mutation, test);
    }

    private static boolean containsAny(String value, List<String> tokens) {
        for (String token : tokens) {
            if (token != null && value.contains(token)) return true;
        }
        return false;
    }

    public record Requirements(boolean requiresWorkspaceChange, boolean requiresTests) { }
}