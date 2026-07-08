package com.paicli.platform.server.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkPolicyTest {
    @Test
    void blocksLocalAndNonHttpTargets() {
        assertThatThrownBy(() -> NetworkPolicy.requirePublicHttpUrl("http://127.0.0.1/admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NetworkPolicy.requirePublicHttpUrl("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NetworkPolicy.requirePublicHttpUrl("http://user@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
