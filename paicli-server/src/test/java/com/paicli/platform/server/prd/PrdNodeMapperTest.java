package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.PrdAnalysisStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrdNodeMapperTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PrdNodeMapper mapper = new PrdNodeMapper(json);

    @Test
    void deterministicallyMapsHeadingsAndParentDependencies() throws Exception {
        var nodes = mapper.map("""
                # 产品
                简介
                ## 订单流程
                创建订单
                ### 支付规则
                必须付款
                ## 数据接口
                字段定义
                """);

        assertThat(nodes).extracting(PrdAnalysisStore.NodeDraft::nodeKey)
                .containsExactly("N001", "N002", "N003", "N004");
        assertThat(json.readValue(nodes.get(2).dependenciesJson(), String[].class)).containsExactly("N002");
        assertThat(json.readValue(nodes.get(3).dependenciesJson(), String[].class)).containsExactly("N001");
        assertThat(nodes.get(1).tagsJson()).contains("flow");
        assertThat(nodes.get(3).tagsJson()).contains("source");
    }

    @Test
    void computesDependencyLevelsAndRejectsCycle() {
        Instant now = Instant.now();
        List<PrdAnalysisStore.AnalysisNode> nodes = List.of(
                node("N001", "[]", now), node("N002", "[\"N001\"]", now),
                node("N003", "[\"N002\"]", now));

        assertThat(mapper.dependencyLevels(nodes)).containsEntry("N001", 0)
                .containsEntry("N002", 1).containsEntry("N003", 2);
    }

    private static PrdAnalysisStore.AnalysisNode node(String key, String dependencies, Instant now) {
        return new PrdAnalysisStore.AnalysisNode("id-" + key, "job", key,
                Integer.parseInt(key.substring(1)), key, 2, 1, 2, key, dependencies,
                "[]", "PENDING", "{}", null, now, now);
    }
}
