package com.paicli.platform.server.security;

import com.paicli.platform.server.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentSecurityValidatorTest {
    @Test
    void acceptsLoopbackWithoutApiKey() {
        new DeploymentSecurityValidator("127.0.0.1", new SecurityProperties("", false, true)).validate();
        new DeploymentSecurityValidator("::1", new SecurityProperties("", false, true)).validate();
        assertThat(DeploymentSecurityValidator.isLoopback("localhost")).isTrue();
    }

    @Test
    void rejectsExternalBindWithoutApiKey() {
        DeploymentSecurityValidator validator = new DeploymentSecurityValidator(
                "0.0.0.0", new SecurityProperties("", false, true));

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAICLI_API_KEY");
    }

    @Test
    void acceptsExternalBindWithApiKey() {
        new DeploymentSecurityValidator(
                "0.0.0.0", new SecurityProperties("secret", false, true)).validate();
    }
}
