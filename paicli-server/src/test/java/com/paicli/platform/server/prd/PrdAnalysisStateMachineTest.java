package com.paicli.platform.server.prd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrdAnalysisStateMachineTest {
    private final PrdAnalysisStateMachine machine = new PrdAnalysisStateMachine();

    @Test
    void enforcesQualityPipelineAndControlledLoops() {
        assertThat(machine.next("MAP_PRD", "SUCCESS"))
                .isEqualTo(new PrdAnalysisStateMachine.Transition("DISPATCH", "QUEUED"));
        assertThat(machine.next("PROBE", "FIXABLE"))
                .isEqualTo(new PrdAnalysisStateMachine.Transition("MERGE", "QUEUED"));
        assertThat(machine.next("PROBE", "AMBIGUOUS"))
                .isEqualTo(new PrdAnalysisStateMachine.Transition("CLARIFY", "AWAITING_USER"));
        assertThat(machine.next("CLARIFY", "RESOLVED"))
                .isEqualTo(new PrdAnalysisStateMachine.Transition("PROBE", "QUEUED"));
        assertThat(machine.next("HANDOFF", "SUCCESS").status()).isEqualTo("COMPLETED");
    }

    @Test
    void rejectsStageSkipping() {
        assertThatThrownBy(() -> machine.next("DISPATCH", "HANDOFF"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("illegal PRD analysis transition");
    }
}
