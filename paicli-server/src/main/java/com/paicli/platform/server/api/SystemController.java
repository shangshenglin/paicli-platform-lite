package com.paicli.platform.server.api;

import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ToolRouter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/system")
public class SystemController {
    private final SqliteRuntimeStore store;
    private final ToolRouter toolRouter;

    public SystemController(SqliteRuntimeStore store, ToolRouter toolRouter) {
        this.store = store;
        this.toolRouter = toolRouter;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "name", "paicli-platform-lite",
                "phase", 4,
                "sandboxMode", toolRouter.mode(),
                "database", store.databasePath().toString());
    }
}
