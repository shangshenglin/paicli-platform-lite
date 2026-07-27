package com.paicli.platform.server;

import com.paicli.platform.server.config.ModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {
        "paicli.data-dir=target/test-data/application-context",
        "paicli.workspace-root=target/test-data/application-context/workspaces",
        "paicli.worker-count=1",
        "paicli.worker-poll-millis=1000",
        "paicli.model.provider=demo"
})
@DirtiesContext
class PaiCliServerApplicationTest {
    @Autowired
    ModelProperties modelProperties;

    @Test
    void startsWithAllServerToolProvidersWired() {
        org.assertj.core.api.Assertions.assertThat(modelProperties.maxRunTokens()).isZero();
    }
}
