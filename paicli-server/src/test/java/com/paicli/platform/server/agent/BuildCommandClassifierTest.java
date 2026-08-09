package com.paicli.platform.server.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildCommandClassifierTest {

    @Test
    void recognizesKnownGeneratedOutputCommands() {
        assertThat(BuildCommandClassifier.producesGeneratedOutput("mvn compile")).isTrue();
        assertThat(BuildCommandClassifier.producesGeneratedOutput("./mvnw package")).isTrue();
        assertThat(BuildCommandClassifier.producesGeneratedOutput("./gradlew assemble")).isTrue();
        assertThat(BuildCommandClassifier.producesGeneratedOutput("npm run build")).isTrue();
        assertThat(BuildCommandClassifier.producesGeneratedOutput("cargo build")).isTrue();
    }

    @Test
    void leavesUnknownCommandsAvailableAsExplicitMutationEvidence() {
        assertThat(BuildCommandClassifier.producesGeneratedOutput("python modify_config.py")).isFalse();
        assertThat(BuildCommandClassifier.producesGeneratedOutput("git status")).isFalse();
        assertThat(BuildCommandClassifier.producesGeneratedOutput("mvn compile; echo done")).isFalse();
    }
}
