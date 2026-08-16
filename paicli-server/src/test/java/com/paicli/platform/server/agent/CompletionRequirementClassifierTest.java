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
    void negatedMutationAndTestPhrasesDoNotStrengthenTextOnlyTasks() {
        var marker = CompletionRequirementClassifier.classify(
                "不要调用任何工具，只回复 PAICLI_EVAL_OK，不要添加其他内容。");
        assertThat(marker.requiresWorkspaceChange()).isFalse();
        assertThat(marker.requiresTests()).isFalse();

        var honest = CompletionRequirementClassifier.classify(
                "不要声称任何测试通过，除非真的执行测试命令并看到通过结果。");
        assertThat(honest.requiresWorkspaceChange()).isFalse();
        assertThat(honest.requiresTests()).isFalse();

        var delegated = CompletionRequirementClassifier.classify(
                "调用 list_agents 查看状态，不要创建或取消子 Agent。");
        assertThat(delegated.requiresWorkspaceChange()).isFalse();
    }

    @Test
    void laterAffirmativeClauseStillDetectsRequiredMutation() {
        var requirements = CompletionRequirementClassifier.classify(
                "不要只解释，修改 config.yml 并运行测试。");
        assertThat(requirements.requiresWorkspaceChange()).isTrue();
        assertThat(requirements.requiresTests()).isTrue();
    }

    @Test
    void blankFallsBackToTextOnly() {
        var blank = CompletionRequirementClassifier.classify("");
        assertThat(blank.requiresWorkspaceChange()).isFalse();
        assertThat(blank.requiresTests()).isFalse();
    }
}
