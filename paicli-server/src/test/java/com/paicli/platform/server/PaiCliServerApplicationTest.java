package com.paicli.platform.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {
        "paicli.data-dir=target/test-data/application-context",
        "paicli.workspace-root=target/test-data/application-context/workspaces",
        "paicli.worker-count=1",
        "paicli.worker-poll-millis=1000",
        "paicli.model.provider=demo",
        "paicli.web.enabled=false"
})
@DirtiesContext
class PaiCliServerApplicationTest {
    @Test
    void startsWithAllServerToolProvidersWired() { }
}
