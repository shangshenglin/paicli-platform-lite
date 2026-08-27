package com.paicli.platform.server.context;

import com.paicli.platform.server.model.ModelMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    @Test
    void appliesHistoricallyCalibratedDeepSeekFallbackPerModel() {
        var profile = TokenEstimator.forModel("openai-compatible/deepseek-v4-flash", "deepseek-v4-flash");
        List<ModelMessage> messages = List.of(ModelMessage.user("中文和 English context"));
        int raw = TokenEstimator.estimateMessagesRaw(messages);

        assertThat(profile.tokenizer()).isEqualTo("deepseek-codepoint-fallback");
        assertThat(profile.exactTokenizer()).isFalse();
        assertThat(profile.calibrationFactor()).isEqualTo(1.60d);
        assertThat(profile.calibrationSource()).isEqualTo("historical-p90-18-calls");
        assertThat(TokenEstimator.estimateMessages(messages, profile))
                .isEqualTo((int) Math.ceil(raw * 1.60d));
    }

    @Test
    void leavesUncalibratedModelsOnTheCompatibleFallbackBaseline() {
        var profile = TokenEstimator.forModel("demo", "demo");
        List<ModelMessage> messages = List.of(ModelMessage.user("hello"));

        assertThat(profile.calibrationFactor()).isEqualTo(1.0d);
        assertThat(TokenEstimator.estimateMessages(messages, profile))
                .isEqualTo(TokenEstimator.estimateMessagesRaw(messages));
    }
}
