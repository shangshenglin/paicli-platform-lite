package com.paicli.platform.server.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildCommandClassifierTest {

    @Test
    void recognizesKnownGeneratedOutputCommands() {
        assertThat(BuildCommandClassifier.classify("mvn process-resources"))
                .isEqualTo(BuildCommandClassifier.Classification.GENERATED_ONLY);
        assertThat(BuildCommandClassifier.classify("./gradlew :server:assemble"))
                .isEqualTo(BuildCommandClassifier.Classification.GENERATED_ONLY);
        assertThat(BuildCommandClassifier.classify("npm run bundle"))
                .isEqualTo(BuildCommandClassifier.Classification.GENERATED_ONLY);
        assertThat(BuildCommandClassifier.classify("dotnet build"))
                .isEqualTo(BuildCommandClassifier.Classification.GENERATED_ONLY);
        assertThat(BuildCommandClassifier.classify("webpack"))
                .isEqualTo(BuildCommandClassifier.Classification.GENERATED_ONLY);
        assertThat(BuildCommandClassifier.classify("tsc"))
                .isEqualTo(BuildCommandClassifier.Classification.GENERATED_ONLY);
    }

    @Test
    void recognizesOnlyExplicitDirectProductMutationCommands() {
        assertThat(BuildCommandClassifier.classify("sed -i 's/old/new/' config.ini"))
                .isEqualTo(BuildCommandClassifier.Classification.POTENTIAL_PRODUCT_MUTATION);
        assertThat(BuildCommandClassifier.classify("git apply fix.patch"))
                .isEqualTo(BuildCommandClassifier.Classification.POTENTIAL_PRODUCT_MUTATION);
        assertThat(BuildCommandClassifier.classify("gofmt -w main.go"))
                .isEqualTo(BuildCommandClassifier.Classification.POTENTIAL_PRODUCT_MUTATION);
        assertThat(BuildCommandClassifier.classify("python modify_config.py"))
                .isEqualTo(BuildCommandClassifier.Classification.UNTRUSTED_OR_UNKNOWN);
        assertThat(BuildCommandClassifier.classify("rm -rf target"))
                .isEqualTo(BuildCommandClassifier.Classification.UNTRUSTED_OR_UNKNOWN);
        assertThat(BuildCommandClassifier.classify("touch foo.txt"))
                .isEqualTo(BuildCommandClassifier.Classification.UNTRUSTED_OR_UNKNOWN);
    }

    @Test
    void compoundOrUnknownCommandsCannotBePromotedByWorkspaceFingerprint() {
        assertThat(BuildCommandClassifier.classify("mvn compile && echo done"))
                .isEqualTo(BuildCommandClassifier.Classification.GENERATED_ONLY);
        assertThat(BuildCommandClassifier.classify("mvn compile || true"))
                .isEqualTo(BuildCommandClassifier.Classification.UNTRUSTED_OR_UNKNOWN);
        assertThat(BuildCommandClassifier.classify("mvn test || true"))
                .isEqualTo(BuildCommandClassifier.Classification.UNTRUSTED_OR_UNKNOWN);
        assertThat(BuildCommandClassifier.classify("git status"))
                .isEqualTo(BuildCommandClassifier.Classification.GENERATED_ONLY);
    }
}
