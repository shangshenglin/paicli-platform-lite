package com.paicli.platform.server.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestCommandClassifierTest {

    @Test
    void mavenTestGoalsAreMavenFamily() {
        assertThat(TestCommandClassifier.classify("./mvnw test")).contains(TestFamily.MAVEN);
        assertThat(TestCommandClassifier.classify("mvn -q -Dtest=UserServiceTest test")).contains(TestFamily.MAVEN);
        assertThat(TestCommandClassifier.classify("mvn verify")).contains(TestFamily.MAVEN);
    }

    @Test
    void mavenCompileAndPackageAreNotTests() {
        assertThat(TestCommandClassifier.classify("mvn compile")).isEmpty();
        assertThat(TestCommandClassifier.classify("./mvnw -q clean package")).isEmpty();
        assertThat(TestCommandClassifier.classify("mvn install -DskipTests")).isEmpty();
        assertThat(TestCommandClassifier.classify("mvnw clean")).isEmpty();
        assertThat(TestCommandClassifier.classify("mvn -Dtest=UserServiceTest compile")).isEmpty();
    }

    @Test
    void checkStatusScriptIsNotATest() {
        assertThat(TestCommandClassifier.classify("./check-status.sh")).isEmpty();
        assertThat(TestCommandClassifier.classify("bash check_status.sh")).isEmpty();
    }

    @Test
    void otherFamilies() {
        assertThat(TestCommandClassifier.classify("npm test")).contains(TestFamily.NPM);
        assertThat(TestCommandClassifier.classify("npm run test:unit")).contains(TestFamily.NPM);
        assertThat(TestCommandClassifier.classify("npx jest --runInBand")).contains(TestFamily.JEST);
        assertThat(TestCommandClassifier.classify("vitest run")).contains(TestFamily.VITEST);
        assertThat(TestCommandClassifier.classify("pytest tests/")).contains(TestFamily.PYTEST);
        assertThat(TestCommandClassifier.classify("go test ./...")).contains(TestFamily.GO_TEST);
        assertThat(TestCommandClassifier.classify("node --test")).contains(TestFamily.NODE_TEST);
        assertThat(TestCommandClassifier.classify("cargo test")).contains(TestFamily.CARGO);
        assertThat(TestCommandClassifier.classify("./gradlew test")).contains(TestFamily.GRADLE);
        assertThat(TestCommandClassifier.classify("gradle test")).contains(TestFamily.GRADLE);
    }

    @Test
    void plainCommandsAreNotTests() {
        assertThat(TestCommandClassifier.classify("ls -la")).isEmpty();
        assertThat(TestCommandClassifier.classify("git status")).isEmpty();
        assertThat(TestCommandClassifier.classify("echo hello")).isEmpty();
        assertThat(TestCommandClassifier.classify("")).isEmpty();
        assertThat(TestCommandClassifier.classify(null)).isEmpty();
    }

    @Test
    void shellTestScriptsAreConservative() {
        assertThat(TestCommandClassifier.classify("./run-tests.sh")).contains(TestFamily.SHELL_TEST);
        assertThat(TestCommandClassifier.classify("bash test.sh")).contains(TestFamily.SHELL_TEST);
        // check/status scripts never match the test token.
        assertThat(TestCommandClassifier.classify("bash check-status.sh")).isEmpty();
        assertThat(TestCommandClassifier.classify("./test-data.sh")).isEmpty();
    }

    @Test
    void unsafeAggregatesCannotBecomePassingTestEvidence() {
        assertThat(TestCommandClassifier.classify("mvn test || true")).isEmpty();
        assertThat(TestCommandClassifier.classify("mvn test; true")).isEmpty();
        assertThat(TestCommandClassifier.classify("pytest | tee test.log")).isEmpty();
        assertThat(TestCommandClassifier.classify("mvn test && echo done")).isEmpty();
        assertThat(TestCommandClassifier.classify("mvn test\ntrue")).isEmpty();
        assertThat(TestCommandClassifier.classify("mvn test & true")).isEmpty();
        assertThat(TestCommandClassifier.classify("mvn test -DskipTests=true")).isEmpty();
        assertThat(TestCommandClassifier.classify("./gradlew test -x test")).isEmpty();
        assertThat(TestCommandClassifier.classify("./gradlew check --exclude-task test")).isEmpty();
        assertThat(TestCommandClassifier.classify("./gradlew :server:test -x :server:test")).isEmpty();
        assertThat(TestCommandClassifier.classify("pytest --collect-only")).isEmpty();
        assertThat(TestCommandClassifier.classify("cargo test --no-run")).isEmpty();
        assertThat(TestCommandClassifier.classify("jest --listTests")).isEmpty();
        assertThat(TestCommandClassifier.classify("npx jest --listTests")).isEmpty();
        assertThat(TestCommandClassifier.classify("npm test -- --listTests")).isEmpty();
        assertThat(TestCommandClassifier.classify("npm install jest")).isEmpty();
        assertThat(TestCommandClassifier.classify("pnpm add jest")).isEmpty();
        assertThat(TestCommandClassifier.classify("yarn add jest")).isEmpty();
        assertThat(TestCommandClassifier.classify("./gradlew test --dry-run")).isEmpty();
        assertThat(TestCommandClassifier.classify("./gradlew test -m")).isEmpty();
        assertThat(TestCommandClassifier.classify("vitest --list")).isEmpty();
        assertThat(TestCommandClassifier.classify("dotnet test --list-tests")).isEmpty();
        assertThat(TestCommandClassifier.classify("cd server && mvn test")).contains(TestFamily.MAVEN);
        assertThat(TestCommandClassifier.classify("cd server && mvn test && mvn test")).contains(TestFamily.MAVEN);
    }

    @Test
    void shellOperatorsAndAdditionalFamiliesAreClassifiedConservatively() {
        assertThat(TestCommandClassifier.classify("mvn compile && echo test")).isEmpty();
        assertThat(TestCommandClassifier.classify("npm run build && echo test")).isEmpty();
        assertThat(TestCommandClassifier.classify("echo junit")).isEmpty();
        assertThat(TestCommandClassifier.classify("./gradlew check")).contains(TestFamily.GRADLE);
        assertThat(TestCommandClassifier.classify("./gradlew testClasses")).isEmpty();
        assertThat(TestCommandClassifier.classify("gradle checkstyleMain")).isEmpty();
        assertThat(TestCommandClassifier.classify("pnpm test")).contains(TestFamily.NPM);
        assertThat(TestCommandClassifier.classify("yarn test")).contains(TestFamily.NPM);
        assertThat(TestCommandClassifier.classify("npm exec jest")).contains(TestFamily.JEST);
        assertThat(TestCommandClassifier.classify("pnpm exec jest")).contains(TestFamily.JEST);
        assertThat(TestCommandClassifier.classify("pnpm dlx jest")).contains(TestFamily.JEST);
        assertThat(TestCommandClassifier.classify("dotnet test")).contains(TestFamily.DOTNET);
    }

    @Test
    void helpAndVersionModesNeverBecomeTestEvidence() {
        assertThat(TestCommandClassifier.classify("go help test")).isEmpty();
        assertThat(TestCommandClassifier.classify("cargo help test")).isEmpty();
        assertThat(TestCommandClassifier.classify("dotnet help test")).isEmpty();
        assertThat(TestCommandClassifier.classify("pytest --help")).isEmpty();
        assertThat(TestCommandClassifier.classify("pytest --version")).isEmpty();
        assertThat(TestCommandClassifier.classify("go test --help")).isEmpty();
        assertThat(TestCommandClassifier.classify("cargo test --version")).isEmpty();
        assertThat(TestCommandClassifier.classify("dotnet test --help")).isEmpty();
    }
}
