package com.paicli.platform.server.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskTitleTest {
    @Test
    void createsShortTitlesFromStructuredTasks() {
        assertThat(TaskTitle.summarize("任务目标：请帮我修复登录超时问题。\n范围：仅修改服务端", "任务"))
                .isEqualTo("修复登录超时问题");
        assertThat(TaskTitle.summarize("请分析并实现一个非常长的任务名称用于验证标题不会撑开整个对话界面和侧边栏布局", "任务"))
                .hasSizeLessThanOrEqualTo(36)
                .endsWith("…");
    }

    @Test
    void recognizesOnlyPlaceholderSessionTitles() {
        assertThat(TaskTitle.isGenericSessionTitle("新对话")).isTrue();
        assertThat(TaskTitle.isGenericSessionTitle("New session")).isTrue();
        assertThat(TaskTitle.isGenericSessionTitle("用户自定义标题")).isFalse();
    }
}
