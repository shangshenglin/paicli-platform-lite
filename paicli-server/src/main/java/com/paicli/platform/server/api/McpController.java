package com.paicli.platform.server.api;

import com.paicli.platform.server.mcp.McpToolProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
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

    @GetMapping("/configurations")
    public List<McpToolProvider.ServerConfig> configurations(){return mcp.configuredServers();}
    @GetMapping("/tools")
    public List<McpToolProvider.ToolStatus> tools(){return mcp.toolStatuses();}
    @PutMapping("/servers/{name}")
    public McpToolProvider.ServerConfig save(@PathVariable String name,@RequestBody ApiDtos.McpServerRequest request){
        if(!name.equals(request.name()))throw new IllegalArgumentException("path and body server names must match");
        return mcp.saveServer(name,request.url(),request.enabled()==null||request.enabled(),request.headers());
    }
    @PostMapping("/servers/{name}/test")
    public McpToolProvider.ServerStatus test(@PathVariable String name){return mcp.testServer(name);}
    @DeleteMapping("/servers/{name}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name){if(!mcp.deleteServer(name))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,"MCP server not found");}
}
