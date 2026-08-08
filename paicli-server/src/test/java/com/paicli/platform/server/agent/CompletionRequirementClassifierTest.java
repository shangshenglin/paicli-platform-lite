package com.paicli.platform.server.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionRequirementClassifierTest {

    @Test
    void imperativeMutationTasksAreDetected() {
        var requirements = CompletionRequirementClassifier.classify("请修改 config.yml，把 timeout 改成 30。");
        assertThat(requirements.requiresWorkspaceChange()).isTrue();
    }

    @Test
    void questionsAndAnalysisAreTextOnly() {
        var explain = CompletionRequirementClassifier.classify("解释 RunVerificationService。");
        assertThat(explain.requiresWorkspaceChange()).isFalse();
        assertThat(explain.requiresTests()).isFalse();

        var howTo = CompletionRequirementClassifier.classify("如何修改这个文件？");
        assertThat(howTo.requiresWorkspaceChange()).isFalse();

        var analysis = CompletionRequirementClassifier.classify("分析这个 bug，告诉我应该怎么重构。");
        assertThat(analysis.requiresWorkspaceChange()).isFalse();
    }

    @Test
    void testRequirementsAreDetected() {
        var runTests = CompletionRequirementClassifier.classify("运行测试并确保测试通过");
        assertThat(runTests.requiresTests()).isTrue();
        var english = CompletionRequirementClassifier.classify("run the tests and make them pass");
        assertThat(english.requiresTests()).isTrue();
    }

    @Test
    void testQuestionsAreNotRequirements() {
        var what = CompletionRequirementClassifier.classify("测试是什么？为什么测试失败？");
        assertThat(what.requiresTests()).isFalse();
    }

    @Test
    void blankFallsBackToTextOnly() {
        var blank = CompletionRequirementClassifier.classify("");
        assertThat(blank.requiresWorkspaceChange()).isFalse();
        assertThat(blank.requiresTests()).isFalse();
    }
}