package com.paicli.platform.server.api;

import com.paicli.platform.server.mcp.McpToolProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/mcp")
public class McpController {
    private final McpToolProvider mcp;

    public McpController(McpToolProvider mcp) { this.mcp = mcp; }

    @GetMapping("/servers")
    public List<McpToolProvider.ServerStatus> servers() { return mcp.statuses(); }
}
