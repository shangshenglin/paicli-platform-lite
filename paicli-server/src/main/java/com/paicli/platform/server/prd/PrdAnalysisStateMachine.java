package com.paicli.platform.server.prd;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PrdAnalysisStateMachine {
    private static final Map<Key, Transition> TRANSITIONS = Map.ofEntries(
            Map.entry(new Key("MAP_PRD", "SUCCESS"), new Transition("DISPATCH", "QUEUED")),
            Map.entry(new Key("DISPATCH", "CONTINUE"), new Transition("DISPATCH", "QUEUED")),
            Map.entry(new Key("DISPATCH", "SUCCESS"), new Transition("MERGE", "QUEUED")),
            Map.entry(new Key("MERGE", "SUCCESS"), new Transition("PROBE", "QUEUED")),
            Map.entry(new Key("PROBE", "FIXABLE"), new Transition("MERGE", "QUEUED")),
            Map.entry(new Key("PROBE", "AMBIGUOUS"), new Transition("CLARIFY", "AWAITING_USER")),
            Map.entry(new Key("PROBE", "PASSED"), new Transition("HANDOFF", "QUEUED")),
            Map.entry(new Key("CLARIFY", "WAITING"), new Transition("CLARIFY", "AWAITING_USER")),
            Map.entry(new Key("CLARIFY", "RESOLVED"), new Transition("PROBE", "QUEUED")),
            Map.entry(new Key("HANDOFF", "SUCCESS"), new Transition("HANDOFF", "COMPLETED"))
    );

    public Transition next(String stage, String outcome) {
        Transition transition = TRANSITIONS.get(new Key(stage, outcome));
        if (transition == null) throw new IllegalStateException(
                "illegal PRD analysis transition: " + stage + " + " + outcome);
        return transition;
    }

    private record Key(String stage, String outcome) { }
    public record Transition(String stage, String status) { }
}
