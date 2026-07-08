package com.paicli.platform.sandbox;

import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/tools")
public class SandboxToolController {
    private final SandboxToolService service;
    private final SandboxAgentProperties properties;

    public SandboxToolController(SandboxToolService service, SandboxAgentProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/execute")
    public ToolResult execute(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestBody ToolRequest request) {
        if (!properties.token().isBlank() && !("Bearer " + properties.token()).equals(authorization)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid sandbox token");
        }
        return service.execute(request);
    }
}

